package com.processlens.core.system

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.IoDispatcher
import com.processlens.domain.model.RootState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root-backed shell (Section 28).
 *
 * Detection is deliberately in two stages. Finding an `su` binary on PATH proves
 * only that a superuser manager is *installed* — spawning it is what proves
 * access, and on Magisk that spawn is what raises the user's grant prompt. So
 * [state] reports [RootState.BINARY_PRESENT] from the cheap filesystem check and
 * only escalates to [RootState.GRANTED] after a real `id` probe has succeeded.
 *
 * The probe result is cached: re-running `su` on every capability refresh would
 * spam the superuser log and, on some managers, re-prompt the user.
 */
@Singleton
class RootShell @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : ElevatedShell {

    override val accessLevel: AccessLevel = AccessLevel.ROOT

    @Volatile
    private var probedState: RootState? = null

    /** Cheap, synchronous: does an `su` binary exist anywhere standard? */
    fun hasBinary(): Boolean = SU_PATHS.any { path ->
        try {
            File(path).let { it.exists() && it.canExecute() }
        } catch (t: Throwable) {
            false
        }
    }

    /** Cached state. Returns [RootState.BINARY_PRESENT] until [probe] has run. */
    fun state(): RootState = probedState ?: if (hasBinary()) {
        RootState.BINARY_PRESENT
    } else {
        RootState.UNAVAILABLE
    }

    /**
     * Actually attempts elevation. This may show the superuser prompt, so it is
     * only called when the user has enabled root support in Settings — never
     * speculatively at startup.
     */
    suspend fun probe(): RootState = withContext(io) {
        if (!hasBinary()) {
            probedState = RootState.UNAVAILABLE
            return@withContext RootState.UNAVAILABLE
        }
        val result = execute(DiagnosticCommand.Probe.argv, timeoutMillis = 10_000L)
        val granted = result.isSuccess && result.stdout.contains("uid=0")
        val newState = when {
            granted -> RootState.GRANTED
            // A manager that denies returns non-zero quickly, often with nothing
            // on stdout. Distinguish that from "no binary at all".
            else -> RootState.DENIED
        }
        probedState = newState
        newState
    }

    /** Forgets the cached probe so a settings change can re-evaluate. */
    fun invalidate() {
        probedState = null
    }

    override suspend fun isAvailable(): Boolean = state().isUsable

    /**
     * Runs a diagnostic as root.
     *
     * The argv is passed to `su -c` as *one* shell word per element, joined with
     * single-quote escaping, so a package name containing shell metacharacters
     * cannot break out of its argument position. Only the enumerated
     * [DiagnosticCommand]s ever reach here, and none of them mutate state.
     */
    override suspend fun execute(argv: List<String>, timeoutMillis: Long): ShellResult =
        withContext(io) {
            if (argv.isEmpty()) {
                return@withContext ShellResult.failure("Empty command", accessLevel)
            }
            if (!hasBinary()) {
                return@withContext ShellResult.failure("No su binary on this device", accessLevel)
            }

            var process: Process? = null
            try {
                val command = argv.joinToString(" ") { shellQuote(it) }
                process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(false)
                    .start()

                val out = process.inputStream.readAllTextSafely(MAX_OUTPUT_BYTES)
                val err = process.errorStream.readAllTextSafely(MAX_ERROR_BYTES)

                if (!process.waitForTimeout(timeoutMillis)) {
                    process.destroy()
                    return@withContext ShellResult(
                        -1, out, "Command timed out after $timeoutMillis ms", accessLevel,
                    )
                }
                ShellResult(process.exitValue(), out, err, accessLevel)
            } catch (t: Throwable) {
                ShellResult.failure(t.message ?: t::class.java.simpleName, accessLevel)
            } finally {
                try {
                    process?.destroy()
                } catch (ignored: Throwable) {
                }
            }
        }

    /**
     * POSIX single-quote escaping: wrap in `'…'` and replace each embedded quote
     * with `'\''`. Safe for every byte, unlike a blocklist of metacharacters.
     */
    private fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"

    companion object {
        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
        )
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024
    }
}
