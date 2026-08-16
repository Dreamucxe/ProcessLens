package com.processlens.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.HairlineDivider
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.Favorite
import com.processlens.domain.model.FavoriteType

/**
 * Favourites (Section 31).
 *
 * Pins grouped by what they point at, each carrying the result of a real lookup. A pin
 * whose app has been uninstalled says so and offers to be removed; a pinned process whose
 * run state cannot be determined at this access level says *that*, rather than being
 * quietly drawn as stopped.
 */
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenProcess: (String) -> Unit,
    onOpenTimeline: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier) {
        ScreenHeader(
            title = "Favourites",
            subtitle = state.resolvedAt?.let { "Checked at " + Formatters.clockTimeShort(it) },
            onBack = onBack,
            actions = {
                ActionText(
                    if (state.isResolving) "Checking…" else "Re-check",
                    onClick = viewModel::resolve,
                    enabled = !state.isResolving,
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

            if (state.isEmpty) {
                EmptyState(
                    message = "Nothing pinned yet. The star on a process, an app, or a " +
                        "finished recording pins it here.",
                    action = { ActionText("Back", onClick = onBack) },
                )
                return@ScreenBody
            }

            SummaryCard(state = state, onRemoveGone = viewModel::removeGone)

            FavoriteType.entries.forEach { type ->
                val entries = state.byType(type)
                if (entries.isNotEmpty()) {
                    TypeCard(
                        type = type,
                        entries = entries,
                        onOpen = { favorite ->
                            when (favorite.type) {
                                FavoriteType.APPLICATION -> onOpenApp(favorite.key)
                                FavoriteType.PROCESS -> onOpenProcess(favorite.key)
                                FavoriteType.INVESTIGATION ->
                                    favorite.key.toLongOrNull()?.let(onOpenTimeline)
                            }
                        },
                        onRemove = viewModel::remove,
                    )
                }
            }

            Spacer(Modifier.height(Dimens.cardSpacing))
        }
    }
}

@Composable
private fun SummaryCard(
    state: FavoritesViewModel.State,
    onRemoveGone: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = Formatters.count(state.entries.size, "pin"),
            subtitle = if (state.liveCount > 0) {
                state.liveCount.toString() +
                    (if (state.liveCount == 1) " is" else " are") + " running now"
            } else {
                null
            },
        )

        if (state.goneCount > 0) {
            NoticeBanner(
                text = Formatters.count(state.goneCount, "pin") +
                    " no longer points anywhere. The app was uninstalled, or the " +
                    "recording was deleted.",
                severity = EventSeverity.WARNING,
                action = { ActionText("Remove them", onClick = onRemoveGone) },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (!state.processListReadable) {
            NoticeBanner(
                text = "The process list is incomplete at this access level, so pinned " +
                    "processes show \"Run state unknown\" rather than a guess.",
                severity = EventSeverity.INFO,
                detail = "From Android 9 an app may only see its own process through the " +
                        "activity manager, and from Android 10 /proc hides other " +
                        "processes. Shizuku or root restores the full list. Until then, " +
                        "ProcessLens can confirm a process is running but cannot prove " +
                        "one is not.",
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            "A pin is a bookmark. It does not keep a process alive, hold a wake lock, or " +
                "run anything in the background — nothing here is monitored until you " +
                "open it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TypeCard(
    type: FavoriteType,
    entries: List<FavoritesViewModel.Entry>,
    onOpen: (Favorite) -> Unit,
    onRemove: (Favorite) -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = type.label,
            subtitle = Formatters.count(entries.size, "pin"),
        )
        entries.forEachIndexed { index, entry ->
            FavoriteRow(entry = entry, onOpen = onOpen, onRemove = onRemove)
            if (index != entries.lastIndex) HairlineDivider()
        }
    }
}

/**
 * One pin.
 *
 * The presence state is a word in a chip, and the content description spells out both the
 * state and its reason — a screen-reader user gets the same caveat a sighted user reads
 * in the chip (Sections 48, 49).
 */
@Composable
private fun FavoriteRow(
    entry: FavoritesViewModel.Entry,
    onOpen: (Favorite) -> Unit,
    onRemove: (Favorite) -> Unit,
) {
    val favorite = entry.favorite
    val gone = entry.presence == FavoritesViewModel.Presence.GONE

    Column {
        ListRow(
            title = favorite.label,
            subtitle = entry.secondary ?: entry.presence.detail,
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip(text = entry.presence.label)
                }
            },
            onClick = if (gone) null else ({ onOpen(favorite) }),
            contentDescriptionOverride = favorite.label + ". " + favorite.type.label + ". " +
                entry.presence.label + ". " + entry.presence.detail + "." +
                (if (gone) " Cannot be opened." else " Double tap to open."),
        )
        Row(
            Modifier.height(Dimens.minTouchTarget),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionText("Unpin", onClick = { onRemove(favorite) })
            Text(
                "Pinned " + Formatters.dateTime(favorite.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (gone) {
            ExpandableDetail(
                summary = "Why this cannot be opened",
                detail = "The key this pin stored is \"" + favorite.key + "\", and nothing " +
                    "on the device answers to it now. ProcessLens keeps the pin rather " +
                    "than silently deleting it, so you can see what was lost.",
            )
        }
    }
}
