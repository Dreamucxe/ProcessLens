package com.processlens.feature.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.common.valueOrNull
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.BarSegment
import com.processlens.core.designsystem.DetailRow
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.LabelledChart
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.MeterBar
import com.processlens.core.designsystem.NotAvailable
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ObservedRow
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.RingGauge
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.SegmentedBar
import com.processlens.core.designsystem.SourceFootnote
import com.processlens.core.designsystem.UnavailableStyle
import com.processlens.core.designsystem.loadColor
import com.processlens.domain.model.EventSeverity

/**
 * The memory screen (Section 8).
 *
 * Total and available memory come from `ActivityManager.MemoryInfo`, which every app
 * can read on every supported API level, so the headline figure is always real. The
 * finer breakdown comes from `/proc/meminfo`, which some hardened kernels restrict —
 * so the cache/buffers/swap card can legitimately be unavailable while the headline is
 * fine, and the screen says which is which.
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    onOpenProcesses: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val system = state.system
    val accent = ProcessLensTheme.accent
    val dark = ProcessLensTheme.isDark

    Column(modifier) {
        ScreenHeader(
            title = "Memory",
            subtitle = system?.let {
                Formatters.bytes(it.memory.totalBytes) + " total · " + it.accessLevelName +
                    " access"
            },
            onBack = onBack,
            actions = {
                IconTapTarget(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Refresh now",
                    onClick = viewModel::refresh,
                )
            },
        )

        if (system == null) {
            LoadingBlock(label = "Reading memory state")
            return@Column
        }

        val memory = system.memory

        ScreenBody {
            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            if (memory.isLowMemory) {
                NoticeBanner(
                    text = "The system is under memory pressure. Android is terminating " +
                        "background processes to reclaim memory.",
                    severity = EventSeverity.WARNING,
                    detail = "ActivityManager.MemoryInfo.lowMemory = true" +
                        (
                            memory.lowMemoryThresholdBytes.valueOrNull
                                ?.let { "\nThreshold: " + Formatters.bytes(it) } ?: ""
                            ),
                )
            }

            // ---- Headline ----
            GlassCard {
                SectionHeader(
                    title = "In use",
                    subtitle = Formatters.bytes(memory.availableBytes) + " available",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge(
                        fraction = memory.usedFraction,
                        label = "Used",
                        centerText = Formatters.percent(memory.usedFraction),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        DetailRow(
                            label = "Used",
                            value = Formatters.bytes(memory.usedBytes),
                            monospace = true,
                        )
                        DetailRow(
                            label = "Available",
                            value = Formatters.bytes(memory.availableBytes),
                            monospace = true,
                        )
                        DetailRow(
                            label = "Total",
                            value = Formatters.bytes(memory.totalBytes),
                            monospace = true,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "\"Available\" is Android's own estimate of what could be given to a " +
                        "new app without killing anything, which is why it is larger than " +
                        "free memory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Trend ----
            if (state.history.size >= 2) {
                GlassCard {
                    SectionHeader(title = "Trend")
                    LabelledChart(
                        values = state.history,
                        maxValue = memory.totalBytes.toFloat(),
                        formatAxis = { Formatters.bytes(it.toLong()) },
                        footer = "Memory in use, one point per refresh.",
                    )
                }
            }

            // ---- Breakdown ----
            GlassCard {
                SectionHeader(
                    title = "Breakdown",
                    subtitle = "From /proc/meminfo",
                )
                val cached = memory.cachedBytes.valueOrNull
                if (cached != null) {
                    val segments = buildList {
                        add(
                            BarSegment(
                                "Used",
                                memory.usedBytes,
                                loadColor(memory.usedFraction, accent, dark),
                            ),
                        )
                        add(BarSegment("Cached", cached, accent.bright))
                        memory.buffersBytes.valueOrNull?.let {
                            add(BarSegment("Buffers", it, accent.base))
                        }
                        memory.freeBytes.valueOrNull?.let {
                            add(BarSegment("Free", it, MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                    SegmentedBar(segments = segments, total = memory.totalBytes)
                    Spacer(Modifier.height(10.dp))
                }
                ObservedRow(label = "Cached", observed = memory.cachedBytes, monospace = true) {
                    Formatters.bytes(it)
                }
                ObservedRow(label = "Buffers", observed = memory.buffersBytes, monospace = true) {
                    Formatters.bytes(it)
                }
                ObservedRow(label = "Free", observed = memory.freeBytes, monospace = true) {
                    Formatters.bytes(it)
                }
                ObservedRow(
                    label = "Low-memory threshold",
                    observed = memory.lowMemoryThresholdBytes,
                    monospace = true,
                ) { Formatters.bytes(it) }
            }

            // ---- Swap ----
            val swapTotal = memory.swapTotalBytes.valueOrNull
            GlassCard {
                SectionHeader(
                    title = "Swap and compressed memory",
                    subtitle = "Most Android devices use zRAM rather than a swap file",
                )
                if (swapTotal != null && swapTotal > 0) {
                    val swapFree = memory.swapFreeBytes.valueOrNull ?: 0L
                    val swapUsed = (swapTotal - swapFree).coerceAtLeast(0L)
                    val fraction = swapUsed.toFloat() / swapTotal
                    DetailRow(
                        label = "In use",
                        value = Formatters.bytes(swapUsed) + " of " + Formatters.bytes(swapTotal),
                        monospace = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    MeterBar(fraction = fraction, color = loadColor(fraction, accent, dark))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "On a device using zRAM this is compressed memory, not disk. " +
                            "Heavy use here means pressure, but it is far cheaper than a " +
                            "process being killed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (swapTotal == 0L) {
                    Text(
                        "This device reports no swap or zRAM configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    NotAvailable(
                        observed = memory.swapTotalBytes,
                        what = "Swap",
                        style = UnavailableStyle.REASON,
                    )
                }
            }

            // ---- Per process ----
            GlassCard(onClick = onOpenProcesses) {
                SectionHeader(
                    title = "Largest processes",
                    subtitle = if (state.topConsumers.isEmpty()) {
                        "No per-process memory figures are readable here"
                    } else {
                        "By resident memory, where readable"
                    },
                    trailing = { ActionText("All", onClick = onOpenProcesses) },
                )
                if (state.topConsumers.isEmpty()) {
                    NotAvailable(
                        observed = memory.appTotalBytes,
                        what = "Per-process memory",
                        style = UnavailableStyle.REASON,
                    )
                } else {
                    val largest = state.topConsumers
                        .firstNotNullOfOrNull { it.memoryBytes.valueOrNull } ?: 1L
                    state.topConsumers.forEach { process ->
                        val bytes = process.memoryBytes.valueOrNull ?: return@forEach
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    process.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    Formatters.bytes(bytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            MeterBar(
                                fraction = bytes.toFloat() / largest,
                                color = accent.base,
                                height = 4.dp,
                            )
                        }
                    }
                }
            }

            // ---- ProcessLens itself ----
            GlassCard {
                SectionHeader(
                    title = "ProcessLens' own memory",
                    subtitle = "Section 43: a monitor should be cheap",
                )
                ObservedRow(
                    label = "Resident",
                    observed = system.ownUsage.memoryBytes,
                    monospace = true,
                ) { Formatters.bytes(it) }
                DetailRow(
                    label = "Java heap",
                    value = Formatters.bytes(system.ownUsage.heapUsedBytes) + " of " +
                        Formatters.bytes(system.ownUsage.heapMaxBytes),
                    monospace = true,
                )
                Spacer(Modifier.height(6.dp))
                MeterBar(
                    fraction = system.ownUsage.heapFraction,
                    color = loadColor(system.ownUsage.heapFraction, accent, dark),
                )
                Spacer(Modifier.height(8.dp))
                DetailRow(label = "Threads", value = system.ownUsage.threadCount.toString())
            }

            SourceFootnote(
                memory.cachedBytes,
                memory.swapTotalBytes,
                system.ownUsage.memoryBytes,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}
