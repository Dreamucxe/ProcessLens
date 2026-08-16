package com.processlens.feature.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.Formatters
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.HairlineDivider
import com.processlens.core.designsystem.IconTapTarget
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.Radii
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.PermissionGroup

/**
 * The permission inspector (Sections 21, 32).
 *
 * Grouped exactly as Section 21 specifies — Location, Camera, Microphone, Storage,
 * Notifications, Sensors, Phone, Network, Nearby devices — plus the Contacts, Calendar,
 * System and Other groups the platform also defines, and reversed: each group lists the
 * applications that *hold* something in it.
 *
 * Three honesty rules are visible in the layout:
 *
 *  1. **No revoke button.** No application can revoke another's permission on stock
 *    Android, so tapping a row opens that app's detail screen, from which Android's own
 *    settings page is one tap away. Section 21: "do not claim to change permissions
 *    unless Android actually allows the requested operation."
 *  2. **Held is not the same as revocable.** A `normal`-protection permission is granted
 *    at install and cannot be turned off; a soft-restricted one is held but inert. Both
 *    are shown, both are labelled, and the group header counts them separately from the
 *    runtime grants a user can actually change.
 *  3. **Unreadable packages are counted, not hidden.** From API 30 package visibility
 *    can conceal an installed app, and a total that silently omitted it would understate
 *    the answer (Section 42).
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }

    Column(modifier) {
        ScreenChrome(
            state = state,
            showSearch = showSearch,
            onBack = onBack,
            onToggleSearch = {
                showSearch = !showSearch
                if (!showSearch) viewModel.setQuery("")
            },
            onRescan = viewModel::rescan,
        )

        if (showSearch) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding, vertical = 4.dp),
                placeholder = { Text("Filter holders by name or package") },
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
        }

        // Progress, not a spinner: this is a bounded sweep with a known denominator and
        // saying "112 of 208" is more use than an indeterminate circle (Section 48).
        state.progress?.let { progress ->
            Column(Modifier.padding(horizontal = Dimens.screenPadding, vertical = 6.dp)) {
                Text(
                    "Reading permissions: ${progress.done} of ${progress.total} packages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = ProcessLensTheme.accent.base,
                )
            }
        }

        GroupFilterBar(
            groups = state.availableGroups,
            selected = state.groupFilter,
            includeSystem = state.includeSystem,
            onSelect = viewModel::setGroup,
            onToggleSystem = viewModel::toggleSystemApps,
        )

        HairlineDivider()

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Dimens.screenPadding,
                end = Dimens.screenPadding,
                top = 10.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
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

            item(key = "explainer") { Explainer(scan = state.scan) }

            val visible = state.visible
            if (visible.isEmpty() && !state.isFirstLoad) {
                item(key = "empty") {
                    EmptyState(
                        message = if (state.query.isNotBlank()) {
                            "No application matching \"${state.query}\" holds a permission " +
                                "in the selected group."
                        } else {
                            "No permissions were readable. On API 30 and above, package " +
                                "visibility can hide installed applications from this app."
                        },
                        icon = Icons.Outlined.Shield,
                    )
                }
            }

            visible.forEach { summary ->
                item(key = "group-${summary.group.name}") {
                    GroupCard(summary = summary, onOpenApp = onOpenApp)
                }
            }
        }
    }
}

@Composable
private fun ScreenChrome(
    state: PermissionsViewModel.State,
    showSearch: Boolean,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onRescan: () -> Unit,
) {
    val scan = state.scan
    ScreenHeader(
        title = "Permissions",
        subtitle = when {
            state.isScanning && scan == null -> "Scanning installed packages"
            scan == null -> null
            else -> buildString {
                append(Formatters.count(scan.groups.size, "group"))
                append(" · ")
                append(Formatters.count(scan.packagesRead, "package"))
                append(" read")
                if (scan.packagesUnreadable > 0) {
                    append(" · ")
                    append(scan.packagesUnreadable)
                    append(" unreadable")
                }
            }
        },
        onBack = onBack,
        actions = {
            Row {
                IconTapTarget(
                    icon = if (showSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (showSearch) "Close the filter box" else "Filter holders",
                    onClick = onToggleSearch,
                )
                IconTapTarget(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Re-read every package's permissions",
                    onClick = onRescan,
                )
            }
        },
    )
}

/**
 * What this screen is and is not.
 *
 * Stated once, at the top, rather than repeated as a caveat on every row — and it
 * includes the scan's scope, because "42 packages" and "310 packages" are very different
 * answers to the same question and the reader has to know which they are looking at.
 */
@Composable
private fun Explainer(scan: PermissionsViewModel.Scan?) {
    GlassCard {
        SectionHeader(
            title = "Who holds what",
            subtitle = "Read from PackageManager for every visible package",
        )
        Text(
            "Android exposes no reverse index of permission holders, so this list is " +
                "built by reading each installed package and inverting the result. " +
                "ProcessLens cannot grant or revoke another application's permissions — " +
                "no app can — so tapping a row opens that application, where Android's " +
                "own permission settings are one tap away.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (scan != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    text = if (scan.includedSystemApps) {
                        "All ${scan.totalPackages} packages"
                    } else {
                        "${scan.totalPackages} user-installed"
                    },
                    icon = Icons.Outlined.Shield,
                )
                if (scan.packagesUnreadable > 0) {
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        text = "${scan.packagesUnreadable} not readable",
                        icon = Icons.Outlined.MoreHoriz,
                    )
                }
            }
            if (scan.packagesUnreadable > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Packages that could not be read are counted rather than dropped: " +
                        "from API 30 the platform can hide an installed application from " +
                        "this app entirely, and a total that omitted them would understate " +
                        "the answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupFilterBar(
    groups: List<PermissionGroup>,
    selected: PermissionGroup?,
    includeSystem: Boolean,
    onSelect: (PermissionGroup?) -> Unit,
    onToggleSystem: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.screenPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all") {
            Chip(
                text = "All groups",
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(groups, key = { it.name }) { group ->
            Chip(
                text = group.label,
                icon = group.icon(),
                selected = selected == group,
                onClick = { onSelect(if (selected == group) null else group) },
            )
        }
        item(key = "system") {
            Chip(
                text = if (includeSystem) "System included" else "System excluded",
                icon = Icons.Outlined.Settings,
                selected = includeSystem,
                onClick = onToggleSystem,
            )
        }
    }
}

/**
 * One group and its holders.
 *
 * The subtitle separates "holds it" from "you can turn it off", because those are
 * different facts and conflating them would make the Network group — whose permissions
 * are install-time and irrevocable — look like something the user has a choice about.
 */
@Composable
private fun GroupCard(
    summary: PermissionsViewModel.GroupSummary,
    onOpenApp: (String) -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(vertical = Dimens.cardPadding)) {
        Column(Modifier.padding(horizontal = Dimens.cardPadding)) {
            SectionHeader(
                title = summary.group.label,
                subtitle = buildString {
                    append(Formatters.count(summary.holderCount, "application"))
                    append(" hold")
                    if (summary.holderCount == 1) append("s")
                    if (summary.revocableHolderCount > 0) {
                        append(" · ")
                        append(summary.revocableHolderCount)
                        append(" revocable in Settings")
                    } else {
                        append(" · granted at install, not revocable")
                    }
                },
                trailing = {
                    Icon(
                        summary.group.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.iconLarge),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (summary.restrictedHolderCount > 0) {
                Text(
                    "${summary.restrictedHolderCount} hold a soft-restricted permission: " +
                        "it is in the grant set but the operation behind it is revoked, so " +
                        "it does nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        summary.holders.forEach { holder ->
            HolderRow(holder = holder, onOpenApp = onOpenApp)
        }
    }
}

@Composable
private fun HolderRow(
    holder: PermissionsViewModel.Holder,
    onOpenApp: (String) -> Unit,
) {
    ListRow(
        title = holder.label,
        subtitle = holder.heldSummary,
        onClick = { onOpenApp(holder.packageName) },
        // One utterance for the whole row: a screen reader should say what the app
        // holds, not read the label and then a comma-separated list as separate nodes.
        contentDescriptionOverride = buildString {
            append(holder.label)
            if (holder.isSystemApp) append(", system application")
            append(", holds ")
            append(holder.heldSummary)
            if (holder.revocable.isEmpty()) {
                append(", granted at install and not revocable")
            }
            if (holder.hasRestricted) append(", one is soft-restricted")
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (holder.isSystemApp) {
                    Chip(text = "System")
                    Spacer(Modifier.width(6.dp))
                }
                if (holder.revocable.isNotEmpty()) {
                    Chip(
                        text = Formatters.count(holder.revocable.size, "runtime"),
                        icon = Icons.Outlined.Shield,
                    )
                } else {
                    Chip(text = "Install-time")
                }
            }
        },
    )
}

/**
 * Group icon.
 *
 * Never the only signal — every group also shows its name, per Section 49's rule that
 * important state must not be communicated by an icon or a colour alone.
 */
private fun PermissionGroup.icon(): ImageVector = when (this) {
    PermissionGroup.LOCATION -> Icons.Outlined.LocationOn
    PermissionGroup.CAMERA -> Icons.Outlined.PhotoCamera
    PermissionGroup.MICROPHONE -> Icons.Outlined.Mic
    PermissionGroup.STORAGE -> Icons.Outlined.Folder
    PermissionGroup.NOTIFICATIONS -> Icons.Outlined.Notifications
    PermissionGroup.SENSORS -> Icons.Outlined.Sensors
    PermissionGroup.PHONE -> Icons.Outlined.Phone
    PermissionGroup.CONTACTS -> Icons.Outlined.Contacts
    PermissionGroup.CALENDAR -> Icons.Outlined.CalendarMonth
    PermissionGroup.NETWORK -> Icons.Outlined.Wifi
    PermissionGroup.NEARBY_DEVICES -> Icons.Outlined.Bluetooth
    PermissionGroup.SYSTEM -> Icons.Outlined.Settings
    PermissionGroup.OTHER -> Icons.Outlined.MoreHoriz
}
