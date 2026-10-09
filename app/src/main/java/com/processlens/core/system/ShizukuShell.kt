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
    private val runner: ProcessRunner,
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
     * the app. A `null` from [newProcess] — the shell declined to hand back a
     * process — is passed straight through to [ProcessRunner], which reports it as
     * a start failure rather than a crash.
     *
     * The deadline, the concurrent drain of both pipes and the destroy-on-every-
     * path teardown all live in [ProcessRunner]. This used to drain stdout to EOF
     * and only then stderr, which both missed the timeout entirely (the first
     * blocking `read()` on an unanswered prompt never returned) and could deadlock
     * against a child filling its stderr pipe. See [ProcessRunner]'s KDoc.
     */
    override suspend fun execute(argv: List<String>, timeoutMillis: Long): ShellResult =
        withContext(io) {
            if (!isAvailable()) {
                return@withContext ShellResult.failure(
                    "Shizuku is not running or permission was not granted.",
                    accessLevel,
                )
            }
            runner.execute(argv, accessLevel, timeoutMillis) { command ->
                newProcess(command.toTypedArray())
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
    }
}
