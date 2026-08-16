package com.processlens.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.processlens.core.common.Formatters
import kotlin.math.max

/**
 * Charts (Sections 6, 7, 8, 13).
 *
 * Drawn with [Canvas] rather than a charting library: the app ships no chart
 * dependency, these run on a 1–2 second refresh, and Section 43 makes the tool's own
 * cost a design constraint. Everything here is a handful of draw calls with the
 * paths built once per data change, not per frame.
 *
 * The series type is `List<Float?>`, and the nullability is load-bearing. A sample
 * where a reading was unavailable is a **gap in the line**, never a zero — a chart
 * that dips to the floor when data is missing tells the user CPU usage dropped, and
 * Section 42 forbids exactly that kind of invented fact. Gaps are drawn as breaks
 * with a dotted bridge so they are visibly absent rather than silently interpolated.
 */

/**
 * A filled line chart.
 *
 * @param values one entry per sample, oldest first; null where no reading existed.
 * @param maxValue the top of the y-axis. Pass a fixed value (100f for a percentage)
 *   so the chart does not silently rescale between refreshes and make a steady load
 *   look like it is changing.
 */
@Composable
fun LineChart(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    maxValue: Float = 100f,
    color: Color? = null,
    height: Dp = Dimens.chartHeight,
    fill: Boolean = true,
    gridLines: Int = 3,
    contentDescription: String? = null,
) {
    val accent = ProcessLensTheme.accent
    val scheme = MaterialTheme.colorScheme
    val lineColor = color ?: accent.base
    val gridColor = scheme.outlineVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Grid first, so the series draws over it.
            if (gridLines > 0) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 6f), 0f)
                for (i in 1..gridLines) {
                    val y = h * i / (gridLines + 1f)
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(w, y),
                        strokeWidth = 1f,
                        pathEffect = dash,
                    )
                }
            }

            if (values.size < 2) return@Canvas
            val stepX = w / (values.size - 1).toFloat()
            val safeMax = if (maxValue <= 0f) 1f else maxValue

            fun yFor(v: Float): Float =
                h - (v.coerceIn(0f, safeMax) / safeMax) * h

            // Each run of consecutive non-null samples is its own path. A gap
            // therefore breaks the stroke instead of being bridged with a straight
            // line through data that does not exist.
            var index = 0
            while (index < values.size) {
                if (values[index] == null) { index++; continue }
                var end = index
                while (end + 1 < values.size && values[end + 1] != null) end++

                if (end == index) {
                    // A lone sample between two gaps: a dot, since a one-point line
                    // has no direction to draw.
                    drawCircle(
                        color = lineColor,
                        radius = 2.2f,
                        center = androidx.compose.ui.geometry.Offset(
                            index * stepX,
                            yFor(values[index]!!),
                        ),
                    )
                } else {
                    val path = Path()
                    for (i in index..end) {
                        val x = i * stepX
                        val y = yFor(values[i]!!)
                        if (i == index) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    if (fill) {
                        val area = Path()
                        area.addPath(path)
                        area.lineTo(end * stepX, h)
                        area.lineTo(index * stepX, h)
                        area.close()
                        drawPath(
                            path = area,
                            brush = Brush.verticalGradient(
                                0f to lineColor.copy(alpha = 0.28f),
                                1f to lineColor.copy(alpha = 0.02f),
                            ),
                        )
                    }
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 2.2f, cap = StrokeCap.Round),
                    )
                }
                index = end + 1
            }

            // Mark the gaps explicitly: a faint dotted segment at mid-height, so an
            // absent stretch reads as "no data here" rather than as chart edge.
            val gapDash = PathEffect.dashPathEffect(floatArrayOf(2f, 5f), 0f)
            var g = 0
            while (g < values.size) {
                if (values[g] != null) { g++; continue }
                var end = g
                while (end + 1 < values.size && values[end + 1] == null) end++
                drawLine(
                    color = scheme.onSurfaceVariant.copy(alpha = 0.45f),
                    start = androidx.compose.ui.geometry.Offset(g * stepX, h / 2f),
                    end = androidx.compose.ui.geometry.Offset(end * stepX + stepX * 0.001f, h / 2f),
                    strokeWidth = 1.4f,
                    pathEffect = gapDash,
                )
                g = end + 1
            }
        }
    }
}

/**
 * A chart with its own axis labels and a legend for missing samples.
 *
 * Kept separate from [LineChart] so the bare chart can be embedded in a tile without
 * dragging the chrome along.
 */
@Composable
fun LabelledChart(
    values: List<Float?>,
    maxValue: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    height: Dp = Dimens.chartHeight,
    formatAxis: (Float) -> String = { Formatters.percentValue(it) },
    footer: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val missing = remember(values) { values.count { it == null } }
    val latest = remember(values) { values.lastOrNull { it != null } }
    val peak = remember(values) { values.filterNotNull().maxOrNull() }

    val description = remember(values, missing, latest, peak) {
        buildString {
            append("Chart of ${values.size} samples. ")
            latest?.let { append("Latest ${formatAxis(it)}. ") }
            peak?.let { append("Peak ${formatAxis(it)}. ") }
            if (missing > 0) append("$missing samples had no reading.")
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            // Axis labels in a fixed-width gutter rather than drawn into the Canvas:
            // real Text nodes scale with the user's font-size setting and are
            // readable by a screen reader, which text painted into a canvas is not.
            Column(
                Modifier
                    .width(46.dp)
                    .height(height),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    formatAxis(maxValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    formatAxis(maxValue / 2f),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    formatAxis(0f),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            LineChart(
                values = values,
                modifier = Modifier.weight(1f),
                maxValue = maxValue,
                color = color,
                height = height,
                contentDescription = description,
            )
        }
        if (missing > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (missing == 1) {
                    "1 sample had no reading and is shown as a gap."
                } else {
                    "$missing samples had no reading and are shown as gaps."
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (footer != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                footer,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A tiny inline trend line for list rows (Section 11's process list).
 *
 * No axes, no fill, no labels — at this size they would be illegible, and the
 * sparkline's job is only to answer "rising, falling or flat?". The row's numeric
 * column carries the actual figure.
 */
@Composable
fun Sparkline(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    maxValue: Float = 100f,
    color: Color? = null,
    width: Dp = 56.dp,
    height: Dp = 20.dp,
) {
    val lineColor = color ?: ProcessLensTheme.accent.base
    val present = remember(values) { values.count { it != null } }
    if (present < 2) {
        Spacer(modifier.size(width, height))
        return
    }
    Canvas(
        modifier
            .size(width, height)
            // The adjacent numeric column already announces the value; a second
            // announcement of the same trend would just slow a reader down.
            .semantics { contentDescription = "" },
    ) {
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1).toFloat()
        val safeMax = if (maxValue <= 0f) 1f else maxValue
        var index = 0
        while (index < values.size) {
            if (values[index] == null) { index++; continue }
            var end = index
            while (end + 1 < values.size && values[end + 1] != null) end++
            if (end > index) {
                val path = Path()
                for (i in index..end) {
                    val x = i * stepX
                    val y = h - (values[i]!!.coerceIn(0f, safeMax) / safeMax) * h
                    if (i == index) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
            }
            index = end + 1
        }
    }
}

/**
 * Per-core utilisation bars (Section 7).
 *
 * Vertical bars, one per core, because the question being asked is "is one core
 * pinned while the rest idle?" — which a set of side-by-side bars answers instantly
 * and eight separate line charts do not.
 */
@Composable
fun CoreBars(
    perCore: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
    /** Cores reported offline; drawn as an empty slot with a label, not as 0%. */
    offlineCores: Set<Int> = emptySet(),
) {
    val accent = ProcessLensTheme.accent
    val dark = ProcessLensTheme.isDark
    val scheme = MaterialTheme.colorScheme

    val description = remember(perCore, offlineCores) {
        perCore.mapIndexed { i, v ->
            if (i in offlineCores) "Core $i offline" else "Core $i ${Formatters.percentValue(v)}"
        }.joinToString(", ")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        perCore.forEachIndexed { index, value ->
            val offline = index in offlineCores
            val fraction = (value / 100f).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // Track
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(Radii.bar))
                            .background(scheme.surfaceContainerHighest),
                    )
                    if (!offline) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(max(fraction, 0.02f))
                                .clip(RoundedCornerShape(Radii.bar))
                                .background(loadColor(fraction, accent, dark)),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (offline) "—" else "$index",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One band of a [SegmentedBar]. */
data class BarSegment(val label: String, val value: Long, val color: Color)

/**
 * A stacked horizontal bar with a legend — used for the memory breakdown (Section 8)
 * and storage.
 *
 * A stacked bar rather than a pie: the parts are being compared to the whole *and*
 * to each other, and rectangles side by side are easier to compare than angles.
 */
@Composable
fun SegmentedBar(
    segments: List<BarSegment>,
    total: Long,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    showLegend: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val safeTotal = if (total <= 0L) segments.sumOf { it.value }.coerceAtLeast(1L) else total

    val description = remember(segments, safeTotal) {
        segments.joinToString(", ") {
            "${it.label} ${Formatters.bytes(it.value)}"
        }
    }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(Radii.bar))
                .background(scheme.surfaceContainerHighest)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            segments.forEach { segment ->
                val weight = (segment.value.toFloat() / safeTotal.toFloat()).coerceIn(0.0001f, 1f)
                Box(
                    Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .background(segment.color),
                )
            }
            val used = segments.sumOf { it.value }
            val free = (safeTotal - used).coerceAtLeast(0L)
            if (free > 0) {
                Box(
                    Modifier
                        .weight((free.toFloat() / safeTotal.toFloat()).coerceIn(0.0001f, 1f))
                        .fillMaxHeight(),
                )
            }
        }
        if (showLegend) {
            Spacer(Modifier.height(8.dp))
            segments.forEach { segment ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(segment.color),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        segment.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Formatters.bytes(segment.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * A ring gauge for a single dominant figure (memory pressure on the dashboard).
 *
 * The number sits inside the ring as real [Text], not painted into the canvas, so it
 * scales with the user's font size.
 */
@Composable
fun RingGauge(
    fraction: Float,
    label: String,
    centerText: String,
    modifier: Modifier = Modifier,
    size: Dp = 108.dp,
    color: Color? = null,
) {
    val accent = ProcessLensTheme.accent
    val dark = ProcessLensTheme.isDark
    val scheme = MaterialTheme.colorScheme
    val ringColor = color ?: loadColor(fraction, accent, dark)
    val safe = fraction.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .size(size)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: $centerText"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val stroke = this.size.minDimension * 0.10f
            val inset = stroke / 2f
            drawArc(
                color = scheme.surfaceContainerHighest,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    this.size.width - stroke,
                    this.size.height - stroke,
                ),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = ringColor,
                startAngle = 135f,
                sweepAngle = 270f * safe,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    this.size.width - stroke,
                    this.size.height - stroke,
                ),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, style = MetricSmallStyle, color = scheme.onSurface)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
