package com.processlens.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.processlens.domain.model.AccentColor
import com.processlens.domain.model.ThemeMode
import com.processlens.domain.model.UserSettings

/**
 * The app theme (Sections 3, 4, 35, 38, 39).
 *
 * Three things are threaded through composition alongside Material's own scheme,
 * because Material 3 has no slot for them and every screen needs them:
 *
 * - [LocalAccent] — the resolved accent ramp, so a chart can reach for a tonal
 *   step rather than approximating one from `colorScheme.primary`.
 * - [LocalGlass] — glass-card parameters, which the user can switch off entirely.
 * - [LocalMotion] — whether animation is permitted, honouring both the app's own
 *   setting *and* the platform accessibility setting (Section 49).
 */
private val LocalAccentRamp = staticCompositionLocalOf { AccentRamp.from(AccentColor.INDIGO) }
private val LocalGlassSpec = staticCompositionLocalOf { GlassSpec.enabled(true) }
private val LocalMotionSpec = staticCompositionLocalOf { MotionSpec(enabled = true, scale = 1f) }
private val LocalHaptics = staticCompositionLocalOf { true }
private val LocalIsDark = staticCompositionLocalOf { true }

object ProcessLensTheme {
    val accent: AccentRamp @Composable get() = LocalAccentRamp.current
    val glass: GlassSpec @Composable get() = LocalGlassSpec.current
    val motion: MotionSpec @Composable get() = LocalMotionSpec.current
    val hapticsEnabled: Boolean @Composable get() = LocalHaptics.current
    val isDark: Boolean @Composable get() = LocalIsDark.current
}

/**
 * Glass-card parameters (Section 3).
 *
 * Real blur (`Modifier.blur` on a background layer) is API 31+ and costs a render
 * pass per card — on a screen with a dozen cards, on a device this app might be
 * investigating *because* it is struggling, that is the tool becoming the problem
 * (Section 43). So "glass" here is a layered translucent fill plus a light-catching
 * hairline border, which reads as glass at card scale and costs one draw call.
 */
@Immutable
data class GlassSpec(
    val enabled: Boolean,
    val fillAlpha: Float,
    val highlightAlpha: Float,
    val borderAlpha: Float,
) {
    companion object {
        fun enabled(on: Boolean): GlassSpec =
            if (on) GlassSpec(true, 0.55f, 0.07f, 0.55f) else GlassSpec(false, 1f, 0f, 1f)
    }
}

@Immutable
data class MotionSpec(val enabled: Boolean, val scale: Float) {
    /** Scaled duration; returns 0 when motion is off so animations resolve instantly. */
    fun duration(base: Int): Int = if (!enabled) 0 else (base * scale).toInt().coerceAtLeast(1)
}

@Composable
fun ProcessLensTheme(
    settings: UserSettings,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val accent = remember(settings.accentColor) { AccentRamp.from(settings.accentColor) }
    val context = LocalContext.current

    // Dynamic colour is offered because Section 39 asks for it, but it is opt-in
    // and off by default: a wallpaper-derived palette can put the severity colours
    // and the accent close enough together to blur the distinction, and this app's
    // whole point is that a warning must not look like an ordinary reading.
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = remember(dark, settings.accentColor, settings.useDynamicColor, settings.highContrast) {
        when {
            settings.useDynamicColor && supportsDynamic && dark -> dynamicDarkColorScheme(context)
            settings.useDynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
            dark -> darkScheme(accent, settings.highContrast)
            else -> lightScheme(accent, settings.highContrast)
        }
    }

    // Reduced motion has two sources and either is sufficient: the in-app toggle,
    // and the platform's own animator scale (which a user sets once, system-wide,
    // and should not have to set again per app).
    val systemMotionDisabled = remember(context) { isSystemAnimationDisabled(context) }
    val motion = MotionSpec(
        enabled = !settings.motionDisabled && !systemMotionDisabled,
        scale = settings.animationIntensity.scale,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalAccentRamp provides accent,
        LocalGlassSpec provides GlassSpec.enabled(settings.glassEffectEnabled),
        LocalMotionSpec provides motion,
        LocalHaptics provides settings.hapticsEnabled,
        LocalIsDark provides dark,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ProcessLensTypography,
            shapes = ProcessLensShapes,
            content = content,
        )
    }
}

/**
 * The dark scheme. Hand-mapped rather than generated from a seed because Material's
 * generator produces a mid-grey surface family that flattens the elevation steps
 * this app relies on to separate a card from the canvas behind it.
 */
private fun darkScheme(accent: AccentRamp, highContrast: Boolean): ColorScheme {
    val text = if (highContrast) Color.White else Palette.TextPrimary
    val secondary = if (highContrast) Palette.TextPrimary else Palette.TextSecondary
    val outline = if (highContrast) Color(0xFF4A5263) else Palette.Outline
    return darkColorScheme(
        primary = accent.onDarkText,
        onPrimary = accent.base.readableForeground(),
        primaryContainer = accent.container,
        onPrimaryContainer = accent.onDarkText,
        secondary = accent.bright,
        onSecondary = accent.base.readableForeground(),
        secondaryContainer = Palette.Surface3,
        onSecondaryContainer = text,
        tertiary = Palette.Info,
        onTertiary = Color(0xFF07080C),
        background = Palette.Ink,
        onBackground = text,
        surface = Palette.Surface1,
        onSurface = text,
        surfaceVariant = Palette.Surface2,
        onSurfaceVariant = secondary,
        surfaceContainer = Palette.Surface2,
        surfaceContainerHigh = Palette.Surface3,
        surfaceContainerHighest = Palette.Surface3,
        surfaceContainerLow = Palette.Surface1,
        surfaceContainerLowest = Palette.Ink,
        inverseSurface = Palette.TextPrimary,
        inverseOnSurface = Palette.Ink,
        outline = outline,
        outlineVariant = Palette.OutlineSoft,
        error = Palette.Critical,
        onError = Color.White,
        errorContainer = Palette.CriticalDim.copy(alpha = 0.28f),
        onErrorContainer = Color(0xFFFFB4B7),
        scrim = Color(0xCC000000),
    )
}

private fun lightScheme(accent: AccentRamp, highContrast: Boolean): ColorScheme {
    val text = if (highContrast) Color(0xFF05070B) else Palette.LightTextPrimary
    val secondary = if (highContrast) Palette.LightTextPrimary else Palette.LightTextSecondary
    val outline = if (highContrast) Color(0xFF9AA3B4) else Palette.LightOutline
    return lightColorScheme(
        primary = accent.onLightText,
        onPrimary = Color.White,
        primaryContainer = accent.containerLight,
        onPrimaryContainer = accent.onLightText,
        secondary = accent.base,
        onSecondary = Color.White,
        secondaryContainer = Palette.LightSurface3,
        onSecondaryContainer = text,
        tertiary = Palette.LightInfo,
        onTertiary = Color.White,
        background = Palette.LightCanvas,
        onBackground = text,
        surface = Palette.LightSurface1,
        onSurface = text,
        surfaceVariant = Palette.LightSurface2,
        onSurfaceVariant = secondary,
        surfaceContainer = Palette.LightSurface2,
        surfaceContainerHigh = Palette.LightSurface3,
        surfaceContainerHighest = Palette.LightSurface3,
        surfaceContainerLow = Palette.LightSurface1,
        surfaceContainerLowest = Color.White,
        inverseSurface = Palette.LightTextPrimary,
        inverseOnSurface = Palette.LightCanvas,
        outline = outline,
        outlineVariant = Palette.LightOutlineSoft,
        error = Palette.LightCritical,
        onError = Color.White,
        errorContainer = Color(0xFFFDE7E8),
        onErrorContainer = Color(0xFF7A1518),
        scrim = Color(0x99000000),
    )
}

/**
 * Reads the platform animator scale. `Settings.Global.ANIMATOR_DURATION_SCALE` set
 * to 0 is how a user says "no animations" to the whole system, including from
 * Developer options and from the accessibility "Remove animations" toggle.
 */
private fun isSystemAnimationDisabled(context: android.content.Context): Boolean = try {
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
} catch (t: Throwable) {
    false
}

/** Layout constants, kept in one place so spacing stays consistent across screens. */
object Dimens {
    val screenPadding = 16.dp
    val cardPadding = 16.dp
    val cardSpacing = 12.dp
    val tileSpacing = 10.dp
    val sectionSpacing = 22.dp
    val rowHeight = 56.dp
    val listItemHeight = 68.dp
    /** Section 49: minimum 48dp touch targets. */
    val minTouchTarget = 48.dp
    val iconSmall = 16.dp
    val iconMedium = 20.dp
    val iconLarge = 24.dp
    val chartHeight = 132.dp
    val sparklineHeight = 40.dp
    val hairline = 1.dp
    val timelineDot = 12.dp
    val timelineRail = 2.dp
}
