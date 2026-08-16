package com.processlens.feature.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.Radii

/**
 * Dashboard quick actions (Section 32).
 *
 * Nine destinations, in the order the spec lists them: start an investigation, then
 * Processes, CPU, Memory, Battery, Network, Apps, Permissions, Favorites.
 *
 * Two deliberate choices:
 *
 *  - **Every tile navigates to a screen that already exists.** Section 2 forbids
 *    buttons that only open a placeholder, so this grid is wired straight to the
 *    navigation graph rather than to a "coming soon" sheet.
 *  - **Starting an investigation gets its own full-width card.** Section 0.1 draws the
 *    centre nav item larger and in the accent colour because recording is the app's
 *    reason to exist; the dashboard mirrors that emphasis instead of burying the same
 *    action in a grid of nine equals.
 *
 * The tiles duplicate destinations that the stat tiles below also reach (CPU, Memory,
 * Battery, Network, Processes). That is intentional and not redundancy: a stat tile is
 * a *reading* you tap to explain, and a quick action is a *place you meant to go*. The
 * stat tile is unavailable-aware and shows "Not available" when a metric cannot be
 * read, at which point it stops looking tappable — the quick action still gets you to
 * the screen, which is where the limitation is explained.
 */
@Composable
internal fun QuickActions(
    isRecording: Boolean,
    onOpenInvestigate: () -> Unit,
    onOpenProcesses: () -> Unit,
    onOpenCpu: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Section 32's order, minus the investigation card which is promoted above the grid.
    val actions = listOf(
        QuickAction("Processes", Icons.AutoMirrored.Outlined.List, "Process list", onOpenProcesses),
        QuickAction("CPU", Icons.Outlined.DeveloperBoard, "CPU detail", onOpenCpu),
        QuickAction("Memory", Icons.Outlined.Memory, "Memory detail", onOpenMemory),
        QuickAction("Battery", Icons.Outlined.BatteryStd, "Battery detail", onOpenBattery),
        QuickAction("Network", Icons.Outlined.SwapVert, "Network detail", onOpenNetwork),
        QuickAction("Apps", Icons.Outlined.Apps, "Installed applications", onOpenApps),
        QuickAction("Permissions", Icons.Outlined.Shield, "Permission inspector", onOpenPermissions),
        QuickAction("Favorites", Icons.Outlined.Star, "Favourites", onOpenFavorites),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.tileSpacing),
    ) {
        InvestigationAction(isRecording = isRecording, onClick = onOpenInvestigate)

        // Four across, two rows. Chunking rather than a LazyVerticalGrid because the
        // set is fixed at eight and nesting a lazy grid inside the dashboard's
        // scrolling column would need an explicit height to resolve its constraints.
        actions.chunked(COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileSpacing)) {
                row.forEach { action ->
                    QuickActionTile(action = action, modifier = Modifier.weight(1f))
                }
                // Keeps the last row's tiles the same width as the first row's when the
                // count is not a multiple of four.
                repeat(COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private const val COLUMNS = 4

private data class QuickAction(
    val label: String,
    val icon: ImageVector,
    /** Spoken description; the visible label is often too terse on its own ("CPU"). */
    val description: String,
    val onClick: () -> Unit,
)

/**
 * The promoted card.
 *
 * Its wording follows the actual state. Offering "Start investigation" while one is
 * already recording would describe something the tap does not do — the Investigate
 * screen shows the live recording and its stop control instead — so the label says so.
 */
@Composable
private fun InvestigationAction(isRecording: Boolean, onClick: () -> Unit) {
    val accent = ProcessLensTheme.accent
    val dark = ProcessLensTheme.isDark
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.card)

    val title = if (isRecording) "Investigation in progress" else "Start investigation"
    val subtitle = if (isRecording) {
        "Open the live timeline, add a note, or stop recording"
    } else {
        "Sample CPU, memory, battery and network into a timeline"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.container, shape)
            .border(Dimens.hairline, accent.base.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .heightIn(min = Dimens.minTouchTarget)
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(accent.base.copy(alpha = if (dark) 0.30f else 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.CenterFocusStrong,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconLarge),
                tint = if (dark) accent.onDarkText else accent.onLightText,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(Dimens.iconMedium),
            tint = if (dark) accent.onDarkText else accent.onLightText,
        )
    }
}

/**
 * One rounded tile.
 *
 * `minLines = 2` on the label is what keeps the two rows aligned: "CPU" and
 * "Permissions" would otherwise produce tiles of different heights, and the grid would
 * look broken at larger font scales rather than merely tight.
 */
@Composable
private fun QuickActionTile(action: QuickAction, modifier: Modifier = Modifier) {
    val accent = ProcessLensTheme.accent
    val dark = ProcessLensTheme.isDark
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.tile))
            .clickable(onClick = action.onClick)
            .semantics(mergeDescendants = true) { contentDescription = action.description }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainer,
                )
                .border(
                    Dimens.hairline,
                    scheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(15.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconLarge),
                tint = if (dark) accent.onDarkText else accent.onLightText,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            minLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
