package com.processlens.feature.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.DetailRow
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.EventRow
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.LabelledChart
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.rememberHaptics
import com.processlens.domain.model.EventGroup
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.InvestigationSummary
import com.processlens.domain.model.Observed3State
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.RankedProcess
import com.processlens.domain.repository.SeriesPoint
import com.processlens.domain.usecase.ExportRenderer

/**
 * Timeline and replay (Sections 13, 24, 25).
 *
 * Two things are on this screen and they are kept visibly apart: what was *measured*
 * (the chart and the snapshot rows) and what was *derived* (the events and the summary).
 * Derived findings are worded as correlations, because sampling at a two-second cadence
 * establishes coincidence and nothing stronger — Section 16 is explicit that this app
 * must not claim causation it cannot demonstrate.
 */
@Composable
fun TimelineScreen(
    onBack: () -> Unit,
    onOpenProcess: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val investigation = state.investigation
    val context = LocalContext.current
    val haptics = rememberHaptics()

    Column(modifier) {
        ScreenHeader(
            title = investigation?.name ?: "Recording",
            subtitle = investigation?.let {
                Formatters.dateTime(it.startedAt) + "  ·  " +
                    Formatters.durationCoarse(it.durationMillis)
            },
            onBack = onBack,
        )

        if (state.isLoading && investigation == null) {
            LoadingBlock(label = "Reading the recording")
            return@Column
        }

        if (investigation == null) {
            ScreenBody {
                EmptyState(
                    message = "This recording no longer exists on this device.",
                    action = { ActionText("Back", onClick = onBack) },
                )
            }
            return@Column
        }

        ScreenBody {
            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            if (investigation.state == InvestigationState.INTERRUPTED) {
                NoticeBanner(
                    text = "This recording was interrupted before its planned end. " +
                        "The samples it did take are complete and accurate; there are " +
                        "simply fewer of them.",
                    severity = EventSeverity.INFO,
                )
            }

            RecordingFactsCard(investigation = investigation, series = state.series)

            if (state.series.isEmpty()) {
                GlassCard {
                    SectionHeader(title = "No samples")
                    Text(
                        "This recording stopped before its first sample was written.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ChartCard(
                    state = state,
                    onMetric = viewModel::setMetric,
                    onScrub = viewModel::scrubTo,
                )
                state.scrubPoint?.let { point ->
                    ScrubCard(
                        state = state,
                        point = point,
                        onOpenProcess = onOpenProcess,
                        onClose = { viewModel.scrubTo(null) },
                    )
                }
            }

            state.summary?.let { SummaryCard(summary = it, onOpenApp = onOpenApp) }

            EventsCard(
                state = state,
                onGroup = viewModel::setGroup,
                onSeverity = viewModel::setMinSeverity,
                onSeek = viewModel::scrubToTimestamp,
            )

            ExportCard(
                exportState = exportState,
                onExport = { format ->
                    haptics.tick()
                    viewModel.export(format)
                },
                onShare = {
                    val intent = viewModel.shareIntent()
                    if (intent == null) {
                        haptics.reject()
                    } else {
                        haptics.confirm()
                        context.startActivity(intent)
                    }
                },
                onDismiss = viewModel::dismissExport,
            )
        }
    }
}

/**
 * Export (Section 26).
 *
 * Placed at the foot of the recording rather than in the header, because it is the
 * last thing done with a recording, not the first. All three formats are offered as
 * peers: a user who wants CSV for a spreadsheet should not have to visit Settings to
 * get it, and picking one here does not change the saved default.
 *
 * The result names the file and where it went. When local-only mode is on there is no
 * share button at all — the file is written and the sheet says why sharing is absent,
 * rather than offering a button that would refuse.
 */
@Composable
private fun ExportCard(
    exportState: TimelineViewModel.ExportState,
    onExport: (ExportFormat) -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Export this recording",
            subtitle = "Every stored sample and event, not just what is charted above",
        )

        if (exportState.isExporting) {
            LoadingBlock(label = "Writing the export")
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExportFormat.entries.forEach { format ->
                    Chip(
                        text = format.label,
                        icon = Icons.Outlined.FileDownload,
                        onClick = { onExport(format) },
                    )
                }
            }
        }

        when (val outcome = exportState.outcome) {
            null -> Unit

            is TimelineViewModel.Outcome.Written -> {
                Spacer(Modifier.height(10.dp))
                DetailRow(label = "File", value = outcome.fileName, monospace = true)
                DetailRow(label = "Written to", value = outcome.displayPath)
                DetailRow(
                    label = "Size",
                    value = Formatters.bytes(outcome.byteCount) + "  ·  " +
                        outcome.formatLabel,
                )
                DetailRow(
                    label = "Contains",
                    value = Formatters.count(outcome.sampleCount, "sample") + " and " +
                        Formatters.count(outcome.eventCount, "event"),
                )
                outcome.omissionNote?.let {
                    Spacer(Modifier.height(8.dp))
                    NoticeBanner(text = it, severity = EventSeverity.INFO)
                }
                Spacer(Modifier.height(10.dp))
                if (outcome.shareable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ActionText(
                            "Share",
                            onClick = onShare,
                            icon = Icons.Outlined.Share,
                        )
                        ActionText("Done", onClick = onDismiss)
                    }
                } else {
                    NoticeBanner(
                        text = "Local-only mode is on, so this file is not offered to " +
                            "other apps. It is on this device at the path above.",
                        severity = EventSeverity.INFO,
                    )
                    Spacer(Modifier.height(8.dp))
                    ActionText("Done", onClick = onDismiss)
                }
            }

            is TimelineViewModel.Outcome.Failed -> {
                Spacer(Modifier.height(10.dp))
                NoticeBanner(
                    text = outcome.message,
                    severity = EventSeverity.WARNING,
                    detail = outcome.technical,
                    action = { ActionText("Dismiss", onClick = onDismiss) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        ExpandableDetail(
            summary = "What the file contains",
            detail = "Every stored sample with its timestamp, the system figures at " +
                "that instant, whichever per-process rows were observable, and every " +
                "event derived from them — plus the access level and API level the " +
                "recording ran under, since the same figures mean different things at " +
                "different levels.\n\n" +
                ExportRenderer.ABSENCE_NOTE + "\n\n" +
                "It carries process names, package names, PIDs and resource " +
                "measurements. ProcessLens reads no file contents, credentials or " +
                "personal data, so none can appear in an export.",
        )
    }
}

// ------------------------------------------------------------------------- context

@Composable
private fun RecordingFactsCard(investigation: Investigation, series: List<SeriesPoint>) {
    GlassCard {
        SectionHeader(
            title = "The recording",
            subtitle = "Conditions under which these numbers were taken",
        )
        DetailRow(label = "State", value = investigation.state.label)
        DetailRow(
            label = "Started",
            value = Formatters.dateTime(investigation.startedAt),
        )
        investigation.endedAt?.let {
            DetailRow(label = "Ended", value = Formatters.dateTime(it))
        }
        DetailRow(
            label = "Sample interval",
            value = Formatters.duration(investigation.sampleIntervalMillis),
            monospace = true,
        )
        DetailRow(label = "Samples", value = series.size.toString(), monospace = true)
        DetailRow(
            label = "Scope",
            value = investigation.targetPackage ?: "Whole system",
            monospace = investigation.targetPackage != null,
        )
        DetailRow(label = "Access level", value = investigation.accessLevelName)
        DetailRow(
            label = "Device",
            value = investigation.deviceLabel + ", API " + investigation.apiLevel,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Access level and API level are recorded with the data because they change " +
                "what it can mean. A recording taken without per-process CPU is not the " +
                "same recording with zeroes in it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --------------------------------------------------------------------------- chart

@Composable
private fun ChartCard(
    state: TimelineViewModel.State,
    onMetric: (TimelineViewModel.Metric) -> Unit,
    onScrub: (Int?) -> Unit,
) {
    val chart = state.chart
    val present = chart.count { it != null }

    GlassCard {
        SectionHeader(
            title = state.metric.label,
            subtitle = if (present == chart.size) {
                Formatters.count(chart.size, "sample")
            } else {
                present.toString() + " of " + Formatters.count(chart.size, "sample") +
                    " readable"
            },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TimelineViewModel.Metric.entries.forEach { entry ->
                Chip(
                    text = entry.label,
                    selected = state.metric == entry,
                    onClick = { onMetric(entry) },
                )
            }
        }

        if (present == 0) {
            Text(
                "No sample in this recording carried a readable " +
                    state.metric.label.lowercase() + " figure, so there is no line to " +
                    "draw. The gap is the finding.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }

        LabelledChart(
            values = chart,
            maxValue = state.chartMax,
            formatAxis = { formatMetric(state.metric, it) },
            footer = Formatters.clockTimeShort(state.series.first().timestamp) + " – " +
                Formatters.clockTimeShort(state.series.last().timestamp),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "Replay",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = (state.scrubIndex ?: state.series.lastIndex).toFloat(),
            onValueChange = { onScrub(it.toInt().coerceIn(0, state.series.lastIndex)) },
            valueRange = 0f..state.series.lastIndex.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = ProcessLensTheme.accent.base,
                activeTrackColor = ProcessLensTheme.accent.base,
            ),
        )
        Text(
            state.scrubPoint?.let { Formatters.clockTime(it.timestamp) }
                ?: "Drag to step through the recording",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatMetric(metric: TimelineViewModel.Metric, value: Float): String =
    when (metric) {
        TimelineViewModel.Metric.CPU,
        TimelineViewModel.Metric.BATTERY,
        TimelineViewModel.Metric.OWN_CPU,
        -> Formatters.percentValue(value, decimals = 0)

        TimelineViewModel.Metric.MEMORY -> Formatters.bytes(value.toLong())
        TimelineViewModel.Metric.TEMPERATURE -> Formatters.temperature((value * 10).toInt())
        TimelineViewModel.Metric.NETWORK -> Formatters.rate(value.toDouble())
    }

/**
 * The state of the device at one recorded instant (Section 25).
 *
 * Everything shown is read back from the stored snapshot. Where a field was null when
 * recorded it stays absent here — the replay reproduces the recording's blind spots
 * rather than papering over them.
 */
@Composable
private fun ScrubCard(
    state: TimelineViewModel.State,
    point: SeriesPoint,
    onOpenProcess: (String) -> Unit,
    onClose: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = Formatters.clockTime(point.timestamp),
            subtitle = "Sample " + ((state.scrubIndex ?: 0) + 1) + " of " + state.series.size,
            trailing = { ActionText("Close", onClick = onClose) },
        )

        DetailRow(
            label = "CPU",
            value = point.cpuPercent?.let { Formatters.percentValue(it) }
                ?: "Not readable at this sample",
            monospace = point.cpuPercent != null,
        )
        DetailRow(
            label = "Memory used",
            value = Formatters.bytes(point.memoryUsedBytes),
            monospace = true,
        )
        DetailRow(
            label = "Memory available",
            value = Formatters.bytes(point.memoryAvailableBytes),
            monospace = true,
        )
        DetailRow(
            label = "Battery",
            value = point.batteryLevel.toString() + "%" +
                if (point.isCharging) "  ·  charging" else "",
            monospace = true,
        )
        point.batteryTemperatureDeciCelsius?.let {
            DetailRow(
                label = "Battery temperature",
                value = Formatters.temperature(it),
                monospace = true,
            )
        }
        DetailRow(label = "Screen", value = if (point.isScreenOn) "On" else "Off")
        DetailRow(
            label = "Processes observed",
            value = point.processCount.toString(),
            monospace = true,
        )
        point.ownCpuPercent?.let {
            DetailRow(
                label = "ProcessLens itself",
                value = Formatters.percentValue(it) +
                    (point.ownMemoryBytes?.let { m -> "  ·  " + Formatters.bytes(m) } ?: ""),
                monospace = true,
            )
        }

        val snapshot = state.scrubSnapshot
        Spacer(Modifier.height(10.dp))
        when {
            state.isLoadingSnapshot -> LoadingBlock(label = "Reading this sample's processes")

            snapshot == null -> Text(
                "The per-process rows for this instant could not be read back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            snapshot.entries.isEmpty() -> Text(
                "No per-process row was recorded at this instant. On Android 8 and " +
                    "above a normal app may see only its own process.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    "Top processes at this instant",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                snapshot.entries
                    .sortedByDescending { it.cpuPercent ?: -1f }
                    .take(8)
                    .forEach { entry ->
                        ListRow(
                            title = entry.processName,
                            subtitle = buildString {
                                append(entry.importance.label)
                                entry.pid?.let {
                                    append("  ·  PID ")
                                    append(it)
                                }
                                entry.memoryBytes?.let {
                                    append("  ·  ")
                                    append(Formatters.bytes(it))
                                }
                            },
                            onClick = entry.pid?.let { pid ->
                                { onOpenProcess(ProcessInfo.idForPid(pid)) }
                            },
                            trailing = {
                                Text(
                                    entry.cpuPercent?.let { Formatters.percentValue(it) } ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tapping a row opens that process as it is now, not as it was — a " +
                        "recording holds what was measured, and the live process may " +
                        "since have exited or been reused under a new PID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------- summary

/**
 * The generated findings (Section 14).
 *
 * Wording is the whole design here. Each headline states a measurement and its rank,
 * and the correlation lines are prefixed as potential — the recording establishes that
 * two things moved together, never that one caused the other (Section 16).
 */
@Composable
private fun SummaryCard(summary: InvestigationSummary, onOpenApp: (String) -> Unit) {
    GlassCard {
        SectionHeader(
            title = "What the recording shows",
            subtitle = "Derived from the samples above, recomputed on open",
        )

        RankedRow("Highest CPU", summary.highestCpuProcess, onOpenApp)
        RankedRow("Largest memory increase", summary.largestMemoryIncrease, onOpenApp)
        RankedRow("Most restarts", summary.mostFrequentlyRestarted, onOpenApp)

        DetailRow(
            label = "Battery",
            value = summary.batteryDrainPercent?.let {
                if (it <= 0) "No net drain over the recording" else it.toString() + "% drained"
            } ?: "Not measurable over this window",
            monospace = summary.batteryDrainPercent != null,
        )
        summary.batteryTemperatureRiseDeciCelsius?.let {
            DetailRow(
                label = "Temperature rise",
                value = Formatters.temperature(it),
                monospace = true,
            )
        }
        DetailRow(
            label = "Network activity",
            value = if (summary.networkActivityDetected) "Detected" else "None detected",
        )
        DetailRow(label = "WakeLocks", value = summary.wakeLockActivityDetected.label)
        if (summary.wakeLockActivityDetected == Observed3State.NOT_OBSERVABLE) {
            Text(
                "WakeLock attribution needs the platform's own power dump, which a " +
                    "normal app cannot read. \"Not observable\" is not the same as \"none\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        if (summary.eventCounts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary.eventCounts.entries
                    .sortedByDescending { it.value }
                    .forEach { (group, count) ->
                        Chip(text = group.label + " " + count)
                    }
            }
        }

        if (summary.correlations.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Potential correlations",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            summary.correlations.forEach { line ->
                Text(
                    "·  " + line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "These are timing coincidences found in the samples. Sampling every " +
                    Formatters.duration(summary.investigation.sampleIntervalMillis) +
                    " can show that two things moved together; it cannot show that one " +
                    "caused the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (summary.limitations.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ExpandableDetail(
                summary = Formatters.count(summary.limitations.size, "limitation") +
                    " applied to this recording",
                detail = summary.limitations.joinToString("\n\n"),
            )
        }
    }
}

@Composable
private fun RankedRow(label: String, ranked: RankedProcess?, onOpenApp: (String) -> Unit) {
    if (ranked == null) {
        DetailRow(label = label, value = "Nothing measurable")
        return
    }
    ListRow(
        title = ranked.label,
        subtitle = label + "  ·  " + formatRanked(ranked),
        onClick = ranked.packageName?.let { pkg -> { onOpenApp(pkg) } },
    )
}

/** Formats a ranked value in the unit the detector recorded it under. */
private fun formatRanked(ranked: RankedProcess): String = when (ranked.unit) {
    "%" -> Formatters.percentValue(ranked.value.toFloat())
    "bytes" -> Formatters.bytes(ranked.value.toLong())
    else -> ranked.value.toLong().toString() + " " + ranked.unit
}

// -------------------------------------------------------------------------- events

@Composable
private fun EventsCard(
    state: TimelineViewModel.State,
    onGroup: (EventGroup?) -> Unit,
    onSeverity: (EventSeverity) -> Unit,
    onSeek: (Long) -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Events",
            subtitle = if (state.events.isEmpty()) {
                "None derived"
            } else {
                state.visibleEvents.size.toString() + " of " +
                    Formatters.count(state.events.size, "event") + " shown"
            },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(
                text = "All",
                selected = state.groupFilter == null,
                onClick = { onGroup(null) },
            )
            EventGroup.entries.forEach { group ->
                val count = state.events.count { it.type.group == group }
                if (count > 0) {
                    Chip(
                        text = group.label + " " + count,
                        selected = state.groupFilter == group,
                        onClick = { onGroup(if (state.groupFilter == group) null else group) },
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EventSeverity.entries.forEach { severity ->
                Chip(
                    text = severity.label + " and above",
                    selected = state.minSeverity == severity,
                    onClick = { onSeverity(severity) },
                )
            }
        }

        if (state.events.isEmpty()) {
            Text(
                "Nothing crossed a threshold during this recording. That is a result: " +
                    "the system was quiet by the measures you set.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }

        if (state.visibleEvents.isEmpty()) {
            EmptyState(
                message = "No event matches this filter.",
                action = {
                    ActionText(
                        "Show all",
                        onClick = {
                            onGroup(null)
                            onSeverity(EventSeverity.INFO)
                        },
                    )
                },
            )
            return@GlassCard
        }

        state.visibleEvents.forEach { event ->
            EventRow(
                event = event,
                showRelativeTo = state.investigation?.startedAt,
                onClick = { onSeek(event.timestamp) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap an event to move the replay above to the sample it came from.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
    }
}
