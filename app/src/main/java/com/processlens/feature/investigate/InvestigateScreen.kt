package com.processlens.feature.investigate

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.common.FuzzyMatch
import com.processlens.core.common.Formatters
import com.processlens.core.designsystem.ActionText
import com.processlens.core.designsystem.Chip
import com.processlens.core.designsystem.DetailRow
import com.processlens.core.designsystem.Dimens
import com.processlens.core.designsystem.EmptyState
import com.processlens.core.designsystem.EventRow
import com.processlens.core.designsystem.ExpandableDetail
import com.processlens.core.designsystem.GlassCard
import com.processlens.core.designsystem.ListRow
import com.processlens.core.designsystem.NoticeBanner
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.core.designsystem.Radii
import com.processlens.core.designsystem.ScreenBody
import com.processlens.core.designsystem.ScreenHeader
import com.processlens.core.designsystem.SectionHeader
import com.processlens.core.system.InvestigationService
import com.processlens.domain.model.Capability
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.SystemCapabilities
import kotlinx.coroutines.launch

/**
 * Investigation Mode (Section 14).
 *
 * The screen has two faces: a setup form when idle, and a live progress view while a
 * recording is in flight. Both state what the recording can and cannot see — the
 * "before you start" list is built from the runtime capability matrix, so a device that
 * cannot observe WakeLocks says so before five minutes are spent rather than after.
 */
@Composable
fun InvestigateScreen(
    onOpenTimeline: (Long) -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InvestigateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Investigation?>(null) }

    // The recorder prepares the row; the service owns the loop. Starting the service
    // here rather than in the ViewModel keeps the Android context out of the domain.
    LaunchedEffect(state.startedId) {
        val id = state.startedId ?: return@LaunchedEffect
        runCatching { startRecordingService(context, id) }
        viewModel.clearStarted()
    }

    Column(modifier) {
        ScreenHeader(
            title = "Investigate",
            subtitle = if (state.isRecording) {
                "Recording in progress"
            } else {
                "Record the system, then read what happened"
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

            if (state.isRecording) {
                LiveCard(state = state, onStop = viewModel::stop)
                LiveEventsCard(state = state)
            } else {
                SetupCard(
                    state = state,
                    onName = viewModel::setName,
                    onDuration = viewModel::setDuration,
                    onPickApp = viewModel::openAppPicker,
                    onClearTarget = { viewModel.setTarget(null) },
                    onStart = { scope.launch { viewModel.prepare() } },
                )
                CapabilityPreview(capabilities = state.capabilities)
            }

            HistoryCard(
                history = state.history,
                onOpen = onOpenTimeline,
                onCompare = onOpenCompare,
                onDelete = { pendingDelete = it },
            )
        }
    }

    if (state.showAppPicker) {
        AppPickerDialog(
            state = state,
            onQuery = viewModel::setAppQuery,
            onPick = viewModel::setTarget,
            onDismiss = viewModel::closeAppPicker,
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this recording?") },
            text = {
                Text(
                    "\"" + target.name + "\" and its " +
                        Formatters.count(target.snapshotCount, "sample") +
                        " will be removed from this device. Nothing was ever uploaded, " +
                        "so this is the only copy.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.id)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}

// --------------------------------------------------------------------------- setup

@Composable
private fun SetupCard(
    state: InvestigateViewModel.State,
    onName: (String) -> Unit,
    onDuration: (InvestigateViewModel.Duration) -> Unit,
    onPickApp: () -> Unit,
    onClearTarget: () -> Unit,
    onStart: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "New investigation",
            subtitle = "Samples every " +
                Formatters.duration(state.settings.investigationSampleIntervalMillis),
        )

        OutlinedTextField(
            value = state.name,
            onValueChange = onName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name (optional)") },
            placeholder = { Text("Battery drain while idle") },
            singleLine = true,
            shape = RoundedCornerShape(Radii.chip),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "Duration",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InvestigateViewModel.Duration.entries.forEach { entry ->
                Chip(
                    text = entry.label,
                    selected = state.duration == entry,
                    onClick = { onDuration(entry) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        ListRow(
            title = state.targetLabel ?: "Whole system",
            subtitle = if (state.targetPackage == null) {
                "Every observable process is sampled"
            } else {
                "Events are scoped to this package; system figures are still recorded"
            },
            onClick = onPickApp,
            trailing = {
                if (state.targetPackage != null) {
                    ActionText("Clear", onClick = onClearTarget)
                } else {
                    ActionText("Choose app", onClick = onPickApp)
                }
            },
        )

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onStart,
            enabled = !state.isStarting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radii.chip),
            colors = ButtonDefaults.buttonColors(
                containerColor = ProcessLensTheme.accent.base,
                contentColor = ProcessLensTheme.accent.onDarkText,
            ),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Text(
                if (state.isStarting) "Starting…" else "Start recording",
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Recording runs in a foreground service with a notification, so Android does " +
                "not stop it while the screen is off. Everything stays on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What this recording will and will not be able to see.
 *
 * Built from the live matrix, and only the observation-relevant capabilities are listed.
 * Stating the blind spots before the recording starts is the difference between a
 * limitation and a surprise (Section 42).
 */
@Composable
private fun CapabilityPreview(capabilities: SystemCapabilities?) {
    if (capabilities == null) return
    val relevant = listOf(
        Capability.PROCESS_LIST,
        Capability.PROCESS_CPU,
        Capability.MEMORY_PER_PROCESS,
        Capability.WAKELOCKS,
        Capability.NETWORK_PER_APP,
        Capability.BATTERY_TEMPERATURE,
        Capability.RUNNING_SERVICES,
    )
    val missing = relevant.filterNot { capabilities.isUsable(it) }

    GlassCard {
        SectionHeader(
            title = "What this recording can see",
            subtitle = capabilities.accessLevel.label + " access on API " +
                capabilities.apiLevel,
        )
        relevant.forEach { capability ->
            DetailRow(
                label = capability.displayName,
                value = capabilities.availability(capability).let {
                    it.symbol + "  " + it.label
                },
            )
        }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            NoticeBanner(
                text = Formatters.count(missing.size, "measurement") +
                    " cannot be recorded on this device, so the timeline will have " +
                    "gaps there rather than zeroes.",
                severity = EventSeverity.INFO,
                detail = missing.joinToString("\n") {
                    it.displayName + " — " + capabilities[it].reason
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------- live

@Composable
private fun LiveCard(state: InvestigateViewModel.State, onStop: () -> Unit) {
    val recording = state.recording
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The dot is paired with the word "Recording": Section 49 forbids letting
            // colour alone carry a state this consequential.
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
            Text(
                "Recording",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            state.targetLabel?.let { "Scoped to " + it } ?: "Whole system",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        val fraction = recording.progressFraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(Radii.bar)),
                color = ProcessLensTheme.accent.base,
            )
            Spacer(Modifier.height(6.dp))
        }

        DetailRow(
            label = "Elapsed",
            value = Formatters.duration(recording.elapsedMillis),
            monospace = true,
        )
        if (recording.plannedDurationMillis != null) {
            DetailRow(
                label = "Planned",
                value = Formatters.durationCoarse(recording.plannedDurationMillis),
                monospace = true,
            )
        } else {
            DetailRow(label = "Planned", value = "Runs until you stop it")
        }
        DetailRow(
            label = "Samples taken",
            value = recording.sampleCount.toString(),
            monospace = true,
        )
        DetailRow(
            label = "Events derived",
            value = recording.eventCount.toString(),
            monospace = true,
        )
        recording.lastSampleAt?.let {
            DetailRow(label = "Last sample", value = Formatters.clockTimeShort(it))
        }

        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radii.chip),
        ) {
            Icon(Icons.Outlined.Stop, contentDescription = null)
            Text("Stop and summarise", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun LiveEventsCard(state: InvestigateViewModel.State) {
    GlassCard {
        SectionHeader(
            title = "Events so far",
            subtitle = if (state.liveEvents.isEmpty()) {
                "Nothing has crossed a threshold yet"
            } else {
                Formatters.count(state.liveEvents.size, "event")
            },
        )
        if (state.liveEvents.isEmpty()) {
            Text(
                "Events appear when a measurement actually crosses one of your " +
                    "thresholds — currently " +
                    state.settings.cpuWarningThreshold + "% CPU and " +
                    Formatters.bytes(state.settings.memoryIncreaseWarningMb * 1024L * 1024L) +
                    " of memory growth. A quiet timeline is a real result.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.liveEvents.take(12).forEach { event ->
                EventRow(
                    event = event,
                    showRelativeTo = state.recording.startedAt,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------- history

@Composable
private fun HistoryCard(
    history: List<Investigation>,
    onOpen: (Long) -> Unit,
    onCompare: () -> Unit,
    onDelete: (Investigation) -> Unit,
) {
    GlassCard {
        SectionHeader(
            title = "Recordings",
            subtitle = if (history.isEmpty()) {
                null
            } else {
                Formatters.count(history.size, "recording") + " on this device"
            },
            trailing = {
                if (history.count { !it.isRunning } >= 2) {
                    ActionText("Compare", onClick = onCompare)
                }
            },
        )
        if (history.isEmpty()) {
            EmptyState(message = "No recordings yet. Start one above.")
            return@GlassCard
        }
        history.forEach { investigation ->
            ListRow(
                title = investigation.name,
                subtitle = buildString {
                    append(Formatters.dateTime(investigation.startedAt))
                    append("  ·  ")
                    append(Formatters.durationCoarse(investigation.durationMillis))
                    append("  ·  ")
                    append(Formatters.count(investigation.eventCount, "event"))
                },
                onClick = { onOpen(investigation.id) },
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (investigation.state != InvestigationState.COMPLETED) {
                            Chip(text = investigation.state.label)
                        }
                        ActionText("Delete", onClick = { onDelete(investigation) })
                    }
                },
                contentDescriptionOverride = investigation.name + ", recorded " +
                    Formatters.dateTime(investigation.startedAt) + ", " +
                    investigation.state.label + ", " +
                    Formatters.count(investigation.eventCount, "event") + ".",
            )
        }
        Spacer(Modifier.height(8.dp))
        ExpandableDetail(
            summary = "Why some recordings say \"Interrupted\"",
            detail = "Android may stop a foreground service under memory pressure, and " +
                "the process can be killed outright. When ProcessLens next starts it " +
                "marks any recording that was still open as interrupted.\n\n" +
                "The samples already written are real and the timeline is still worth " +
                "reading — it simply ends earlier than planned.",
        )
    }
}

// ---------------------------------------------------------------------- app picker

@Composable
private fun AppPickerDialog(
    state: InvestigateViewModel.State,
    onQuery: (String) -> Unit,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val matches = remember(state.apps, state.appQuery) {
        if (state.appQuery.isBlank()) {
            state.apps.take(PICKER_LIMIT)
        } else {
            state.apps
                .map { it to FuzzyMatch.bestScore(state.appQuery, it.label, it.packageName) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(PICKER_LIMIT)
                .map { it.first }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scope to one app") },
        text = {
            Column {
                OutlinedTextField(
                    value = state.appQuery,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search installed apps") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.appQuery.isNotEmpty()) {
                            ActionText(
                                "Clear",
                                onClick = { onQuery("") },
                                icon = Icons.Outlined.Close,
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(Radii.chip),
                )
                Spacer(Modifier.height(8.dp))
                if (matches.isEmpty()) {
                    Text(
                        "Nothing matches that.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(matches, key = { it.packageName }) { app ->
                            ListRow(
                                title = app.label,
                                subtitle = app.packageName,
                                onClick = { onPick(app.packageName) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Scoping filters which per-process events are derived. System-wide " +
                        "figures — CPU, memory, battery — are recorded either way, " +
                        "because an app's behaviour only means something against them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(null) }) { Text("Whole system") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Starts the recording service.
 *
 * `startForegroundService` is used from API 26 onward; the service itself calls
 * `startForeground` promptly, and handles the API 31+ restriction on starting a
 * foreground service from the background by falling back to a plain notification.
 */
private fun startRecordingService(context: Context, investigationId: Long) {
    val intent = InvestigationService.startIntent(context, investigationId)
    context.startForegroundService(intent)
}

private const val PICKER_LIMIT = 60
