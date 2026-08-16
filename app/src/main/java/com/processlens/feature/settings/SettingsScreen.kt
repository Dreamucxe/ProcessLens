package com.processlens.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.HairlineDivider
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.SettingChoice
import com.processlens.core.designsystem.SettingSlider
import com.processlens.core.designsystem.SettingSwitch
import com.processlens.domain.model.AccentColor
import com.processlens.domain.model.AnimationIntensity
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.model.RefreshRate
import com.processlens.domain.model.ThemeMode

/**
 * Settings (Sections 40, 41).
 *
 * Every switch on this screen changes what ProcessLens does, not what it shows.
 * That distinction is why the monitoring section spells out what each one stops
 * sampling: turning memory detail off does not hide `/proc/meminfo`, it stops reading
 * it, and the fields it filled then read "Switched off in Settings" rather than
 * blaming Android for a limitation the user chose (Sections 42, 43).
 *
 * There is no account section, no sync section, and no analytics section, because
 * there is nothing to put in them (Section 29).
 */
@Composable
fun SettingsScreen(
    onOpenAccess: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenFavorites: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingReset by remember { mutableStateOf(false) }

    Column(modifier) {
        ScreenHeader(
            title = "Settings",
            subtitle = state.device?.let { it.manufacturer + " " + it.model },
        )

        ScreenBody {
            state.savingError?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissNotice) },
                )
            }
            state.notice?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.INFO,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissNotice) },
                )
            }

            AppearanceCard(state = state, viewModel = viewModel)
            MonitoringCard(state = state, viewModel = viewModel)
            InvestigationCard(state = state, viewModel = viewModel)
            AccessCard(
                state = state,
                viewModel = viewModel,
                onOpenAccess = onOpenAccess,
                onOpenCapabilities = onOpenCapabilities,
            )
            PrivacyCard(state = state, viewModel = viewModel)
            DataCard(
                state = state,
                onOpenFavorites = onOpenFavorites,
                onClearExports = viewModel::clearExportCache,
            )
            ResetCard(
                confirming = confirmingReset,
                onAsk = { confirmingReset = true },
                onCancel = { confirmingReset = false },
                onConfirm = {
                    confirmingReset = false
                    viewModel.resetToDefaults()
                },
            )
            AboutCard(state = state)

            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}

// --------------------------------------------------------------------- appearance

@Composable
private fun AppearanceCard(state: SettingsViewModel.State, viewModel: SettingsViewModel) {
    val settings = state.settings
    GlassCard {
        SectionHeader(title = "Appearance")

        SettingChoice(
            title = "Theme",
            options = ThemeMode.entries.toList(),
            selected = settings.themeMode,
            onSelect = viewModel::setThemeMode,
            label = { it.label },
        )
        HairlineDivider()

        SettingChoice(
            title = "Accent",
            options = AccentColor.entries.toList(),
            selected = settings.accentColor,
            onSelect = viewModel::setAccent,
            label = { it.label },
            subtitle = if (settings.useDynamicColor) {
                "Overridden by the wallpaper colours below"
            } else {
                null
            },
        )
        HairlineDivider()

        SettingSwitch(
            title = "Use wallpaper colours",
            subtitle = "Material You. Android 12 and later only; below that the accent " +
                "above is used.",
            checked = settings.useDynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )
        HairlineDivider()

        SettingSwitch(
            title = "Glass cards",
            subtitle = "Translucent card surfaces. Off gives flat opaque cards, which are " +
                "slightly cheaper to draw on a long list.",
            checked = settings.glassEffectEnabled,
            onCheckedChange = viewModel::setGlass,
        )
        HairlineDivider()

        SettingChoice(
            title = "Animation",
            options = AnimationIntensity.entries.toList(),
            selected = settings.animationIntensity,
            onSelect = viewModel::setAnimationIntensity,
            label = { it.label },
            subtitle = if (settings.reducedMotion) {
                "Held at none while reduced motion is on"
            } else {
                null
            },
        )
        HairlineDivider()

        SettingSwitch(
            title = "Reduce motion",
            subtitle = "Stops chart and card transitions regardless of the setting above.",
            checked = settings.reducedMotion,
            onCheckedChange = viewModel::setReducedMotion,
        )
        HairlineDivider()

        SettingSwitch(
            title = "High contrast",
            subtitle = "Stronger text and outline colours.",
            checked = settings.highContrast,
            onCheckedChange = viewModel::setHighContrast,
        )
        HairlineDivider()

        SettingSwitch(
            title = "Haptics",
            subtitle = "A tick on discrete actions only — never on scrolling or refreshes.",
            checked = settings.hapticsEnabled,
            onCheckedChange = viewModel::setHaptics,
        )
    }
}

// --------------------------------------------------------------------- monitoring

/**
 * The polling controls (Sections 6, 43).
 *
 * Each switch names what it stops reading, because that is the only way a user can
 * judge the trade. The subtitle is not decoration: a person who switches network
 * counters off should know before they do it that throughput cannot be recovered
 * afterwards, since it is a rate between two samples that were never taken.
 */
@Composable
private fun MonitoringCard(state: SettingsViewModel.State, viewModel: SettingsViewModel) {
    val settings = state.settings
    GlassCard {
        SectionHeader(
            title = "Monitoring",
            subtitle = "What ProcessLens samples, and how often",
        )

        SettingChoice(
            title = "Refresh rate",
            options = RefreshRate.entries.toList(),
            selected = settings.refreshRate,
            onSelect = viewModel::setRefreshRate,
            label = { it.label },
            subtitle = if (settings.refreshRate.isAutomatic) {
                "Faster is more responsive and costs more battery"
            } else {
                "Nothing is sampled until you ask for a reading"
            },
        )
        HairlineDivider()

        SettingSlider(
            title = "Process list frequency",
            subtitle = "Walking the process table is the most expensive read " +
                "ProcessLens performs, so it runs less often than the system figures.",
            value = settings.processListPollMultiplier,
            range = 1..10,
            steps = 8,
            onValueChange = viewModel::setProcessMultiplier,
            valueLabel = if (settings.processListPollMultiplier == 1) {
                "Every poll"
            } else {
                "Every " + settings.processListPollMultiplier + " polls"
            },
        )
        HairlineDivider()

        Spacer(Modifier.height(4.dp))
        Text(
            "Switching one of these off stops the read, not the display. The figures it " +
                "supplied then say \"Switched off in Settings\" — ProcessLens does not " +
                "keep showing the last value as though it were current.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        SettingSwitch(
            title = "Sample CPU",
            subtitle = "/proc/stat, the per-core counters, CPU frequencies and the thermal " +
                "zones. The largest saving of the four.",
            checked = settings.cpuPollingEnabled,
            onCheckedChange = viewModel::setCpuPolling,
        )
        HairlineDivider()

        SettingSwitch(
            title = "Sample memory detail",
            subtitle = "/proc/meminfo — the cached, buffer, free and swap breakdown. Total " +
                "and available memory come from elsewhere and are unaffected.",
            checked = settings.memoryPollingEnabled,
            onCheckedChange = viewModel::setMemoryPolling,
        )
        HairlineDivider()

        SettingSwitch(
            title = "Sample battery detail",
            subtitle = "The current, charge and energy counters. Level, charging state and " +
                "temperature arrive in the system's own broadcast and are unaffected.",
            checked = settings.batteryPollingEnabled,
            onCheckedChange = viewModel::setBatteryPolling,
        )
        HairlineDivider()

        SettingSwitch(
            title = "Sample network counters",
            subtitle = "Interface byte counters and the throughput derived from them. " +
                "Throughput cannot be filled in later — it is a rate between two samples.",
            checked = settings.networkPollingEnabled,
            onCheckedChange = viewModel::setNetworkPolling,
        )
        HairlineDivider()

        SettingSwitch(
            title = "Show ProcessLens' own usage",
            subtitle = "Its own CPU, memory and thread count on the dashboard. A tool that " +
                "measures other apps should be measurable itself.",
            checked = settings.showOwnResourceUsage,
            onCheckedChange = viewModel::setShowOwnUsage,
        )
        HairlineDivider()

        SettingSwitch(
            title = "Include system processes",
            subtitle = "System and platform processes in the process and app lists.",
            checked = settings.showSystemProcesses,
            onCheckedChange = viewModel::setShowSystemProcesses,
        )
    }
}

// ------------------------------------------------------------------ investigation

@Composable
private fun InvestigationCard(state: SettingsViewModel.State, viewModel: SettingsViewModel) {
    val settings = state.settings
    GlassCard {
        SectionHeader(
            title = "Investigation",
            subtitle = "Defaults and the thresholds that raise an event",
        )

        SettingSlider(
            title = "Default duration",
            value = settings.defaultDurationMinutes,
            range = 1..60,
            onValueChange = viewModel::setDefaultDuration,
            valueLabel = Formatters.count(settings.defaultDurationMinutes, "min"),
            subtitle = "A recording can still be stopped by hand at any point.",
        )
        HairlineDivider()

        SettingSlider(
            title = "Sample interval",
            value = (settings.investigationSampleIntervalMillis / 1_000L).toInt(),
            range = 1..60,
            onValueChange = { viewModel.setSampleInterval(it * 1_000L) },
            valueLabel = Formatters.count(
                (settings.investigationSampleIntervalMillis / 1_000L).toInt(),
                "second",
            ),
            subtitle = "How often a recording writes a sample. A shorter interval gives a " +
                "finer timeline and a larger database.",
        )
        HairlineDivider()

        SettingSwitch(
            title = "Detect events automatically",
            subtitle = "Raises timeline events when a threshold below is crossed. Off " +
                "records the samples and leaves the reading to you.",
            checked = settings.automaticEventDetection,
            onCheckedChange = viewModel::setAutomaticEventDetection,
        )
        HairlineDivider()

        SettingSlider(
            title = "CPU warning",
            value = settings.cpuWarningThreshold,
            range = 1..99,
            onValueChange = viewModel::setCpuWarning,
            valueLabel = settings.cpuWarningThreshold.toString() + "%",
        )
        SettingSlider(
            title = "CPU critical",
            value = settings.cpuCriticalThreshold,
            range = 2..100,
            onValueChange = viewModel::setCpuCritical,
            valueLabel = settings.cpuCriticalThreshold.toString() + "%",
            subtitle = "Kept above the warning line; moving one moves the other if they " +
                "would cross.",
        )
        HairlineDivider()

        SettingSlider(
            title = "Memory growth warning",
            value = settings.memoryIncreaseWarningMb,
            range = 10..2_048,
            onValueChange = viewModel::setMemoryIncreaseMb,
            valueLabel = settings.memoryIncreaseWarningMb.toString() + " MB",
            subtitle = "Growth across one recording, not an absolute figure — a leak is a " +
                "trend, not a size.",
        )
        HairlineDivider()

        SettingSlider(
            title = "Battery temperature warning",
            value = settings.batteryTemperatureWarningDeciCelsius / 10,
            range = 30..60,
            onValueChange = { viewModel.setBatteryTemperatureWarning(it * 10) },
            valueLabel = (settings.batteryTemperatureWarningDeciCelsius / 10).toString() + " °C",
            subtitle = "Only raised on devices that actually report battery temperature.",
        )

        if (!state.thresholdsAreOrdered) {
            Spacer(Modifier.height(8.dp))
            NoticeBanner(
                text = "The warning threshold must stay below the critical one.",
                severity = EventSeverity.WARNING,
            )
        }
    }
}

// -------------------------------------------------------------------------- access

/**
 * Elevated access (Sections 27, 28).
 *
 * The switches here are consent, not capability: turning Shizuku on does not obtain
 * it, and turning it off withdraws it immediately. Nothing is probed until the user
 * opens the access screen, and no command is ever run to find out.
 */
@Composable
private fun AccessCard(
    state: SettingsViewModel.State,
    viewModel: SettingsViewModel,
    onOpenAccess: () -> Unit,
    onOpenCapabilities: () -> Unit,
) {
    val settings = state.settings
    GlassCard {
        SectionHeader(
            title = "Access",
            subtitle = "What ProcessLens is permitted to use",
        )

        SettingSwitch(
            title = "Use Shizuku when available",
            subtitle = "Read-only diagnostics through the Shizuku service, which restores " +
                "the process table Android 9 and later withhold.",
            checked = settings.shizukuEnabled,
            onCheckedChange = viewModel::setShizukuEnabled,
        )
        HairlineDivider()

        SettingSwitch(
            title = "Use root when available",
            subtitle = "Off by default. ProcessLens runs read-only diagnostic commands " +
                "only, never a command that changes the device.",
            checked = settings.rootEnabled,
            onCheckedChange = viewModel::setRootEnabled,
        )

        Spacer(Modifier.height(8.dp))
        ExpandableDetail(
            summary = "What elevated access is used for",
            detail = "Reading the full process table, per-process memory the platform " +
                "computes itself, held wake locks, running services, and Android's own " +
                "per-app battery attribution. Every command is a read: process listings, " +
                "/proc contents, and dumpsys output. Nothing is killed, force-stopped, " +
                "uninstalled, or written. Shizuku grants shell-level access, not root — " +
                "some readings stay unavailable even with it.",
        )
        Spacer(Modifier.height(8.dp))

        ListRow(
            title = "Access and permissions",
            subtitle = "Probe Shizuku or root, and grant the optional permissions",
            onClick = onOpenAccess,
        )
        HairlineDivider()
        ListRow(
            title = "Capability matrix",
            subtitle = "What this device actually lets ProcessLens read",
            onClick = onOpenCapabilities,
        )
    }
}

// ------------------------------------------------------------------------ privacy

@Composable
private fun PrivacyCard(state: SettingsViewModel.State, viewModel: SettingsViewModel) {
    val settings = state.settings
    GlassCard {
        SectionHeader(
            title = "Privacy and export",
            subtitle = "Nothing leaves the device unless you send it",
        )

        SettingSwitch(
            title = "Local-only mode",
            subtitle = "Blocks the share sheet, so an export can only be written to a file " +
                "you choose.",
            checked = settings.localOnlyMode,
            onCheckedChange = viewModel::setLocalOnly,
        )
        HairlineDivider()

        SettingChoice(
            title = "Export format",
            options = ExportFormat.entries.toList(),
            selected = settings.exportFormat,
            onSelect = viewModel::setExportFormat,
            label = { it.label },
            subtitle = "JSON keeps the provenance of every figure; CSV is one row per " +
                "sample; plain text is a readable report.",
        )
        HairlineDivider()

        SettingSwitch(
            title = "Include system apps in exports",
            subtitle = "Off keeps an export to the apps you installed.",
            checked = settings.includeSystemAppsInExport,
            onCheckedChange = viewModel::setIncludeSystemAppsInExport,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "ProcessLens has no internet permission. That is not a setting — the " +
                "permission is absent from the manifest, so the operating system will " +
                "refuse a network connection even if the app asks for one. There is no " +
                "account, no cloud, no analytics and no crash reporting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ExpandableDetail(
            summary = "What an export contains",
            detail = "The samples of the recording you export, the events raised in it, " +
                "the device and Android version it was taken on, the access level in " +
                "effect, and the process and package names observed. It does not contain " +
                "file contents, message contents, account names, credentials, or anything " +
                "from an app's private storage — ProcessLens cannot read those either.",
        )
    }
}

// --------------------------------------------------------------------------- data

@Composable
private fun DataCard(
    state: SettingsViewModel.State,
    onOpenFavorites: () -> Unit,
    onClearExports: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Stored data",
            subtitle = "On this device, in ProcessLens' own database",
        )

        ListRow(
            title = "Recordings",
            subtitle = Formatters.count(state.recordingCount, "recording") + " · " +
                Formatters.count(state.storedSampleCount, "sample"),
        )
        HairlineDivider()
        ListRow(
            title = "Favourites",
            subtitle = "Pinned processes, apps and recordings",
            onClick = onOpenFavorites,
        )
        HairlineDivider()
        // Exports live in the cache, so Android may reclaim them on its own. The row
        // says as much: a user should not treat one as an archive.
        ListRow(
            title = "Export files",
            subtitle = if (state.exportCacheBytes == 0L) {
                "None written yet"
            } else {
                Formatters.bytes(state.exportCacheBytes) +
                    " in the app cache, which Android may reclaim"
            },
            trailing = {
                if (state.exportCacheBytes > 0L) {
                    ActionText("Delete", onClick = onClearExports)
                }
            },
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "A recording is deleted from the recordings screen. Restoring defaults below " +
                "does not touch recordings, favourites, or observation history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------------------- reset

/**
 * Restore defaults, behind one confirmation.
 *
 * Two taps rather than one, because the action is not individually reversible — but
 * only two, because it destroys no observation data and the confirmation says so.
 */
@Composable
private fun ResetCard(
    confirming: Boolean,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    GlassCard {
        SectionHeader(title = "Restore defaults")
        if (confirming) {
            NoticeBanner(
                text = "Every preference on this screen returns to its default. Recordings, " +
                    "favourites and observation history are kept.",
                severity = EventSeverity.WARNING,
            )
            Spacer(Modifier.height(10.dp))
            ActionText("Restore defaults", onClick = onConfirm)
            Spacer(Modifier.height(6.dp))
            ActionText("Cancel", onClick = onCancel)
        } else {
            Text(
                "Returns appearance, monitoring, investigation and privacy preferences to " +
                    "their defaults.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            ActionText("Restore defaults…", onClick = onAsk)
        }
    }
}

// -------------------------------------------------------------------------- about

@Composable
private fun AboutCard(state: SettingsViewModel.State) {
    GlassCard {
        SectionHeader(title = "About")
        val device = state.device
        if (device != null) {
            ListRow(
                title = "Device",
                subtitle = device.manufacturer + " " + device.model + " · " + device.device,
            )
            HairlineDivider()
            ListRow(
                title = "Android",
                subtitle = device.androidRelease + " · API " + device.apiLevel +
                    (device.securityPatch?.let { " · patch " + it } ?: ""),
            )
            HairlineDivider()
        }
        ListRow(
            title = "ProcessLens",
            subtitle = "A process observatory. Everything it shows was measured on this " +
                "device; anything it could not measure says so.",
        )
    }
}
