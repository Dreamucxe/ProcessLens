package com.processlens.core.common

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formatting helpers. Pure functions with no Android dependencies so they are
 * covered by fast JVM unit tests.
 */
object Formatters {

    /**
     * Binary byte sizes, as used by every Android memory API (`MemoryInfo`,
     * `Debug.MemoryInfo`, /proc/meminfo are all KiB/bytes powers of two).
     * 1 decimal below 100, none above, so column widths stay stable in lists.
     */
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var v = value.toDouble() / 1024.0
        var idx = 0
        while (v >= 1024.0 && idx < units.lastIndex) {
            v /= 1024.0
            idx++
        }
        val pattern = if (v >= 100.0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.US, pattern, v, units[idx])
    }

    /** Kibibytes as reported by /proc/meminfo and `Debug.MemoryInfo` getters. */
    fun kibibytes(kb: Long): String = bytes(kb * 1024L)

    fun percent(fraction: Float, decimals: Int = 0): String {
        if (fraction.isNaN()) return "—"
        val clamped = fraction.coerceIn(0f, 1f) * 100f
        return String.format(Locale.US, "%.${decimals}f%%", clamped)
    }

    /** A already-scaled percentage value (0..100). */
    fun percentValue(value: Float, decimals: Int = 1): String {
        if (value.isNaN()) return "—"
        return String.format(Locale.US, "%.${decimals}f%%", value.coerceAtLeast(0f))
    }

    /** `HH:MM:SS`, or `MM:SS` under an hour. Used for uptime and durations. */
    fun duration(millis: Long): String {
        if (millis < 0) return "—"
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** Coarse, human phrasing for long spans: "3d 4h", "2h 11m", "45s". */
    fun durationCoarse(millis: Long): String {
        if (millis < 0) return "—"
        val d = TimeUnit.MILLISECONDS.toDays(millis)
        val h = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** Throughput for the network screen. */
    fun rate(bytesPerSecond: Double): String {
        if (bytesPerSecond.isNaN() || bytesPerSecond < 0) return "—"
        if (bytesPerSecond < 1.0) return "0 B/s"
        return "${bytes(bytesPerSecond.toLong())}/s"
    }

    fun frequencyKHz(khz: Long): String = when {
        khz <= 0 -> "—"
        khz >= 1_000_000 -> String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
        else -> String.format(Locale.US, "%d MHz", khz / 1000)
    }

    fun temperature(deciCelsius: Int): String =
        String.format(Locale.US, "%.1f °C", deciCelsius / 10.0)

    fun voltage(milliVolts: Int): String =
        String.format(Locale.US, "%.3f V", milliVolts / 1000.0)

    /**
     * `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` is documented as microamps,
     * but its sign convention is left to the OEM and some devices report
     * milliamps. The screen therefore shows the magnitude with the raw unit
     * named, and never derives a "time remaining" from it (Section 0.1).
     */
    fun currentMicroAmps(microAmps: Int): String =
        String.format(Locale.US, "%.0f mA", microAmps / 1000.0)

    fun clockTime(epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%02d:%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    fun clockTimeShort(epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    fun dateTime(epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%04d-%02d-%02d %02d:%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    /** ISO-8601 UTC, for JSON/CSV exports that may be read on another machine. */
    fun iso8601(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(epochMillis))
    }

    fun count(value: Int, singular: String, plural: String = singular + "s"): String =
        "$value ${if (value == 1) singular else plural}"
}
