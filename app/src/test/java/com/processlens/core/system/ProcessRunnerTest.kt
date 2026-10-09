package com.processlens.core.system

import com.processlens.core.common.AccessLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

/**
 * Tests for the real-deadline process runner (requirement 4 of the issue #1 spec).
 *
 * The seam that makes this a JVM test with no device: [ProcessStarter]. Every path
 * the field hit — `su` missing, `su` denied, `su` parked on an unanswered prompt,
 * `su` granting — is driven here by a [FakeProcess] standing in for the OS process,
 * so the timeout, the destroy-on-every-path teardown and the concurrent drain are
 * exercised against code that never touches a shell.
 *
 * These use [runBlocking] on a real dispatcher rather than virtual time on purpose:
 * the whole point of the class is that a blocking `read()` and a wall-clock deadline
 * behave correctly together, and virtual time would hide exactly the interaction
 * being tested.
 */
class ProcessRunnerTest {

    private val runner = ProcessRunner(Dispatchers.IO)

    private fun run(
        argv: List<String> = listOf("id"),
        timeoutMillis: Long = 1_000L,
        starter: ProcessStarter,
    ): ShellResult = runBlocking {
        runner.execute(argv, AccessLevel.ROOT, timeoutMillis, starter = starter)
    }

    // ---------------------------------------------------------------- su succeeds

    @Test
    fun `a process that exits zero reports success and its stdout`() {
        val result = run { FakeProcess(stdout = "uid=0(root) gid=0(root)\n", exitCode = 0) }

        assertTrue(result.isSuccess)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("uid=0"))
        assertEquals(AccessLevel.ROOT, result.accessLevel)
    }

    @Test
    fun `both pipes are drained and kept separate`() {
        val result = run {
            FakeProcess(stdout = "the answer\n", stderr = "a warning\n", exitCode = 0)
        }

        assertTrue(result.stdout.contains("the answer"))
        assertTrue(result.stderr.contains("a warning"))
        assertFalse("stderr must not leak into stdout", result.stdout.contains("a warning"))
    }

    // ----------------------------------------------------------------- su denied

    @Test
    fun `a non-zero exit is a failure that carries the exit code and stderr`() {
        val result = run {
            FakeProcess(stderr = "Permission denied\n", exitCode = 1)
        }

        assertFalse(result.isSuccess)
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Permission denied"))
    }

    // ---------------------------------------------------------------- su missing

    @Test
    fun `a starter that returns null is a start failure, not a crash`() {
        val result = run { null }

        assertFalse(result.isSuccess)
        assertEquals(ProcessRunner.EXIT_NOT_STARTED, result.exitCode)
        assertTrue(result.stderr.contains("could not start"))
    }

    @Test
    fun `a thrown starter is reported as a failure with its message`() {
        val result = run { throw java.io.IOException("No such file or directory") }

        assertFalse(result.isSuccess)
        assertEquals(ProcessRunner.EXIT_NOT_STARTED, result.exitCode)
        assertTrue(result.stderr.contains("No such file or directory"))
    }

    @Test
    fun `an empty argv never starts anything`() {
        var started = false
        val result = run(argv = emptyList()) {
            started = true
            FakeProcess(exitCode = 0)
        }

        assertFalse(started)
        assertFalse(result.isSuccess)
    }

    // ------------------------------------------------------------------ su hangs

    @Test
    fun `a process that never exits is timed out and destroyed`() {
        val process = FakeProcess(exitCode = 0, hang = true)

        val started = System.currentTimeMillis()
        val result = run(timeoutMillis = 200L) { process }
        val elapsed = System.currentTimeMillis() - started

        assertEquals(ProcessRunner.EXIT_TIMED_OUT, result.exitCode)
        assertFalse(result.isSuccess)
        assertTrue("the deadline is real, not merely a label", result.stderr.contains("timed out"))
        assertTrue("the child must be destroyed to release the readers", process.wasDestroyed())
        assertTrue(
            "the call must return near the deadline, not hang: ${elapsed}ms",
            elapsed < 2_000L,
        )
    }

    @Test
    fun `output produced before a hang survives the timeout`() {
        val process = FakeProcess(
            stdoutStream = PrefixThenBlockStream("partial output\n"),
            exitCode = 0,
            hang = true,
        )

        val result = run(timeoutMillis = 200L) { process }

        assertEquals(ProcessRunner.EXIT_TIMED_OUT, result.exitCode)
        assertTrue(
            "bytes captured before the hang are evidence, not discarded",
            result.stdout.contains("partial output"),
        )
    }

    /**
     * A fake OS process.
     *
     * [hang] parks it: [exitValue] throws until [destroy] is called, so the real
     * `Process.waitFor(timeout, unit)` polling loop returns false at the deadline —
     * which is precisely the "`su` on an unanswered prompt" case.
     */
    private class FakeProcess(
        stdout: String = "",
        stderr: String = "",
        private val exitCode: Int = 0,
        private val hang: Boolean = false,
        stdoutStream: InputStream? = null,
    ) : Process() {

        private val out: InputStream = stdoutStream ?: ByteArrayInputStream(stdout.toByteArray())
        private val err: InputStream = ByteArrayInputStream(stderr.toByteArray())
        private val sink = ByteArrayOutputStream()
        private val destroyed = AtomicBoolean(false)

        fun wasDestroyed(): Boolean = destroyed.get()

        override fun getOutputStream(): OutputStream = sink
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = err

        override fun waitFor(): Int {
            while (hang && !destroyed.get()) Thread.sleep(10)
            return exitValue()
        }

        override fun exitValue(): Int {
            if (hang && !destroyed.get()) throw IllegalThreadStateException("still running")
            return if (destroyed.get() && hang) 143 else exitCode
        }

        override fun destroy() {
            if (destroyed.compareAndSet(false, true)) {
                runCatching { out.close() }
                runCatching { err.close() }
            }
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            super.waitFor(timeout, unit)
    }

    /**
     * Yields a prefix, then blocks until closed — the shape of a pipe whose child is
     * still alive but no longer producing. [close] (which the runner calls when it
     * destroys the process) is what lets the blocked read throw and the drain end.
     */
    private class PrefixThenBlockStream(prefix: String) : InputStream() {
        private val head = ByteArrayInputStream(prefix.toByteArray())
        private val closed = AtomicBoolean(false)

        override fun read(): Int {
            val b = head.read()
            if (b != -1) return b
            while (!closed.get()) Thread.sleep(10)
            throw java.io.IOException("stream closed")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = head.read(b, off, len)
            if (n != -1) return n
            while (!closed.get()) Thread.sleep(10)
            throw java.io.IOException("stream closed")
        }

        override fun close() {
            closed.set(true)
        }
    }
}
