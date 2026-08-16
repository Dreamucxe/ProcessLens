package com.processlens.domain.usecase

import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.testing.Fixtures
import com.processlens.testing.assertContains
import com.processlens.testing.assertDoesNotContain
import com.processlens.testing.assertEndsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the export renderer (Sections 26, 42).
 *
 * The assertions here are deliberately about *absence*. An export is the one artefact
 * that leaves the app and outlives every explanatory label on screen, so the tests
 * that matter most are the ones proving a missing measurement stays missing: JSON
 * `null`, an empty CSV cell, the words "not measured" in text — and never `0`.
 */
class ExportRendererTest {

    private fun data(
        snapshots: List<ProcessSnapshot> = listOf(Fixtures.snapshot()),
        events: List<InvestigationEvent> = listOf(Fixtures.event()),
        includeSystemApps: Boolean = true,
        systemPackages: Set<String> = emptySet(),
        investigation: Investigation = Fixtures.investigation(),
    ) = ExportData(
        investigation = investigation,
        snapshots = snapshots,
        events = events,
        summary = null,
        includeSystemApps = includeSystemApps,
        systemPackages = systemPackages,
        appVersionName = "1.0-test",
        exportedAt = Fixtures.T0 + 120_000L,
    )

    /**
     * The rows of one `#`-delimited CSV block, header row first.
     *
     * A block runs until a blank line or the next `#` comment, which is exactly how a
     * spreadsheet reads it, so parsing it the same way here keeps the test honest
     * about what a user would actually see.
     */
    private fun table(csv: String, heading: String): List<String> {
        assertContains(csv, "# $heading")
        return csv.substringAfter("# $heading\n")
            .lineSequence()
            .takeWhile { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    private fun samplesTable(csv: String) = table(csv, "Table 1: system samples")

    private fun processTable(csv: String) = table(csv, "Table 2: per-process rows")

    private fun eventsTable(csv: String) = table(csv, "Table 3: events")

    // ------------------------------------------------------------ absent values

    @Test
    fun `json writes null for an unmeasured cpu sample rather than zero`() {
        val json = ExportRenderer.render(
            data(snapshots = listOf(Fixtures.snapshot(cpuPercent = null))),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"cpuPercent\": null")
        assertDoesNotContain(json, "\"cpuPercent\": 0")
    }

    @Test
    fun `json writes null for both network counters when neither was readable`() {
        val json = ExportRenderer.render(
            data(
                snapshots = listOf(
                    Fixtures.snapshot(networkRxBytes = null, networkTxBytes = null),
                ),
            ),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"networkRxBytes\": null")
        assertContains(json, "\"networkTxBytes\": null")
    }

    @Test
    fun `csv leaves an unmeasured cell empty rather than filling it`() {
        val csv = ExportRenderer.render(
            data(snapshots = listOf(Fixtures.snapshot(cpuPercent = null))),
            ExportFormat.CSV,
        ).content

        val rows = samplesTable(csv)
        val cpuColumn = rows.first().split(",").indexOf("cpu_percent")
        assertTrue("cpu_percent column not found in ${rows.first()}", cpuColumn >= 0)

        // An empty field between two commas is the only honest CSV representation of
        // "not measured": a spreadsheet skips it when averaging a column, where a 0
        // would silently drag the mean down and look like a real reading.
        assertEquals("", rows[1].split(",")[cpuColumn])
    }

    @Test
    fun `text names an unmeasured figure instead of printing a number`() {
        val text = ExportRenderer.render(
            data(snapshots = listOf(Fixtures.snapshot(cpuPercent = null))),
            ExportFormat.TEXT,
        ).content

        assertContains(text, ExportRenderer.NOT_MEASURED)
    }

    @Test
    fun `text reports network as unmeasured when only one direction was readable`() {
        // Half a pair is not a measurement of throughput. Printing the readable half
        // beside a zero would imply the other direction was idle.
        val text = ExportRenderer.render(
            data(
                snapshots = listOf(
                    Fixtures.snapshot(networkRxBytes = 2_048L, networkTxBytes = null),
                ),
            ),
            ExportFormat.TEXT,
        ).content

        assertContains(text, ExportRenderer.NOT_MEASURED)
    }

    @Test
    fun `a zero that was genuinely measured is still written as zero`() {
        val json = ExportRenderer.render(
            data(snapshots = listOf(Fixtures.snapshot(cpuPercent = 0f))),
            ExportFormat.JSON,
        ).content

        // The converse of the rule above, and just as important: a real 0% is a
        // measurement, and turning it into an absence would be its own fabrication.
        assertContains(json, "\"cpuPercent\": 0")
        assertDoesNotContain(json, "\"cpuPercent\": null")
    }

    @Test
    fun `every format carries the absence legend`() {
        ExportFormat.entries.forEach { format ->
            assertContains(
                ExportRenderer.render(data(), format).content,
                ExportRenderer.ABSENCE_NOTE,
            )
        }
    }

    // -------------------------------------------------------------- provenance

    @Test
    fun `every format records the access level the recording ran under`() {
        val investigation = Fixtures.investigation(accessLevelName = "Shizuku")
        ExportFormat.entries.forEach { format ->
            assertContains(
                ExportRenderer.render(data(investigation = investigation), format).content,
                "Shizuku",
            )
        }
    }

    @Test
    fun `every format records the api level in a labelled field`() {
        val investigation = Fixtures.investigation(apiLevel = 26)
        val json = ExportRenderer
            .render(data(investigation = investigation), ExportFormat.JSON).content
        val csv = ExportRenderer
            .render(data(investigation = investigation), ExportFormat.CSV).content
        val text = ExportRenderer
            .render(data(investigation = investigation), ExportFormat.TEXT).content

        // Labelled, not merely present: "26" could appear in a timestamp by accident.
        assertContains(json, "\"apiLevel\": 26")
        assertContains(csv, "API 26")
        assertContains(text, "Android API 26")
    }

    @Test
    fun `the sampling interval is stated so a reader can judge resolution`() {
        val json = ExportRenderer.render(
            data(investigation = Fixtures.investigation(sampleIntervalMillis = 5_000L)),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"sampleIntervalMillis\": 5000")
    }

    // --------------------------------------------------------- system app filter

    @Test
    fun `system app rows are dropped when the user asked for user apps only`() {
        val snapshot = Fixtures.snapshot(
            entries = listOf(
                Fixtures.entry("com.example.user"),
                Fixtures.entry("com.android.systemui"),
            ),
        )
        val export = data(
            snapshots = listOf(snapshot),
            includeSystemApps = false,
            systemPackages = setOf("com.android.systemui"),
        )

        val json = ExportRenderer.render(export, ExportFormat.JSON).content

        assertContains(json, "com.example.user")
        assertDoesNotContain(json, "com.android.systemui")
        assertTrue(export.hasFilteredRows)
    }

    @Test
    fun `a row with no package name survives the system app filter`() {
        // A kernel thread, or a process whose package could not be resolved. It is not
        // a system *app*, and dropping it would remove the rows hardest to observe.
        val snapshot = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("kworker/0:1", packageName = null)),
        )
        val export = data(
            snapshots = listOf(snapshot),
            includeSystemApps = false,
            systemPackages = setOf("com.android.systemui"),
        )

        assertEquals(1, export.entriesOf(snapshot).size)
        assertFalse(export.hasFilteredRows)
    }

    @Test
    fun `an export that filtered nothing does not claim it did`() {
        val export = data(includeSystemApps = false)
        assertFalse(export.hasFilteredRows)
        assertDoesNotContain(
            ExportRenderer.render(export, ExportFormat.JSON).content,
            "\"omitted\"",
        )
    }

    @Test
    fun `an export that filtered rows says so in every format`() {
        val export = data(
            snapshots = listOf(
                Fixtures.snapshot(entries = listOf(Fixtures.entry("com.android.systemui"))),
            ),
            includeSystemApps = false,
            systemPackages = setOf("com.android.systemui"),
        )

        assertContains(ExportRenderer.render(export, ExportFormat.JSON).content, "\"omitted\"")
        assertContains(
            ExportRenderer.render(export, ExportFormat.CSV).content,
            "System-app rows excluded",
        )
        assertContains(
            ExportRenderer.render(export, ExportFormat.TEXT).content,
            "System-app rows were excluded",
        )
    }

    // ------------------------------------------------------------- empty tables

    @Test
    fun `an empty process table is explained rather than left blank`() {
        // The API 28 case. An empty table with no note reads as "nothing was running",
        // which is the single most misleading thing this export could imply.
        val csv = ExportRenderer.render(data(), ExportFormat.CSV).content
        assertContains(csv, "cannot enumerate other processes")

        val text = ExportRenderer.render(data(), ExportFormat.TEXT).content
        assertContains(text, "cannot")
        assertContains(text, "empty rather than")
    }

    @Test
    fun `a recording with no samples still produces a well formed document`() {
        val json = ExportRenderer.render(
            data(snapshots = emptyList(), events = emptyList()),
            ExportFormat.JSON,
        ).content

        // An empty container closes on the same line, so "none" is unambiguous.
        assertContains(json, "\"samples\": []")
        assertContains(json, "\"events\": []")
    }

    @Test
    fun `a recording with no events says none were raised rather than nothing`() {
        val text = ExportRenderer.render(data(events = emptyList()), ExportFormat.TEXT).content
        assertContains(text, "None were raised.")
    }

    // ----------------------------------------------------------------- escaping

    @Test
    fun `a quote in a recording name does not break the json`() {
        val json = ExportRenderer.render(
            data(investigation = Fixtures.investigation(name = "Bug \"42\"")),
            ExportFormat.JSON,
        ).content

        assertContains(json, "Bug \\\"42\\\"")
    }

    @Test
    fun `a backslash is escaped`() {
        val json = ExportRenderer.render(
            data(events = listOf(Fixtures.event(detail = "path\\to\\thing"))),
            ExportFormat.JSON,
        ).content

        assertContains(json, "path\\\\to\\\\thing")
    }

    @Test
    fun `a newline in an event detail is escaped rather than emitted raw`() {
        val json = ExportRenderer.render(
            data(events = listOf(Fixtures.event(detail = "line one\nline two"))),
            ExportFormat.JSON,
        ).content

        assertContains(json, "line one\\nline two")
    }

    @Test
    fun `a stray control byte becomes a unicode escape`() {
        // Process names come off the device and are not guaranteed to be clean text.
        val json = ExportRenderer.render(
            data(
                snapshots = listOf(
                    Fixtures.snapshot(entries = listOf(Fixtures.entry("odd\u0001name"))),
                ),
            ),
            ExportFormat.JSON,
        ).content

        assertContains(json, "odd\\u0001name")
    }

    @Test
    fun `a comma in a csv cell is quoted`() {
        val csv = ExportRenderer.render(
            data(events = listOf(Fixtures.event(title = "Rose, then fell"))),
            ExportFormat.CSV,
        ).content

        assertContains(csv, "\"Rose, then fell\"")
    }

    @Test
    fun `a quote in a csv cell is doubled`() {
        val csv = ExportRenderer.render(
            data(events = listOf(Fixtures.event(title = "the \"main\" thread, idle"))),
            ExportFormat.CSV,
        ).content

        assertContains(csv, "\"the \"\"main\"\" thread, idle\"")
    }

    @Test
    fun `a csv cell that looks like a formula is neutralised`() {
        // A cell starting with = would be executed as a formula by a spreadsheet.
        // Process names come from the device, so this is a real injection route.
        val csv = ExportRenderer.render(
            data(
                snapshots = listOf(
                    Fixtures.snapshot(entries = listOf(Fixtures.entry("=cmd|'/c calc'!A1"))),
                ),
            ),
            ExportFormat.CSV,
        ).content

        assertDoesNotContain(csv, ",=cmd")
        assertContains(csv, "'=cmd")
    }

    @Test
    fun `each formula lead in character is neutralised`() {
        listOf("=x", "+x", "-x", "@x").forEach { name ->
            val csv = ExportRenderer.render(
                data(
                    snapshots = listOf(
                        Fixtures.snapshot(entries = listOf(Fixtures.entry(name))),
                    ),
                ),
                ExportFormat.CSV,
            ).content
            assertContains(csv, "'$name")
        }
    }

    @Test
    fun `a newline inside a csv cell is flattened so the row count stays right`() {
        val csv = ExportRenderer.render(
            data(events = listOf(Fixtures.event(detail = "first\nsecond"))),
            ExportFormat.CSV,
        ).content

        // Header plus exactly one data row: the embedded newline did not silently
        // become a second record, which would inflate any count taken from this file.
        assertEquals(2, eventsTable(csv).size)
    }

    // ------------------------------------------------------------- table shapes

    @Test
    fun `every csv row has the same number of cells as its header`() {
        val csv = ExportRenderer.render(
            data(
                snapshots = listOf(
                    Fixtures.snapshot(
                        entries = listOf(
                            Fixtures.entry("a"),
                            Fixtures.entry("b, with comma", cpuPercent = null),
                        ),
                    ),
                    Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, cpuPercent = null),
                ),
                events = listOf(Fixtures.event(), Fixtures.event(detail = "has, comma")),
            ),
            ExportFormat.CSV,
        ).content

        listOf(samplesTable(csv), processTable(csv), eventsTable(csv)).forEach { rows ->
            val expected = countCells(rows.first())
            rows.forEach { row ->
                assertEquals("wrong cell count in: $row", expected, countCells(row))
            }
        }
    }

    /** Counts RFC 4180 fields, so a quoted comma does not count as a separator. */
    private fun countCells(row: String): Int {
        var cells = 1
        var inQuotes = false
        for (char in row) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> cells++
            }
        }
        return cells
    }

    // ---------------------------------------------------------------- filenames

    @Test
    fun `a file name is slugged so it cannot contain a path separator`() {
        val name = ExportRenderer.fileName(
            data(investigation = Fixtures.investigation(name = "../../etc/passwd")),
            ExportFormat.JSON,
        )

        assertDoesNotContain(name, "/")
        assertDoesNotContain(name, "..")
        assertEndsWith(name, ".json")
    }

    @Test
    fun `a recording named only in punctuation still gets a usable file name`() {
        val name = ExportRenderer.fileName(
            data(investigation = Fixtures.investigation(name = "!!!")),
            ExportFormat.CSV,
        )

        assertContains(name, "recording")
        assertEndsWith(name, ".csv")
    }

    @Test
    fun `a very long recording name is truncated`() {
        val name = ExportRenderer.fileName(
            data(investigation = Fixtures.investigation(name = "x".repeat(300))),
            ExportFormat.TEXT,
        )

        assertTrue("file name too long: ${name.length}", name.length < 100)
        assertEndsWith(name, ".txt")
    }

    @Test
    fun `each format gets its own extension and mime type`() {
        val json = ExportRenderer.render(data(), ExportFormat.JSON)
        val csv = ExportRenderer.render(data(), ExportFormat.CSV)
        val text = ExportRenderer.render(data(), ExportFormat.TEXT)

        assertEndsWith(json.fileName, ".json")
        assertEquals("application/json", json.mimeType)
        assertEndsWith(csv.fileName, ".csv")
        assertEquals("text/csv", csv.mimeType)
        assertEndsWith(text.fileName, ".txt")
        assertEquals("text/plain", text.mimeType)
    }

    // ------------------------------------------------------------ json structure

    @Test
    fun `json output is balanced and has no trailing commas`() {
        val json = ExportRenderer.render(
            data(
                snapshots = listOf(
                    Fixtures.snapshot(entries = listOf(Fixtures.entry("a"), Fixtures.entry("b"))),
                    Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L),
                ),
                events = listOf(Fixtures.event(), Fixtures.event(timestamp = Fixtures.T0 + 1_000L)),
            ),
            ExportFormat.JSON,
        ).content

        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
        // A trailing comma before a close is the classic hand-rolled serialiser bug,
        // and it makes the entire file unparseable rather than partly wrong.
        assertFalse(
            "trailing comma before a closing brace or bracket",
            Regex(",\\s*[}\\]]").containsMatchIn(json),
        )
    }

    @Test
    fun `json quoting survives a scan for unescaped structural characters`() {
        val json = ExportRenderer.render(
            data(
                investigation = Fixtures.investigation(name = "a \"quoted\", {braced} name"),
                events = listOf(Fixtures.event(detail = "detail with } and ] inside")),
            ),
            ExportFormat.JSON,
        ).content

        // Walking the document with a tiny scanner proves the braces that matter are
        // the structural ones, which is the property the hand-rolled writer must hold.
        assertEquals(0, unbalancedDepth(json))
    }

    /**
     * Returns the final nesting depth, ignoring anything inside a string literal.
     *
     * Zero means every container opened was closed and no brace inside a quoted value
     * was mistaken for structure.
     */
    private fun unbalancedDepth(json: String): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in json) {
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' || char == '[' -> depth++
                char == '}' || char == ']' -> depth--
            }
        }
        return depth
    }

    // ----------------------------------------------------------------- rounding

    @Test
    fun `a whole number is written without a decimal point`() {
        val json = ExportRenderer.render(
            data(snapshots = listOf(Fixtures.snapshot(cpuPercent = 25f))),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"cpuPercent\": 25")
        assertDoesNotContain(json, "\"cpuPercent\": 25.0")
    }

    @Test
    fun `a fractional figure keeps two decimals`() {
        val json = ExportRenderer.render(
            data(snapshots = listOf(Fixtures.snapshot(cpuPercent = 12.3456f))),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"cpuPercent\": 12.35")
    }

    @Test
    fun `a non finite float becomes null rather than the token NaN`() {
        // NaN is not legal JSON, and it is also the honest answer: a NaN here would
        // mean the arithmetic had no valid input, which is an absent measurement.
        val json = ExportRenderer.render(
            data(snapshots = listOf(Fixtures.snapshot(cpuPercent = Float.NaN))),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"cpuPercent\": null")
        assertDoesNotContain(json, "NaN")
    }

    @Test
    fun `an infinite float becomes null`() {
        val json = ExportRenderer.render(
            data(snapshots = listOf(Fixtures.snapshot(cpuPercent = Float.POSITIVE_INFINITY))),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"cpuPercent\": null")
        assertDoesNotContain(json, "Infinity")
    }

    // -------------------------------------------------------------------- state

    @Test
    fun `an interrupted recording reports its state`() {
        val json = ExportRenderer.render(
            data(investigation = Fixtures.investigation(state = InvestigationState.INTERRUPTED)),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"state\": \"INTERRUPTED\"")
    }

    @Test
    fun `a running recording with no end time writes null rather than a guess`() {
        val json = ExportRenderer.render(
            data(
                investigation = Fixtures.investigation(
                    state = InvestigationState.RECORDING,
                    endedAt = null,
                ),
            ),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"endedAt\": null")
    }

    @Test
    fun `absent optional text is null rather than an empty string`() {
        val json = ExportRenderer.render(
            data(investigation = Fixtures.investigation(notes = null, targetPackage = null)),
            ExportFormat.JSON,
        ).content

        assertContains(json, "\"notes\": null")
        assertContains(json, "\"targetPackage\": null")
        assertDoesNotContain(json, "\"notes\": \"\"")
    }

    @Test
    fun `the privacy statement names what the file contains`() {
        ExportFormat.entries.forEach { format ->
            val content = ExportRenderer.render(data(), format).content
            assertContains(content, "no file contents, credentials, tokens or personal data")
        }
    }
}
