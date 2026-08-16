package com.processlens.feature.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.HairlineDivider
import com.processlens.core.designsystem.LabelledChart
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.Investigation

/**
 * Comparison mode (Section 23).
 *
 * The comparability verdict sits at the top rather than in a footnote, because it
 * governs how every row below it should be read. A pair of recordings taken at
 * different access levels can produce a large, real-looking difference that reflects
 * only what each recording was allowed to see.
 */
@Composable
fun CompareScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Side?>(null) }

    Column(modifier) {
        ScreenHeader(
            title = "Compare",
            subtitle = "Two recordings, side by side",
            onBack = onBack,
            actions = {
                if (state.isPaired) {
                    ActionText("Swap", onClick = viewModel::swap)
                }
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

            if (state.available.size < 2) {
                EmptyState(
                    message = "Comparison needs two finished recordings. There " +
                        (if (state.available.isEmpty()) "are none" else "is one") +
                        " on this device so far.",
                    action = { ActionText("Back", onClick = onBack) },
                )
                return@ScreenBody
            }

            SelectionCard(
                state = state,
                onPick = { picking = it },
                onClear = viewModel::clear,
            )

            if (state.isLoading) {
                LoadingBlock(label = "Reading both recordings")
                return@ScreenBody
            }

            if (!state.isPaired) {
                return@ScreenBody
            }

            state.comparability?.let { VerdictCard(it) }
            ChartsCard(state = state)
            TableCard(state = state)
        }
    }

    picking?.let { side ->
        PickerDialog(
            side = side,
            available = state.available,
            excludeId = if (side == Side.LEFT) state.rightId else state.leftId,
            onPick = { id ->
                if (side == Side.LEFT) viewModel.selectLeft(id) else viewModel.selectRight(id)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

private enum class Side(val label: String) {
    LEFT("First recording"),
    RIGHT("Second recording"),
}

@Composable
private fun SelectionCard(
    state: CompareViewModel.State,
    onPick: (Side) -> Unit,
    onClear: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Choose two",
            subtitle = Formatters.count(state.available.size, "finished recording") +
                " available",
            trailing = {
                if (state.leftId != null || state.rightId != null) {
                    ActionText("Clear", onClick = onClear)
                }
            },
        )
        SelectionRow(side = Side.LEFT, investigation = state.left, onPick = onPick)
        HairlineDivider()
        SelectionRow(side = Side.RIGHT, investigation = state.right, onPick = onPick)
    }
}

@Composable
private fun SelectionRow(
    side: Side,
    investigation: Investigation?,
    onPick: (Side) -> Unit,
) {
    ListRow(
        title = investigation?.name ?: side.label,
        subtitle = investigation?.let {
            Formatters.dateTime(it.startedAt) + "  ·  " +
                Formatters.durationCoarse(it.durationMillis) + "  ·  " + it.accessLevelName
        } ?: "Tap to choose",
        onClick = { onPick(side) },
        trailing = {
            ActionText(
                if (investigation == null) "Choose" else "Change",
                onClick = { onPick(side) },
            )
        },
    )
}

/**
 * How far these two recordings can be compared.
 *
 * Shown as a banner with its reasoning expanded inline, not hidden behind a tap: the
 * verdict changes the meaning of every number below it.
 */
@Composable
private fun VerdictCard(verdict: CompareViewModel.Comparability) {
    GlassCard {
        SectionHeader(title = verdict.label)
        Text(
            verdict.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (verdict == CompareViewModel.Comparability.WEAK) {
            Spacer(Modifier.height(10.dp))
            NoticeBanner(
                text = "Read the differences below with care. A figure that is missing " +
                    "on one side was not zero there — it was unobservable.",
                severity = EventSeverity.WARNING,
            )
        }
    }
}

@Composable
private fun ChartsCard(state: CompareViewModel.State) {
    val left = state.leftChart
    val right = state.rightChart
    if (left.none { it != null } && right.none { it != null }) return

    GlassCard {
        SectionHeader(
            title = "CPU over each recording",
            subtitle = "Both on a 0–100% scale, so their shapes are comparable",
        )
        Text(
            state.left?.name.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabelledChart(
            values = left,
            maxValue = 100f,
            color = ProcessLensTheme.accent.base,
            footer = Formatters.count(left.size, "sample") + " over " +
                Formatters.durationCoarse(state.left?.durationMillis ?: 0L),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            state.right?.name.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabelledChart(
            values = right,
            maxValue = 100f,
            color = ProcessLensTheme.accent.bright,
            footer = Formatters.count(right.size, "sample") + " over " +
                Formatters.durationCoarse(state.right?.durationMillis ?: 0L),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The horizontal axis is samples, not clock time. Where the two recordings " +
                "ran for different lengths, the same width of chart covers a different " +
                "span — so compare shapes and heights, not positions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableCard(state: CompareViewModel.State) {
    GlassCard {
        SectionHeader(title = "Side by side")

        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                "",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1.2f),
            )
            Text(
                state.left?.name.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Text(
                state.right?.name.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        HairlineDivider()

        state.rows.forEach { row ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1.2f),
                    )
                    Text(
                        row.left,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        row.right,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                // The delta carries an arrow *and* the word, so the direction survives
                // greyscale and screen readers (Section 49).
                row.delta?.let { delta ->
                    Text(
                        row.direction.symbol + " " + delta + " " + row.direction.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                row.note?.let { note ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Chip(text = note)
                    }
                }
            }
            HairlineDivider()
        }

        Spacer(Modifier.height(10.dp))
        ExpandableDetail(
            summary = "How these differences were computed",
            detail = "Averages and peaks are recomputed from the stored samples of each " +
                "recording, skipping any sample where the figure was unreadable. A row " +
                "showing \"" + CompareViewModel.NOT_MEASURED + "\" on one side has no " +
                "difference at all, rather than a difference against zero.\n\n" +
                "Rows marked as depending on duration are totals: they grow with the " +
                "length of the recording, so comparing them only means something when " +
                "the two recordings ran for a similar time.",
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
    }
}

@Composable
private fun PickerDialog(
    side: Side,
    available: List<Investigation>,
    excludeId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = available.filter { it.id != excludeId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(side.label) },
        text = {
            if (choices.isEmpty()) {
                Text("There is no other finished recording to choose.")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(choices, key = { it.id }) { investigation ->
                        ListRow(
                            title = investigation.name,
                            subtitle = Formatters.dateTime(investigation.startedAt) +
                                "  ·  " +
                                Formatters.durationCoarse(investigation.durationMillis) +
                                "  ·  " +
                                Formatters.count(investigation.snapshotCount, "sample"),
                            onClick = { onPick(investigation.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
