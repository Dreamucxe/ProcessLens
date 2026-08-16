package com.processlens.feature.applications

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.HairlineDivider
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.LoadingBlock
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.Radii
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.domain.model.AppInfo
import com.processlens.domain.model.EventSeverity

/**
 * The installed-application list (Section 20).
 *
 * From API 30 the platform filters which packages an app can see. ProcessLens holds
 * QUERY_ALL_PACKAGES so the list is normally complete, but the banner below states
 * plainly when it is not — an under-reported list that looks complete would be exactly
 * the kind of quiet fabrication Section 42 forbids.
 */
@Composable
fun AppListScreen(
    onOpenApp: (String) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }

    Column(modifier) {
        ScreenHeader(
            title = "Applications",
            subtitle = if (state.isLoading) {
                null
            } else {
                Formatters.count(state.visible.size, "application") + " of " + state.totalCount
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
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Re-read installed packages",
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

        FilterBar(
            filter = state.filter,
            includeSystem = state.includeSystem,
            onFilter = viewModel::setFilter,
            onToggleSystem = viewModel::toggleSystemApps,
        )

        if (state.isLoading) {
            LoadingBlock(label = "Reading installed packages")
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

            if (!state.includeSystem) {
                item(key = "system-hidden") {
                    NoticeBanner(
                        text = "System packages are hidden by your settings, so this is not " +
                            "every installed package.",
                        severity = EventSeverity.INFO,
                        action = { ActionText("Show them", onClick = viewModel::toggleSystemApps) },
                    )
                }
            }

            if (state.visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        message = if (state.query.isNotBlank()) {
                            "Nothing matches \"" + state.query + "\"."
                        } else {
                            "No package matches this filter."
                        },
                        action = {
                            ActionText(
                                "Show all",
                                onClick = {
                                    viewModel.setQuery("")
                                    viewModel.setFilter(AppListViewModel.Filter.ALL)
                                },
                            )
                        },
                    )
                }
            }

            items(state.visible, key = { it.packageName }) { app ->
                AppRow(app = app, onClick = { onOpenApp(app.packageName) })
            }
        }
    }
}

/**
 * One package row.
 *
 * "Disabled" and "Debuggable" are shown as words, not colours, because both are states
 * a user may need to act on and Section 49 forbids carrying meaning in hue alone.
 */
@Composable
private fun AppRow(app: AppInfo, onClick: () -> Unit) {
    ListRow(
        title = app.label,
        subtitle = buildString {
            append(app.packageName)
            app.versionName?.let {
                append("  ·  ")
                append(it)
            }
        },
        onClick = onClick,
        contentDescriptionOverride = buildString {
            append(app.label)
            append(". Package ")
            append(app.packageName)
            append(". ")
            append(if (app.isSystemApp) "System package. " else "Installed by the user. ")
            if (!app.isEnabled) append("Disabled. ")
            if (app.isDebuggable) append("Debuggable. ")
            append("Targets API ")
            append(app.targetSdk)
            append(".")
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!app.isEnabled) Chip(text = "Disabled")
                if (app.isDebuggable) Chip(text = "Debuggable")
                if (app.isSystemApp) Chip(text = "System")
            }
        },
    )
}

@Composable
private fun FilterBar(
    filter: AppListViewModel.Filter,
    includeSystem: Boolean,
    onFilter: (AppListViewModel.Filter) -> Unit,
    onToggleSystem: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppListViewModel.Filter.entries.forEach { entry ->
            Chip(
                text = entry.label,
                selected = filter == entry,
                onClick = { onFilter(entry) },
            )
        }
        Chip(
            text = if (includeSystem) "System included" else "System hidden",
            selected = includeSystem,
            onClick = onToggleSystem,
        )
    }
}
