package com.processlens.feature.processdetail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.AwaitingSample
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.DetailRow
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.LabelledChart
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.MonoText
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ObservedRow
import com.processlens.core.designsystem.ObservedTile
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.SourceFootnote
import com.processlens.core.designsystem.UnlockHint
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.ProcessInfo

/**
 * Process details (Section 10).
 *
 * Section 0.1 is explicit that there must be **no bare one-tap kill button** here, and
 * the deeper reason is that a normal Android app has no such power to offer: nothing in
 * the repository layer can terminate another process. What this screen offers instead
 * is the platform's own app-info screen — the actual mechanism a user has — behind a
 * clearly-labelled row that explains what it does before you tap it.
 */
@Composable
fun ProcessDetailScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenThreads: (Int) -> Unit,
    onOpenInvestigate: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProcessDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val process = state.process

    Column(modifier) {
        ScreenHeader(
            title = process?.displayName ?: "Process",
            subtitle = process?.let { it.processName + " · " + it.state.label },
            onBack = onBack,
            actions = {
                IconTapTarget(
                    icon = if (state.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (state.isFavorite) {
                        "Remove from favourites"
                    } else {
                        "Add to favourites"
                    },
                    onClick = viewModel::toggleFavorite,
                )
            },
        )

        if (process == null) {
            if (state.isLoading) {
                LoadingBlock(label = "Reading this process")
            } else {
                Column(Modifier.padding(Dimens.screenPadding)) {
                    NoticeBanner(
                        text = "This process is no longer visible. It may have exited, or " +
                            "it may have become unreadable at the current access level.",
                        severity = EventSeverity.WARNING,
                    )
                }
            }
            return@Column
        }

        ScreenBody {
            if (state.gone) {
                NoticeBanner(
                    text = "This process has exited. The figures below are the last " +
                        "readings ProcessLens took, not live values.",
                    severity = EventSeverity.WARNING,
                )
            }

            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            MetricsCard(process, state)
            IdentityCard(process)
            TrendCard(state)
            ThreadsCard(state, onOpenThreads)
            ActionsCard(
                process = process,
                availability = viewModel.stopAvailability(process),
                onOpenApp = onOpenApp,
                onOpenInvestigate = onOpenInvestigate,
                onOpenSystemInfo = { openAppInfo(context, it) },
            )
        }
    }
}

@Composable
private fun MetricsCard(process: ProcessInfo, state: ProcessDetailViewModel.State) {
    GlassCard {
        SectionHeader(
            title = "Live metrics",
            subtitle = "Sampled every two seconds while this screen is open",
            trailing = { Chip(text = process.importance.label) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
            ObservedTile(
                label = "CPU",
                observed = process.cpuPercent,
                modifier = Modifier.weight(1f),
                fractionOf = { it / 100f },
                format = { Formatters.percentValue(it) },
            )
            ObservedTile(
                label = "Memory",
                observed = process.memoryBytes,
                modifier = Modifier.weight(1f),
                format = { Formatters.bytes(it) },
            )
        }
        Spacer(Modifier.height(Dimens.tileSpacing))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
            ObservedTile(
                label = "Threads",
                observed = process.threadCount,
                modifier = Modifier.weight(1f),
                format = { it.toString() },
            )
            ObservedTile(
                label = "Started",
                observed = process.startTimeMillis,
                modifier = Modifier.weight(1f),
                caption = "Uptime",
                format = { Formatters.durationCoarse(System.currentTimeMillis() - it) },
            )
        }

        if (process.cpuPercent is Observed.Restricted &&
            state.cpuHistory.all { it == null } &&
            state.cpuHistory.isNotEmpty()
        ) {
            Spacer(Modifier.height(10.dp))
            UnlockHint(
                level = (process.cpuPercent as Observed.Restricted).unlockedBy
                    ?: com.processlens.core.common.AccessLevel.SHIZUKU,
            )
        }

        Spacer(Modifier.height(8.dp))
        SourceFootnote(
            process.cpuPercent,
            process.memoryBytes,
            process.pid,
        )
    }
}

@Composable
private fun IdentityCard(process: ProcessInfo) {
    GlassCard {
        SectionHeader(title = "Identity")
        DetailRow(label = "Process name", value = process.processName, monospace = true)
        ObservedRow(label = "PID", observed = process.pid, monospace = true) { it.toString() }
        ObservedRow(label = "UID", observed = process.uid, monospace = true) { it.toString() }
        DetailRow(label = "Kernel state", value = process.state.label)
        DetailRow(label = "Importance", value = process.importance.label)
        DetailRow(
            label = "Package",
            value = process.packageName ?: "No owning package",
            monospace = process.packageName != null,
        )
        DetailRow(label = "System process", value = if (process.isSystem) "Yes" else "No")
        ObservedRow(label = "Parent PID", observed = process.parentPid, monospace = true) {
            it.toString()
        }
        Spacer(Modifier.height(6.dp))
        ExpandableDetail(
            summary = "How this process was found",
            detail = buildString {
                appendLine("Discovery method: " + process.discoveredVia.label)
                appendLine("Complete enumeration: " + process.discoveredVia.isCompleteList)
                appendLine("Requires access level: " + process.discoveredVia.requires.label)
                append("Stable identifier used for navigation: " + process.id)
            },
        )
    }
}

@Composable
private fun TrendCard(state: ProcessDetailViewModel.State) {
    val readableCpu = state.cpuHistory.count { it != null }
    GlassCard {
        SectionHeader(
            title = "Since you opened this screen",
            subtitle = Formatters.count(state.cpuHistory.size, "sample", "samples"),
        )
        if (state.cpuHistory.size < 2) {
            // A rate needs two samples. Showing 0% for the first tick would be a
            // fabricated reading (Section 6).
            AwaitingSample()
        } else if (readableCpu == 0) {
            Text(
                "No CPU figure was readable for this process during this session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LabelledChart(
                values = state.cpuHistory,
                maxValue = 100f,
                formatAxis = { Formatters.percent(it / 100f) },
                footer = "CPU as a share of one core-second per wall-second.",
            )
        }

        if (state.memoryHistory.any { it != null }) {
            Spacer(Modifier.height(14.dp))
            val peak = state.memoryHistory.filterNotNull().maxOrNull() ?: 0f
            LabelledChart(
                values = state.memoryHistory,
                maxValue = maxOf(peak * 1.15f, 1f),
                color = ProcessLensTheme.accent.bright,
                formatAxis = { Formatters.bytes((it * 1_048_576f).toLong()) },
                footer = "Resident memory attributed to this process.",
            )
        }
    }
}

@Composable
private fun ThreadsCard(state: ProcessDetailViewModel.State, onOpenThreads: (Int) -> Unit) {
    val threads = state.threads ?: return
    val pid = state.process?.pid?.valueOrNull

    GlassCard {
        SectionHeader(
            title = "Threads",
            trailing = {
                if (threads is Observed.Value && pid != null) {
                    ActionText("Open", onClick = { onOpenThreads(pid) })
                }
            },
        )
        when (threads) {
            is Observed.Value -> {
                Text(
                    Formatters.count(threads.value.size, "thread", "threads") + " readable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                threads.value
                    .sortedWith(
                        compareByDescending<com.processlens.domain.model.ThreadInfo> {
                            it.cpuPercent.valueOrNull ?: -1f
                        },
                    )
                    .take(5)
                    .forEach { thread ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            MonoText(thread.tid.toString())
                            Spacer(Modifier.width(10.dp))
                            Text(
                                thread.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                thread.cpuPercent.valueOrNull
                                    ?.let { Formatters.percentValue(it) } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
            else -> com.processlens.core.designsystem.NotAvailable(
                observed = threads,
                what = "Thread list",
            )
        }
    }
}

/**
 * What a user can actually do from here.
 *
 * Every row performs a real operation. The app-info row is labelled with what it
 * opens, and the note above it says plainly that ProcessLens cannot stop a process
 * itself — which is true of every non-system Android app, and is a limitation worth
 * stating rather than papering over with a button that fails (Sections 2, 42, 59).
 */
@Composable
private fun ActionsCard(
    process: ProcessInfo,
    availability: ProcessDetailViewModel.StopAvailability,
    onOpenApp: (String) -> Unit,
    onOpenInvestigate: () -> Unit,
    onOpenSystemInfo: (String) -> Unit,
) {
    GlassCard {
        SectionHeader(title = "Actions")

        process.packageName?.let { pkg ->
            ListRow(
                title = "Application details",
                subtitle = "Components, permissions and behaviour profile",
                onClick = { onOpenApp(pkg) },
                trailing = { Chip(text = "In app", icon = Icons.Outlined.Apps) },
            )
        }

        ListRow(
            title = "Record an investigation",
            subtitle = "Sample this process over time and build a timeline",
            onClick = onOpenInvestigate,
            trailing = { Chip(text = "Record", icon = Icons.Outlined.CenterFocusStrong) },
        )

        process.packageName?.let { pkg ->
            ListRow(
                title = "Open system app info",
                subtitle = "Android's own screen, where force stop lives",
                onClick = { onOpenSystemInfo(pkg) },
                trailing = { Chip(text = "System", icon = Icons.Outlined.OpenInNew) },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = when (availability) {
                ProcessDetailViewModel.StopAvailability.OwnProcess ->
                    "This is ProcessLens itself. Ending it would only close the app."
                ProcessDetailViewModel.StopAvailability.SystemSettingsOnly ->
                    "ProcessLens cannot stop another process. Android reserves that for " +
                        "the system, so the row above hands you to the platform's own " +
                        "app-info screen instead of pretending to do it here."
                ProcessDetailViewModel.StopAvailability.NoRoute ->
                    "This process has no owning package, so there is no system screen to " +
                        "open for it, and no user-space app can signal it."
                ProcessDetailViewModel.StopAvailability.Unknown ->
                    "Still reading this process."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Chip(text = "Read-only observation", icon = Icons.Outlined.Layers)
    }
}

/**
 * Opens Android's own application-details screen.
 *
 * Wrapped in a runCatching because a package can be uninstalled between the read and
 * the tap, and because some heavily-modified OEM builds restrict this intent — in
 * which case doing nothing quietly is better than crashing, and the row's subtitle has
 * already told the user where they are being sent.
 */
private fun openAppInfo(context: Context, packageName: String) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
