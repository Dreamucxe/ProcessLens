package com.processlens.feature.access

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.AccessLevel
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.DetailRow
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.RootState
import com.processlens.domain.model.ShizukuState

/**
 * Access level (Sections 27, 28, 41).
 *
 * Three tiers, described in terms of what each one lets ProcessLens *see* rather than
 * what it lets ProcessLens *do* — because this app only ever reads. The Shizuku section
 * states plainly that shell access is not root; the root section lists every command
 * that will ever be run with it.
 */
@Composable
fun AccessScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier) {
        ScreenHeader(
            title = "Access level",
            subtitle = "Currently " + state.effectiveLevel.label,
            onBack = onBack,
            actions = { ActionText("Re-check", onClick = viewModel::refresh) },
        )

        ScreenBody {
            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }
            state.lastProbeMessage?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.INFO,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissMessage) },
                )
            }

            SummaryCard(state = state)
            NormalCard(state = state, context = context, onRefresh = viewModel::refresh)
            ShizukuCard(state = state, context = context, onRequest = viewModel::requestShizuku)
            RootCard(state = state, onProbe = viewModel::probeRoot)

            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}

@Composable
private fun SummaryCard(state: AccessViewModel.State) {
    val capabilities = state.capabilities
    GlassCard {
        SectionHeader(
            title = state.effectiveLevel.label + " access",
            subtitle = capabilities?.let {
                it.fullCount.toString() + " full, " + it.limitedCount + " limited, " +
                    it.unavailableCount + " unavailable"
            },
        )
        Text(
            when (state.effectiveLevel) {
                AccessLevel.NORMAL ->
                    "ProcessLens is using only what any app may read: its own process, " +
                        "system-wide CPU and memory counters, the battery broadcast, the " +
                        "package manager, and — where you have granted it — usage access."

                AccessLevel.SHIZUKU ->
                    "ProcessLens is running its read-only diagnostics through Shizuku's " +
                        "shell service. That reaches the full process table and the " +
                        "platform's own dumps. It is not root."

                AccessLevel.ROOT ->
                    "ProcessLens is running its read-only diagnostics as root. Nothing on " +
                        "this screen or anywhere in the app writes, kills, or modifies " +
                        "anything — the elevated commands are all reads."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------------------- normal

/**
 * Grants any app can ask for.
 *
 * Listed first because they are the ones that matter most on an unmodified device:
 * usage access alone unlocks per-app network attribution and recent-use figures, which
 * is most of what people come to a tool like this for.
 */
@Composable
private fun NormalCard(
    state: AccessViewModel.State,
    context: Context,
    onRefresh: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Standard grants",
            subtitle = "Available on every device, no special setup",
        )

        GrantRow(
            title = "Usage access",
            granted = state.hasUsageAccess,
            unlocks = "Per-app network usage, recent-use times, launch counts",
            actionLabel = "Open settings",
            onAction = { openSettings(context, android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS) },
        )
        GrantRow(
            title = "Phone state",
            granted = state.hasPhoneState,
            unlocks = "Mobile-data attribution on Android 10 and above",
            actionLabel = "App settings",
            onAction = { openAppDetails(context) },
        )
        GrantRow(
            title = "Notifications",
            granted = state.hasNotifications,
            unlocks = "The recording notification on Android 13 and above",
            actionLabel = "App settings",
            onAction = { openAppDetails(context) },
        )
        GrantRow(
            title = "Unrestricted background",
            granted = state.isBatteryOptimisationIgnored,
            unlocks = "Long recordings are less likely to be interrupted",
            actionLabel = "Open settings",
            onAction = {
                openSettings(
                    context,
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                )
            },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "ProcessLens asks for none of these up front. Each one is requested only when " +
                "you ask for something that needs it, and refusing simply means the " +
                "corresponding figures read \"Not available\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ActionText("Re-check after changing a setting", onClick = onRefresh)
    }
}

/**
 * One grant.
 *
 * The state is a word in a chip, not a coloured dot, and the content description spells
 * out both the state and what it unlocks (Sections 48, 49).
 */
@Composable
private fun GrantRow(
    title: String,
    granted: Boolean,
    unlocks: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    ListRow(
        title = title,
        subtitle = unlocks,
        trailing = {
            Row {
                Chip(text = if (granted) "Granted" else "Not granted")
            }
        },
        onClick = if (granted) null else onAction,
        contentDescriptionOverride = title + ". " +
            (if (granted) "Granted." else "Not granted.") + " Unlocks: " + unlocks + "." +
            (if (granted) "" else " Double tap to " + actionLabel.lowercase() + "."),
    )
}

// ------------------------------------------------------------------------- shizuku

@Composable
private fun ShizukuCard(
    state: AccessViewModel.State,
    context: Context,
    onRequest: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Shizuku",
            subtitle = state.shizukuState.label,
        )

        Text(
            when (state.shizukuState) {
                ShizukuState.NOT_INSTALLED ->
                    "Shizuku is a separate open-source app that exposes Android's own " +
                        "shell service to apps you authorise. ProcessLens works without " +
                        "it; with it, the full process table becomes readable."

                ShizukuState.INSTALLED_NOT_RUNNING ->
                    "Shizuku is installed but its service is not running. Open Shizuku and " +
                        "start it — by wireless debugging, by adb, or by root — then come " +
                        "back and re-check."

                ShizukuState.RUNNING_PERMISSION_UNKNOWN ->
                    "Shizuku is running and has not yet been asked about ProcessLens."

                ShizukuState.RUNNING_PERMISSION_DENIED ->
                    "Shizuku is running and has denied ProcessLens. You can ask again, or " +
                        "grant it from the Shizuku app's own list."

                ShizukuState.RUNNING_PERMISSION_GRANTED ->
                    "Shizuku is running and has granted ProcessLens. Elevated reads are " +
                        "going through it now."

                ShizukuState.VERSION_UNSUPPORTED ->
                    "This Shizuku predates version 11 and uses a permission model that " +
                        "was removed. Updating Shizuku will fix it."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        when (state.shizukuState) {
            ShizukuState.RUNNING_PERMISSION_UNKNOWN,
            ShizukuState.RUNNING_PERMISSION_DENIED,
            -> ActionText(
                if (state.isRequestingShizuku) "Asking Shizuku…" else "Ask Shizuku for access",
                onClick = onRequest,
                enabled = !state.isRequestingShizuku,
            )

            ShizukuState.INSTALLED_NOT_RUNNING -> ActionText(
                "Open Shizuku",
                onClick = { openShizuku(context) },
            )

            ShizukuState.NOT_INSTALLED -> Text(
                "Nothing to do here unless you choose to install Shizuku. ProcessLens " +
                    "does not bundle it, link to a download, or nag about it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ShizukuState.RUNNING_PERMISSION_GRANTED -> ActionText(
                "Open Shizuku",
                onClick = { openShizuku(context) },
            )

            ShizukuState.VERSION_UNSUPPORTED -> ActionText(
                "Open Shizuku",
                onClick = { openShizuku(context) },
            )
        }

        Spacer(Modifier.height(12.dp))
        ExpandableDetail(
            summary = "Shizuku is not root",
            detail = "Commands sent through Shizuku run as the shell user, UID 2000 — the " +
                "same identity adb has. That is enough to list every process, read " +
                "/proc entries the app itself is denied, and query the platform's " +
                "service dumps.\n\n" +
                "It is not enough to read another app's private files, to write to " +
                "system partitions, or to change any setting. If something on this " +
                "device needs root, Shizuku will not provide it, and ProcessLens will " +
                "keep saying \"Not available\" rather than pretending otherwise.",
        )
    }
}

// ---------------------------------------------------------------------------- root

@Composable
private fun RootCard(state: AccessViewModel.State, onProbe: () -> Unit) {
    GlassCard {
        SectionHeader(title = "Root", subtitle = state.rootState.label)

        Text(
            when (state.rootState) {
                RootState.UNAVAILABLE ->
                    "No su binary was found in any standard location. Either this device " +
                        "is not rooted, or the root manager hides itself from apps — " +
                        "ProcessLens cannot tell those apart and does not guess."

                RootState.BINARY_PRESENT ->
                    "A su binary exists but has not been used. Probing runs one command, " +
                        "`id`, and checks whether it comes back as uid 0."

                RootState.DENIED ->
                    "The root request was refused. Nothing was run."

                RootState.GRANTED ->
                    "Root is granted. It is used only for the read-only diagnostics below."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.rootState != RootState.UNAVAILABLE) {
            Spacer(Modifier.height(12.dp))
            ActionText(
                if (state.isProbingRoot) "Probing…" else "Probe for root",
                onClick = onProbe,
                enabled = !state.isProbingRoot,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "What elevated access is used for",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        ELEVATED_USES.forEach { (command, purpose) ->
            DetailRow(label = purpose, value = command, monospace = true)
        }

        Spacer(Modifier.height(10.dp))
        NoticeBanner(
            text = "Every elevated command in ProcessLens is a read. There is no kill, no " +
                "force-stop, no \"clean up\", and no batch action — not disabled, not " +
                "hidden behind a warning: absent.",
            severity = EventSeverity.INFO,
            detail = "The elevated interface accepts only the fixed set of commands listed " +
                "above. It takes no free-text input and builds no command strings from " +
                "user data, so there is no path from the interface to an arbitrary " +
                "shell command.",
        )
    }
}

/**
 * The complete list of elevated commands.
 *
 * Written out in full deliberately. Section 28 requires that root use be explicit and
 * bounded, and a list a user can read is stronger than a promise they cannot check.
 */
private val ELEVATED_USES: List<Pair<String, String>> = listOf(
    "id" to "Probe access",
    "ps -A -o PID,PPID,USER,RSS,VSZ,STAT,TIME,NAME" to "Full process table",
    "cat /proc/stat" to "System CPU counters",
    "cat /proc/<pid>/stat" to "Per-process CPU",
    "cat /proc/<pid>/status" to "Per-process memory",
    "ls /proc/<pid>/task" to "Thread list",
    "dumpsys power" to "Held wake locks",
    "dumpsys batterystats --charged" to "Per-app battery",
    "dumpsys activity services" to "Running services",
)

// ------------------------------------------------------------------------- intents

private fun openSettings(context: Context, action: String) {
    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/**
 * Opens the Shizuku app if it is installed.
 *
 * Two known package names are tried and failure is silent — the button is only shown
 * when a manager was detected, but it can be uninstalled between the check and the tap.
 */
private fun openShizuku(context: Context) {
    val packages = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")
    for (pkg in packages) {
        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }
            .getOrNull()
        if (intent != null) {
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
    }
}
