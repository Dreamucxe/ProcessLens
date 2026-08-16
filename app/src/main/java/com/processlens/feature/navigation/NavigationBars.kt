package com.processlens.feature.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.Radii
import com.processlens.core.designsystem.readableForeground
import com.processlens.core.designsystem.rememberHaptics

/**
 * Bottom navigation for phones (Section 33).
 *
 * Hand-built rather than Material's `NavigationBar` for one reason: the centre item
 * has to be visually promoted — a filled accent disc lifted above the bar — and
 * `NavigationBar` distributes identical items with no hook for that. Everything else
 * follows Material's behaviour: `selectable` with `Role.Tab`, a selected-state label,
 * and 48dp minimum targets.
 */
@Composable
fun ProcessLensBottomBar(
    current: String?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val glass = ProcessLensTheme.glass

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (glass.enabled) scheme.surface.copy(alpha = 0.92f) else scheme.surfaceContainer,
            ),
    ) {
        // Hairline above the bar, so it separates from a scrolling list without a
        // shadow (shadows on a near-black canvas are invisible anyway).
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dimens.hairline)
                .background(scheme.outlineVariant),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                BottomBarItem(
                    destination = destination,
                    selected = current == destination.route,
                    showBadge = destination == TopLevelDestination.INVESTIGATE && isRecording,
                    onClick = { onNavigate(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    destination: TopLevelDestination,
    selected: Boolean,
    showBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = ProcessLensTheme.accent
    val motion = ProcessLensTheme.motion
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()

    val tint = when {
        destination.isCentre -> accent.base.readableForeground()
        selected -> if (ProcessLensTheme.isDark) accent.onDarkText else accent.onLightText
        else -> scheme.onSurfaceVariant
    }

    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = tween(motion.duration(160)),
        label = "navItemScale",
    )

    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (destination.isCentre) {
            // The promoted centre action. A filled disc rather than a bare icon, so
            // it reads as the primary action of the whole app.
            Box(
                Modifier
                    .size(Dimens.minTouchTarget)
                    .scale(if (motion.enabled) scale else 1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) accent.base else accent.bright),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconLarge),
                    tint = tint,
                )
                if (showBadge) {
                    // Recording indicator. Paired with the "Recording" label below,
                    // so the state is never carried by the dot alone (Section 49).
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFE5484D)),
                    )
                }
            }
        } else {
            Box(
                Modifier
                    .size(Dimens.minTouchTarget)
                    .scale(if (motion.enabled) scale else 1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconLarge),
                    tint = tint,
                )
            }
        }
        Text(
            if (showBadge) "Recording" else destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected || destination.isCentre) {
                if (ProcessLensTheme.isDark) accent.onDarkText else accent.onLightText
            } else {
                scheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/**
 * Navigation rail for tablets and landscape (Section 33).
 *
 * The same destinations in the same order; the centre item keeps its accent disc so
 * the two form factors do not teach different visual languages.
 */
@Composable
fun ProcessLensRail(
    current: String?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = ProcessLensTheme.accent
    val haptics = rememberHaptics()

    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(84.dp)
                .fillMaxHeight()
                .background(scheme.surfaceContainerLow)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = current == destination.route
                val tint = when {
                    destination.isCentre -> accent.base.readableForeground()
                    selected -> if (ProcessLensTheme.isDark) accent.onDarkText else accent.onLightText
                    else -> scheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radii.tile))
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = {
                                haptics.tick()
                                onNavigate(destination)
                            },
                        )
                        .then(
                            if (selected && !destination.isCentre) {
                                Modifier.background(accent.container)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(Dimens.minTouchTarget)
                            .then(
                                if (destination.isCentre) {
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selected) accent.base else accent.bright)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            destination.icon,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconLarge),
                            tint = tint,
                        )
                        if (destination == TopLevelDestination.INVESTIGATE && isRecording) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFFE5484D)),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (destination == TopLevelDestination.INVESTIGATE && isRecording) {
                            "Recording"
                        } else {
                            destination.label
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            if (ProcessLensTheme.isDark) accent.onDarkText else accent.onLightText
                        } else {
                            scheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            Modifier
                .width(Dimens.hairline)
                .fillMaxHeight()
                .background(scheme.outlineVariant),
        )
    }
}

/** Small clickable text row used by the rail's overflow and the overview shortcuts. */
@Composable
fun ShortcutRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.chip))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconMedium),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
