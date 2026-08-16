package com.processlens.feature.processes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.common.valueOrNull
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.HairlineDivider
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.MonoStyle
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.Radii
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.loadColor
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.ProcessInfo

/**
 * The process browser (Sections 9, 10).
 *
 * The list is lazy and keyed on [ProcessInfo.id] so a two-second refresh reuses rows
 * instead of rebuilding a few hundred of them (Section 43).
 *
 * The header carries a limitation banner whenever the list is not the real process
 * table. On API 28+ without elevated access it never is, and the banner explains what
 * the rows actually represent rather than letting the user assume they are looking at
 * `ps` output (Section 42).
 */
@Composable
fun ProcessListScreen(
    onOpenProcess: (String) -> Unit,
    onOpenTree: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProcessListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }

    Column(modifier) {
        ScreenHeader(
            title = "Processes",
            subtitle = state.result?.let { result ->
                buildString {
                    append(Formatters.count(state.visible.size, "process", "processes"))
                    if (state.hiddenCount > 0) append(" · ${state.hiddenCount} filtered out")
                    append(" · ")
                    append(result.accessLevelName)
                    append(" access")
                }
            },
            actions = {
                Row {
                    IconTapTarget(
                        icon = if (showSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (showSearch) {
                            "Close the filter box"
                        } else {
                            "Filter this list"
                        },
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.setQuery("")
                        },
                    )
                    IconTapTarget(
                        icon = Icons.Outlined.AccountTree,
                        contentDescription = "Process tree",
                        onClick = onOpenTree,
                    )
                    IconTapTarget(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Refresh now",
                        onClick = viewModel::refresh,
                    )
                }
            },
        )

        if (showSearch) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding, vertical = 4.dp),
                placeholder = { Text("Filter by name or package") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconTapTarget(
                            icon = Icons.Outlined.Close,
                            contentDescription = "Clear the filter",
                            onClick = { viewModel.setQuery("") },
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(Radii.chip),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ProcessLensTheme.accent.base,
                ),
            )
            Row(
                Modifier.padding(horizontal = Dimens.screenPadding, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Fuzzy match, ranked by relevance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                ActionText("Search everything", onClick = onOpenSearch)
            }
        }

        FilterBar(filter = state.filter, onFilter = viewModel::setFilter)
        SortBar(sort = state.sort, descending = state.descending, onSort = viewModel::setSort)

        val result = state.result
        if (result == null) {
            LoadingBlock(label = "Reading the process table")
            return@Column
        }

        HairlineDivider()

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Dimens.screenPadding,
                end = Dimens.screenPadding,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.error?.let { message ->
                item(key = "error") {
                    NoticeBanner(
                        text = message,
                        severity = EventSeverity.WARNING,
                        action = { ActionText("Dismiss", onClick = viewModel::dismissError) },
                    )
                }
            }

            if (!result.isCompleteList || result.limitationNote != null) {
                item(key = "limitation") {
                    NoticeBanner(
                        text = result.limitationNote
                            ?: "This is not the complete process table.",
                        severity = EventSeverity.INFO,
                        detail = "Discovered via: " + result.discoveryMethods.joinToString(", ") +
                            "\nAccess level: " + result.accessLevelName,
                    )
                }
            }

            if (state.visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        message = if (state.query.isNotBlank()) {
                            "Nothing matches \"${state.query}\"."
                        } else {
                            "No processes match this filter."
                        },
                        action = {
                            ActionText(
                                "Show all",
                                onClick = { viewModel.setFilter(ProcessFilter.ALL) },
                            )
                        },
                    )
                }
            }

            items(state.visible, key = { it.id }) { process ->
                ProcessRow(
                    process = process,
                    showImportance = state.sort == ProcessSort.IMPORTANCE,
                    onClick = { onOpenProcess(process.id) },
                )
            }

            item(key = "footer") {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Metrics shown as \"—\" were not readable at ${result.accessLevelName} " +
                        "access. They are not zero.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One row. Deliberately dense: name, the two headline metrics, and the state.
 *
 * The CPU and memory columns show an em-dash for a restricted reading, and the row's
 * accessibility description says "not readable" rather than reading the dash out —
 * a dash is a typographic convention, not a measurement (Section 42).
 */
@Composable
private fun ProcessRow(
    process: ProcessInfo,
    showImportance: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = ProcessLensTheme.accent
    val dark = ProcessLensTheme.isDark

    val cpu = process.cpuPercent.valueOrNull
    val memory = process.memoryBytes.valueOrNull
    val railColor = cpu?.let { loadColor(it / 100f, accent, dark) } ?: scheme.outlineVariant

    val description = buildString {
        append(process.displayName)
        append(". ")
        append(process.state.label)
        append(", ")
        append(process.importance.label)
        append(". CPU ")
        append(cpu?.let { Formatters.percentValue(it) } ?: "not readable")
        append(". Memory ")
        append(memory?.let { Formatters.bytes(it) } ?: "not readable")
        append(". ")
        append(process.pid.valueOrNull?.let { "PID $it." } ?: "PID not readable.")
    }

    ListRow(
        title = process.displayName,
        subtitle = buildString {
            process.pid.valueOrNull?.let {
                append(it)
                append(" · ")
            }
            append(process.processName)
        },
        onClick = onClick,
        contentDescriptionOverride = description,
        leading = {
            // A load-coloured rail rather than an icon: it encodes the same figure the
            // number does, so the row scans at a glance, and it is grey (not green)
            // when the figure was not readable.
            Box(
                Modifier
                    .width(4.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(Radii.bar))
                    .background(railColor),
            )
        },
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        cpu?.let { Formatters.percentValue(it) } ?: "—",
                        style = MonoStyle,
                        color = cpu?.let { loadColor(it / 100f, accent, dark) }
                            ?: scheme.onSurfaceVariant,
                    )
                    Text(
                        memory?.let { Formatters.bytes(it) } ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (showImportance || process.importance.isForeground) {
                    Chip(text = process.importance.label)
                }
            }
        },
    )
}

@Composable
private fun FilterBar(filter: ProcessFilter, onFilter: (ProcessFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.FilterList,
            contentDescription = "Filter",
            modifier = Modifier.size(Dimens.iconSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProcessFilter.entries.forEach { entry ->
            Chip(
                text = entry.label,
                selected = entry == filter,
                onClick = { onFilter(entry) },
            )
        }
    }
}

@Composable
private fun SortBar(sort: ProcessSort, descending: Boolean, onSort: (ProcessSort) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding, vertical = 2.dp)
            .heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.SwapVert,
            contentDescription = "Sort",
            modifier = Modifier.size(Dimens.iconSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProcessSort.entries.forEach { entry ->
            val isActive = entry == sort
            Chip(
                // The arrow is text, not a colour: direction must be legible without
                // relying on the selected tint (Section 49).
                text = entry.label + if (isActive) (if (descending) " ↓" else " ↑") else "",
                selected = isActive,
                onClick = { onSort(entry) },
            )
        }
    }
}
