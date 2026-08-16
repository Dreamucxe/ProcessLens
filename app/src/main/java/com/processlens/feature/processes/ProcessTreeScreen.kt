package com.processlens.feature.processes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.MonoStyle
import com.processlens.core.designsystem.NotAvailable
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.Radii
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.ScreenList
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.designsystem.UnavailableStyle
import com.processlens.core.designsystem.loadColor
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.repository.ProcessTreeNode

/**
 * Parent/child process structure (Section 12).
 *
 * When parent PIDs are not readable this screen shows one explanation and nothing
 * else. There is no fallback "grouped by package" pseudo-tree, because that would
 * look like a process hierarchy while being an invention (Section 42).
 */
@Composable
fun ProcessTreeScreen(
    onBack: () -> Unit,
    onOpenProcess: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProcessTreeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier) {
        ScreenHeader(
            title = "Process tree",
            subtitle = when (val tree = state.tree) {
                is Observed.Value -> Formatters.count(state.nodeCount, "process", "processes") +
                    " in " + Formatters.count(tree.value.size, "root", "roots")
                else -> "Derived from real parent process identifiers"
            },
            onBack = onBack,
            actions = {
                IconTapTarget(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Rebuild the tree",
                    onClick = viewModel::load,
                )
            },
        )

        if (state.isLoading && state.tree == null) {
            LoadingBlock(label = "Reading parent process identifiers")
            return@Column
        }

        val tree = state.tree
        if (tree !is Observed.Value) {
            Column(Modifier.padding(Dimens.screenPadding)) {
                GlassCard {
                    SectionHeader(title = "No hierarchy available")
                    NotAvailable(
                        observed = tree ?: Observed.Failed("Not read yet"),
                        what = "Process tree",
                        style = UnavailableStyle.FULL,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "A tree needs each process's real parent identifier. Where Android " +
                            "withholds that, ProcessLens shows nothing here rather than " +
                            "grouping processes by name and calling it a hierarchy — that " +
                            "would assert relationships the system never reported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        if (tree.value.isEmpty()) {
            EmptyState(message = "No processes with readable parents were found.")
            return@Column
        }

        ScreenList(verticalSpacing = 0.dp) {
            item(key = "note") {
                NoticeBanner(
                    text = "Built from parent process identifiers reported by the system.",
                    severity = EventSeverity.INFO,
                    detail = "Source: " + tree.source.label,
                )
                Spacer(Modifier.height(8.dp))
            }
            treeNodes(
                nodes = tree.value,
                depth = 0,
                expanded = state.expanded,
                onToggle = viewModel::toggle,
                onOpen = onOpenProcess,
            )
        }
    }
}

/**
 * Flattens the visible part of the tree into list items.
 *
 * Recursive emission into the same [LazyListScope] rather than nested lazy lists:
 * nesting scrollables in the same direction is both a crash risk and a performance
 * trap, and a flattened list keeps row recycling working at any depth (Section 43).
 */
private fun LazyListScope.treeNodes(
    nodes: List<ProcessTreeNode>,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    nodes.forEach { node ->
        val id = node.process.id
        item(key = "$depth:$id") {
            TreeRow(
                node = node,
                depth = depth,
                isExpanded = id in expanded,
                onToggle = { onToggle(id) },
                onOpen = { onOpen(id) },
            )
        }
        if (id in expanded && node.children.isNotEmpty()) {
            treeNodes(node.children, depth + 1, expanded, onToggle, onOpen)
        }
    }
}

@Composable
private fun TreeRow(
    node: ProcessTreeNode,
    depth: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = ProcessLensTheme.accent
    val process = node.process
    val hasChildren = node.children.isNotEmpty()
    val cpu = process.cpuPercent.valueOrNull

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Indent guide. Drawn rather than spaced so depth is visible, not merely
        // implied by whitespace.
        if (depth > 0) {
            Box(
                Modifier
                    .width(Dimens.timelineRail)
                    .height(28.dp)
                    .background(scheme.outlineVariant),
            )
            Spacer(Modifier.width(8.dp))
        }

        if (hasChildren) {
            IconTapTarget(
                icon = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (isExpanded) {
                    "Collapse ${process.displayName}, ${node.descendantCount} descendants"
                } else {
                    "Expand ${process.displayName}, ${node.descendantCount} descendants"
                },
                onClick = onToggle,
            )
        } else {
            Spacer(Modifier.width(Dimens.minTouchTarget))
        }

        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radii.chip))
                .clickable(onClick = onOpen)
                .padding(vertical = 6.dp, horizontal = 4.dp),
        ) {
            Text(
                process.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    process.pid.valueOrNull?.let { "PID $it" } ?: "PID not readable",
                    style = MonoStyle,
                    color = scheme.onSurfaceVariant,
                )
                if (hasChildren) {
                    Spacer(Modifier.width(6.dp))
                    Chip(text = Formatters.count(node.descendantCount, "child", "children"))
                }
            }
        }

        cpu?.let {
            Text(
                Formatters.percentValue(it),
                style = MonoStyle,
                color = loadColor(it / 100f, accent, ProcessLensTheme.isDark),
            )
        }
    }
}

/**
 * Threads of one process (Section 11).
 *
 * `/proc/<pid>/task` is readable for our own process on every supported API level, and
 * for others only where hidepid does not apply or an elevated route exists — so the
 * common outcome on a modern device is a single honest explanation.
 */
@Composable
fun ThreadsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThreadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val threads = state.threads

    Column(modifier) {
        ScreenHeader(
            title = "Threads",
            subtitle = buildString {
                append("PID ")
                append(if (state.pid > 0) state.pid.toString() else "unknown")
                (threads as? Observed.Value)?.let {
                    append(" · ")
                    append(Formatters.count(it.value.size, "thread", "threads"))
                }
            },
            onBack = onBack,
            actions = {
                IconTapTarget(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Re-read threads",
                    onClick = viewModel::load,
                )
            },
        )

        if (state.isLoading && threads == null) {
            LoadingBlock(label = "Reading the thread table")
            return@Column
        }

        if (threads !is Observed.Value) {
            Column(Modifier.padding(Dimens.screenPadding)) {
                GlassCard {
                    SectionHeader(title = "Thread list not available")
                    NotAvailable(
                        observed = threads ?: Observed.Failed("Not read yet"),
                        what = "Threads",
                        style = UnavailableStyle.FULL,
                    )
                }
            }
            return@Column
        }

        if (threads.value.isEmpty()) {
            EmptyState(message = "The process reported no threads. It may have exited.")
            return@Column
        }

        val sorted = threads.value.sortedWith(
            compareByDescending<com.processlens.domain.model.ThreadInfo> {
                it.cpuPercent.valueOrNull ?: -1f
            }.thenBy { it.tid },
        )

        ScreenList(verticalSpacing = 0.dp) {
            item(key = "source") {
                NoticeBanner(
                    text = "Read from the kernel's thread table for this process.",
                    severity = EventSeverity.INFO,
                    detail = "Source: " + threads.source.label +
                        "\nPrecision: " + threads.precision.label,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(sorted.size, key = { sorted[it].tid }) { index ->
                ThreadRow(sorted[index])
            }
            item(key = "footer") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Per-thread CPU is sampled between two reads of the same counters. " +
                        "A thread with no figure had no readable counter, not zero work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: com.processlens.domain.model.ThreadInfo) {
    val scheme = MaterialTheme.colorScheme
    val accent = ProcessLensTheme.accent
    val cpu = thread.cpuPercent.valueOrNull

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            thread.tid.toString(),
            style = MonoStyle,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                thread.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(text = thread.state.label)
                thread.priority.valueOrNull?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "priority $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            cpu?.let { Formatters.percentValue(it) } ?: "—",
            style = MonoStyle,
            color = cpu?.let { loadColor(it / 100f, accent, ProcessLensTheme.isDark) }
                ?: scheme.onSurfaceVariant,
        )
    }
}
