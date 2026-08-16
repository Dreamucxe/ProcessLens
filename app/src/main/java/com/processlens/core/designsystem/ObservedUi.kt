package com.processlens.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.Precision
import com.processlens.core.common.RestrictionReason
import com.processlens.core.common.unavailabilityText
import com.processlens.core.common.valueOrNull

/**
 * The UI half of the honesty contract (Section 42).
 *
 * [Observed] makes it impossible for the *data* layer to invent a value. These
 * composables make it impossible for the *presentation* layer to hide that a value
 * is missing: there is no code path here that turns a [Observed.Restricted] into a
 * dash, a zero, or an empty string. Every unavailable reading renders as the words
 * "Not available" plus the reason and, where one exists, the access level that
 * would expose it.
 *
 * Screens are written against these components rather than unwrapping `Observed`
 * themselves, so the wording of an unavailability message is identical in all
 * eleven features and cannot drift.
 */

/** How much explanation an unavailable reading should render. */
enum class UnavailableStyle {
    /** Just the words, for dense rows and tiles. */
    SHORT,

    /** Words plus the one-line reason, for cards with room. */
    REASON,

    /** Words, reason, unlock hint, and an expander with the raw technical detail. */
    FULL,
}

/**
 * Renders a reading, or explains its absence.
 *
 * [format] receives the value only when there is one, so a formatter can never be
 * handed a fabricated default.
 */
@Composable
fun <T> ObservedText(
    observed: Observed<T>,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle? = null,
    color: Color? = null,
    format: (T) -> String,
) {
    val scheme = MaterialTheme.colorScheme
    when (observed) {
        is Observed.Value -> Text(
            format(observed.value),
            modifier = modifier,
            style = style ?: MaterialTheme.typography.bodyMedium,
            color = color ?: scheme.onSurface,
        )

        else -> Text(
            NOT_AVAILABLE,
            modifier = modifier.semantics {
                contentDescription = "Not available. " + (observed.unavailabilityText() ?: "")
            },
            style = style ?: MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** [DetailRow] driven by an observation. */
@Composable
fun <T> ObservedRow(
    label: String,
    observed: Observed<T>,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    valueColor: Color? = null,
    format: (T) -> String,
) {
    val value = observed.valueOrNull
    if (value != null) {
        DetailRow(
            label = label,
            value = format(value),
            modifier = modifier,
            monospace = monospace,
            valueColor = valueColor,
        )
    } else {
        DetailRow(
            label = label,
            value = NOT_AVAILABLE,
            modifier = modifier.semantics {
                contentDescription = "$label: not available. " + (observed.unavailabilityText() ?: "")
            },
            valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = observed.icon(),
        )
    }
}

/** [StatTile] driven by an observation. */
@Composable
fun <T> ObservedTile(
    label: String,
    observed: Observed<T>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    caption: String? = null,
    fractionOf: ((T) -> Float)? = null,
    accentColor: Color? = null,
    onClick: (() -> Unit)? = null,
    format: (T) -> String,
) {
    val value = observed.valueOrNull
    StatTile(
        label = label,
        value = if (value != null) format(value) else NOT_AVAILABLE,
        modifier = modifier,
        icon = icon,
        // With no reading, the caption becomes the explanation: the tile still
        // occupies the grid so the layout does not reflow, but it says why it is
        // empty instead of implying a measurement of zero.
        caption = if (value != null) caption else observed.shortReason(),
        fraction = if (value != null && fractionOf != null) fractionOf(value) else null,
        accentColor = accentColor,
        isUnavailable = value == null,
        onClick = onClick,
    )
}

/**
 * The standalone "Not available" block (Section 42).
 *
 * Used where a whole card or section has nothing to show. Deliberately not styled as
 * an error — a restriction is normal, expected Android behaviour, and colouring it
 * red would teach users to read platform limits as app faults.
 */
@Composable
fun NotAvailable(
    observed: Observed<*>,
    modifier: Modifier = Modifier,
    what: String? = null,
    style: UnavailableStyle = UnavailableStyle.FULL,
) {
    if (observed is Observed.Value) return
    val scheme = MaterialTheme.colorScheme
    val reason = observed.unavailabilityText().orEmpty()
    val heading = if (what != null) "$what: $NOT_AVAILABLE" else NOT_AVAILABLE

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                observed.icon(),
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconMedium),
                tint = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                heading,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (style != UnavailableStyle.SHORT && reason.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (style == UnavailableStyle.FULL) {
            val unlock = (observed as? Observed.Restricted)?.unlockedBy
            if (unlock != null) {
                Spacer(Modifier.height(8.dp))
                UnlockHint(unlock)
            }
            val detail = observed.technicalDetail()
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                ExpandableDetail(summary = "Technical details", detail = detail)
            }
        }
    }
}

/**
 * "Shizuku would expose this" / "Root would expose this" (Sections 27, 28).
 *
 * Phrased as what the access level *would* provide, never as a promise that it
 * will: whether a given OEM's dumpsys actually returns the field is only knowable
 * once the shell has been asked, so the hint stops at the honest claim.
 */
@Composable
fun UnlockHint(level: AccessLevel, modifier: Modifier = Modifier) {
    val text = when (level) {
        AccessLevel.NORMAL -> "Available without elevated access."
        AccessLevel.SHIZUKU -> "Shizuku access would allow ProcessLens to read this."
        AccessLevel.ROOT -> "Root access would allow ProcessLens to read this."
    }
    val icon = when (level) {
        AccessLevel.NORMAL -> Icons.Outlined.Sensors
        AccessLevel.SHIZUKU -> Icons.Outlined.Shield
        AccessLevel.ROOT -> Icons.Outlined.Bolt
    }
    Chip(text = text, modifier = modifier, icon = icon)
}

/**
 * Provenance chip: where the figure came from, and how exact it is (Sections 7, 42).
 *
 * [Precision.EXACT] is not labelled — labelling the normal case everywhere is noise,
 * and it would make "Sampled" and "Estimated" less noticeable, which is the opposite
 * of the point.
 */
@Composable
fun SourceChip(
    source: DataSource,
    precision: Precision = Precision.EXACT,
    modifier: Modifier = Modifier,
) {
    val text = if (precision == Precision.EXACT) {
        source.label
    } else {
        "${source.label} · ${precision.label}"
    }
    Chip(text = text, modifier = modifier)
}

/** Provenance chip for an observation, rendered only when there is a real reading. */
@Composable
fun ObservedSourceChip(observed: Observed<*>, modifier: Modifier = Modifier) {
    val v = observed as? Observed.Value ?: return
    SourceChip(v.source, v.precision, modifier)
}

/**
 * A footnote naming the sources behind a screen's figures.
 *
 * Section 42 requires the app to be legible about where data comes from; a single
 * line at the bottom of a card does that without a chip on every row.
 */
@Composable
fun SourceFootnote(vararg observations: Observed<*>, modifier: Modifier = Modifier) {
    val sources = observations
        .filterIsInstance<Observed.Value<*>>()
        .map { it.source.label }
        .distinct()
    if (sources.isEmpty()) return
    Text(
        "Source: " + sources.joinToString(", "),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ----------------------------------------------------------------------- helpers

const val NOT_AVAILABLE = "Not available"

/**
 * Icon for an unavailable state. A padlock for a restriction, a warning triangle for
 * a genuine failure — the two mean different things to a user deciding whether to
 * grant Shizuku access or file a bug.
 */
fun Observed<*>.icon(): ImageVector = when (this) {
    is Observed.Value -> Icons.Outlined.Sensors
    is Observed.Failed -> Icons.Outlined.ErrorOutline
    is Observed.Restricted -> when (reason) {
        RestrictionReason.NOT_PRESENT_ON_DEVICE -> Icons.Outlined.Sensors
        RestrictionReason.SAMPLING_DISABLED -> Icons.Outlined.Settings
        else -> Icons.Outlined.Lock
    }
}

/** Two or three words for a tile caption, where the full sentence will not fit. */
fun Observed<*>.shortReason(): String? = when (this) {
    is Observed.Value -> null
    is Observed.Failed -> "Read failed"
    is Observed.Restricted -> when (reason) {
        RestrictionReason.PLATFORM_RESTRICTED -> "Restricted by Android"
        RestrictionReason.PERMISSION_REQUIRED -> "Permission required"
        RestrictionReason.NOT_PRESENT_ON_DEVICE -> "Not reported by this device"
        RestrictionReason.REQUIRES_ELEVATED_ACCESS -> "Needs elevated access"
        RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL -> "Not on this Android version"
        RestrictionReason.SAMPLING_DISABLED -> "Switched off in Settings"
    }
}

/** The raw diagnostic string for the technical-details expander (Section 48). */
fun Observed<*>.technicalDetail(): String? = when (this) {
    is Observed.Value -> null
    is Observed.Failed -> listOfNotNull(detail, cause).joinToString("\n")
    is Observed.Restricted -> detail.takeIf { it.isNotBlank() }
}

/**
 * Formats a reading, or returns the "Not available" string.
 *
 * For the handful of places that need a plain String rather than a composable —
 * export previews, accessibility descriptions, notification text.
 */
inline fun <T> Observed<T>.text(format: (T) -> String): String =
    (this as? Observed.Value)?.let { format(it.value) } ?: NOT_AVAILABLE

/**
 * Section 6's live-monitor rule: a rate needs two samples, so the first tick is
 * honestly "awaiting second sample" rather than 0%.
 */
@Composable
fun AwaitingSample(modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Sensors,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconSmall),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Awaiting second sample",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
