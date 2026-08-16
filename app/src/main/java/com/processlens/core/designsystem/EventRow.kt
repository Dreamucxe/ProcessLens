package com.processlens.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.processlens.core.common.Formatters
import com.processlens.domain.model.InvestigationEvent

/**
 * One row on the event timeline (Sections 0.1, 13).
 *
 * The visual grammar Section 0.1 asks for: a coloured dot on a vertical rail, the
 * time to its left in monospace so the column does not jitter, then the event. The
 * dot's *shape* also varies with severity (see [TimelineDot]) so the timeline is
 * readable without colour vision, and the severity word is in the accessibility
 * description even when the badge is not shown.
 *
 * [showRail] draws the connecting line; the last row in a list passes false so the
 * rail does not run off into empty space.
 */
@Composable
fun EventRow(
    event: InvestigationEvent,
    modifier: Modifier = Modifier,
    showRail: Boolean = true,
    showRelativeTo: Long? = null,
    onClick: (() -> Unit)? = null,
    expanded: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val time = if (showRelativeTo != null) {
        "+" + Formatters.duration(event.timestamp - showRelativeTo)
    } else {
        Formatters.clockTimeShort(event.timestamp)
    }

    val description = buildString {
        append(time)
        append(", ")
        append(event.severity.label)
        append(": ")
        append(event.title)
        append(". ")
        append(event.detail)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            // Intrinsic height so the rail can fill the row: without it, a weighted
            // child inside an unbounded column collapses to nothing and the timeline
            // becomes a column of disconnected dots.
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(
            time,
            style = MonoStyle,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .width(if (showRelativeTo != null) 58.dp else 52.dp)
                .padding(top = 2.dp),
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))

        // The rail column. The dot sits at the top so it aligns with the first line
        // of the title rather than floating in the middle of a two-line row.
        Column(
            modifier = Modifier
                .width(Dimens.timelineDot)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(3.dp))
            TimelineDot(event.severity)
            if (showRail) {
                Box(
                    Modifier
                        .width(Dimens.timelineRail)
                        .weight(1f)
                        .background(scheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.width(10.dp))

        Column(
            Modifier
                .weight(1f)
                .padding(bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Chip(text = event.type.group.label)
            }
            Text(
                event.detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = if (expanded) 6 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeverityBadge(event.severity)
                    event.processName?.let {
                        Spacer(Modifier.width(6.dp))
                        Chip(text = it)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // The evidence string is the whole point of the expanded state: an
                // event is a claim, and this is the measurement behind it.
                ExpandableDetail(
                    summary = "Evidence",
                    detail = buildString {
                        appendLine(event.evidence)
                        event.value?.let { appendLine("Measured value: $it") }
                        event.previousValue?.let { appendLine("Previous value: $it") }
                        append("Recorded at ")
                        append(Formatters.dateTime(event.timestamp))
                    },
                )
            }
        }
    }
}