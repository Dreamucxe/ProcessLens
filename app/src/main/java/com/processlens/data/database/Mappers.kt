package com.processlens.data.database

import com.processlens.domain.model.AccentColor
import com.processlens.domain.model.AnimationIntensity
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.EventType
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.model.Favorite
import com.processlens.domain.model.FavoriteType
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.model.RefreshRate
import com.processlens.domain.model.ThemeMode
import com.processlens.domain.model.UserSettings

/**
 * Entity ↔ domain mapping.
 *
 * Enums cross this boundary as their `name`, not their ordinal. Ordinals are
 * positional: inserting `TEAL` into the middle of [AccentColor] would silently
 * repaint every user's saved accent, and reordering [EventType] would rewrite the
 * meaning of history already on disk. Names survive reordering, and an unknown name
 * (a value written by a newer build, then downgraded) falls back to a documented
 * default rather than throwing on read.
 */

// -------------------------------------------------------------------- investigation

fun InvestigationEntity.toDomain(
    eventCount: Int = 0,
    snapshotCount: Int = 0,
    processesObserved: Int = 0,
): Investigation = Investigation(
    id = id,
    name = name,
    startedAt = startedAt,
    endedAt = endedAt,
    state = enumOrDefault(state, InvestigationState.COMPLETED),
    targetPackage = targetPackage,
    sampleIntervalMillis = sampleIntervalMillis,
    eventCount = eventCount,
    snapshotCount = snapshotCount,
    processesObserved = processesObserved,
    accessLevelName = accessLevel,
    apiLevel = apiLevel,
    deviceLabel = deviceModel,
    notes = notes,
)

fun Investigation.toEntity(): InvestigationEntity = InvestigationEntity(
    id = id,
    name = name,
    startedAt = startedAt,
    endedAt = endedAt,
    state = state.name,
    targetPackage = targetPackage,
    accessLevel = accessLevelName,
    apiLevel = apiLevel,
    deviceModel = deviceLabel,
    sampleIntervalMillis = sampleIntervalMillis,
    notes = notes,
)

// --------------------------------------------------------------------------- event

fun InvestigationEventEntity.toDomain(): InvestigationEvent = InvestigationEvent(
    id = id,
    investigationId = investigationId,
    timestamp = timestamp,
    type = enumOrDefault(type, EventType.OBSERVATION_LIMITED),
    severity = enumOrDefault(severity, EventSeverity.INFO),
    title = title,
    detail = detail,
    packageName = packageName,
    processName = processName,
    value = value,
    previousValue = previousValue,
    evidence = evidence,
)

fun InvestigationEvent.toEntity(): InvestigationEventEntity = InvestigationEventEntity(
    id = id,
    investigationId = investigationId,
    timestamp = timestamp,
    type = type.name,
    severity = severity.name,
    title = title,
    detail = detail,
    evidence = evidence,
    processName = processName,
    packageName = packageName,
    value = value,
    previousValue = previousValue,
)

// ------------------------------------------------------------------------ snapshot

fun ProcessSnapshotEntity.toDomain(converters: Converters): ProcessSnapshot = ProcessSnapshot(
    id = id,
    investigationId = investigationId,
    timestamp = timestamp,
    cpuPercent = cpuPercent,
    memoryUsedBytes = memoryUsedBytes,
    memoryAvailableBytes = memoryAvailableBytes,
    batteryLevel = batteryLevel,
    batteryTemperatureDeciCelsius = batteryTemperatureDeciCelsius,
    isCharging = isCharging,
    isScreenOn = isScreenOn,
    networkRxBytes = networkRxBytes,
    networkTxBytes = networkTxBytes,
    processCount = processCount,
    ownCpuPercent = ownCpuPercent,
    ownMemoryBytes = ownMemoryBytes,
    entries = converters.jsonToSnapshotEntries(entries),
)

fun ProcessSnapshot.toEntity(
    converters: Converters,
    cpuProvenance: String,
    discoveryMethod: String,
): ProcessSnapshotEntity = ProcessSnapshotEntity(
    id = id,
    investigationId = investigationId,
    timestamp = timestamp,
    cpuPercent = cpuPercent,
    cpuProvenance = cpuProvenance,
    memoryUsedBytes = memoryUsedBytes,
    memoryAvailableBytes = memoryAvailableBytes,
    batteryLevel = batteryLevel,
    batteryTemperatureDeciCelsius = batteryTemperatureDeciCelsius,
    isCharging = isCharging,
    isScreenOn = isScreenOn,
    networkRxBytes = networkRxBytes,
    networkTxBytes = networkTxBytes,
    ownCpuPercent = ownCpuPercent,
    ownMemoryBytes = ownMemoryBytes,
    entries = converters.snapshotEntriesToJson(entries),
    processCount = processCount,
    discoveryMethod = discoveryMethod,
)

// ------------------------------------------------------------------------ favorite

fun FavoriteEntity.toDomain(): Favorite = Favorite(
    id = id,
    type = enumOrDefault(type, FavoriteType.APPLICATION),
    key = key,
    label = label,
    createdAt = createdAt,
)

fun Favorite.toEntity(): FavoriteEntity = FavoriteEntity(
    id = id,
    type = type.name,
    key = key,
    label = label,
    createdAt = createdAt,
)

// ------------------------------------------------------------------------ settings

fun UserSettingsEntity.toDomain(): UserSettings = UserSettings(
    themeMode = enumOrDefault(themeMode, ThemeMode.DARK),
    accentColor = enumOrDefault(accentColor, AccentColor.INDIGO),
    useDynamicColor = useDynamicColor,
    glassEffectEnabled = glassEffectEnabled,
    animationIntensity = enumOrDefault(animationIntensity, AnimationIntensity.FULL),
    reducedMotion = reducedMotion,
    hapticsEnabled = hapticsEnabled,
    highContrast = highContrast,
    refreshRate = enumOrDefault(refreshRate, RefreshRate.TWO_SECONDS),
    cpuPollingEnabled = cpuPollingEnabled,
    memoryPollingEnabled = memoryPollingEnabled,
    batteryPollingEnabled = batteryPollingEnabled,
    networkPollingEnabled = networkPollingEnabled,
    processListPollMultiplier = processListPollMultiplier,
    defaultDurationMinutes = defaultDurationMinutes,
    cpuWarningThreshold = cpuWarningThreshold,
    cpuCriticalThreshold = cpuCriticalThreshold,
    memoryIncreaseWarningMb = memoryIncreaseWarningMb,
    batteryTemperatureWarningDeciCelsius = batteryTemperatureWarningDeciCelsius,
    automaticEventDetection = automaticEventDetection,
    investigationSampleIntervalMillis = investigationSampleIntervalMillis,
    localOnlyMode = localOnlyMode,
    includeSystemAppsInExport = includeSystemAppsInExport,
    exportFormat = enumOrDefault(exportFormat, ExportFormat.JSON),
    shizukuEnabled = shizukuEnabled,
    rootEnabled = rootEnabled,
    showOwnResourceUsage = showOwnResourceUsage,
    showSystemProcesses = showSystemProcesses,
    onboardingCompleted = onboardingCompleted,
)

fun UserSettings.toEntity(): UserSettingsEntity = UserSettingsEntity(
    id = SETTINGS_ROW_ID,
    themeMode = themeMode.name,
    accentColor = accentColor.name,
    useDynamicColor = useDynamicColor,
    glassEffectEnabled = glassEffectEnabled,
    animationIntensity = animationIntensity.name,
    reducedMotion = reducedMotion,
    hapticsEnabled = hapticsEnabled,
    highContrast = highContrast,
    refreshRate = refreshRate.name,
    cpuPollingEnabled = cpuPollingEnabled,
    memoryPollingEnabled = memoryPollingEnabled,
    batteryPollingEnabled = batteryPollingEnabled,
    networkPollingEnabled = networkPollingEnabled,
    processListPollMultiplier = processListPollMultiplier,
    defaultDurationMinutes = defaultDurationMinutes,
    cpuWarningThreshold = cpuWarningThreshold,
    cpuCriticalThreshold = cpuCriticalThreshold,
    memoryIncreaseWarningMb = memoryIncreaseWarningMb,
    batteryTemperatureWarningDeciCelsius = batteryTemperatureWarningDeciCelsius,
    automaticEventDetection = automaticEventDetection,
    investigationSampleIntervalMillis = investigationSampleIntervalMillis,
    localOnlyMode = localOnlyMode,
    includeSystemAppsInExport = includeSystemAppsInExport,
    exportFormat = exportFormat.name,
    shizukuEnabled = shizukuEnabled,
    rootEnabled = rootEnabled,
    showOwnResourceUsage = showOwnResourceUsage,
    showSystemProcesses = showSystemProcesses,
    onboardingCompleted = onboardingCompleted,
)

/**
 * Enum lookup by name with a fallback. Never throws on unrecognised stored text —
 * a value written by a newer build must not crash an older one on downgrade.
 */
private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    if (name == null) default else runCatching { enumValueOf<T>(name) }.getOrDefault(default)
