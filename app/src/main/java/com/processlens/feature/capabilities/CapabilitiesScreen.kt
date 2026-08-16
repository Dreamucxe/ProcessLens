package com.processlens.feature.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.Formatters
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.DetailRow
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.HairlineDivider
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.UnlockHint
import com.processlens.core.designsystem.color
import com.processlens.domain.model.Availability
import com.processlens.domain.model.CapabilityGroup
import com.processlens.domain.model.CapabilityStatus
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.SystemCapabilities

/**
 * The capability matrix (Section 47).
 *
 * The point of this screen is that every "Not available" in ProcessLens is accountable.
 * A user who sees an empty per-process CPU column can come here and find the row that
 * says so, why, and whether anything they can do would change it.
 *
 * Availability is shown as a symbol *and* a word, never colour alone (Section 49), and
 * where a probe was involved its actual result is available behind a tap — a capability
 * marked unavailable because a specific read failed says which read.
 */
@Composable
fun CapabilitiesScreen(
    onBack: () -> Unit,
    onOpenAccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CapabilitiesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier) {
        ScreenHeader(
            title = "Capabilities",
            subtitle = state.capabilities?.let {
                "API " + it.apiLevel + "  ·  " + it.accessLevel.label + " access"
            },
            onBack = onBack,
            actions = {
                ActionText(
                    if (state.isRefreshing) "Probing…" else "Re-probe",
                    onClick = viewModel::refresh,
                    enabled = !state.isRefreshing,
                )
            },
        )

        ScreenBody {
            state.error?.let {
                NoticeBanner(
                    text = it,
                    severity = EventSeverity.WARNING,
                    action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                )
            }

            val capabilities = state.capabilities
            if (capabilities == null) {
                LoadingBlock(label = "Probing this device")
                return@ScreenBody
            }

            SummaryCard(capabilities = capabilities, state = state)
            FilterCard(state = state, onGroup = viewModel::setGroup, onAvailability = viewModel::setAvailability)

            val groups = state.visible
            if (groups.isEmpty()) {
                GlassCard {
                    Text(
                        "No capability matches that filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                groups.forEach { (group, rows) ->
                    GroupCard(group = group, rows = rows)
                }
            }

            UnlockCard(capabilities = capabilities, onOpenAccess = onOpenAccess)
            DeviceCard(state = state)

            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}

// ------------------------------------------------------------------------- summary

@Composable
private fun SummaryCard(
    capabilities: SystemCapabilities,
    state: CapabilitiesViewModel.State,
) {
    GlassCard {
        SectionHeader(
            title = "What this device exposes",
            subtitle = Formatters.count(capabilities.statuses.size, "capability", "capabilities") +
                " probed",
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountColumn(
                count = capabilities.fullCount,
                availability = Availability.FULL,
                modifier = Modifier.weight(1f),
            )
            CountColumn(
                count = capabilities.limitedCount,
                availability = Availability.LIMITED,
                modifier = Modifier.weight(1f),
            )
            CountColumn(
                count = capabilities.unavailableCount,
                availability = Availability.UNAVAILABLE,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "This matrix was measured on this device, just now — not deduced from the " +
                "Android version. Two phones on API " + capabilities.apiLevel + " can " +
                "give different answers: one exposes a thermal zone and the other does " +
                "not, one lets an app read /proc and the other hides it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.isRefreshing) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Re-probing…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One of the three tallies.
 *
 * The number is above the word, and the word is the label — the colour is decoration on
 * top of a reading that already works without it.
 */
@Composable
private fun CountColumn(
    count: Int,
    availability: Availability,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            availability.symbol + " " + count,
            style = MaterialTheme.typography.titleLarge,
            color = availability.color(ProcessLensTheme.isDark),
            textAlign = TextAlign.Start,
        )
        Text(
            availability.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------------------- filters

@Composable
private fun FilterCard(
    state: CapabilitiesViewModel.State,
    onGroup: (CapabilityGroup?) -> Unit,
    onAvailability: (Availability?) -> Unit,
) {
    GlassCard {
        Text("Show", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(
                text = "All",
                selected = state.availabilityFilter == null,
                onClick = { onAvailability(null) },
            )
            Availability.entries.forEach { availability ->
                Chip(
                    text = availability.label,
                    selected = state.availabilityFilter == availability,
                    onClick = { onAvailability(availability) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Group", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(
                    text = "All",
                    selected = state.groupFilter == null,
                    onClick = { onGroup(null) },
                )
                CapabilityGroup.entries.take(4).forEach { group ->
                    Chip(
                        text = group.displayName,
                        selected = state.groupFilter == group,
                        onClick = { onGroup(group) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CapabilityGroup.entries.drop(4).forEach { group ->
                    Chip(
                        text = group.displayName,
                        selected = state.groupFilter == group,
                        onClick = { onGroup(group) },
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- groups

@Composable
private fun GroupCard(group: CapabilityGroup, rows: List<CapabilityStatus>) {
    GlassCard {
        SectionHeader(
            title = group.displayName,
            subtitle = rows.count { it.isUsable }.toString() + " of " + rows.size + " usable",
        )
        rows.forEachIndexed { index, status ->
            CapabilityRow(status)
            if (index != rows.lastIndex) HairlineDivider()
        }
    }
}

/**
 * One capability.
 *
 * The reason is not optional prose — it is the answer to the question the user came here
 * with. Where a probe produced it, [CapabilityStatus.probeDetail] holds the raw result,
 * which is what makes the claim checkable rather than merely stated.
 */
@Composable
private fun CapabilityRow(status: CapabilityStatus) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    status.capability.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    status.capability.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                status.availability.symbol + " " + status.availability.label,
                style = MaterialTheme.typography.labelMedium,
                color = status.availability.color(ProcessLensTheme.isDark),
            )
        }

        if (status.reason.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                status.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        status.unlockedBy?.let { level ->
            Spacer(Modifier.height(4.dp))
            UnlockHint(level)
        }

        status.probeDetail?.takeIf { it.isNotBlank() }?.let { detail ->
            Spacer(Modifier.height(4.dp))
            ExpandableDetail(summary = "What the probe found", detail = detail)
        }
    }
}

// --------------------------------------------------------------------------- unlock

/**
 * The capabilities a higher access level would unlock.
 *
 * Only real ones: this counts the rows whose own [CapabilityStatus.unlockedBy] names a
 * level above the current one. If nothing would change, the card says that instead of
 * advertising an upgrade that buys nothing.
 */
@Composable
private fun UnlockCard(capabilities: SystemCapabilities, onOpenAccess: () -> Unit) {
    val current = capabilities.accessLevel
    val unlockable = capabilities.statuses.values.filter { status ->
        val required = status.unlockedBy
        required != null && !(current satisfies required)
    }

    GlassCard {
        SectionHeader(title = "Access level")
        if (unlockable.isEmpty()) {
            Text(
                "Nothing on this device is waiting on a higher access level. What is " +
                    "unavailable here is unavailable because the platform does not " +
                    "expose it at all, not because ProcessLens lacks permission.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val byLevel = unlockable.groupBy { it.unlockedBy }
            Text(
                Formatters.count(unlockable.size, "capability", "capabilities") +
                    " would become readable at a higher access level. ProcessLens will " +
                    "keep reporting them as unavailable until then, rather than " +
                    "estimating them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            byLevel.forEach { (level, statuses) ->
                if (level != null) {
                    DetailRow(
                        label = "With " + level.label,
                        value = statuses.joinToString(", ") { it.capability.displayName },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            ActionText("Access level", onClick = onOpenAccess)
        }

        if (current != AccessLevel.NORMAL) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Currently using " + current.label + " access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------------- device

@Composable
private fun DeviceCard(state: CapabilitiesViewModel.State) {
    val device = state.device ?: return
    GlassCard {
        SectionHeader(title = "This device", subtitle = "What the probe ran against")
        DetailRow(label = "Model", value = device.manufacturer + " " + device.model)
        DetailRow(label = "Device", value = device.device, monospace = true)
        DetailRow(label = "Android", value = device.androidRelease + " (API " + device.apiLevel + ")")
        device.securityPatch?.let { DetailRow(label = "Security patch", value = it) }
        DetailRow(label = "Cores", value = device.coreCount.toString())
        DetailRow(label = "RAM", value = Formatters.bytes(device.totalRamBytes))
        DetailRow(label = "ABIs", value = device.supportedAbis.joinToString(", "), monospace = true)
        DetailRow(label = "Uptime", value = Formatters.durationCoarse(device.uptimeMillis))
        if (device.isEmulator) {
            Spacer(Modifier.height(8.dp))
            NoticeBanner(
                text = "This looks like an emulator. Several capabilities behave " +
                    "differently there — thermal zones and per-core frequencies in " +
                    "particular are often absent rather than restricted.",
                severity = EventSeverity.INFO,
            )
        }
    }
}
