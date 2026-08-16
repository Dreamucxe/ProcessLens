package com.processlens.core.system

import com.processlens.testing.assertContains
import com.processlens.testing.assertDoesNotContain
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Test

/**
 * The crash report's text (Section 56).
 *
 * Worth testing for a reason that is easy to miss: this is the one piece of code that runs
 * *after* something has already gone wrong, so if it is broken the symptom is an absent
 * report rather than a failure anyone can see. A test is the only place it gets exercised
 * before it matters.
 *
 * Only the text is tested here. Where the file lands goes through `MediaStore`, which does
 * not exist on the JVM; that path is exercised by running the app.
 */
class CrashReporterTest {

    /** A fixed instant, so the assertion does not depend on when the test runs. */
    private val instant = Date(1_700_000_000_000L)

    private fun report(error: Throwable): String =
        CrashReporter.report(Thread.currentThread(), error, instant, DEVICE)

    @Test
    fun `the exception type and message are both in the report`() {
        // The type alone is rarely enough — "IllegalStateException" describes hundreds of
        // different bugs, and the message is what distinguishes them.
        val text = report(IllegalStateException("database is closed"))

        assertContains(text, "IllegalStateException")
        assertContains(text, "database is closed")
    }

    @Test
    fun `the cause chain is included and not only the outermost throwable`() {
        // The failure that actually started it is almost always the innermost one. A
        // report that stopped at the wrapper would name the messenger, not the problem —
        // which is exactly the shape of the launch crash this was written for, where a
        // ClassNotFoundException arrives wrapped in a RuntimeException from the framework.
        val root = ClassNotFoundException("rikka.shizuku.ShizukuProvider")
        val text = report(RuntimeException("Unable to get provider", root))

        assertContains(text, "Unable to get provider")
        assertContains(text, "Caused by")
        assertContains(text, "ClassNotFoundException")
        assertContains(text, "rikka.shizuku.ShizukuProvider")
    }

    @Test
    fun `a stack frame is included`() {
        // Without frames the report is a sentence, not a diagnosis.
        val text = report(RuntimeException("boom"))

        assertContains(text, "CrashReporterTest")
        assertContains(text, "at ")
    }

    @Test
    fun `the thread and the device are named`() {
        // Which thread separates a main-thread crash from a background one, and the
        // device matters because half of what this app reads varies by API level.
        val text = report(RuntimeException("boom"))

        // The whole labelled line, not just the bare name: a thread called "main" would
        // match almost any report by accident.
        assertContains(text, "Thread:  " + Thread.currentThread().name)
        assertContains(text, "Device:  " + DEVICE)
    }

    @Test
    fun `the time of the crash is stated as a date rather than an epoch`() {
        val text = report(RuntimeException("boom"))

        // Expected value formatted here rather than written out, because the report uses
        // the device's own time zone and this test must pass in any of them.
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(instant)

        assertContains(text, "When:")
        assertContains(text, expected)
        // A raw millisecond count would be useless to the person reading the file.
        assertDoesNotContain(text, instant.time.toString())
    }

    @Test
    fun `the report says that nothing was uploaded`() {
        // Section 29. A file that appears in Downloads after a crash looks like telemetry
        // unless it says otherwise, and the app has no INTERNET permission to send it
        // with. The reassurance travels with the file rather than living in a settings
        // screen the user is not currently looking at.
        val text = report(RuntimeException("boom"))

        assertContains(text, "Nothing was uploaded")
    }

    @Test
    fun `a throwable with no message still produces a usable report`() {
        // `NullPointerException()` with no message is common, and formatting must not
        // depend on there being one.
        val text = report(NullPointerException())

        assertContains(text, "NullPointerException")
        assertContains(text, "CrashReporterTest")
        assertDoesNotContain(text, "null: null")
    }

    private companion object {
        const val DEVICE = "ProcessLens 1.0 · Test Device · Android 14 (API 34) · arm64-v8a"
    }
}
