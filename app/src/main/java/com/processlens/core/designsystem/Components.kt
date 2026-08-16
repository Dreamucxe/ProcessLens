package com.processlens.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.processlens.domain.model.Availability
import com.processlens.domain.model.EventSeverity

/**
 * The shared component vocabulary (Sections 3, 35, 48, 49).
 *
 * Two rules run through all of it:
 *
 * 1. **Never colour alone.** Every state-bearing component takes an icon and a text
 *    label as well as a colour, because Section 49 forbids encoding meaning in hue.
 *    The colour is a scanning aid on top of a label that already says the thing.
 * 2. **Cheap to draw.** These render on every refresh tick. No blur passes, no
 *    per-frame allocation, no nested scroll containers (Section 43).
 */

/**
 * The glass card (Section 3).
 *
 * Built from a translucent fill over the canvas plus a hairline top-edge highlight,
 * which is what actually reads as glass — the eye reads the bright edge, not the
 * blur. See [GlassSpec] for why real blur is not used.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.card),
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(Dimens.cardPadding),
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val glass = ProcessLensTheme.glass
    val dark = ProcessLensTheme.isDark
    val scheme = MaterialTheme.colorScheme

    val fill = if (glass.enabled) {
        scheme.surface.copy(alpha = glass.fillAlpha)
    } else {
        scheme.surfaceContainer
    }

    // The highlight runs top-to-transparent over roughly the first third: a light
    // source above the card, which is where every physical glass panel gets its
    // sheen. Bottom-lighting looks synthetic.
    val highlight = if (glass.enabled) {
        Brush.verticalGradient(
            0f to (if (dark) Color.White else Color.White).copy(alpha = glass.highlightAlpha),
            0.35f to Color.Transparent,
        )
    } else {
        null
    }

    val base = Modifier
        .clip(shape)
        .background(fill, shape)
        .then(
            if (highlight != null) Modifier.background(highlight, shape) else Modifier,
        )

    Surface(
        modifier = modifier
            .then(base)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = Color.Transparent,
        shape = shape,
        border = BorderStroke(
            Dimens.hairline,
            scheme.outlineVariant.copy(alpha = if (glass.enabled) glass.borderAlpha else 1f),
        ),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** Alias so [GlassCard]'s content slot reads naturally without importing ColumnScope. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * A rounded stat tile (Section 3's dashboard grid, Section 5).
 *
 * [value] is a String because the caller has already decided how to render an
 * unavailable reading — this component never receives a raw number it might have to
 * invent a fallback for. [isUnavailable] switches the tile to the muted "Not
 * available" treatment rather than showing a dash that could be mistaken for zero.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    caption: String? = null,
    /** 0..1 for the tile's bar; null hides the bar entirely. */
    fraction: Float? = null,
    accentColor: Color? = null,
    isUnavailable: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val accent = ProcessLensTheme.accent
    val dark = ProcessLensTheme.isDark
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        isUnavailable -> scheme.onSurfaceVariant
        accentColor != null -> accentColor
        fraction != null -> loadColor(fraction, accent, dark)
        else -> accent.base
    }

    // One description for the whole tile: a screen reader should say
    // "CPU: 24%, of one core" rather than reading four disconnected fragments.
    val description = buildString {
        append(label)
        append(": ")
        append(value)
        caption?.let { append(", "); append(it) }
    }

    GlassCard(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(Radii.tile),
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconSmall),
                    tint = tint,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            style = if (isUnavailable) MaterialTheme.typography.titleMedium else MetricStyle,
            color = if (isUnavailable) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (fraction != null && !isUnavailable) {
            Spacer(Modifier.height(10.dp))
            MeterBar(fraction = fraction, color = tint)
        }
    }
}

/**
 * A horizontal meter. Animated only when motion is enabled; with motion off the
 * value snaps, which is also what a user watching a 1-second refresh rate wants.
 */
@Composable
fun MeterBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 6.dp,
    trackColor: Color? = null,
) {
    val motion = ProcessLensTheme.motion
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = motion.duration(320)),
        label = "meter",
    )
    val track = trackColor ?: MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Radii.bar))
            .background(track)
            // The bar duplicates a figure already shown as text next to it, so it
            // is hidden from the reader rather than announced twice.
            .clearAndSetSemantics { },
    ) {
        Box(
            Modifier
                .fillMaxWidth(if (motion.enabled) animated else target)
                .height(height)
                .clip(RoundedCornerShape(Radii.bar))
                .background(color),
        )
    }
}

/** Section header above a group of cards. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Label/value row, the workhorse of every detail screen. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    valueColor: Color? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconSmall),
                tint = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            value,
            style = if (monospace) MonoStyle else MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = if (monospace) null else FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

/**
 * A small pill. [icon] is not optional decoration when the chip carries state — see
 * [AvailabilityBadge] and [SeverityBadge], which always supply one.
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color? = null,
    containerColor: Color? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = ProcessLensTheme.accent
    val fg = when {
        color != null -> color
        selected -> accent.base.readableForeground()
        else -> scheme.onSurfaceVariant
    }
    val bg = when {
        containerColor != null -> containerColor
        selected -> accent.base
        else -> scheme.surfaceContainerHigh
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.chip))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = fg)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * Capability availability badge (Section 47).
 *
 * Icon *and* word, never a bare coloured dot: "Limited" is a meaningfully different
 * answer from "Available" and a user who cannot distinguish amber from green must
 * still be able to tell them apart.
 */
@Composable
fun AvailabilityBadge(availability: Availability, modifier: Modifier = Modifier) {
    val dark = ProcessLensTheme.isDark
    val color = availability.color(dark)
    val icon = when (availability) {
        Availability.FULL -> Icons.Outlined.CheckCircle
        Availability.LIMITED -> Icons.Outlined.RemoveCircleOutline
        Availability.UNAVAILABLE -> Icons.Outlined.Lock
    }
    Chip(
        text = availability.label,
        modifier = modifier,
        icon = icon,
        color = color,
        containerColor = color.copy(alpha = 0.14f),
    )
}

@Composable
fun SeverityBadge(severity: EventSeverity, modifier: Modifier = Modifier) {
    val dark = ProcessLensTheme.isDark
    val color = severity.color(dark)
    Chip(
        text = severity.label,
        modifier = modifier,
        icon = severity.icon(),
        color = color,
        containerColor = color.copy(alpha = 0.14f),
    )
}

fun EventSeverity.icon(): ImageVector = when (this) {
    EventSeverity.INFO -> Icons.Outlined.Info
    EventSeverity.NORMAL -> Icons.Outlined.CheckCircle
    EventSeverity.WARNING -> Icons.Outlined.WarningAmber
    EventSeverity.CRITICAL -> Icons.Outlined.ErrorOutline
}

/**
 * The coloured timeline dot (Section 0.1's event list, Section 13).
 *
 * Shape varies with severity as well as colour — a filled disc for critical, a ring
 * for informational — so the timeline is still readable in greyscale.
 */
@Composable
fun TimelineDot(
    severity: EventSeverity,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = Dimens.timelineDot,
) {
    val color = severity.color(ProcessLensTheme.isDark)
    val filled = severity == EventSeverity.CRITICAL || severity == EventSeverity.WARNING
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else Color.Transparent)
            .then(
                if (!filled) {
                    Modifier.background(color.copy(alpha = 0.18f))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!filled) {
            Icon(
                Icons.Filled.Circle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

/**
 * An expandable "Technical details" block (Section 48).
 *
 * Errors get a plain-language sentence by default and the raw detail only on
 * demand, because a stack trace shown to everyone teaches users to ignore error
 * text — but hiding it entirely makes the app impossible to debug in the field.
 */
@Composable
fun ExpandableDetail(
    summary: String,
    detail: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
    tint: Color? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val color = tint ?: scheme.onSurfaceVariant
    val motion = ProcessLensTheme.motion

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.minTouchTarget)
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = if (expanded) {
                        "$summary. Technical details shown. Tap to hide."
                    } else {
                        "$summary. Tap to show technical details."
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium), tint = color)
            Spacer(Modifier.width(10.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconMedium),
                tint = scheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = tween(motion.duration(180)),
            ),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = tween(motion.duration(140)),
            ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(Radii.chip))
                    .background(scheme.surfaceContainerHighest)
                    .padding(10.dp),
            ) {
                Text(detail, style = MonoStyle, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Empty state: never a blank screen, always an explanation. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/**
 * An informational banner. Used for the process-list completeness note and other
 * places where a screen must qualify what it is showing (Section 42).
 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    severity: EventSeverity = EventSeverity.INFO,
    detail: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val color = severity.color(ProcessLensTheme.isDark)
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.tile))
            .background(color.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                severity.icon(),
                contentDescription = severity.label,
                modifier = Modifier.size(Dimens.iconMedium),
                tint = color,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MonoStyle,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.padding(start = 30.dp)) { action() }
        }
    }
}

/** Text button styled for this app; used instead of Material's for tighter metrics. */
@Composable
fun ActionText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color? = null,
    enabled: Boolean = true,
) {
    val accent = ProcessLensTheme.accent
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        color != null -> color
        else -> if (ProcessLensTheme.isDark) accent.onDarkText else accent.onLightText
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.chip))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Dimens.minTouchTarget)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconSmall), tint = tint)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/**
 * A dense two-line list row shared by the process and app lists.
 *
 * Kept as one component rather than two so both lists behave identically under
 * font scaling — the process list and app list having different row heights at
 * 200% text size is the kind of drift that separate implementations produce.
 */
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    contentDescriptionOverride: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.listItemHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .then(
                if (contentDescriptionOverride != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = contentDescriptionOverride
                    }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** A thin divider that respects the current outline colour. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Dimens.hairline)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Monospace text that inherits the local content colour. */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(text, modifier = modifier, style = MonoStyle, color = color ?: LocalContentColor.current)
}
