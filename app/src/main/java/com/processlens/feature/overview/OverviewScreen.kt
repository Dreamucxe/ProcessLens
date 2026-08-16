package com.processlens.feature.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.ViewList
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
import com.processlens.core.designsystem.AvailabilityBadge
import com.processlens.core.designsystem.BarSegment
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.EventRow
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.LineChart
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.MonoText
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ObservedTile
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.SegmentedBar
import com.processlens.core.designsystem.StatTile
import com.processlens.core.designsystem.loadColor
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.FavoriteType
import com.processlens.domain.model.RefreshRate
import com.processlens.domain.repository.SystemState

/**
 * The dashboard (Section 5).
 *
 * The layout answers, top to bottom: is anything wrong right now, what are the
 * headline figures, what has happened recently, and what can this device actually
 * show me. Every figure comes through [ObservedTile], so a restricted reading renders
 * as "Not available" with its reason and never as a zero (Section 42).
 */
@Composable
fun OverviewScreen(
    onOpenProcesses: () -> Unit,
    onOpenCpu: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenAccess: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenInvestigate: () -> Unit,
    onOpenTimeline: (Long) -> Unit,
    onOpenProcess: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier) {
        ScreenHeader(
            title = "Overview",
            subtitle = state.system?.let {
                buildString {
                    append(it.accessLevelName)
                    append(" access · ")
                    append(
                        if (state.settings.refreshRate.isAutomatic) {
                            "every ${state.settings.refreshRate.label}"
                        } else {
                            "manual refresh"
                        },
                    )
                }
            },
            actions = {
                Row {
                    IconTapTarget(
                        icon = Icons.Outlined.Search,
                        contentDescription = "Search processes and applications",
                        onClick = onOpenSearch,
                    )
                    IconTapTarget(
                        icon = Icons.Outlined.Star,
                        contentDescription = "Favourites",
                        onClick = onOpenFavorites,
                    )
                    IconTapTarget(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Refresh now",
                        onClick = viewModel::refresh,
                    )
                }
            },
        )

        val system = state.system
        if (system == null) {
            LoadingBlock()
            return@Column
        }

        ScreenBody {
            state.error?.let { message ->
                NoticeBanner(
                    text = message,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            if (state.isRecording) {
                RecordingBanner(
                    onOpen = { state.activeInvestigationId?.let(onOpenTimeline) ?: onOpenInvestigate() },
                )
            }

            if (system.memory.isLowMemory) {
                NoticeBanner(
                    text = "The system reports low memory. Android's low-memory killer is " +
                        "active, so background processes are being terminated.",
                    severity = EventSeverity.WARNING,
                    detail = "ActivityManager.MemoryInfo.lowMemory = true",
                )
            }

            // ---- Quick actions (Section 32) ----
            QuickActions(
                isRecording = state.isRecording,
                onOpenInvestigate = onOpenInvestigate,
                onOpenProcesses = onOpenProcesses,
                onOpenCpu = onOpenCpu,
                onOpenMemory = onOpenMemory,
                onOpenBattery = onOpenBattery,
                onOpenNetwork = onOpenNetwork,
                onOpenApps = onOpenApps,
                onOpenPermissions = onOpenPermissions,
                onOpenFavorites = onOpenFavorites,
            )

            // ---- Headline tiles ----
            StatGrid(
                system = system,
                onOpenCpu = onOpenCpu,
                onOpenMemory = onOpenMemory,
                onOpenBattery = onOpenBattery,
                onOpenNetwork = onOpenNetwork,
                onOpenProcesses = onOpenProcesses,
            )

            // ---- Trend ----
            if (state.cpuHistory.count { it != null } >= 2 || state.memoryHistory.size >= 2) {
                GlassCard {
                    SectionHeader(
                        title = "Recent trend",
                        subtitle = "Since this screen was opened",
                    )
                    if (state.cpuHistory.any { it != null }) {
                        Text(
                            "CPU",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LineChart(
                            values = state.cpuHistory,
                            maxValue = 100f,
                            contentDescription = "CPU trend over the last " +
                                "${state.cpuHistory.size} samples",
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        "Memory used",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LineChart(
                        values = state.memoryHistory,
                        maxValue = 100f,
                        color = ProcessLensTheme.accent.bright,
                        contentDescription = "Memory trend over the last " +
                            "${state.memoryHistory.size} samples",
                    )
                }
            }

            // ---- Memory breakdown ----
            MemoryCard(system, onOpenMemory)

            // ---- Recent events ----
            RecentEventsCard(
                events = state.recentEvents,
                onOpenInvestigate = onOpenInvestigate,
            )

            // ---- Favourites ----
            if (state.favorites.isNotEmpty()) {
                GlassCard {
                    SectionHeader(
                        title = "Favourites",
                        trailing = { ActionText("All", onClick = onOpenFavorites) },
                    )
                    state.favorites.forEach { favorite ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Chip(text = favorite.type.label)
                            Spacer(Modifier.width(8.dp))
                            ActionText(
                                text = favorite.label,
                                onClick = {
                                    when (favorite.type) {
                                        FavoriteType.APPLICATION -> onOpenApp(favorite.key)
                                        FavoriteType.PROCESS -> onOpenProcess(favorite.key)
                                        FavoriteType.INVESTIGATION ->
                                            favorite.key.toLongOrNull()?.let(onOpenTimeline)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ---- What this device allows ----
            CapabilitySummaryCard(
                state = state,
                onOpenCapabilities = onOpenCapabilities,
                onOpenAccess = onOpenAccess,
            )

            // ---- ProcessLens' own cost (Section 43) ----
            if (state.settings.showOwnResourceUsage) {
                OwnUsageCard(system)
            }
        }
    }
}

@Composable
private fun RecordingBanner(onOpen: () -> Unit) {
    NoticeBanner(
        text = "An investigation is recording. Sampling continues in the background.",
        severity = EventSeverity.INFO,
        action = { ActionText("Open", onClick = onOpen, icon = Icons.Outlined.CenterFocusStrong) },
    )
}

@Composable
private fun StatGrid(
    system: SystemState,
    onOpenCpu: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenProcesses: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
            ObservedTile(
                label = "CPU",
                observed = system.cpu.overallPercent,
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.DeveloperBoard,
                caption = "${system.cpu.coreCount} cores",
                fractionOf = { it / 100f },
                onClick = onOpenCpu,
                format = { Formatters.percentValue(it) },
            )
            StatTile(
                label = "Memory",
                value = Formatters.percent(system.memory.usedFraction),
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Memory,
                caption = Formatters.bytes(system.memory.usedBytes) + " of " +
                    Formatters.bytes(system.memory.totalBytes),
                fraction = system.memory.usedFraction,
                onClick = onOpenMemory,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
            StatTile(
                label = "Battery",
                value = "${system.battery.levelPercent}%",
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.BatteryStd,
                caption = system.battery.status.label,
                fraction = system.battery.levelPercent / 100f,
                accentColor = if (system.battery.isCharging) {
                    ProcessLensTheme.accent.bright
                } else {
                    null
                },
                onClick = onOpenBattery,
            )
            StatTile(
                label = "Processes",
                value = if (system.processCount > 0) system.processCount.toString() else "—",
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.ViewList,
                caption = "Visible at ${system.accessLevelName} access",
                onClick = onOpenProcesses,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
            ObservedTile(
                label = "Network down",
                observed = system.network.rxRateBytesPerSecond,
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.SwapVert,
                caption = system.network.transport.label + " · device-wide",
                onClick = onOpenNetwork,
                format = { Formatters.rate(it) },
            )
            StatTile(
                label = "Storage",
                value = Formatters.percent(system.storage.usedFraction),
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Storage,
                caption = Formatters.bytes(system.storage.availableBytes) + " free",
                fraction = system.storage.usedFraction,
            )
        }
    }
}

@Composable
private fun MemoryCard(system: SystemState, onOpenMemory: () -> Unit) {
    val accent = ProcessLensTheme.accent
    val dark = ProcessLensTheme.isDark
    val memory = system.memory

    GlassCard(onClick = onOpenMemory) {
        SectionHeader(
            title = "Memory",
            subtitle = Formatters.bytes(memory.availableBytes) + " available",
            trailing = {
                Text(
                    Formatters.percent(memory.usedFraction),
                    style = MaterialTheme.typography.titleMedium,
                    color = loadColor(memory.usedFraction, accent, dark),
                )
            },
        )

        // Only the bands that were actually readable are drawn. On a kernel that
        // restricts /proc/meminfo the bar shows used-versus-free and nothing more,
        // rather than inventing a cache figure to fill the space.
        val segments = buildList {
            add(BarSegment("Used", memory.usedBytes, loadColor(memory.usedFraction, accent, dark)))
            memory.cachedBytes.valueOrNull?.let {
                add(BarSegment("Cached", it, accent.bright))
            }
            memory.buffersBytes.valueOrNull?.let {
                add(BarSegment("Buffers", it, accent.base))
            }
        }
        SegmentedBar(segments = segments, total = memory.totalBytes)

        val swapTotal = memory.swapTotalBytes.valueOrNull
        val swapFree = memory.swapFreeBytes.valueOrNull
        if (swapTotal != null && swapTotal > 0 && swapFree != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Swap: " + Formatters.bytes(swapTotal - swapFree) + " of " +
                    Formatters.bytes(swapTotal) + " in use",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (memory.cachedBytes !is Observed.Value) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The detailed breakdown needs /proc/meminfo, which this device does " +
                    "not expose to applications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentEventsCard(
    events: List<com.processlens.domain.model.InvestigationEvent>,
    onOpenInvestigate: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Recent events",
            subtitle = if (events.isEmpty()) null else "From your recorded investigations",
            trailing = { ActionText("Investigate", onClick = onOpenInvestigate) },
        )
        if (events.isEmpty()) {
            EmptyState(
                message = "No events yet. Events are derived from recorded " +
                    "investigations — start one to build a timeline.",
                icon = Icons.Outlined.CenterFocusStrong,
                action = {
                    ActionText(
                        "Start an investigation",
                        onClick = onOpenInvestigate,
                        icon = Icons.Outlined.CenterFocusStrong,
                    )
                },
            )
        } else {
            events.forEachIndexed { index, event ->
                EventRow(event = event, showRail = index != events.lastIndex)
            }
        }
    }
}

@Composable
private fun CapabilitySummaryCard(
    state: OverviewViewModel.State,
    onOpenCapabilities: () -> Unit,
    onOpenAccess: () -> Unit,
) {
    val capabilities = state.capabilities ?: return
    GlassCard(onClick = onOpenCapabilities) {
        SectionHeader(
            title = "What this device allows",
            subtitle = "Probed on this device, not assumed from the Android version",
            trailing = {
                AvailabilityBadge(
                    availability = capabilities.availability(
                        com.processlens.domain.model.Capability.PROCESS_LIST,
                    ),
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(text = "${capabilities.fullCount} full", icon = Icons.Outlined.Verified)
            Chip(text = "${capabilities.limitedCount} limited")
            Chip(text = "${capabilities.unavailableCount} unavailable")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Access level: ${capabilities.accessLevel.label}. " +
                "Shizuku: ${capabilities.shizukuState.label}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ActionText(
            "Access and elevated observation",
            onClick = onOpenAccess,
            icon = Icons.Outlined.Bolt,
        )
    }
}

@Composable
private fun OwnUsageCard(system: SystemState) {
    val own = system.ownUsage
    GlassCard {
        SectionHeader(
            title = "ProcessLens itself",
            subtitle = "A monitor should not be the heaviest thing running",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
            ObservedTile(
                label = "Own CPU",
                observed = own.cpuPercent,
                modifier = Modifier.weight(1f),
                fractionOf = { it / 100f },
                format = { Formatters.percentValue(it) },
            )
            ObservedTile(
                label = "Own memory",
                observed = own.memoryBytes,
                modifier = Modifier.weight(1f),
                format = { Formatters.bytes(it) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Java heap ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MonoText(
                Formatters.bytes(own.heapUsedBytes) + " / " + Formatters.bytes(own.heapMaxBytes),
            )
            Spacer(Modifier.width(8.dp))
            Chip(text = "${own.threadCount} threads")
        }
    }
}
