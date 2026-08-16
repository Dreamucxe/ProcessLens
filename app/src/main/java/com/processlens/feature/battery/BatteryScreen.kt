package com.processlens.feature.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
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
import com.processlens.core.designsystem.RingGauge
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.SourceFootnote
import com.processlens.core.designsystem.UnavailableStyle
import com.processlens.domain.model.EventSeverity

/**
 * The battery screen (Section 17).
 *
 * The design constraint here is subtractive. Every other battery app on the store
 * shows "4h 12m remaining", a health percentage, and a mAh capacity. Android exposes
 * none of those to a normal app: `BATTERY_PROPERTY_CURRENT_NOW` is unreliable and
 * unsigned-by-convention, health is a coarse enum and not a percentage, and design
 * capacity is not in the public API at all. Section 17 forbids inventing them, so this
 * screen shows what the platform actually reports and says plainly what it does not.
 */
@Composable
fun BatteryScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BatteryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val system = state.system

    Column(modifier) {
        ScreenHeader(
            title = "Battery",
            subtitle = system?.let {
                it.battery.status.label + " · " + it.battery.levelPercent + "%"
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
            LoadingBlock(label = "Reading battery state")
            return@Column
        }

        val battery = system.battery

        ScreenBody {
            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            // ---- Level ----
            GlassCard {
                SectionHeader(
                    title = battery.status.label,
                    subtitle = if (battery.isCharging) {
                        "Source: " + battery.chargingSource.label
                    } else {
                        "Not connected to power"
                    },
                    trailing = {
                        if (battery.isPowerSaveMode) {
                            Chip(text = "Power saver on", icon = Icons.Outlined.BatteryChargingFull)
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge(
                        fraction = battery.levelPercent / 100f,
                        label = "Charge",
                        centerText = battery.levelPercent.toString() + "%",
                        color = ProcessLensTheme.accent.base,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        DetailRow(label = "Level", value = battery.levelPercent.toString() + "%")
                        DetailRow(
                            label = "Screen",
                            value = if (battery.isScreenOn) "On" else "Off",
                        )
                        ObservedRow(label = "Health", observed = battery.health) { it.label }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // The single most important sentence on this screen.
                Text(
                    "Android reports battery health as one of a handful of states — good, " +
                        "cold, overheating, over-voltage, dead — and not as a percentage. " +
                        "ProcessLens shows the state the platform reports and does not " +
                        "convert it into a wear figure, because no such figure is exposed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Observed drain ----
            GlassCard {
                SectionHeader(
                    title = "Observed while you watched",
                    subtitle = "Measured from two real level readings",
                )
                val drain = state.observedDrainPercent
                val window = state.observedWindowMillis
                if (drain != null && window != null && window > 0) {
                    DetailRow(
                        label = "Drop",
                        value = "$drain percentage points",
                    )
                    DetailRow(
                        label = "Over",
                        value = Formatters.durationCoarse(window),
                        monospace = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This is an observation over a short window, not a prediction. " +
                            "ProcessLens does not estimate remaining time: doing so needs a " +
                            "discharge model that Android does not provide and that a " +
                            "few minutes of watching cannot supply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        if (battery.isCharging) {
                            "The battery is charging, so there is no drain to report."
                        } else {
                            "The level has not changed since this screen opened. A drain " +
                                "figure will appear once a real change is observed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Level trend ----
            if (state.levelHistory.size >= 2) {
                GlassCard {
                    SectionHeader(title = "Level trend")
                    LabelledChart(
                        values = state.levelHistory,
                        maxValue = 100f,
                        formatAxis = { it.toInt().toString() + "%" },
                        footer = "One point per refresh. Android reports whole percentages, " +
                            "so a flat line simply means the level has not crossed a point.",
                    )
                }
            }

            // ---- Temperature ----
            GlassCard {
                SectionHeader(
                    title = "Temperature",
                    subtitle = "From the platform's battery broadcast",
                )
                val temperature = battery.temperatureDeciCelsius.valueOrNull
                if (temperature != null) {
                    DetailRow(
                        label = "Now",
                        value = Formatters.temperature(temperature),
                        monospace = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 0–60 °C: the range a phone battery realistically occupies. A
                    // 0–100 scale would flatten every real variation into nothing.
                    MeterBar(
                        fraction = (temperature / 10f) / 60f,
                        color = when {
                            temperature >= 450 -> MaterialTheme.colorScheme.error
                            temperature >= 400 -> ProcessLensTheme.accent.bright
                            else -> ProcessLensTheme.accent.base
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Scale 0–60 °C.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.temperatureHistory.count { it != null } >= 2) {
                        Spacer(Modifier.height(10.dp))
                        LabelledChart(
                            values = state.temperatureHistory,
                            maxValue = 600f,
                            color = ProcessLensTheme.accent.bright,
                            formatAxis = { Formatters.temperature(it.toInt()) },
                        )
                    }
                } else {
                    NotAvailable(
                        observed = battery.temperatureDeciCelsius,
                        what = "Temperature",
                        style = UnavailableStyle.REASON,
                    )
                }
            }

            // ---- Electrical readings ----
            GlassCard {
                SectionHeader(
                    title = "Electrical readings",
                    subtitle = "Only where this device actually reports them",
                )
                ObservedRow(
                    label = "Voltage",
                    observed = battery.voltageMilliVolts,
                    monospace = true,
                ) { Formatters.voltage(it) }
                ObservedRow(
                    label = "Current",
                    observed = battery.currentMicroAmps,
                    monospace = true,
                ) { Formatters.currentMicroAmps(it) }
                ObservedRow(
                    label = "Charge counter",
                    observed = battery.chargeCounterMicroAh,
                    monospace = true,
                ) { Formatters.count(it / 1000, "mAh", "mAh") }
                ObservedRow(
                    label = "Energy counter",
                    observed = battery.energyCounterNanoWattHours,
                    monospace = true,
                ) { String.format(java.util.Locale.US, "%.2f Wh", it / 1_000_000_000.0) }
                ObservedRow(label = "Technology", observed = battery.technology) { it }

                Spacer(Modifier.height(8.dp))
                ExpandableDetail(
                    summary = "Why some of these are missing",
                    detail = "Current is read through BatteryManager.BATTERY_PROPERTY_" +
                        "CURRENT_NOW. Its sign convention is left to the manufacturer, " +
                        "many devices return 0, and some report milliamps where the " +
                        "documentation says microamps.\n\n" +
                        "Charge and energy counters are optional. Where a device does " +
                        "not implement them the platform returns Integer.MIN_VALUE or 0, " +
                        "which ProcessLens treats as 'not reported' rather than as a " +
                        "measurement.\n\n" +
                        "No figure on this screen is computed from another. If a value " +
                        "is missing, it is missing.",
                )
            }

            // ---- Per-app attribution ----
            PerAppCard(state = state, onOpenApp = onOpenApp, onRetry = viewModel::loadPerApp)

            SourceFootnote(
                battery.health,
                battery.temperatureDeciCelsius,
                battery.voltageMilliVolts,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}

/**
 * Per-app battery attribution.
 *
 * The figures come from `dumpsys batterystats`, which is the platform's own
 * computation against its own power profile. ProcessLens parses them and shows them;
 * it does not compute a mAh figure for any app itself, because it has no power profile
 * to compute one against (Section 17).
 */
@Composable
private fun PerAppCard(
    state: BatteryViewModel.State,
    onOpenApp: (String) -> Unit,
    onRetry: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Per-application drain",
            subtitle = "Android's own attribution, not an estimate by ProcessLens",
            trailing = { ActionText("Reload", onClick = onRetry) },
        )

        val perApp = state.perApp
        when {
            state.isLoadingPerApp -> LoadingBlock(label = "Reading battery statistics")

            perApp == null -> Text(
                "Not read yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            perApp is Observed.Value && perApp.value.isEmpty() -> Text(
                "The platform returned no per-application attribution. This is normal " +
                    "shortly after a charge, when the statistics have been reset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            perApp is Observed.Value -> {
                val heaviest = perApp.value.maxOfOrNull { it.milliampHours } ?: 1.0
                perApp.value
                    .sortedByDescending { it.milliampHours }
                    .take(20)
                    .forEach { usage ->
                        ListRow(
                            title = usage.displayName,
                            subtitle = usage.percentOfComputedDrain.valueOrNull
                                ?.let { Formatters.percentValue(it) + " of computed drain" }
                                ?: ("UID " + usage.uid),
                            onClick = usage.packageName?.let { pkg -> { onOpenApp(pkg) } },
                            trailing = {
                                Text(
                                    String.format(
                                        java.util.Locale.US,
                                        "%.1f mAh",
                                        usage.milliampHours,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            leading = {
                                Column(Modifier.width(3.dp)) {
                                    MeterBar(
                                        fraction = (usage.milliampHours / heaviest).toFloat(),
                                        color = ProcessLensTheme.accent.base,
                                        height = 3.dp,
                                    )
                                }
                            },
                        )
                        usage.breakdown?.let { breakdown ->
                            Row(
                                Modifier.padding(start = 14.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    breakdown,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Figures are the platform's, parsed verbatim from its own dump. " +
                        "Where a row shows a breakdown, that string is what the system " +
                        "reported.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> NotAvailable(
                observed = perApp,
                what = "Per-application drain",
                style = UnavailableStyle.FULL,
            )
        }
    }
}
