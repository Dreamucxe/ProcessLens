package com.processlens.feature.cpu

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
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.AwaitingSample
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.CoreBars
import com.processlens.core.designsystem.DetailRow
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.LabelledChart
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.MonoText
import com.processlens.core.designsystem.NotAvailable
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ObservedRow
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.RingGauge
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.Sparkline
import com.processlens.core.designsystem.UnavailableStyle
import com.processlens.core.designsystem.loadColor
import com.processlens.domain.model.EventSeverity

/**
 * The CPU screen (Section 7).
 *
 * `/proc/stat` is unreadable to apps on most devices from API 26 onward — SELinux
 * denies it even where the file mode looks permissive — so the single most likely state
 * of this screen is "overall CPU not available, own-process CPU available". That is
 * treated as the normal case and explained properly, not as an error.
 */
@Composable
fun CpuScreen(
    onBack: () -> Unit,
    onOpenProcesses: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CpuViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val system = state.system

    Column(modifier) {
        ScreenHeader(
            title = "Processor",
            subtitle = system?.let {
                Formatters.count(it.cpu.coreCount, "core", "cores") + " · " + it.accessLevelName +
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
            LoadingBlock(label = "Reading processor state")
            return@Column
        }

        val cpu = system.cpu

        ScreenBody {
            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            // ---- Overall ----
            GlassCard {
                SectionHeader(
                    title = "Overall utilisation",
                    subtitle = "Share of all cores combined",
                )
                val overall = cpu.overallPercent.valueOrNull
                if (overall != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RingGauge(
                            fraction = overall / 100f,
                            label = "CPU",
                            centerText = Formatters.percentValue(overall),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Sampled between two reads of the kernel's cumulative " +
                                    "counters.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Sparkline(values = state.history)
                        }
                    }
                } else if (state.history.isEmpty()) {
                    AwaitingSample(
                        detail = "CPU utilisation is a rate: it needs two readings taken " +
                            "a moment apart.",
                    )
                } else {
                    NotAvailable(
                        observed = cpu.overallPercent,
                        what = "Overall CPU",
                        style = UnavailableStyle.FULL,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "From Android 8 onward most devices deny apps access to " +
                            "/proc/stat, which is the only source of whole-system CPU time. " +
                            "ProcessLens can still read its own CPU use, and per-process " +
                            "figures where they are exposed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Trend ----
            if (state.history.count { it != null } >= 2) {
                GlassCard {
                    SectionHeader(title = "Trend")
                    LabelledChart(
                        values = state.history,
                        maxValue = 100f,
                        formatAxis = { Formatters.percent(it / 100f) },
                        footer = "One point per refresh while this screen is open.",
                    )
                }
            }

            // ---- Per core ----
            GlassCard {
                SectionHeader(
                    title = "Per core",
                    subtitle = Formatters.count(cpu.coreCount, "core", "cores") + " reported",
                )
                val cores = cpu.perCorePercent.valueOrNull
                val frequencies = cpu.frequenciesKHz.valueOrNull
                if (cores != null && cores.isNotEmpty()) {
                    CoreBars(
                        perCore = cores,
                        offlineCores = frequencies
                            ?.filter { !it.isOnline }
                            ?.map { it.coreIndex }
                            ?.toSet()
                            .orEmpty(),
                    )
                } else {
                    NotAvailable(
                        observed = cpu.perCorePercent,
                        what = "Per-core utilisation",
                        style = UnavailableStyle.REASON,
                    )
                }
            }

            // ---- Frequencies ----
            GlassCard {
                SectionHeader(
                    title = "Clock speeds",
                    subtitle = "Read from /sys, which is usually world-readable",
                )
                val frequencies = cpu.frequenciesKHz.valueOrNull
                if (frequencies != null && frequencies.isNotEmpty()) {
                    frequencies.forEach { core ->
                        Row(
                            Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MonoText("cpu${core.coreIndex}")
                            Spacer(Modifier.width(12.dp))
                            if (core.isOnline) {
                                Text(
                                    Formatters.frequencyKHz(core.currentKHz),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "of " + Formatters.frequencyKHz(core.maxKHz),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                if (core.maxKHz > 0) {
                                    val fraction = core.currentKHz.toFloat() / core.maxKHz
                                    Text(
                                        Formatters.percent(fraction),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = loadColor(
                                            fraction,
                                            ProcessLensTheme.accent,
                                            ProcessLensTheme.isDark,
                                        ),
                                    )
                                }
                            } else {
                                // An offline core is a real state worth naming. It is
                                // emphatically not a core running at 0 MHz.
                                Chip(text = "Offline")
                            }
                        }
                    }
                } else {
                    NotAvailable(
                        observed = cpu.frequenciesKHz,
                        what = "Clock speeds",
                        style = UnavailableStyle.REASON,
                    )
                }
            }

            // ---- Load average and temperature ----
            GlassCard {
                SectionHeader(title = "Kernel counters")
                val load = cpu.loadAverage.valueOrNull
                if (load != null) {
                    DetailRow(
                        label = "Load average",
                        value = "%.2f  %.2f  %.2f".format(
                            load.oneMinute,
                            load.fiveMinute,
                            load.fifteenMinute,
                        ),
                        monospace = true,
                    )
                    Text(
                        "One, five and fifteen minute run-queue averages. Compare against " +
                            "${cpu.coreCount} cores, not against 100%.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    NotAvailable(
                        observed = cpu.loadAverage,
                        what = "Load average",
                        style = UnavailableStyle.SHORT,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ObservedRow(label = "CPU temperature", observed = cpu.temperatureDeciCelsius) {
                    Formatters.temperature(it)
                }
                ObservedRow(label = "ProcessLens' own CPU", observed = cpu.ownProcessPercent) {
                    Formatters.percentValue(it)
                }
            }

            // ---- Top consumers ----
            GlassCard(onClick = onOpenProcesses) {
                SectionHeader(
                    title = "Heaviest processes",
                    subtitle = if (state.topConsumers.isEmpty()) {
                        "No per-process CPU figures are readable here"
                    } else {
                        "Of the processes with a readable figure"
                    },
                    trailing = { ActionText("All", onClick = onOpenProcesses) },
                )
                if (state.topConsumers.isEmpty()) {
                    Text(
                        "Per-process CPU needs /proc/<pid>/stat, which Android restricts " +
                            "for other apps' processes on this device. Elevated access " +
                            "would expose it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.topConsumers.forEach { process ->
                        Row(
                            Modifier.padding(vertical = 5.dp),
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
                            process.cpuPercent.valueOrNull?.let { value ->
                                Text(
                                    Formatters.percentValue(value),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = loadColor(
                                        value / 100f,
                                        ProcessLensTheme.accent,
                                        ProcessLensTheme.isDark,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            if (cpu.overallPercent is Observed.Value) {
                com.processlens.core.designsystem.SourceFootnote(
                    cpu.overallPercent,
                    cpu.perCorePercent,
                    cpu.frequenciesKHz,
                    cpu.loadAverage,
                )
            }
        }
    }
}
