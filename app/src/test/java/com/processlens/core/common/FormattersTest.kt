package com.processlens.core.common

import com.processlens.testing.assertContains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Number and time formatting (Section 56).
 *
 * These are the last step before a figure reaches the user's eye, so a bug here
 * misrepresents data that was read correctly. Two rules are load-bearing: byte sizes
 * are binary (every Android memory API reports KiB/bytes in powers of two, so showing
 * decimal MB would overstate free memory by 5%), and an unrepresentable input becomes
 * an em dash rather than a zero.
 */
class FormattersTest {

    // --------------------------------------------------------------------- bytes

    @Test
    fun `bytes below a kibibyte are shown exactly`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1023 B", Formatters.bytes(1023))
    }

    @Test
    fun `byte scaling is binary rather than decimal`() {
        // 1024, not 1000. Decimal scaling would report 1.05 MB for a mebibyte and
        // overstate every memory figure in the app.
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.0 MB", Formatters.bytes(1024L * 1024))
        assertEquals("1.0 GB", Formatters.bytes(1024L * 1024 * 1024))
        assertEquals("1.0 TB", Formatters.bytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a decimal is shown below one hundred and dropped above it`() {
        // Keeps list column widths stable: "1.5 KB" and "512 MB" are the same length.
        assertEquals("1.5 KB", Formatters.bytes(1536))
        assertEquals("100 KB", Formatters.bytes(100 * 1024))
        assertEquals("512 MB", Formatters.bytes(512L * 1024 * 1024))
    }

    @Test
    fun `a negative byte count is an em dash rather than a number`() {
        // Only ever produced by a subtraction of two readings where one was missing.
        assertEquals("—", Formatters.bytes(-1))
    }

    @Test
    fun `kibibytes are scaled up from the unit proc reports`() {
        // /proc/meminfo and Debug.MemoryInfo both speak KiB.
        assertEquals("1.0 MB", Formatters.kibibytes(1024))
        assertEquals("4.0 GB", Formatters.kibibytes(4L * 1024 * 1024))
    }

    @Test
    fun `the largest unit is not exceeded`() {
        // Guards the while-loop bound: a petabyte-scale figure must still format.
        assertContains(Formatters.bytes(Long.MAX_VALUE), "PB")
    }

    // ------------------------------------------------------------------ percent

    @Test
    fun `a fraction is rendered as a percentage`() {
        assertEquals("50%", Formatters.percent(0.5f))
        assertEquals("0%", Formatters.percent(0f))
        assertEquals("100%", Formatters.percent(1f))
    }

    @Test
    fun `a fraction outside zero to one is clamped`() {
        assertEquals("100%", Formatters.percent(1.5f))
        assertEquals("0%", Formatters.percent(-0.5f))
    }

    @Test
    fun `a not-a-number percentage is an em dash`() {
        // A division by a zero-length sampling interval produces NaN, and "NaN%" on a
        // stat tile would look like a bug rather than a missing measurement.
        assertEquals("—", Formatters.percent(Float.NaN))
        assertEquals("—", Formatters.percentValue(Float.NaN))
    }

    @Test
    fun `an already scaled percentage keeps one decimal by default`() {
        assertEquals("12.3%", Formatters.percentValue(12.34f))
        assertEquals("12.4%", Formatters.percentValue(12.36f))
        assertEquals("0.0%", Formatters.percentValue(0f))
    }

    @Test
    fun `a per process percentage above one hundred is preserved`() {
        // A process pinning two cores really is at 200% of one core, and clamping it to
        // 100% would hide exactly what an investigation is looking for.
        assertEquals("200.0%", Formatters.percentValue(200f))
    }

    @Test
    fun `the decimal count is configurable`() {
        assertEquals("12%", Formatters.percentValue(12.34f, decimals = 0))
        assertEquals("12.34%", Formatters.percentValue(12.34f, decimals = 2))
    }

    // ----------------------------------------------------------------- durations

    @Test
    fun `a duration under an hour omits the hour field`() {
        assertEquals("00:00", Formatters.duration(0))
        assertEquals("01:05", Formatters.duration(65_000))
        assertEquals("59:59", Formatters.duration(3_599_000))
    }

    @Test
    fun `a duration over an hour includes it`() {
        assertEquals("1:01:01", Formatters.duration(3_661_000))
        assertEquals("10:00:00", Formatters.duration(36_000_000))
    }

    @Test
    fun `a negative duration is an em dash`() {
        assertEquals("—", Formatters.duration(-1))
        assertEquals("—", Formatters.durationCoarse(-1))
    }

    @Test
    fun `coarse durations drop to the two most significant units`() {
        assertEquals("45s", Formatters.durationCoarse(45_000))
        assertEquals("1m 30s", Formatters.durationCoarse(90_000))
        assertEquals("1h 1m", Formatters.durationCoarse(3_660_000))
        assertEquals("3d 4h", Formatters.durationCoarse(3 * 86_400_000L + 4 * 3_600_000L))
    }

    // --------------------------------------------------------------------- rate

    @Test
    fun `a throughput is a byte size per second`() {
        assertEquals("2.0 KB/s", Formatters.rate(2048.0))
        assertEquals("1.0 MB/s", Formatters.rate(1024.0 * 1024.0))
    }

    @Test
    fun `sub byte throughput reads as zero rather than a fraction`() {
        assertEquals("0 B/s", Formatters.rate(0.0))
        assertEquals("0 B/s", Formatters.rate(0.4))
    }

    @Test
    fun `an impossible throughput is an em dash`() {
        assertEquals("—", Formatters.rate(-1.0))
        assertEquals("—", Formatters.rate(Double.NaN))
    }

    // ---------------------------------------------------------------- hardware

    @Test
    fun `frequencies switch to gigahertz above a million kilohertz`() {
        assertEquals("1.80 GHz", Formatters.frequencyKHz(1_800_000))
        assertEquals("2.40 GHz", Formatters.frequencyKHz(2_400_000))
        assertEquals("300 MHz", Formatters.frequencyKHz(300_000))
    }

    @Test
    fun `a parked core has no frequency to show`() {
        // The sysfs reader reports 0 kHz alongside isOnline = false, and the formatter
        // must not turn that into a plausible "0 MHz" reading.
        assertEquals("—", Formatters.frequencyKHz(0))
        assertEquals("—", Formatters.frequencyKHz(-1))
    }

    @Test
    fun `temperature converts from the deci celsius the platform reports`() {
        assertEquals("30.5 °C", Formatters.temperature(305))
        assertEquals("0.0 °C", Formatters.temperature(0))
    }

    @Test
    fun `voltage converts from millivolts with three decimals`() {
        assertEquals("3.850 V", Formatters.voltage(3850))
    }

    @Test
    fun `current keeps the sign the device reported`() {
        // The sign convention for CURRENT_NOW is OEM-specific, so the magnitude and
        // unit are shown as read and no time-remaining figure is ever derived from it.
        assertEquals("-350 mA", Formatters.currentMicroAmps(-350_000))
        assertEquals("1200 mA", Formatters.currentMicroAmps(1_200_000))
    }

    // ------------------------------------------------------------------- times

    @Test
    fun `an iso timestamp is utc and machine readable`() {
        // Exports may be opened on another machine in another zone, so the wire format
        // is fixed to UTC rather than the device's locale.
        assertEquals("2023-11-14T22:13:20Z", Formatters.iso8601(1_700_000_000_000L))
    }

    @Test
    fun `the clock time is a fixed width local time`() {
        // Rendered in the device's zone, so only the shape is asserted here.
        val clock = Formatters.clockTime(1_700_000_000_000L)
        assertTrue("unexpected clock format: $clock", Regex("^\\d{2}:\\d{2}:\\d{2}$").matches(clock))

        val short = Formatters.clockTimeShort(1_700_000_000_000L)
        assertTrue("unexpected short clock: $short", Regex("^\\d{2}:\\d{2}$").matches(short))
    }

    @Test
    fun `a date time is year first so it sorts as text`() {
        val stamp = Formatters.dateTime(1_700_000_000_000L)

        assertTrue(
            "unexpected date format: $stamp",
            Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$").matches(stamp),
        )
        assertTrue(stamp.startsWith("2023-11-1"))
    }

    // ------------------------------------------------------------------- counts

    @Test
    fun `counts are pluralised`() {
        assertEquals("1 process", Formatters.count(1, "process", "processes"))
        assertEquals("2 processes", Formatters.count(2, "process", "processes"))
        assertEquals("0 processes", Formatters.count(0, "process", "processes"))
    }

    @Test
    fun `the plural defaults to an appended s`() {
        assertEquals("1 thread", Formatters.count(1, "thread"))
        assertEquals("3 threads", Formatters.count(3, "thread"))
    }
}
