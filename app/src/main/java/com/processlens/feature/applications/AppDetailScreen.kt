package com.processlens.feature.applications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.DetailRow
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.MonoText
import com.processlens.core.designsystem.NotAvailable
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ObservedRow
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.UnavailableStyle
import com.processlens.domain.model.ComponentEntry
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.ObservationHistory
import com.processlens.domain.model.PermissionGrant
import com.processlens.domain.model.PermissionInfo
import com.processlens.domain.model.ServiceInfo

/**
 * One application in depth (Sections 19–22).
 *
 * Four tabs, because the four bodies of information have genuinely different
 * provenance: the manifest, the grant table, the running-service list, and what
 * ProcessLens has itself observed. The screen never presents the last of these as
 * though it came from the platform.
 */
@Composable
fun AppDetailScreen(
    onBack: () -> Unit,
    onOpenProcess: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profile = state.profile

    Column(modifier) {
        ScreenHeader(
            title = profile?.app?.label ?: state.packageName.substringAfterLast('.'),
            subtitle = profile?.app?.let { app ->
                buildString {
                    app.versionName?.let {
                        append(it)
                        append(" · ")
                    }
                    append(if (app.isSystemApp) "System package" else "User-installed")
                }
            } ?: state.packageName,
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

        if (state.notInstalled && profile == null) {
            ScreenBody {
                EmptyState(
                    message = "This package is not installed, or it is not visible to " +
                        "ProcessLens. From Android 11 the system filters which packages " +
                        "an app may see.",
                    action = { ActionText("Back", onClick = onBack) },
                )
            }
            return@Column
        }

        if (state.isLoading && profile == null) {
            LoadingBlock(label = "Reading package information")
            return@Column
        }

        TabBar(tab = state.tab, onSelect = viewModel::selectTab)

        ScreenBody {
            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            when (state.tab) {
                AppDetailViewModel.Tab.OVERVIEW -> OverviewTab(
                    state = state,
                    onOpenProcess = onOpenProcess,
                    onOpenSettings = { openAppSettings(context, state.packageName) },
                )

                AppDetailViewModel.Tab.PERMISSIONS -> PermissionsTab(
                    permissions = state.permissions,
                    onOpenSettings = { openAppSettings(context, state.packageName) },
                )

                AppDetailViewModel.Tab.COMPONENTS -> ComponentsTab(state = state)

                AppDetailViewModel.Tab.SERVICES -> ServicesTab(services = state.services)
            }

            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}

@Composable
private fun TabBar(tab: AppDetailViewModel.Tab, onSelect: (AppDetailViewModel.Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppDetailViewModel.Tab.entries.forEach { entry ->
            Chip(text = entry.label, selected = tab == entry, onClick = { onSelect(entry) })
        }
    }
}

// ------------------------------------------------------------------------ overview

@Composable
private fun OverviewTab(
    state: AppDetailViewModel.State,
    onOpenProcess: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val profile = state.profile ?: return
    val app = profile.app

    GlassCard {
        SectionHeader(
            title = if (profile.isRunning) "Running" else "Not running",
            subtitle = if (profile.isRunning) {
                Formatters.count(profile.runningProcesses.size, "process", "processes") +
                    ", " + Formatters.count(profile.runningServices.size, "service")
            } else {
                "No process or service of this package was observed"
            },
        )
        ObservedRow(label = "Memory", observed = profile.memoryBytes, monospace = true) {
            Formatters.bytes(it)
        }
        ObservedRow(label = "CPU", observed = profile.cpuPercent, monospace = true) {
            Formatters.percentValue(it)
        }
        if (profile.runningProcesses.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Figures are summed across all " + profile.runningProcesses.size +
                    " processes of this package.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (profile.runningProcesses.isNotEmpty()) {
        GlassCard {
            SectionHeader(title = "Processes")
            profile.runningProcesses.forEach { process ->
                ListRow(
                    title = process.processName,
                    subtitle = buildString {
                        append(process.importance.label)
                        process.pid.valueOrNull?.let {
                            append("  ·  PID ")
                            append(it)
                        }
                    },
                    onClick = { onOpenProcess(process.id) },
                    trailing = {
                        Text(
                            process.cpuPercent.valueOrNull
                                ?.let { Formatters.percentValue(it) } ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }

    // ---- Package facts ----
    GlassCard {
        SectionHeader(title = "Package", subtitle = "From the package manager")
        DetailRow(label = "Package", value = app.packageName, monospace = true)
        DetailRow(label = "Version", value = app.versionName ?: "Not declared")
        DetailRow(label = "Version code", value = app.versionCode.toString(), monospace = true)
        DetailRow(label = "Target API", value = app.targetSdk.toString())
        ObservedRow(label = "Minimum API", observed = app.minSdk) { it.toString() }
        DetailRow(label = "UID", value = app.uid.toString(), monospace = true)
        DetailRow(label = "Enabled", value = if (app.isEnabled) "Yes" else "No")
        DetailRow(label = "Debuggable", value = if (app.isDebuggable) "Yes" else "No")
        DetailRow(label = "Installed", value = Formatters.dateTime(app.firstInstallTime))
        DetailRow(label = "Updated", value = Formatters.dateTime(app.lastUpdateTime))
        ObservedRow(label = "Install source", observed = app.installSource) { it }
        ObservedRow(label = "APK size", observed = app.apkSizeBytes, monospace = true) {
            Formatters.bytes(it)
        }
    }

    // ---- Usage ----
    GlassCard {
        SectionHeader(title = "Recent use", subtitle = "From UsageStatsManager")
        when (val usage = profile.usage) {
            is Observed.Value -> {
                val value = usage.value
                if (value.lastTimeUsed > 0L) {
                    DetailRow(
                        label = "Last used",
                        value = Formatters.dateTime(value.lastTimeUsed),
                    )
                } else {
                    DetailRow(label = "Last used", value = "Not in the recorded window")
                }
                DetailRow(
                    label = "Foreground",
                    value = Formatters.durationCoarse(value.totalForegroundMillis),
                    monospace = true,
                )
                ObservedRow(label = "Launches", observed = value.launchCount) { it.toString() }
            }

            else -> NotAvailable(
                observed = usage,
                what = "Usage statistics",
                style = UnavailableStyle.REASON,
            )
        }
    }

    // ---- Network ----
    GlassCard {
        SectionHeader(title = "Network, last 24 hours", subtitle = "From NetworkStatsManager")
        when (val net = profile.networkUsage) {
            is Observed.Value -> {
                DetailRow(
                    label = "Received",
                    value = Formatters.bytes(net.value.rxBytes),
                    monospace = true,
                )
                DetailRow(
                    label = "Transmitted",
                    value = Formatters.bytes(net.value.txBytes),
                    monospace = true,
                )
            }

            else -> NotAvailable(
                observed = net,
                what = "Network usage",
                style = UnavailableStyle.REASON,
            )
        }
    }

    // ---- Section 22: what ProcessLens itself has recorded ----
    HistoryCard(history = state.history)

    // ---- Actions ----
    GlassCard {
        SectionHeader(
            title = "Actions",
            subtitle = "ProcessLens observes; the platform acts",
        )
        ListRow(
            title = "Open in Android settings",
            subtitle = "Force stop, permissions, storage and notifications live there",
            onClick = onOpenSettings,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "ProcessLens does not force-stop, disable or uninstall anything. No app can " +
                "do those things to another package without system privileges, and " +
                "offering a button that pretends otherwise would be worse than not " +
                "offering one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Section 22 behaviour profile.
 *
 * Every figure here is labelled with the number of samples behind it, and the card is
 * absent entirely for a package ProcessLens has never seen running. "Average 0%" from
 * one sample would be a claim about an app's behaviour that nothing supports.
 */
@Composable
private fun HistoryCard(history: ObservationHistory?) {
    GlassCard {
        SectionHeader(
            title = "What ProcessLens has observed",
            subtitle = "Recorded by this app, not reported by Android",
        )
        if (history == null || history.observationCount == 0) {
            Text(
                "Nothing recorded yet. ProcessLens builds this profile from the samples " +
                    "it takes while you use it, so it fills in over time rather than " +
                    "being fetched from anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }

        DetailRow(
            label = "First seen",
            value = Formatters.dateTime(history.firstObservedAt),
        )
        DetailRow(
            label = "Last seen",
            value = Formatters.dateTime(history.lastObservedAt),
        )
        DetailRow(
            label = "Observations",
            value = history.observationCount.toString(),
            monospace = true,
        )

        if (history.hasCpu) {
            DetailRow(
                label = "Average CPU",
                value = Formatters.percentValue(history.cpuAverage ?: 0f) +
                    "  over " + Formatters.count(history.cpuSampleCount, "sample"),
                monospace = true,
            )
            history.cpuPeak?.let {
                DetailRow(
                    label = "Peak CPU",
                    value = Formatters.percentValue(it),
                    monospace = true,
                )
            }
        } else {
            DetailRow(label = "CPU", value = "No readable sample was recorded")
        }

        if (history.hasMemory) {
            DetailRow(
                label = "Average memory",
                value = Formatters.bytes(history.memoryAverageBytes ?: 0L) +
                    "  over " + Formatters.count(history.memorySampleCount, "sample"),
                monospace = true,
            )
            history.memoryPeakBytes?.let {
                DetailRow(label = "Peak memory", value = Formatters.bytes(it), monospace = true)
            }
        } else {
            DetailRow(label = "Memory", value = "No readable sample was recorded")
        }

        Spacer(Modifier.height(8.dp))
        ExpandableDetail(
            summary = "How to read these averages",
            detail = "Each average covers only the samples in which a figure was actually " +
                "readable. Samples where the platform refused the read are skipped, not " +
                "counted as zero, which is why the sample count is shown next to every " +
                "average.\n\n" +
                "The samples are taken while you have this screen or the process list " +
                "open — they are not a continuous background recording, and the window " +
                "between the first and last observation (" +
                Formatters.durationCoarse(history.observedWindowMillis) +
                ") is not fully covered.",
        )
    }
}

// --------------------------------------------------------------------- permissions

@Composable
private fun PermissionsTab(
    permissions: Observed<List<PermissionInfo>>?,
    onOpenSettings: () -> Unit,
) {
    if (permissions == null) {
        LoadingBlock(label = "Reading permissions")
        return
    }
    if (permissions !is Observed.Value) {
        GlassCard {
            NotAvailable(observed = permissions, what = "Permissions")
        }
        return
    }
    val list = permissions.value
    if (list.isEmpty()) {
        GlassCard {
            SectionHeader(title = "No permissions")
            Text(
                "This package declares no permissions at all.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val dangerous = list.filter { it.isDangerous }
    val granted = dangerous.count { it.grant == PermissionGrant.GRANTED }

    GlassCard {
        SectionHeader(
            title = "Runtime permissions",
            subtitle = granted.toString() + " of " +
                Formatters.count(dangerous.size, "sensitive permission") + " allowed",
        )
        if (dangerous.isEmpty()) {
            Text(
                "This package declares no sensitive permissions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dangerous
                .sortedWith(compareBy({ it.group.label }, { it.shortName }))
                .forEach { PermissionRow(it) }
        }
        Spacer(Modifier.height(10.dp))
        ActionText("Change these in Android settings", onClick = onOpenSettings)
        Spacer(Modifier.height(4.dp))
        Text(
            "ProcessLens cannot grant or revoke another app's permissions — only the " +
                "platform's own settings can, so that is where this sends you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val others = list.filterNot { it.isDangerous }
    if (others.isNotEmpty()) {
        GlassCard {
            SectionHeader(
                title = "Other declared permissions",
                subtitle = Formatters.count(others.size, "permission") +
                    " granted at install or reserved for system apps",
            )
            others
                .sortedWith(compareBy({ it.group.label }, { it.shortName }))
                .forEach { PermissionRow(it) }
        }
    }
}

/**
 * One permission row.
 *
 * The grant state is a word, never a colour alone (Section 49), and
 * [PermissionGrant.RESTRICTED] is spelled out because "granted but inert" is the state
 * users are most often misled about.
 */
@Composable
private fun PermissionRow(permission: PermissionInfo) {
    ListRow(
        title = permission.label ?: permission.shortName,
        subtitle = buildString {
            append(permission.group.label)
            if (permission.label != null) {
                append("  ·  ")
                append(permission.shortName)
            }
            if (permission.isSignatureLevel) append("  ·  signature level")
        },
        trailing = { Chip(text = permission.grant.label) },
        contentDescriptionOverride = (permission.label ?: permission.shortName) +
            ". " + permission.grant.label + ". " + permission.group.label + ".",
    )
    permission.description?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------- components

@Composable
private fun ComponentsTab(state: AppDetailViewModel.State) {
    val components = state.components
    if (components == null) {
        LoadingBlock(label = "Reading the manifest")
        return
    }
    if (components !is Observed.Value) {
        GlassCard {
            NotAvailable(observed = components, what = "Components")
        }
        return
    }
    val value = components.value

    GlassCard {
        SectionHeader(
            title = "Declared components",
            subtitle = Formatters.count(value.total, "component") + " in the manifest",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(text = Formatters.count(value.activities.size, "activity", "activities"))
            Chip(text = Formatters.count(value.services.size, "service"))
            Chip(text = Formatters.count(value.receivers.size, "receiver"))
            Chip(text = Formatters.count(value.providers.size, "provider"))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "\"Exported\" means another app can start or bind to the component. It is a " +
                "declaration in the manifest, not evidence that anything has done so.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ComponentGroup("Activities", value.activities)
    ComponentGroup("Services", value.services)
    ComponentGroup("Receivers", value.receivers)
    ComponentGroup("Providers", value.providers)
}

@Composable
private fun ComponentGroup(title: String, entries: List<ComponentEntry>) {
    if (entries.isEmpty()) return
    GlassCard {
        SectionHeader(title = title, subtitle = Formatters.count(entries.size, "declared"))
        entries.sortedBy { it.simpleName }.forEach { entry ->
            ListRow(
                title = entry.simpleName,
                subtitle = buildString {
                    append(if (entry.isExported) "Exported" else "Not exported")
                    if (!entry.isEnabled) append("  ·  disabled")
                    entry.permission?.let {
                        append("  ·  guarded by ")
                        append(it.substringAfterLast('.'))
                    }
                    entry.authority?.let {
                        append("  ·  ")
                        append(it)
                    }
                    entry.extra?.let {
                        append("  ·  ")
                        append(it)
                    }
                },
            )
            MonoText(
                entry.className,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------------ services

/**
 * Running services (Section 19).
 *
 * Read-only by design: the spec forbids arbitrary service control, and in any case
 * `stopService` from another app is refused by the platform. Where a service is
 * foreground, that is stated, since a foreground service is the single most common
 * reason an app keeps running.
 */
@Composable
private fun ServicesTab(services: Observed<List<ServiceInfo>>?) {
    if (services == null) {
        LoadingBlock(label = "Reading running services")
        return
    }
    if (services !is Observed.Value) {
        GlassCard {
            NotAvailable(observed = services, what = "Running services")
            Spacer(Modifier.height(8.dp))
            Text(
                "From Android 8, getRunningServices returns only this app's own " +
                    "services. Elevated access reads the platform's own service dump " +
                    "instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val list = services.value
    GlassCard {
        SectionHeader(
            title = "Running services",
            subtitle = if (list.isEmpty()) {
                "None observed for this package"
            } else {
                Formatters.count(list.size, "service") + " observed"
            },
        )
        if (list.isEmpty()) {
            Text(
                "No service of this package was running at the last read.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            list.sortedBy { it.simpleName }.forEach { service ->
                ListRow(
                    title = service.simpleName,
                    subtitle = buildString {
                        append(service.processName)
                        service.pid.valueOrNull?.let {
                            append("  ·  PID ")
                            append(it)
                        }
                    },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (service.isForeground) Chip(text = "Foreground")
                        }
                    },
                )
                Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                    ObservedRow(label = "Active for", observed = service.activeSinceMillis) {
                        Formatters.durationCoarse(it)
                    }
                    ObservedRow(label = "Bound clients", observed = service.clientCount) {
                        it.toString()
                    }
                    ObservedRow(label = "Exported", observed = service.isExported) {
                        if (it) "Yes" else "No"
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Read-only. ProcessLens does not start or stop other apps' services: the " +
                "platform refuses it, and a control that silently failed would be worse " +
                "than none.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Opens Android's own application-details page.
 *
 * Guarded: the package can be uninstalled between the read and the tap, and a few OEM
 * builds restrict the intent — in which case doing nothing is better than crashing.
 */
private fun openAppSettings(context: Context, packageName: String) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
