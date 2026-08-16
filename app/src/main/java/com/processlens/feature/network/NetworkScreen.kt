package com.processlens.feature.network

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.processlens.core.designsystem.MeterBar
import com.processlens.core.designsystem.NotAvailable
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ObservedRow
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.SourceFootnote
import com.processlens.core.designsystem.StatTile
import com.processlens.core.designsystem.UnavailableStyle
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.NetworkTransport

/**
 * The network screen (Section 18).
 *
 * ProcessLens holds no INTERNET permission — deliberately, so that "no hidden network
 * calls" in Section 29 is a structural fact rather than a promise. Everything here is
 * read from counters the platform keeps about *other* apps' traffic.
 */
@Composable
fun NetworkScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val system = state.system
    val context = LocalContext.current

    Column(modifier) {
        ScreenHeader(
            title = "Network",
            subtitle = system?.let {
                it.network.transport.label + (if (it.network.isConnected) "" else " · no route")
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
            LoadingBlock(label = "Reading network state")
            return@Column
        }

        val network = system.network

        ScreenBody {
            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            // ---- Connection ----
            GlassCard {
                SectionHeader(
                    title = if (network.isConnected) "Connected" else "No connection",
                    subtitle = "Reported by ConnectivityManager",
                )
                DetailRow(label = "Transport", value = network.transport.label)
                DetailRow(
                    label = "Metered",
                    value = if (network.isMetered) "Yes — the system treats this as costed" else "No",
                )
                DetailRow(label = "VPN active", value = if (network.isVpnActive) "Yes" else "No")
                ObservedRow(label = "Link downstream", observed = network.linkDownstreamKbps) {
                    Formatters.rate(it * 1000.0 / 8.0) + " estimated"
                }
                ObservedRow(label = "Link upstream", observed = network.linkUpstreamKbps) {
                    Formatters.rate(it * 1000.0 / 8.0) + " estimated"
                }
                ObservedRow(label = "Interface", observed = network.interfaceName) { it }

                if (network.transport == NetworkTransport.NONE) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "With no active network the counters below still show what moved " +
                            "earlier in this boot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Throughput ----
            GlassCard {
                SectionHeader(
                    title = "Throughput",
                    subtitle = "Sampled between two reads of the device counters",
                )
                val rx = network.rxRateBytesPerSecond.valueOrNull
                val tx = network.txRateBytesPerSecond.valueOrNull
                if (rx != null || tx != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
                        StatTile(
                            label = "Down",
                            value = rx?.let { Formatters.rate(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Download,
                        )
                        StatTile(
                            label = "Up",
                            value = tx?.let { Formatters.rate(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Upload,
                        )
                    }
                } else {
                    AwaitingSample(
                        detail = "A transfer rate is the difference between two counter " +
                            "reads, so the first sample cannot produce one.",
                    )
                }

                if (state.rxHistory.count { it != null } >= 2) {
                    Spacer(Modifier.height(12.dp))
                    LabelledChart(
                        values = state.rxHistory,
                        maxValue = state.rateCeiling,
                        formatAxis = { Formatters.rate(it.toDouble()) },
                        footer = "Download, whole device. Gaps are samples with no reading.",
                    )
                    Spacer(Modifier.height(10.dp))
                    LabelledChart(
                        values = state.txHistory,
                        maxValue = state.rateCeiling,
                        color = ProcessLensTheme.accent.bright,
                        formatAxis = { Formatters.rate(it.toDouble()) },
                        footer = "Upload, on the same scale as download.",
                    )
                }
            }

            // ---- Device totals ----
            GlassCard {
                SectionHeader(
                    title = "Since this boot",
                    subtitle = "Whole device, all applications together",
                )
                ObservedRow(
                    label = "Received",
                    observed = network.totalRxBytes,
                    monospace = true,
                ) { Formatters.bytes(it) }
                ObservedRow(
                    label = "Transmitted",
                    observed = network.totalTxBytes,
                    monospace = true,
                ) { Formatters.bytes(it) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "These are TrafficStats counters. They reset when the device restarts, " +
                        "cover every app at once, and cannot be attributed to any single " +
                        "one — which is what the section below is for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Per app ----
            PerAppCard(
                state = state,
                onOpenApp = onOpenApp,
                onSelectWindow = viewModel::selectWindow,
                onReload = viewModel::loadPerApp,
                onGrantUsageAccess = {
                    openUsageAccessSettings(context)
                    viewModel.recheckAccess()
                },
            )

            SourceFootnote(
                network.totalRxBytes,
                network.rxRateBytesPerSecond,
                network.linkDownstreamKbps,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}

/**
 * Per-application attribution from `NetworkStatsManager`.
 *
 * Three distinct unavailable states are possible and each gets its own wording: no
 * usage access at all, usage access but no phone-state permission (so Wi-Fi only, and
 * the total is genuinely partial), and a platform refusal. Section 42 forbids
 * collapsing them into one "not available".
 */
@Composable
private fun PerAppCard(
    state: NetworkViewModel.State,
    onOpenApp: (String) -> Unit,
    onSelectWindow: (NetworkViewModel.Window) -> Unit,
    onReload: () -> Unit,
    onGrantUsageAccess: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Per-application traffic",
            subtitle = "From the platform's own statistics database",
            trailing = { ActionText("Reload", onClick = onReload) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NetworkViewModel.Window.entries.forEach { window ->
                Chip(
                    text = window.label,
                    selected = state.window == window,
                    onClick = { onSelectWindow(window) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        val perApp = state.perApp
        val mobileMissing = state.capabilities?.hasUsageAccess == true &&
            state.capabilities?.hasPhoneStatePermission == false

        when {
            state.isLoadingPerApp -> LoadingBlock(label = "Reading network statistics")

            perApp == null -> Text(
                "Not read yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            perApp is Observed.Value && perApp.value.isEmpty() -> Text(
                "No application moved any bytes in this window.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            perApp is Observed.Value -> {
                if (mobileMissing) {
                    NoticeBanner(
                        text = "Wi-Fi traffic only. Mobile-data attribution needs the " +
                            "phone-state permission on Android 10 and above.",
                        severity = EventSeverity.INFO,
                        detail = "NetworkStatsManager.querySummary for TYPE_MOBILE " +
                            "requires READ_PHONE_STATE to resolve a subscriber. " +
                            "ProcessLens skips that query rather than reporting a " +
                            "figure it knows is incomplete without saying so.",
                    )
                    Spacer(Modifier.height(8.dp))
                }
                val rows = perApp.value.sortedByDescending { it.totalBytes }.take(40)
                val heaviest = rows.firstOrNull()?.totalBytes?.takeIf { it > 0 } ?: 1L
                rows.forEach { usage ->
                    Column(Modifier.padding(vertical = 2.dp)) {
                        ListRow(
                            title = usage.appLabel ?: usage.packageName ?: ("UID " + usage.uid),
                            subtitle = "↓ " + Formatters.bytes(usage.rxBytes) +
                                "   ↑ " + Formatters.bytes(usage.txBytes),
                            onClick = usage.packageName?.let { pkg -> { onOpenApp(pkg) } },
                            trailing = {
                                Text(
                                    Formatters.bytes(usage.totalBytes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                        )
                        MeterBar(
                            fraction = usage.totalBytes.toFloat() / heaviest,
                            color = ProcessLensTheme.accent.base,
                            height = 3.dp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ExpandableDetail(
                    summary = "How these numbers are attributed",
                    detail = "Android tags every socket with the UID that opened it and " +
                        "accumulates the bytes per UID. ProcessLens queries that " +
                        "summary for the selected window and resolves each UID to a " +
                        "package name.\n\n" +
                        "Where several apps share a UID, the traffic cannot be split " +
                        "further — the platform never recorded which of them it belonged " +
                        "to. Such rows are labelled by UID.\n\n" +
                        "The figures will not sum exactly to the device counters above: " +
                        "they cover a different time range and exclude traffic the " +
                        "kernel could not attribute.",
                )
            }

            perApp is Observed.Restricted &&
                state.capabilities?.hasUsageAccess == false -> {
                Text(
                    "Per-application traffic needs usage access — a permission you grant " +
                        "once in Android's settings. Nothing else on this screen requires it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ActionText("Open usage access settings", onClick = onGrantUsageAccess)
            }

            else -> NotAvailable(
                observed = perApp,
                what = "Per-application traffic",
                style = UnavailableStyle.FULL,
            )
        }
    }
}

/**
 * Sends the user to the usage-access page.
 *
 * The per-app deep link is missing on some OEM builds, so this falls back to the global
 * list and then to app details; and the whole thing is guarded because an unresolvable
 * intent would otherwise crash on a tap.
 */
private fun openUsageAccessSettings(context: Context) {
    runCatching {
        val perApp = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (perApp.resolveActivityInfo(context.packageManager, 0) != null) {
            context.startActivity(perApp)
            return
        }
        val global = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (global.resolveActivityInfo(context.packageManager, 0) != null) {
            context.startActivity(global)
            return
        }
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
