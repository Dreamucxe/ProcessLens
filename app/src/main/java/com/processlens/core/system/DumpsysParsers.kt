package com.processlens.core.system

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.Precision
import com.processlens.domain.model.AppBatteryUsage
import com.processlens.domain.model.ProcessState
import com.processlens.domain.model.ServiceInfo
import com.processlens.domain.model.WakeLockInfo

/**
 * Parsers for the text that `dumpsys` and `ps` produce (Section 46).
 *
 * These exist because there is no API for any of it — the information is real and
 * the platform genuinely computes it, but the only way to obtain it is to read the
 * service's own debug dump through an elevated shell.
 *
 * Two rules apply throughout, both from Section 42:
 *
 *  1. **A parse that does not match returns [Observed.Failed], never a guess.**
 *     dumpsys output is not a stable contract; it varies between AOSP versions and
 *     OEM builds. When the expected structure is absent the honest answer is "this
 *     device's output could not be parsed", which the UI shows with the raw first
 *     lines in the technical-details expander.
 *  2. **A field the dump does not contain stays [Observed.Restricted].** Held
 *     durations, for instance, are absent from most versions' wake-lock dump, so
 *     they are reported as unavailable rather than derived from something else.
 *
 * Everything here is pure text-in/data-out, which also makes it unit-testable
 * against captured fixtures without a device.
 */
internal object DumpsysParsers {

    // ------------------------------------------------------------------ ps table

    /**
     * One row of `ps -A -o PID,PPID,USER,RSS,VSZ,STAT,TIME,NAME`.
     *
     * [rssKb] and [vszKb] are kilobytes as toybox prints them; [cpuTimeMillis] is
     * the accumulated CPU time, which is a total rather than a rate — the caller
     * turns it into a percentage by sampling twice, exactly as it does for /proc.
     */
    data class PsRow(
        val pid: Int,
        val ppid: Int,
        val user: String,
        val rssKb: Long,
        val vszKb: Long,
        val state: ProcessState,
        val cpuTimeMillis: Long,
        val name: String,
    )

    fun parsePsTable(text: String): Observed<List<PsRow>> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return Observed.Failed("The process table command produced no output")

        // Locate the header so column order is verified rather than assumed: a
        // toybox build that ignores -o would otherwise be parsed as if it had not.
        val headerIndex = lines.indexOfFirst { line ->
            val upper = line.uppercase()
            upper.contains("PID") && upper.contains("NAME")
        }
        if (headerIndex < 0) {
            return Observed.Failed(
                "The process table output did not contain the expected columns",
                lines.firstOrNull()?.take(200),
            )
        }

        val rows = ArrayList<PsRow>(lines.size)
        for (i in (headerIndex + 1) until lines.size) {
            // NAME is last and may contain spaces, so the split is bounded.
            val parts = lines[i].trim().split(WHITESPACE, limit = 8)
            if (parts.size < 8) continue
            val pid = parts[0].toIntOrNull() ?: continue
            rows += PsRow(
                pid = pid,
                ppid = parts[1].toIntOrNull() ?: -1,
                user = parts[2],
                rssKb = parts[3].toLongOrNull() ?: 0L,
                vszKb = parts[4].toLongOrNull() ?: 0L,
                // STAT is a multi-character field: "S", "Ss", "R+", "D<". Only the
                // first character is the scheduler state.
                state = ProcessState.fromProcChar(parts[5].firstOrNull() ?: '?'),
                cpuTimeMillis = parseCpuTime(parts[6]),
                name = parts[7].trim(),
            )
        }

        return if (rows.isEmpty()) {
            Observed.Failed(
                "The process table output could not be parsed on this device",
                lines.take(3).joinToString(" / ").take(300),
            )
        } else {
            Observed.of(rows, DataSource.SHELL_SHIZUKU)
        }
    }

    /** `[[DD-]HH:]MM:SS[.cc]` as printed by toybox ps. */
    private fun parseCpuTime(raw: String): Long {
        var text = raw
        var days = 0L
        val dash = text.indexOf('-')
        if (dash > 0) {
            days = text.substring(0, dash).toLongOrNull() ?: 0L
            text = text.substring(dash + 1)
        }
        val fields = text.split(':')
        val seconds = when (fields.size) {
            3 -> (fields[0].toLongOrNull() ?: 0L) * 3600 +
                (fields[1].toLongOrNull() ?: 0L) * 60 +
                (fields[2].toDoubleOrNull() ?: 0.0).toLong()
            2 -> (fields[0].toLongOrNull() ?: 0L) * 60 +
                (fields[1].toDoubleOrNull() ?: 0.0).toLong()
            1 -> (fields[0].toDoubleOrNull() ?: 0.0).toLong()
            else -> 0L
        }
        return (days * 86_400L + seconds) * 1000L
    }

    // ----------------------------------------------------------------- wakelocks

    /**
     * The `Wake Locks:` section of `dumpsys power`.
     *
     * AOSP's `PowerManagerService.WakeLock.toString()` produces, per lock:
     * ```
     *   PARTIAL_WAKE_LOCK              'AlarmManager' ON_AFTER_RELEASE (uid=1000, pid=1234, ws=null)
     * ```
     * The level, the quoted tag and the `uid=`/`pid=` pairs are stable across every
     * version that has this dump. A held duration is *not* printed on most
     * versions, so it is reported unavailable unless an `elapsedTime=` appears.
     *
     * The `ws=WorkSource{...}` field matters: it names the app the lock is *held on
     * behalf of*, which is often not the holder. Attribution uses it when present,
     * because saying a wake lock belongs to `system_server` when it is held for a
     * third-party app would misdirect the whole investigation.
     */
    fun parseWakeLocks(text: String, level: AccessLevel): Observed<List<WakeLockInfo>> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("Wake Locks:") }
        if (start < 0) {
            return Observed.Failed(
                "This device's power dump does not contain a wake lock section",
                lines.firstOrNull()?.take(200),
            )
        }

        // "Wake Locks: size=0" is a real answer meaning nothing is held.
        val declaredSize = SIZE.find(lines[start])?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (declaredSize == 0) {
            return Observed.of(emptyList(), sourceFor(level))
        }

        val out = ArrayList<WakeLockInfo>()
        for (i in (start + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            // The section ends at the next top-level key, which is unindented or
            // ends in ':' — e.g. "Suspend Blockers: size=4".
            val indent = line.takeWhile { it == ' ' }.length
            if (indent == 0) break
            val match = WAKE_LOCK.find(line.trim())
            if (match == null) {
                if (line.trimStart().endsWith(":")) break
                continue
            }

            val lockLevel = match.groupValues[1]
            val tag = match.groupValues[2]
            val rest = match.groupValues[3]

            val uid = UID.find(rest)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val workSourceUid = WORK_SOURCE_UID.find(rest)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val elapsed = ELAPSED.find(rest)?.groupValues?.getOrNull(1)?.toLongOrNull()

            out += WakeLockInfo(
                tag = tag,
                // The work source wins when present: it is the app the lock is for.
                packageName = null,
                type = lockLevel,
                isActive = true,
                heldDurationMillis = elapsed?.let { Observed.of(it, sourceFor(level)) }
                    ?: Observed.notPresent(
                        "This Android version does not print how long each wake lock has been held",
                    ),
                acquireCount = Observed.notPresent(
                    "Acquisition counts are not printed in the power dump",
                ),
                uid = workSourceUid ?: uid,
                isOnBehalfOfOther = workSourceUid != null && workSourceUid != uid,
            )
        }

        return if (out.isEmpty() && declaredSize != null && declaredSize > 0) {
            Observed.Failed(
                "The power dump reported $declaredSize wake locks but none could be parsed",
                lines.getOrNull(start + 1)?.take(200),
            )
        } else {
            Observed.of(out, sourceFor(level))
        }
    }

    // ------------------------------------------------------------------ services

    /**
     * `dumpsys activity services`. Each record starts with
     * `* ServiceRecord{hash u0 package/.Class}` and is followed by indented
     * `key=value` lines, of which `app=ProcessRecord{hash pid:process/uid}` carries
     * the hosting PID and `isForeground=` the foreground state.
     */
    fun parseRunningServices(
        text: String,
        level: AccessLevel,
        filterPackage: String?,
    ): Observed<List<ServiceInfo>> {
        val lines = text.lines()
        if (lines.none { it.contains("ServiceRecord{") }) {
            // An entirely empty services dump is possible but vanishingly unlikely;
            // treat a missing marker as an unparseable dump, not as "none running".
            return if (lines.any { it.contains("active services", ignoreCase = true) }) {
                Observed.of(emptyList(), sourceFor(level))
            } else {
                Observed.Failed(
                    "This device's activity services dump could not be recognised",
                    lines.firstOrNull()?.take(200),
                )
            }
        }

        val out = ArrayList<ServiceInfo>()
        var index = 0
        while (index < lines.size) {
            val header = SERVICE_RECORD.find(lines[index])
            if (header == null) {
                index++
                continue
            }
            val component = header.groupValues[1]
            val slash = component.indexOf('/')
            if (slash <= 0) {
                index++
                continue
            }
            val packageName = component.substring(0, slash)
            // Android abbreviates a class in the same package as ".Name".
            val rawClass = component.substring(slash + 1)
            val className = if (rawClass.startsWith(".")) packageName + rawClass else rawClass

            // Collect the record's indented body.
            val body = StringBuilder()
            var cursor = index + 1
            while (cursor < lines.size && !lines[cursor].contains("ServiceRecord{")) {
                body.append(lines[cursor]).append('\n')
                cursor++
            }
            index = cursor

            if (filterPackage != null && packageName != filterPackage) continue

            val bodyText = body.toString()
            val pid = APP_PROCESS_RECORD.find(bodyText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val processName = KEY_PROCESS_NAME.find(bodyText)?.groupValues?.getOrNull(1)
                ?: packageName
            val isForeground = bodyText.contains("isForeground=true")

            out += ServiceInfo(
                className = className,
                packageName = packageName,
                processName = processName.trim(),
                pid = pid?.let { Observed.of(it, sourceFor(level)) }
                    ?: Observed.notPresent("This service is not currently hosted in a process"),
                isForeground = isForeground,
                // The dump prints "connections" as a nested block rather than a
                // count, so a client count is not claimed.
                clientCount = Observed.notPresent(
                    "The services dump does not print a client count",
                ),
                activeSinceMillis = Observed.notPresent(
                    "The services dump prints a relative age, not an absolute start time",
                ),
                isExported = Observed.platform(
                    "Export state comes from the manifest, shown on the component list",
                ),
            )
        }
        return Observed.of(out, sourceFor(level))
    }

    // ------------------------------------------------------------------- meminfo

    /**
     * The `Total PSS by process:` table of `dumpsys meminfo`.
     *
     * PSS is the figure Android itself uses to decide what to kill, which makes it
     * more meaningful than the RSS in `/proc`, and it is only obtainable this way.
     * Returns bytes keyed by PID.
     */
    fun parsePssByPid(text: String): Observed<Map<Int, Long>> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.contains("Total PSS by process", ignoreCase = true) }
        if (start < 0) {
            return Observed.Failed(
                "This device's meminfo dump does not contain a per-process PSS table",
                lines.firstOrNull()?.take(200),
            )
        }
        val out = HashMap<Int, Long>()
        for (i in (start + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                // A blank line after at least one row ends the table.
                if (out.isNotEmpty()) break else continue
            }
            val match = PSS_ROW.find(line) ?: continue
            val kb = match.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val pid = match.groupValues[3].toIntOrNull() ?: continue
            out[pid] = kb * 1024L
        }
        return if (out.isEmpty()) {
            Observed.Failed("The per-process PSS table could not be parsed on this device")
        } else {
            Observed.of(out, DataSource.DUMPSYS_SHIZUKU)
        }
    }

    // -------------------------------------------------------------- batterystats

    /**
     * The `Estimated power use (mAh):` section of `dumpsys batterystats`.
     *
     * This is Android's *own* attribution, computed by BatteryStatsService from the
     * device power profile — not something ProcessLens estimates. That distinction
     * is the whole reason it is worth parsing: Section 17 forbids inventing battery
     * figures, and this is a real, platform-computed one.
     *
     * `Uid u0a123:` is Android's encoding for uid 10123 (`u0` = user 0,
     * `a123` = app id 123, so uid = 100000*0 + 10000 + 123).
     */
    fun parseBatteryUsage(text: String, level: AccessLevel): Observed<List<AppBatteryUsage>> {
        val lines = text.lines()
        val start = lines.indexOfFirst {
            it.contains("Estimated power use", ignoreCase = true)
        }
        if (start < 0) {
            return Observed.Failed(
                "This device's battery stats dump does not contain a power-use section",
                lines.firstOrNull()?.take(200),
            )
        }

        var computedDrainMah: Double? = null
        val out = ArrayList<AppBatteryUsage>()

        for (i in (start + 1) until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                if (out.isNotEmpty()) break else continue
            }
            // Section header for the next block, e.g. "Per-app mobile ms per packet:".
            if (line.endsWith(":") && !line.startsWith("Uid ")) break

            // Only the computed-drain total is taken from this line. The design
            // capacity in group 1 is deliberately dropped: nothing displays it, and
            // Section 18 forbids deriving a battery-health figure from it — design
            // capacity against a dump's computed drain is not a measurement of wear.
            CAPACITY.find(line)?.let { m ->
                computedDrainMah = m.groupValues.getOrNull(2)?.toDoubleOrNull()
            }

            val match = UID_POWER.find(line) ?: continue
            val uid = decodeDumpsysUid(match.groupValues[1]) ?: continue
            val mah = match.groupValues[2].toDoubleOrNull() ?: continue
            val breakdown = match.groupValues.getOrNull(3)?.trim().orEmpty()

            out += AppBatteryUsage(
                uid = uid,
                packageName = null,
                appLabel = null,
                milliampHours = mah,
                // The parenthesised breakdown ("cpu=30.0 wake=5.0 wifi=10.6") is
                // kept verbatim as evidence rather than reinterpreted.
                breakdown = breakdown.ifBlank { null },
                percentOfComputedDrain = computedDrainMah
                    ?.takeIf { it > 0.0 }
                    ?.let { Observed.of((mah / it * 100.0).toFloat(), sourceFor(level), Precision.ESTIMATED) }
                    ?: Observed.notPresent(
                        "This dump does not report a total computed drain to compare against",
                    ),
            )
        }

        return if (out.isEmpty()) {
            Observed.Failed(
                "No per-app power attribution could be parsed from this device's battery stats",
            )
        } else {
            Observed.of(
                out.sortedByDescending { it.milliampHours },
                sourceFor(level),
            )
        }
    }

    /**
     * Decodes `u0a123` / `u10a5` / a bare numeric uid.
     *
     * The encoding is `u<user>a<appId>` for app uids and `u<user>s<n>` /
     * `u<user>i<n>` for shared and isolated ones. Anything unrecognised returns
     * null so the row is dropped rather than attributed to the wrong app.
     */
    fun decodeDumpsysUid(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val match = DUMPSYS_UID.matchEntire(token) ?: return null
        val user = match.groupValues[1].toIntOrNull() ?: return null
        val kind = match.groupValues[2]
        val id = match.groupValues[3].toIntOrNull() ?: return null
        val base = when (kind) {
            "a" -> FIRST_APPLICATION_UID + id
            "i" -> FIRST_ISOLATED_UID + id
            "s" -> id // shared system uid, e.g. u0s1000
            else -> return null
        }
        return user * PER_USER_RANGE + base
    }

    private fun sourceFor(level: AccessLevel): DataSource = when (level) {
        AccessLevel.ROOT -> DataSource.DUMPSYS_ROOT
        else -> DataSource.DUMPSYS_SHIZUKU
    }

    private val WHITESPACE = Regex("\\s+")
    private val SIZE = Regex("size=(\\d+)")
    private val WAKE_LOCK = Regex("^([A-Z_]+_WAKE_LOCK|[A-Z_]{4,})\\s+'([^']*)'(.*)$")
    private val UID = Regex("uid=(\\d+)")
    private val WORK_SOURCE_UID = Regex("ws=WorkSource\\{(\\d+)")
    private val ELAPSED = Regex("elapsedTime=(\\d+)")
    private val SERVICE_RECORD = Regex("ServiceRecord\\{[^}]*?\\s(\\S+/\\S+)\\}")
    private val APP_PROCESS_RECORD = Regex("app=ProcessRecord\\{[^}]*?\\s(\\d+):")
    private val KEY_PROCESS_NAME = Regex("processName=(\\S+)")
    private val PSS_ROW = Regex("^\\s*([\\d,]+)K:\\s+(\\S+)\\s+\\(pid\\s+(\\d+)")
    private val UID_POWER = Regex("^Uid\\s+(\\S+?):\\s+([\\d.]+)(?:\\s*\\((.*)\\))?")
    private val CAPACITY = Regex("Capacity:\\s*([\\d.]+).*?Computed drain:\\s*([\\d.]+)")
    private val DUMPSYS_UID = Regex("^u(\\d+)([ais])(\\d+)$")

    private const val FIRST_APPLICATION_UID = 10_000
    private const val FIRST_ISOLATED_UID = 99_000
    private const val PER_USER_RANGE = 100_000
}
