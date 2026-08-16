package com.processlens.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared screen chrome.
 *
 * Every feature screen is laid out through here so the title metrics, insets and
 * scroll behaviour are identical across all eleven. Material's `TopAppBar` is not
 * used: it centres or left-aligns a single line, and these screens need a title with
 * a live subtitle underneath it (the access level, the sample count, the refresh
 * rate) that has to stay legible at 200% font scale.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(
                start = if (onBack != null) 4.dp else Dimens.screenPadding,
                end = Dimens.screenPadding,
                top = 8.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconTapTarget(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            Spacer(Modifier.width(2.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions?.invoke()
    }
}

/**
 * A 48dp-minimum icon button (Section 49).
 *
 * [contentDescription] is required, not optional: an icon-only control with no
 * description is invisible to a screen reader, and making the parameter mandatory
 * means it cannot be forgotten.
 */
@Composable
fun IconTapTarget(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null,
    badge: Boolean = false,
) {
    val accent = ProcessLensTheme.accent
    Box(
        modifier = modifier
            .size(Dimens.minTouchTarget)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(Dimens.iconLarge),
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 11.dp, end = 11.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent.bright),
            )
        }
    }
}

/**
 * A vertically scrolling screen body with consistent padding.
 *
 * For screens whose content is a bounded set of cards. Screens with a potentially
 * unbounded list (processes, apps, events) use [ScreenList] instead — a `Column` in
 * a `verticalScroll` composes every child immediately, which on a 900-process list
 * is precisely the self-inflicted load Section 43 exists to prevent.
 */
@Composable
fun ScreenBody(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = Dimens.screenPadding,
        end = Dimens.screenPadding,
        top = 0.dp,
        bottom = 24.dp,
    ),
    verticalSpacing: androidx.compose.ui.unit.Dp = Dimens.cardSpacing,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/** Lazy equivalent of [ScreenBody], for long lists. */
@Composable
fun ScreenList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = Dimens.screenPadding,
        end = Dimens.screenPadding,
        bottom = 24.dp,
    ),
    verticalSpacing: androidx.compose.ui.unit.Dp = Dimens.cardSpacing,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/**
 * Inline loading indicator.
 *
 * Used only for the genuinely unknown-duration first read. Refresh ticks do not show
 * one — a spinner appearing twice a second is worse than a value that updates in
 * place, and it hides the fact that the previous reading is still valid.
 */
@Composable
fun LoadingBlock(modifier: Modifier = Modifier, label: String = "Reading system state") {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = ProcessLensTheme.accent.base,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Fixed-height spacer used to separate sections without a divider. */
@Composable
fun SectionGap(modifier: Modifier = Modifier) {
    Spacer(modifier.height(Dimens.sectionSpacing - Dimens.cardSpacing))
}
