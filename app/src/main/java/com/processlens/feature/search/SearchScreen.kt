package com.processlens.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.HairlineDivider
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.Radii
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.domain.model.EventSeverity

/**
 * Global search (Section 30).
 *
 * Three corpora, one query, and an explicit statement of what was searched. The last part
 * matters more than it looks: on a modern Android an ordinary app cannot enumerate other
 * processes, so "no results" for a process name is frequently a fact about ProcessLens'
 * access rather than about the device. The footer says which of the two it was.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenProcess: (String) -> Unit,
    onOpenTimeline: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    // The user came here to type. Anything else would be a wasted tap.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(modifier) {
        ScreenHeader(
            title = "Search",
            subtitle = "Apps, processes and recordings on this device",
            onBack = onBack,
            actions = {
                if (query.isNotEmpty()) {
                    ActionText("Clear", onClick = viewModel::clear)
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

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("Search") },
                placeholder = { Text("chrome, com.android, RenderThread…") },
                singleLine = true,
                shape = RoundedCornerShape(Radii.chip),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchViewModel.Scope.entries.forEach { scope ->
                    Chip(
                        text = scope.label,
                        selected = state.scope == scope,
                        onClick = { viewModel.setScope(scope) },
                    )
                }
            }

            if (state.isLoading) {
                LoadingBlock(label = "Reading what there is to search")
                return@ScreenBody
            }

            if (!state.hasQuery) {
                CorpusCard(corpus = state.corpus, onReload = viewModel::reload)
                return@ScreenBody
            }

            if (state.isEmptyResult) {
                NoResultsCard(state = state)
                CorpusCard(corpus = state.corpus, onReload = viewModel::reload)
                return@ScreenBody
            }

            if (state.appHits.isNotEmpty()) {
                GlassCard {
                    SectionHeader(
                        title = "Apps",
                        subtitle = sectionSubtitle(state.appHits.size, state.corpus.appCount),
                    )
                    state.appHits.forEachIndexed { index, hit ->
                        ListRow(
                            title = hit.title,
                            subtitle = hit.subtitle,
                            onClick = { onOpenApp(hit.app.packageName) },
                            trailing = {
                                if (hit.app.isSystemApp) Chip(text = "System")
                            },
                        )
                        if (index != state.appHits.lastIndex) HairlineDivider()
                    }
                }
            }

            if (state.processHits.isNotEmpty()) {
                GlassCard {
                    SectionHeader(
                        title = "Processes",
                        subtitle = sectionSubtitle(
                            state.processHits.size,
                            state.corpus.processCount,
                        ),
                    )
                    state.processHits.forEachIndexed { index, hit ->
                        ListRow(
                            title = hit.title,
                            subtitle = hit.subtitle,
                            onClick = { onOpenProcess(hit.process.id) },
                            trailing = {
                                if (hit.process.isOwnProcess) {
                                    Chip(text = "ProcessLens")
                                } else if (hit.process.isSystem) {
                                    Chip(text = "System")
                                }
                            },
                        )
                        if (index != state.processHits.lastIndex) HairlineDivider()
                    }
                    if (!state.corpus.processListComplete) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Searched only the part of the process table this device lets " +
                                "ProcessLens see. Something matching may be running and " +
                                "absent from these results.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.recordingHits.isNotEmpty()) {
                GlassCard {
                    SectionHeader(
                        title = "Recordings",
                        subtitle = sectionSubtitle(
                            state.recordingHits.size,
                            state.corpus.recordingCount,
                        ),
                    )
                    state.recordingHits.forEachIndexed { index, hit ->
                        ListRow(
                            title = hit.title,
                            subtitle = hit.subtitle + "  ·  " +
                                Formatters.dateTime(hit.investigation.startedAt),
                            onClick = { onOpenTimeline(hit.investigation.id) },
                        )
                        if (index != state.recordingHits.lastIndex) HairlineDivider()
                    }
                }
            }

            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}

/** "12 of 340" — or just "12" when nothing was truncated. */
private fun sectionSubtitle(shown: Int, corpusSize: Int): String {
    val capped = shown >= SearchViewModel.LIMIT_PER_SECTION
    return if (capped) {
        "Top " + shown + " of " + corpusSize + " searched"
    } else {
        Formatters.count(shown, "match", "matches")
    }
}

/**
 * What is available to search.
 *
 * Shown before the user types, so the scope of the search is known before its results
 * are. A process count of 1 is the tell that this device restricts enumeration — that
 * one process is ProcessLens itself.
 */
@Composable
private fun CorpusCard(corpus: SearchViewModel.Corpus, onReload: () -> Unit) {
    GlassCard {
        SectionHeader(
            title = "What is searched",
            subtitle = corpus.readAt?.let { "Read at " + Formatters.clockTimeShort(it) },
            trailing = { ActionText("Re-read", onClick = onReload) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CorpusColumn(
                value = corpus.appCount,
                label = "installed packages",
                modifier = Modifier.weight(1f),
            )
            CorpusColumn(
                value = corpus.processCount,
                label = "visible processes",
                modifier = Modifier.weight(1f),
            )
            CorpusColumn(
                value = corpus.recordingCount,
                label = "recordings",
                modifier = Modifier.weight(1f),
            )
        }

        if (!corpus.processListComplete) {
            Spacer(Modifier.height(10.dp))
            NoticeBanner(
                text = "The process list is incomplete on this device, so process results " +
                    "are drawn from a partial table.",
                severity = EventSeverity.INFO,
                detail = corpus.processLimitation
                    ?: "Android restricts process enumeration; Shizuku or root restores it.",
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Matching runs on this device against these three lists. Nothing is sent " +
                "anywhere, and no search history is kept — closing this screen forgets " +
                "the query.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CorpusColumn(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * An empty result, explained.
 *
 * "No matches" and "nothing to match against" are different answers, and which one this
 * is depends on the scope and on whether the process table was readable.
 */
@Composable
private fun NoResultsCard(state: SearchViewModel.State) {
    GlassCard {
        SectionHeader(title = "No matches for \"" + state.query.trim() + "\"")
        Text(
            "Nothing in " + when (state.scope) {
                SearchViewModel.Scope.ALL -> "the packages, processes or recordings"
                SearchViewModel.Scope.APPS -> "the installed packages"
                SearchViewModel.Scope.PROCESSES -> "the visible processes"
                SearchViewModel.Scope.RECORDINGS -> "the stored recordings"
            } + " on this device matched.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.corpus.processListComplete &&
            (state.scope == SearchViewModel.Scope.ALL ||
                state.scope == SearchViewModel.Scope.PROCESSES)
        ) {
            Spacer(Modifier.height(10.dp))
            NoticeBanner(
                text = "This is not proof that no such process exists. Only " +
                    state.corpus.processCount +
                    " of this device's processes are visible to ProcessLens.",
                severity = EventSeverity.WARNING,
            )
        }
        Spacer(Modifier.height(8.dp))
        ExpandableDetail(
            summary = "How matching works",
            detail = "The query is matched against app labels, package names, process " +
                "names and recording names, in that order of preference. A query can be " +
                "an abbreviation: letters are matched in sequence, so \"cac\" finds " +
                "\"com.android.chrome\" and \"rt\" finds \"RenderThread\". Matches at the " +
                "start of a word or a dotted segment rank above matches buried inside " +
                "one.",
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
    }
}
