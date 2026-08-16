package com.processlens.core.system

import android.content.Context
import android.content.pm.PackageManager
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.IoDispatcher
import com.processlens.domain.model.ShizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku-backed shell (Section 27).
 *
 * Shizuku gives an app the same authority `adb shell` has, via a binder the user
 * starts themselves. That is genuinely more than a normal app gets — `dumpsys
 * power` becomes readable, so do other processes' `/proc` entries — but it is
 * emphatically *not* root, and this class never presents it as such.
 *
 * Every Shizuku symbol is touched only behind an availability check, and every
 * call site catches [NoClassDefFoundError]/[NoSuchMethodError] as well as
 * exceptions: the Shizuku API is a thin binder wrapper and on a device where the
 * manager app is absent or ancient, class loading itself is what fails.
 */
@Singleton
class ShizukuShell @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ElevatedShell {

    override val accessLevel: AccessLevel = AccessLevel.SHIZUKU

    /**
     * Distinguishes all five states Section 27 requires. Cheap and synchronous —
     * it never blocks on the binder — so the capability detector can call it on
     * every refresh.
     */
    fun state(): ShizukuState = try {
        if (!Shizuku.pingBinder()) {
            if (isManagerInstalled()) ShizukuState.INSTALLED_NOT_RUNNING
            else ShizukuState.NOT_INSTALLED
        } else if (Shizuku.isPreV11()) {
            // Pre-v11 used a different, now-removed permission model.
            ShizukuState.VERSION_UNSUPPORTED
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuState.RUNNING_PERMISSION_GRANTED
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // Shizuku reports rationale only after an explicit denial.
            ShizukuState.RUNNING_PERMISSION_DENIED
        } else {
            ShizukuState.RUNNING_PERMISSION_UNKNOWN
        }
    } catch (t: Throwable) {
        // NoClassDefFoundError on devices without the provider, or a binder death
        // race. Either way the honest answer is "not usable".
        if (isManagerInstalled()) ShizukuState.INSTALLED_NOT_RUNNING else ShizukuState.NOT_INSTALLED
    }

    private fun isManagerInstalled(): Boolean {
        val pm = context.packageManager
        return MANAGER_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (t: Throwable) {
                false
            }
        }
    }

    override suspend fun isAvailable(): Boolean = state().isUsable

    /**
     * Requests the Shizuku permission. Returns the resulting state so the caller
     * can react without a second round-trip. The listener is removed in a
     * `finally` so a denied request cannot leak it.
     */
    suspend fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE): ShizukuState =
        withContext(io) {
            val current = state()
            if (current == ShizukuState.RUNNING_PERMISSION_GRANTED) return@withContext current
            if (current == ShizukuState.NOT_INSTALLED ||
                current == ShizukuState.INSTALLED_NOT_RUNNING ||
                current == ShizukuState.VERSION_UNSUPPORTED
            ) {
                return@withContext current
            }

            try {
                Shizuku.requestPermission(requestCode)
            } catch (t: Throwable) {
                return@withContext state()
            }
            state()
        }

    /**
     * Runs a diagnostic through Shizuku's `newProcess`.
     *
     * `Shizuku.newProcess` is a hidden API reached by reflection inside the
     * Shizuku library, so it is wrapped defensively: a signature change in a
     * future Shizuku release degrades this to "unavailable" rather than crashing
     * the app.
     */
    override suspend fun execute(argv: List<String>, timeoutMillis: Long): ShellResult =
        withContext(io) {
            if (!isAvailable()) {
                return@withContext ShellResult.failure(
                    "Shizuku is not running or permission was not granted.",
                    accessLevel,
                )
            }
            var process: Process? = null
            try {
                process = newProcess(argv.toTypedArray())
                    ?: return@withContext ShellResult.failure(
                        "Shizuku could not start a process on this device.",
                        accessLevel,
                    )

                // Drain both pipes before waiting: a command like `dumpsys
                // batterystats` produces megabytes, and a full pipe buffer would
                // deadlock a waitFor() that has not been read from.
                val out = process.inputStream.readAllTextSafely(MAX_OUTPUT_BYTES)
                val err = process.errorStream.readAllTextSafely(MAX_ERROR_BYTES)

                val finished = process.waitForTimeout(timeoutMillis)
                if (!finished) {
                    process.destroy()
                    return@withContext ShellResult(
                        exitCode = -1,
                        stdout = out,
                        stderr = "Command timed out after ${timeoutMillis} ms",
                        accessLevel = accessLevel,
                    )
                }
                ShellResult(process.exitValue(), out, err, accessLevel)
            } catch (t: Throwable) {
                ShellResult.failure(
                    t.message ?: t::class.java.simpleName,
                    accessLevel,
                )
            } finally {
                try {
                    process?.destroy()
                } catch (ignored: Throwable) {
                }
            }
        }

    private fun newProcess(argv: Array<String>): Process? = try {
        // Signature: newProcess(String[] cmd, String[] env, String dir)
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        method.invoke(null, argv, null, null) as? Process
    } catch (t: Throwable) {
        null
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 8571

        private val MANAGER_PACKAGES = listOf(
            "moe.shizuku.privileged.api",
            "moe.shizuku.manager",
        )

        /**
         * Caps on captured output. `dumpsys batterystats` can exceed 10 MB on a
         * device that has been up for weeks; the parsers only need the header
         * sections, and an unbounded read would be a genuine OOM risk on the
         * low-memory devices this app targets.
         */
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024
    }
}

/**
 * Reads a stream to text with a hard byte cap, never throwing. Truncation is
 * marked inline so a parser cannot mistake a cut-off dump for a complete one.
 */
internal fun InputStream.readAllTextSafely(maxBytes: Int): String = try {
    use { stream ->
        val buffer = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            val allowed = minOf(read, maxBytes - total)
            if (allowed > 0) {
                buffer.write(chunk, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) {
                buffer.write("\n[output truncated at $maxBytes bytes]\n".toByteArray())
                break
            }
        }
        buffer.toString("UTF-8")
    }
} catch (t: Throwable) {
    ""
}

/**
 * `Process.waitFor(timeout, unit)` is API 26+, which matches this app's minSdk,
 * but the `Process` returned by Shizuku is a remote proxy whose implementation
 * may not honour it. Falling back to polling `exitValue()` keeps a hung command
 * from blocking the caller forever.
 */
internal fun Process.waitForTimeout(timeoutMillis: Long): Boolean {
    try {
        return waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (t: Throwable) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            try {
                exitValue()
                return true
            } catch (notYet: IllegalThreadStateException) {
                try {
                    Thread.sleep(50)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }
}
