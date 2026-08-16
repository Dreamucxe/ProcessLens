package com.processlens.domain.usecase

import com.processlens.core.common.Formatters
import com.processlens.domain.model.EventGroup
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationSummary
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.model.RankedProcess
import com.processlens.domain.model.SnapshotEntry

/**
 * Everything an export is allowed to contain (Section 26).
 *
 * Assembled by [ExportInvestigation] and rendered here. The split exists so the
 * rendering is pure: no repositories, no `Context`, no clock. That makes the exact
 * bytes of an export assertable in a unit test, which matters more here than
 * anywhere else in the app — an export is the artefact a user pastes into a bug
 * report, and a silent zero in it would outlive any wording on screen.
 *
 * [systemPackages] is the set of packages known to be system apps. It is used to
 * *drop* rows when the user asked for user-installed apps only, never to relabel
 * them. A package absent from the set is not assumed to be either kind: it is kept,
 * because dropping unknowns would quietly shrink the evidence.
 */
data class ExportData(
    val investigation: Investigation,
    val snapshots: List<ProcessSnapshot>,
    val events: List<InvestigationEvent>,
    val summary: InvestigationSummary?,
    val includeSystemApps: Boolean,
    val systemPackages: Set<String>,
    val appVersionName: String,
    val exportedAt: Long,
) {
    /**
     * Per-process rows, filtered by the user's system-app preference.
     *
     * A row with no package name at all — a kernel thread, or a process whose
     * package could not be resolved — is kept regardless. It is not a system *app*,
     * and discarding it would remove the very rows that are hardest to see.
     */
    fun entriesOf(snapshot: ProcessSnapshot): List<SnapshotEntry> =
        if (includeSystemApps) {
            snapshot.entries
        } else {
            snapshot.entries.filter { it.packageName == null || it.packageName !in systemPackages }
        }

    /** True when at least one row was withheld, so the export can say so. */
    val hasFilteredRows: Boolean
        get() = !includeSystemApps && snapshots.any { entriesOf(it).size != it.entries.size }
}

/** A finished export, ready to be written to disk. */
data class ExportDocument(
    val fileName: String,
    val mimeType: String,
    val content: String,
)

/**
 * Renders an investigation as JSON, CSV or plain text (Section 26).
 *
 * Three rules govern all three formats.
 *
 * First, an absent measurement is absent. A null CPU sample becomes JSON `null`, an
 * empty CSV field, and the words "not measured" in text — never `0`. A spreadsheet
 * that averages a zero-filled column produces a number that looks like evidence and
 * is not, and the whole point of Section 42 is that this app does not do that.
 *
 * Second, every export carries its own provenance: the access level in force, the
 * API level, the sampling interval, and the limitations the recording ran under. The
 * same recording means different things at different access levels, and a file that
 * has left the app can no longer ask.
 *
 * Third, nothing is included that ProcessLens did not observe about processes and
 * system resources. There are no credentials, tokens or file contents in here
 * because none are ever read — the export carries process names, package names,
 * PIDs, and resource figures, and the header says exactly that.
 */
object ExportRenderer {

    const val NOT_MEASURED = "not measured"

    /** The legend printed in every format, explaining absence. */
    const val ABSENCE_NOTE: String =
        "A blank or null figure means the value was not measurable at the access " +
            "level in force, not that it was zero."

    private const val PRIVACY_NOTE: String =
        "Contents: process names, package names, PIDs, and resource measurements. " +
            "ProcessLens reads no file contents, credentials, tokens or personal data, " +
            "so none can appear here."

    fun render(data: ExportData, format: ExportFormat): ExportDocument = ExportDocument(
        fileName = fileName(data, format),
        mimeType = format.mimeType,
        content = when (format) {
            ExportFormat.JSON -> renderJson(data)
            ExportFormat.CSV -> renderCsv(data)
            ExportFormat.TEXT -> renderText(data)
        },
    )

    /**
     * A file name that stays sortable and survives every file system.
     *
     * The recording's own name is slugged rather than trusted: it is user text and
     * may contain slashes or colons.
     */
    fun fileName(data: ExportData, format: ExportFormat): String {
        val slug = slug(data.investigation.name).ifEmpty { "recording" }
        val stamp = Formatters.iso8601(data.investigation.startedAt)
            .replace(":", "")
            .replace("-", "")
        return "processlens-" + slug + "-" + stamp + "." + format.extension
    }

    private fun slug(value: String): String {
        val builder = StringBuilder()
        var lastWasDash = false
        for (char in value.lowercase()) {
            if (char.isLetterOrDigit()) {
                builder.append(char)
                lastWasDash = false
            } else if (!lastWasDash && builder.isNotEmpty()) {
                builder.append('-')
                lastWasDash = true
            }
        }
        return builder.toString().trimEnd('-').take(40)
    }

    // ---------------------------------------------------------------- JSON

    private fun renderJson(data: ExportData): String {
        val investigation = data.investigation
        val out = JsonWriter()
        out.obj {
            key("export")
            obj {
                keyValue("tool", "ProcessLens")
                keyValue("toolVersion", data.appVersionName)
                keyValue("formatVersion", 1)
                keyValue("exportedAt", Formatters.iso8601(data.exportedAt))
                keyValue("absenceMeaning", ABSENCE_NOTE)
                keyValue("privacy", PRIVACY_NOTE)
            }

            key("recording")
            obj {
                keyValue("id", investigation.id)
                keyValue("name", investigation.name)
                keyValue("state", investigation.state.name)
                keyValue("startedAt", Formatters.iso8601(investigation.startedAt))
                keyNullableString(
                    "endedAt",
                    investigation.endedAt?.let { Formatters.iso8601(it) },
                )
                keyValue("durationMillis", investigation.durationMillis)
                keyValue("sampleIntervalMillis", investigation.sampleIntervalMillis)
                keyNullableString("targetPackage", investigation.targetPackage)
                keyValue("snapshotCount", investigation.snapshotCount)
                keyValue("eventCount", investigation.eventCount)
                keyValue("processesObserved", investigation.processesObserved)
                keyNullableString("notes", investigation.notes)
            }

            key("provenance")
            obj {
                keyValue("accessLevel", investigation.accessLevelName)
                keyValue("apiLevel", investigation.apiLevel)
                keyValue("device", investigation.deviceLabel)
                keyValue(
                    "note",
                    "The access level determines what was observable. Figures absent " +
                        "here may be available on the same device at a higher level.",
                )
            }

            data.summary?.let { summary ->
                key("summary")
                obj {
                    keyRanked("highestCpuProcess", summary.highestCpuProcess)
                    keyRanked("largestMemoryIncrease", summary.largestMemoryIncrease)
                    keyRanked("mostFrequentlyRestarted", summary.mostFrequentlyRestarted)
                    keyNullableNumber("batteryDrainPercent", summary.batteryDrainPercent)
                    keyNullableNumber(
                        "batteryTemperatureRiseDeciCelsius",
                        summary.batteryTemperatureRiseDeciCelsius,
                    )
                    keyValue("networkActivityDetected", summary.networkActivityDetected)
                    keyValue("wakeLockActivity", summary.wakeLockActivityDetected.name)
                    key("eventCounts")
                    obj {
                        EventGroup.entries.forEach { group ->
                            keyValue(group.name, summary.eventCounts[group] ?: 0)
                        }
                    }
                    // Correlational by construction: these strings are generated with
                    // "potential correlation" wording and are not causal claims
                    // (Section 16).
                    key("potentialCorrelations")
                    array { summary.correlations.forEach { string(it) } }
                    key("limitations")
                    array { summary.limitations.forEach { string(it) } }
                }
            }

            key("samples")
            array {
                data.snapshots.forEach { snapshot ->
                    obj {
                        keyValue("timestamp", Formatters.iso8601(snapshot.timestamp))
                        keyValue("epochMillis", snapshot.timestamp)
                        keyNullableNumber("cpuPercent", snapshot.cpuPercent)
                        keyValue("memoryUsedBytes", snapshot.memoryUsedBytes)
                        keyValue("memoryAvailableBytes", snapshot.memoryAvailableBytes)
                        keyValue("batteryLevelPercent", snapshot.batteryLevel)
                        keyNullableNumber(
                            "batteryTemperatureDeciCelsius",
                            snapshot.batteryTemperatureDeciCelsius,
                        )
                        keyValue("isCharging", snapshot.isCharging)
                        keyValue("isScreenOn", snapshot.isScreenOn)
                        keyNullableNumber("networkRxBytes", snapshot.networkRxBytes)
                        keyNullableNumber("networkTxBytes", snapshot.networkTxBytes)
                        keyValue("processCount", snapshot.processCount)
                        keyNullableNumber("processLensCpuPercent", snapshot.ownCpuPercent)
                        keyNullableNumber("processLensMemoryBytes", snapshot.ownMemoryBytes)
                        key("processes")
                        array {
                            data.entriesOf(snapshot).forEach { entry ->
                                obj {
                                    keyValue("processName", entry.processName)
                                    keyNullableString("packageName", entry.packageName)
                                    keyNullableNumber("pid", entry.pid)
                                    keyNullableNumber("cpuPercent", entry.cpuPercent)
                                    keyNullableNumber("memoryBytes", entry.memoryBytes)
                                    keyValue("importance", entry.importance.name)
                                }
                            }
                        }
                    }
                }
            }

            key("events")
            array {
                data.events.forEach { event ->
                    obj {
                        keyValue("timestamp", Formatters.iso8601(event.timestamp))
                        keyValue("epochMillis", event.timestamp)
                        keyValue("type", event.type.name)
                        keyValue("group", event.type.group.name)
                        keyValue("severity", event.severity.name)
                        keyValue("title", event.title)
                        keyValue("detail", event.detail)
                        keyNullableString("packageName", event.packageName)
                        keyNullableString("processName", event.processName)
                        keyNullableNumber("value", event.value)
                        keyNullableNumber("previousValue", event.previousValue)
                        keyValue("evidence", event.evidence)
                    }
                }
            }

            if (data.hasFilteredRows) {
                keyValue(
                    "omitted",
                    "Rows for system apps were excluded at the user's request " +
                        "(Settings -> Privacy and export). The recording itself still " +
                        "holds them.",
                )
            }
        }
        return out.finish()
    }

    // ----------------------------------------------------------------- CSV

    /**
     * One row per sample, with the per-process rows in a second table below.
     *
     * Two tables in one file rather than two files: an export a user has to keep
     * together as a pair is an export that arrives in a bug report as one half. The
     * blank line and second header row are what every spreadsheet needs to treat it
     * as a separate block, and the comment lines above each start with `#`.
     */
    private fun renderCsv(data: ExportData): String {
        val investigation = data.investigation
        val out = StringBuilder()

        out.append("# ProcessLens export, format version 1\n")
        out.append("# Recording: ").append(investigation.name).append('\n')
        out.append("# Started: ").append(Formatters.iso8601(investigation.startedAt)).append('\n')
        out.append("# Access level: ").append(investigation.accessLevelName)
            .append(", API ").append(investigation.apiLevel)
            .append(", ").append(investigation.deviceLabel).append('\n')
        out.append("# Sample interval: ")
            .append(Formatters.duration(investigation.sampleIntervalMillis)).append('\n')
        out.append("# ").append(ABSENCE_NOTE).append('\n')
        out.append("# ").append(PRIVACY_NOTE).append('\n')
        if (data.hasFilteredRows) {
            out.append("# System-app rows excluded at the user's request.\n")
        }
        out.append('\n')

        out.append("# Table 1: system samples\n")
        out.append(
            csvRow(
                "timestamp_iso8601", "epoch_millis", "cpu_percent", "memory_used_bytes",
                "memory_available_bytes", "battery_level_percent",
                "battery_temperature_deci_celsius", "charging", "screen_on",
                "network_rx_bytes", "network_tx_bytes", "process_count",
                "processlens_cpu_percent", "processlens_memory_bytes",
            ),
        )
        data.snapshots.forEach { snapshot ->
            out.append(
                csvRow(
                    Formatters.iso8601(snapshot.timestamp),
                    snapshot.timestamp.toString(),
                    numberOrBlank(snapshot.cpuPercent),
                    snapshot.memoryUsedBytes.toString(),
                    snapshot.memoryAvailableBytes.toString(),
                    snapshot.batteryLevel.toString(),
                    numberOrBlank(snapshot.batteryTemperatureDeciCelsius),
                    snapshot.isCharging.toString(),
                    snapshot.isScreenOn.toString(),
                    numberOrBlank(snapshot.networkRxBytes),
                    numberOrBlank(snapshot.networkTxBytes),
                    snapshot.processCount.toString(),
                    numberOrBlank(snapshot.ownCpuPercent),
                    numberOrBlank(snapshot.ownMemoryBytes),
                ),
            )
        }

        out.append('\n')
        out.append("# Table 2: per-process rows\n")
        out.append(
            csvRow(
                "timestamp_iso8601", "epoch_millis", "process_name", "package_name",
                "pid", "cpu_percent", "memory_bytes", "importance",
            ),
        )
        var processRows = 0
        data.snapshots.forEach { snapshot ->
            data.entriesOf(snapshot).forEach { entry ->
                processRows++
                out.append(
                    csvRow(
                        Formatters.iso8601(snapshot.timestamp),
                        snapshot.timestamp.toString(),
                        entry.processName,
                        entry.packageName.orEmpty(),
                        numberOrBlank(entry.pid),
                        numberOrBlank(entry.cpuPercent),
                        numberOrBlank(entry.memoryBytes),
                        entry.importance.name,
                    ),
                )
            }
        }
        if (processRows == 0) {
            out.append(
                "# No per-process rows. On API 28 and above an app without elevated " +
                    "access cannot enumerate other processes, so this table is empty " +
                    "rather than incomplete.\n",
            )
        }

        out.append('\n')
        out.append("# Table 3: events\n")
        out.append(
            csvRow(
                "timestamp_iso8601", "epoch_millis", "type", "group", "severity",
                "title", "detail", "package_name", "process_name", "value",
                "previous_value", "evidence",
            ),
        )
        data.events.forEach { event ->
            out.append(
                csvRow(
                    Formatters.iso8601(event.timestamp),
                    event.timestamp.toString(),
                    event.type.name,
                    event.type.group.name,
                    event.severity.name,
                    event.title,
                    event.detail,
                    event.packageName.orEmpty(),
                    event.processName.orEmpty(),
                    numberOrBlank(event.value),
                    numberOrBlank(event.previousValue),
                    event.evidence,
                ),
            )
        }

        return out.toString()
    }

    private fun csvRow(vararg cells: String): String =
        cells.joinToString(separator = ",", postfix = "\n") { csvCell(it) }

    /**
     * RFC 4180 quoting, plus one hardening step.
     *
     * A cell beginning with `=`, `+`, `-` or `@` is prefixed with an apostrophe.
     * Process and package names come from the device, and a spreadsheet that opens
     * this file would otherwise treat such a cell as a formula. That is a real
     * injection route in exported CSV, and the apostrophe is the standard defence.
     */
    private fun csvCell(value: String): String {
        val guarded = if (value.isNotEmpty() && value[0] in "=+-@") "'" + value else value
        val needsQuotes = guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) {
            "\"" + guarded.replace("\"", "\"\"").replace("\r\n", " ").replace('\n', ' ')
                .replace('\r', ' ') + "\""
        } else {
            guarded
        }
    }

    private fun numberOrBlank(value: Number?): String = value?.toString() ?: ""

    // ---------------------------------------------------------------- Text

    /**
     * The format meant to be read by a person and pasted into an issue.
     *
     * Ordered as a report: what this is, what it was allowed to see, what it found,
     * then the raw material. The limitations sit near the top rather than in an
     * appendix, because someone skimming the first screen should not walk away with
     * a conclusion the data cannot support.
     */
    private fun renderText(data: ExportData): String {
        val investigation = data.investigation
        val out = StringBuilder()

        fun heading(text: String) {
            out.append('\n').append(text).append('\n')
            out.append("-".repeat(text.length)).append('\n')
        }

        out.append("ProcessLens recording export\n")
        out.append("============================\n\n")
        out.append("Recording:      ").append(investigation.name).append('\n')
        out.append("Started:        ").append(Formatters.dateTime(investigation.startedAt))
            .append('\n')
        out.append("Duration:       ")
            .append(Formatters.durationCoarse(investigation.durationMillis)).append('\n')
        out.append("Ended as:       ").append(investigation.state.label).append('\n')
        out.append("Samples:        ").append(investigation.snapshotCount)
            .append(" at ").append(Formatters.duration(investigation.sampleIntervalMillis))
            .append(" intervals\n")
        investigation.targetPackage?.let {
            out.append("Scoped to:      ").append(it).append('\n')
        }
        out.append("Access level:   ").append(investigation.accessLevelName).append('\n')
        out.append("Device:         ").append(investigation.deviceLabel)
            .append(", Android API ").append(investigation.apiLevel).append('\n')
        out.append("Exported:       ").append(Formatters.dateTime(data.exportedAt))
            .append(" by ProcessLens ").append(data.appVersionName).append('\n')
        investigation.notes?.takeIf { it.isNotBlank() }?.let {
            out.append("Notes:          ").append(it).append('\n')
        }

        out.append('\n').append("How to read this file\n")
        out.append("  ").append(ABSENCE_NOTE).append('\n')
        out.append("  ").append(PRIVACY_NOTE).append('\n')

        val summary = data.summary
        if (summary != null) {
            heading("What this recording establishes")
            var stated = false
            summary.highestCpuProcess?.let {
                stated = true
                out.append("  Highest CPU:            ").append(it.label).append('\n')
            }
            summary.largestMemoryIncrease?.let {
                stated = true
                out.append("  Largest memory growth:  ").append(it.label).append('\n')
            }
            summary.mostFrequentlyRestarted?.let {
                stated = true
                out.append("  Most restarts:          ").append(it.label).append('\n')
            }
            summary.batteryDrainPercent?.let {
                stated = true
                out.append("  Battery level change:   ").append(it).append("%\n")
            }
            summary.batteryTemperatureRiseDeciCelsius?.let {
                stated = true
                out.append("  Battery temp rise:      ")
                    .append(Formatters.temperature(it)).append('\n')
            }
            out.append("  Network activity:       ")
                .append(if (summary.networkActivityDetected) "Detected" else "Not detected")
                .append('\n')
            out.append("  WakeLock activity:      ")
                .append(summary.wakeLockActivityDetected.label).append('\n')
            if (!stated) {
                out.append("  Nothing rose above the configured thresholds.\n")
            }

            if (summary.correlations.isNotEmpty()) {
                heading("Potential correlations")
                out.append(
                    "  These are co-occurrences in time. They are not evidence of " +
                        "cause.\n\n",
                )
                summary.correlations.forEach { out.append("  - ").append(it).append('\n') }
            }

            if (summary.limitations.isNotEmpty()) {
                heading("What could not be observed")
                summary.limitations.forEach { out.append("  - ").append(it).append('\n') }
            }
        } else {
            heading("What this recording establishes")
            out.append("  No summary could be built for this recording.\n")
        }

        heading("Events (" + data.events.size + ")")
        if (data.events.isEmpty()) {
            out.append("  None were raised.\n")
        } else {
            data.events.forEach { event ->
                out.append("  ").append(Formatters.clockTime(event.timestamp))
                    .append("  [").append(event.severity.label).append("] ")
                    .append(event.title).append('\n')
                out.append("      ").append(event.detail).append('\n')
                val subject = event.packageName ?: event.processName
                subject?.let { out.append("      Subject:  ").append(it).append('\n') }
                out.append("      Evidence: ").append(event.evidence).append('\n')
            }
        }

        heading("Samples (" + data.snapshots.size + ")")
        if (data.snapshots.isEmpty()) {
            out.append("  None were stored.\n")
        } else {
            out.append("  time      cpu       memory used   battery  net rx/tx\n")
            data.snapshots.forEach { snapshot ->
                out.append("  ")
                    .append(Formatters.clockTimeShort(snapshot.timestamp).padEnd(9))
                    .append(
                        (snapshot.cpuPercent?.let { Formatters.percentValue(it) } ?: NOT_MEASURED)
                            .padEnd(10),
                    )
                    .append(Formatters.bytes(snapshot.memoryUsedBytes).padEnd(14))
                    .append((snapshot.batteryLevel.toString() + "%").padEnd(9))
                    .append(
                        if (snapshot.networkRxBytes == null || snapshot.networkTxBytes == null) {
                            NOT_MEASURED
                        } else {
                            Formatters.bytes(snapshot.networkRxBytes) + " / " +
                                Formatters.bytes(snapshot.networkTxBytes)
                        },
                    )
                    .append('\n')
            }
        }

        val processTotal = data.snapshots.sumOf { data.entriesOf(it).size }
        heading("Per-process rows (" + processTotal + ")")
        if (processTotal == 0) {
            out.append(
                "  None. On API 28 and above an app without elevated access cannot " +
                    "enumerate other processes, so this section is empty rather than " +
                    "incomplete.\n",
            )
        } else {
            data.snapshots.forEach { snapshot ->
                val entries = data.entriesOf(snapshot)
                if (entries.isEmpty()) return@forEach
                out.append("  ").append(Formatters.clockTime(snapshot.timestamp)).append('\n')
                entries.forEach { entry ->
                    out.append("      ")
                        .append(entry.processName)
                        .append(entry.pid?.let { " (pid " + it + ")" } ?: " (pid not readable)")
                        .append("  cpu ")
                        .append(entry.cpuPercent?.let { Formatters.percentValue(it) } ?: NOT_MEASURED)
                        .append("  mem ")
                        .append(entry.memoryBytes?.let { Formatters.bytes(it) } ?: NOT_MEASURED)
                        .append('\n')
                }
            }
        }

        if (data.hasFilteredRows) {
            out.append(
                "\nSystem-app rows were excluded from this export at your request " +
                    "(Settings -> Privacy and export). The recording still holds them.\n",
            )
        }

        return out.toString()
    }
}
/**
 * A minimal JSON writer.
 *
 * Hand-rolled rather than pulled in as a dependency, for two reasons. ProcessLens
 * ships no serialisation library, and adding one to emit a few hundred lines would
 * grow the APK for no gain. More importantly, a reflective serialiser makes it easy
 * to lose the null/zero distinction through a default value or a primitive
 * `Float` — here, the only way to write a number is to have one.
 */
private class JsonWriter {
    private val out = StringBuilder()
    private var depth = 0

    /**
     * True when a sibling has already been written at the current position, so the
     * next one needs a comma before its newline.
     *
     * Containers reset it on entry and set it on exit: an inner object must start its
     * own first key without a comma, and must hand a "sibling written" state back to
     * its parent when it closes.
     */
    private var needsComma = false

    fun finish(): String = out.append('\n').toString()

    private fun indent() {
        out.append("  ".repeat(depth))
    }

    /** Opens the slot for the next sibling: `,\n` after one, plain `\n` for the first. */
    private fun separate() {
        out.append(if (needsComma) ",\n" else "\n")
        indent()
        needsComma = true
    }

    fun obj(body: JsonWriter.() -> Unit) = container('{', '}', body)

    fun array(body: JsonWriter.() -> Unit) = container('[', ']', body)

    private fun container(open: Char, close: Char, body: JsonWriter.() -> Unit) {
        out.append(open)
        depth++
        needsComma = false
        val start = out.length
        body()
        depth--
        // An empty container closes on the same line: `[]` reads as "none", whereas a
        // bare newline between the brackets is just noise.
        if (out.length != start) {
            out.append('\n')
            indent()
        }
        out.append(close)
        needsComma = true
    }

    /** Writes a key and leaves the value position open for [obj]/[array]. */
    fun key(name: String) {
        separate()
        out.append(quote(name)).append(": ")
    }

    fun keyValue(name: String, value: String) {
        key(name)
        out.append(quote(value))
    }

    fun keyValue(name: String, value: Long) {
        key(name)
        out.append(value.toString())
    }

    fun keyValue(name: String, value: Int) {
        key(name)
        out.append(value.toString())
    }

    fun keyValue(name: String, value: Boolean) {
        key(name)
        out.append(value.toString())
    }

    /**
     * The honest-null overload.
     *
     * Every nullable measurement in the app goes through here, and a null writes the
     * JSON literal `null` rather than a substituted figure. There is deliberately no
     * "default" parameter to reach for (Section 42).
     */
    fun keyNullableNumber(name: String, value: Number?) {
        key(name)
        out.append(value?.let { formatNumber(it) } ?: "null")
    }

    fun keyNullableString(name: String, value: String?) {
        key(name)
        out.append(value?.let { quote(it) } ?: "null")
    }

    fun keyRanked(name: String, ranked: RankedProcess?) {
        if (ranked == null) {
            keyNullableString(name, null)
            return
        }
        key(name)
        obj {
            keyValue("processName", ranked.processName)
            keyNullableString("packageName", ranked.packageName)
            keyValue("label", ranked.label)
            keyNullableNumber("value", ranked.value)
            keyValue("unit", ranked.unit)
        }
    }

    fun string(value: String) {
        separate()
        out.append(quote(value))
    }

    /**
     * Keeps whole numbers integral so a reader does not see `4.0` bytes, and rounds
     * to two decimals.
     *
     * A non-finite float becomes `null` rather than the token `NaN`, which is not
     * legal JSON. It is also the honest answer: NaN here would mean the arithmetic
     * had no valid input.
     */
    private fun formatNumber(value: Number): String = when (value) {
        is Float -> if (value.isFinite()) trimFloat(value.toDouble()) else "null"
        is Double -> if (value.isFinite()) trimFloat(value) else "null"
        else -> value.toString()
    }

    private fun trimFloat(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (char in value) {
            when {
                char == '"' -> builder.append("\\\"")
                char == '\\' -> builder.append("\\\\")
                char == '\n' -> builder.append("\\n")
                char == '\r' -> builder.append("\\r")
                char == '\t' -> builder.append("\\t")
                char == '\b' -> builder.append("\\b")
                char == '\u000C' -> builder.append("\\f")
                // Device-sourced strings can carry stray control bytes; escaping them
                // keeps the file parseable rather than silently truncated.
                char < ' ' || char == '\u2028' || char == '\u2029' ->
                    builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> builder.append(char)
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
