package com.processlens.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.Radii
import com.processlens.core.designsystem.readableForeground

/**
 * First-run overlay (Section 53).
 *
 * Three sentences and one button. It sits *over* the already-running app rather than
 * in front of it: the dashboard is live behind this card, nothing is gated, and
 * dismissing it is the entire "setup". Section 53 is explicit — no account, no forced
 * setup — and Section 41 forbids requesting permissions here, so this screen asks for
 * nothing at all. Permissions are requested later, in context, at the point a
 * specific screen actually needs one.
 */
@Composable
fun OnboardingOverlay(
    accessSummary: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = ProcessLensTheme.accent

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.scrim)
            .padding(Dimens.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(Radii.tile))
                        .background(accent.container),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.CenterFocusStrong,
                        contentDescription = null,
                        tint = accent.onDarkText,
                        modifier = Modifier.size(Dimens.iconLarge),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "ProcessLens",
                        style = MaterialTheme.typography.headlineSmall,
                        color = scheme.onSurface,
                    )
                    Text(
                        "A process observatory",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Point(
                icon = Icons.Outlined.Insights,
                title = "Everything shown is measured",
                body = "Android restricts a lot of process detail. Where a value cannot " +
                    "be read, ProcessLens says so instead of showing a number it made up.",
            )
            Spacer(Modifier.height(12.dp))
            Point(
                icon = Icons.Outlined.CloudOff,
                title = "Nothing leaves this device",
                body = "No account, no cloud, no analytics. ProcessLens does not hold the " +
                    "internet permission, so it cannot make a network request at all.",
            )
            Spacer(Modifier.height(12.dp))
            Point(
                icon = Icons.Outlined.VerifiedUser,
                title = "Permissions only when needed",
                body = "Nothing is requested now. A screen asks for the one thing it " +
                    "needs, at the moment it needs it, and explains why.",
            )

            if (accessSummary.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.chip))
                        .background(scheme.surfaceContainerHigh)
                        .padding(10.dp),
                ) {
                    Text(
                        accessSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radii.chip),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent.base,
                    contentColor = accent.base.readableForeground(),
                ),
            ) {
                Text("Start looking", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(Dimens.iconMedium),
            tint = ProcessLensTheme.accent.onDarkText,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}
