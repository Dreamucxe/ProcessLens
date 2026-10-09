package com.processlens.core.system

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the operating-system process for an argv.
 *
 * The single seam in this file that touches the OS, and the reason [ProcessRunner]
 * is testable without a rooted device: a unit test supplies a starter returning a
 * fake [Process] — one that denies, one that never exits, one that succeeds — and
 * drives every path below with no `su` binary, no Shizuku binder and no Android.
 *
 * `null` and a thrown exception are different answers and are reported
 * differently. A throw means the shell could not be invoked at all (`su` is not
 * on PATH, the binder died); `null` means the shell exists but declined to hand
 * back a process, which is what Shizuku's reflective `newProcess` does when its
 * hidden API has moved.
 */
fun interface ProcessStarter {
    fun start(argv: List<String>): Process?
}

/**
 * Runs one external process to completion under a real deadline (requirement 4 of
 * the issue #1 spec, Section 28).
 *
 * Both shells used to carry the identical defect this class exists to remove:
 * they read stdout to EOF, *then* stderr to EOF, and only *then* consulted the
 * timeout. ShizukuShell's comment there was half right — it understood that the
 * pipes must be drained before the wait — and wrong about draining them one after
 * the other. Two things followed, and both were reachable in the field:
 *
 *  - The timeout was unreachable. An `su` parked on an unanswered Magisk prompt
 *    blocked in the first `read()` forever, not for the nominal ten seconds.
 *  - It was a genuine deadlock, not merely a slow path. A child that fills the
 *    ~64 KiB stderr pipe blocks in `write()`, so it never closes stdout, so the
 *    stdout read never reaches EOF, so stderr is never drained. Each side waits
 *    for the other.
 *
 * ### Why the drains are not children of the caller
 *
 * The trap underneath all of this: **a blocking `InputStream.read()` does not
 * observe coroutine cancellation.** `withTimeout` cannot interrupt a thread parked
 * in `read()`; it would cancel the coroutine, then suspend forever waiting for an
 * uncancellable child to finish, and on `Dispatchers.IO` enough parked threads is
 * a failure in its own right. The only thing that releases that reader is closing
 * the pipe, which [Process.destroy] does by killing the writer.
 *
 * So the deadline here is not implemented by abandoning the readers. It is
 * implemented by *destroying the process*, which makes the readers finish. The
 * drains therefore run in a scope this function owns rather than in the caller's
 * job: a caller who gives up must not be made to wait for a thread in `read()`,
 * and [ProcessSession] guarantees the destroy-and-close that releases it happens
 * on every exit path — success, timeout, denial, throw, cancellation — exactly
 * once.
 *
 * Output is captured into a [StreamSink] rather than returned by the drain
 * coroutine, so bytes the child had already produced survive even when the drain
 * itself is abandoned. Reporting "" there would be discarding evidence, which is
 * the opposite of what Section 42 asks for.
 */
@Singleton
class ProcessRunner @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Starts [argv], waits at most [timeoutMillis] for it, and returns whatever it
     * said.
     *
     * Always runs on the injected IO dispatcher, so a caller cannot accidentally
     * put a blocking `su` on the main thread or on the computation pool
     * (Section 43). [starter] is the last parameter so call sites read as a
     * trailing lambda; it is invoked exactly once and inside the IO context.
     *
     * Never throws for a process-level failure — a refusal, a deadline or an
     * absent binary all come back as a non-zero [ShellResult] — because every
     * caller's answer to all three is the same: carry on without the shell.
     * Cancellation of the caller is the one thing that does propagate.
     */
    suspend fun execute(
        argv: List<String>,
        accessLevel: AccessLevel,
        timeoutMillis: Long = ElevatedShell.DEFAULT_TIMEOUT,
        maxOutputBytes: Int = MAX_OUTPUT_BYTES,
        maxErrorBytes: Int = MAX_ERROR_BYTES,
        starter: ProcessStarter,
    ): ShellResult {
        if (argv.isEmpty()) return ShellResult.failure("Empty command", accessLevel)

        return withContext(io) {
            val process = try {
                starter.start(argv)
            } catch (t: Throwable) {
                return@withContext ShellResult.failure(describe(t), accessLevel)
            } ?: return@withContext ShellResult.failure(
                "This shell could not start a process on this device.",
                accessLevel,
            )

            val session = ProcessSession(process)
            val stdout = StreamSink(maxOutputBytes)
            val stderr = StreamSink(maxErrorBytes)

            // Owned, parentless scope. See the class KDoc: these two coroutines can
            // park in an uncancellable read(), so they must not be able to hold the
            // caller's job open.
            val drains = CoroutineScope(io + SupervisorJob() + CoroutineName(DRAIN_SCOPE_NAME))
            try {
                // Before anything else, and not just hygiene: none of the
                // DiagnosticCommands read stdin, but `su` itself may, and a child
                // waiting on a pipe nobody will ever write to is a hang with no
                // deadline attached. Closing it hands over EOF immediately.
                session.closeStdin()

                val stdoutDrain = drains.launch { stdout.drain(process.inputStream) }
                val stderrDrain = drains.launch { stderr.drain(process.errorStream) }

                // Concurrent with both drains. runInterruptible is what makes the
                // outer deadline real: waitForTimeout blocks, and without thread
                // interruption withTimeoutOrNull could not return until it chose to.
                val finished = withTimeoutOrNull(timeoutMillis) {
                    runInterruptible { process.waitForTimeout(timeoutMillis) }
                } ?: false

                if (!finished) {
                    // This, not the cancellation, is the timeout. Killing the child
                    // closes the write ends, which is the only thing that lets the
                    // two readers above unwind.
                    session.tearDown()
                }

                // On the happy path the child has already exited, so EOF is coming
                // and the drains are allowed the full command budget to finish
                // reading a multi-megabyte dump. On the timeout path they have only
                // a short grace window, because by then the pipes are already closed
                // and anything still blocked is a Process implementation whose
                // destroy() did not take — Shizuku's is a remote binder proxy.
                val drainBudget = if (finished) timeoutMillis else DRAIN_GRACE_MILLIS
                joinWithin(drainBudget, stdoutDrain, stderrDrain)

                val out = stdout.text()
                val err = stderr.text()
                val code = session.exitCodeOrNull()

                when {
                    !finished -> ShellResult(
                        EXIT_TIMED_OUT,
                        out,
                        combine("Command timed out after $timeoutMillis ms", err),
                        accessLevel,
                    )
                    // waitForTimeout said it finished but the process will not report
                    // a status. Trusting either answer would be guessing, so this is
                    // reported as the same kind of non-result as a deadline.
                    code == null -> ShellResult(
                        EXIT_TIMED_OUT,
                        out,
                        combine("The shell did not report an exit status.", err),
                        accessLevel,
                    )
                    else -> ShellResult(code, out, err, accessLevel)
                }
            } finally {
                // Every exit path, cancellation included. Both calls are
                // non-suspending, which is why they still run when the caller's job
                // is already cancelled.
                session.tearDown()
                drains.cancel()
            }
        }
    }

    companion object {
        /**
         * Reported when the process could not be started at all. The same value
         * [ShellResult.failure] uses, which is where start failures come from.
         */
        const val EXIT_NOT_STARTED = -1

        /**
         * Reported when the child never exited on its own and was destroyed, or
         * exited without a readable status.
         *
         * Distinct from [EXIT_NOT_STARTED] so a caller can tell "the superuser
         * prompt went unanswered" from "there is no `su` here" — [RootShell] needs
         * exactly that distinction to decide whether it has learned anything. Both
         * are negative, which a real wait status (0..255) can never be.
         */
        const val EXIT_TIMED_OUT = -2

        /**
         * Caps on captured output. `dumpsys batterystats` can exceed 10 MB on a
         * device that has been up for weeks; the parsers only need the header
         * sections, and an unbounded read would be a genuine OOM risk on the
         * low-memory devices this app targets.
         */
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        const val MAX_ERROR_BYTES = 64 * 1024

        /**
         * How long a drain is given *after* the process has been destroyed.
         *
         * Short on purpose. By this point the child is killed and all three pipes
         * are closed, so a reader that is still blocked is pathological rather than
         * busy, and the sink already holds whatever it captured. Waiting longer
         * would only move the hang from the shell into the caller.
         */
        const val DRAIN_GRACE_MILLIS = 500L
    }
}

// The mechanical constants live at file scope rather than in the companion because
// StreamSink and waitForTimeout below need them, and a private companion member is
// visible only inside its own class — not to the rest of the file.

private const val DRAIN_SCOPE_NAME = "process-drain"

/** Poll interval for the [waitForTimeout] fallback. */
private const val POLL_INTERVAL_MILLIS = 50L

private const val CHUNK_BYTES = 16 * 1024
private const val INITIAL_BUFFER_BYTES = 64 * 1024

/**
 * Owns one [Process] and the three pipes hanging off it.
 *
 * Exists so that "destroy the process and close the streams exactly once, on
 * every exit path" is a property of one small class instead of a rule repeated in
 * five `catch` blocks. The guards are atomic because teardown is reachable from
 * the timeout branch and from `finally` on the same run.
 *
 * Teardown here is not tidying. [Process.destroy] is the mechanism by which the
 * deadline in [ProcessRunner.execute] takes effect at all: killing the writer is
 * what gives the blocked readers their EOF.
 */
private class ProcessSession(private val process: Process) {

    private val stdinClosed = AtomicBoolean(false)
    private val tornDown = AtomicBoolean(false)

    /** Closes the parent's write end, giving an `su` that reads stdin its EOF. */
    fun closeStdin() {
        if (stdinClosed.compareAndSet(false, true)) {
            closeQuietly(process.outputStream)
        }
    }

    /**
     * Kills the child, then closes all three pipes. Idempotent.
     *
     * Destroy comes first and the order matters: on Linux, closing a descriptor
     * another thread is already blocked reading does not wake that thread, whereas
     * killing the writer does. The closes that follow are descriptor hygiene, not
     * the release mechanism.
     */
    fun tearDown() {
        if (!tornDown.compareAndSet(false, true)) return
        try {
            process.destroy()
        } catch (ignored: Throwable) {
            // A remote proxy may refuse to be destroyed. Nothing further to try.
        }
        closeStdin()
        closeQuietly(process.inputStream)
        closeQuietly(process.errorStream)
    }

    /** The real exit status, or null when the process has not actually exited. */
    fun exitCodeOrNull(): Int? = try {
        process.exitValue()
    } catch (notYet: IllegalThreadStateException) {
        null
    } catch (t: Throwable) {
        // A Shizuku proxy that cannot answer. Null means "unknown", never zero:
        // a fabricated success here would be read downstream as a working command.
        null
    }
}

/**
 * A bounded accumulator for one pipe.
 *
 * Deliberately separate from the coroutine that fills it. On the timeout path the
 * reader may still be parked when the runner stops waiting for it, and the bytes
 * it already captured are real output — `ps` output that arrived before the hang
 * is as true as `ps` output that arrived before an exit. Keeping the buffer
 * outside the coroutine is what lets the runner read a partial capture instead of
 * reporting nothing (Section 42).
 *
 * [ByteArrayOutputStream]'s own methods are synchronised, and only [drain] writes
 * [captured], so [truncated] is the single field that genuinely crosses threads.
 */
private class StreamSink(private val maxBytes: Int) {

    private val buffer = ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES))

    /** Touched only by the draining coroutine. */
    private var captured = 0

    @Volatile
    private var truncated = false

    /** Reads to EOF, to the byte cap, or until the pipe is closed underneath us. */
    fun drain(stream: InputStream) {
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                val allowed = minOf(read, maxBytes - captured)
                if (allowed > 0) {
                    buffer.write(chunk, 0, allowed)
                    captured += allowed
                }
                if (captured >= maxBytes) {
                    truncated = true
                    break
                }
            }
        } catch (t: Throwable) {
            // The expected ending on the timeout path, not an anomaly: the runner
            // destroyed the process and closed this pipe precisely to land here.
            // Whatever arrived first is kept.
        }
    }

    /**
     * What was captured. Truncation is marked inline so a parser cannot mistake a
     * cut-off dump for a complete one.
     */
    fun text(): String = try {
        val body = buffer.toString("UTF-8")
        if (truncated) body + "\n[output truncated at $maxBytes bytes]\n" else body
    } catch (t: Throwable) {
        ""
    }
}

/**
 * Reads a stream to text with a hard byte cap, never throwing.
 *
 * Kept as a top-level function in this package, as it was in ShizukuShell.kt, so
 * both shells and anything added beside them share one implementation. Two
 * behaviours changed when it moved here, both deliberate:
 *
 *  - It no longer closes the stream. [ProcessRunner] owns the pipes and closes
 *    each exactly once; a second close from here would make "exactly once" a claim
 *    rather than a fact.
 *  - A failure part-way through returns the bytes already captured instead of "",
 *    for the reason given on [StreamSink].
 *
 * This is the synchronous form, for a caller holding a stream and nothing else.
 * [ProcessRunner] does not use it: it needs the sink itself so it can read a
 * partial capture from a drain that never finished.
 */
internal fun InputStream.readAllTextSafely(maxBytes: Int): String =
    StreamSink(maxBytes).let { sink ->
        sink.drain(this)
        sink.text()
    }

/**
 * `Process.waitFor(timeout, unit)` is API 26+, which matches this app's minSdk,
 * but the `Process` returned by Shizuku is a remote proxy whose implementation may
 * not honour it. Falling back to polling `exitValue()` keeps a hung command from
 * blocking the caller forever.
 *
 * Never throws, so a caller does not have to distinguish "not finished" from
 * "could not tell" — both answers lead to the same place, destroying the process.
 */
internal fun Process.waitForTimeout(timeoutMillis: Long): Boolean {
    try {
        return waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        // `runInterruptible` interrupts this thread when the coroutine deadline
        // fires. Reported as "not finished", which is true, and the flag is
        // restored so the dispatcher's own bookkeeping is not left lying — this
        // runs on a pooled IO thread that will be handed to someone else.
        Thread.currentThread().interrupt()
        return false
    } catch (t: Throwable) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            try {
                exitValue()
                return true
            } catch (notYet: IllegalThreadStateException) {
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            } catch (t2: Throwable) {
                // The proxy cannot answer either. "Not finished" sends the caller
                // down the destroy path rather than trusting an unknown status.
                return false
            }
        }
        return false
    }
}

/**
 * Joins [jobs] but never for longer than [millis].
 *
 * `join()` on a coroutine blocked in `read()` does not return when the job is
 * cancelled — the job stays in its cancelling state until the body actually comes
 * back — so the bound is not optional.
 */
private suspend fun joinWithin(millis: Long, vararg jobs: Job) {
    withTimeoutOrNull(millis) {
        jobs.forEach { it.join() }
    }
}

private fun closeQuietly(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (ignored: Throwable) {
    }
}

/** Matches the detail wording the shells already use for a thrown failure. */
private fun describe(t: Throwable): String = t.message ?: t::class.java.simpleName

/** Keeps a drained stderr alongside an explanation instead of replacing it. */
private fun combine(detail: String, stderr: String): String =
    listOf(detail, stderr.trim()).filter { it.isNotBlank() }.joinToString("\n")
