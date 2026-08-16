package com.processlens.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.processlens.domain.model.AccentColor
import com.processlens.domain.model.Availability
import com.processlens.domain.model.EventSeverity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The palette (Sections 3, 4, 35).
 *
 * Dark is the default and the primary design target: a system observatory is used
 * in dim rooms and next to logcat, and a near-black canvas makes a coloured
 * severity dot legible in a way a white one does not. Light mode is a real,
 * fully-specified theme rather than an inverted afterthought (Section 39).
 *
 * Accent colours are *derived* from one seed each rather than hand-written five
 * times over. Five hand-tuned palettes drift: someone fixes contrast on indigo and
 * forgets teal. Deriving them from [AccentColor.seed] with the same tone curve
 * means every accent gets the same contrast guarantees for free, and adding a sixth
 * accent is one line in the enum.
 */
object Palette {

    // Neutral canvas. Not pure black: a true #000 makes elevation invisible on
    // OLED, so surfaces would be indistinguishable from the void behind them.
    val Ink = Color(0xFF07080C)
    val Surface1 = Color(0xFF0E1017)
    val Surface2 = Color(0xFF14171F)
    val Surface3 = Color(0xFF1B1F29)
    val Outline = Color(0xFF2A2F3C)
    val OutlineSoft = Color(0xFF1E222D)

    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFFA8AFBF)
    val TextTertiary = Color(0xFF6F7788)

    val LightCanvas = Color(0xFFF7F8FB)
    val LightSurface1 = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFF1F3F8)
    val LightSurface3 = Color(0xFFE7EAF2)
    val LightOutline = Color(0xFFD3D8E4)
    val LightOutlineSoft = Color(0xFFE4E8F0)
    val LightTextPrimary = Color(0xFF11141B)
    val LightTextSecondary = Color(0xFF4C5464)
    val LightTextTertiary = Color(0xFF767E8F)

    /**
     * Status colours.
     *
     * Section 49 forbids communicating state through colour alone, so every place
     * these appear is paired with an icon and a text label. They exist to make a
     * *already-labelled* state faster to scan, not to carry the meaning.
     */
    val Success = Color(0xFF34C77B)
    val SuccessDim = Color(0xFF1F7A4C)
    val Warning = Color(0xFFE0952B)
    val WarningDim = Color(0xFF8A5C16)
    val Critical = Color(0xFFE5484D)
    val CriticalDim = Color(0xFF8F2226)
    val Info = Color(0xFF5B9DE8)
    val Neutral = Color(0xFF7C8598)

    val LightSuccess = Color(0xFF1B8A54)
    val LightWarning = Color(0xFF9A6413)
    val LightCritical = Color(0xFFC02A2F)
    val LightInfo = Color(0xFF2A6DB5)
    val LightNeutral = Color(0xFF5E6779)
}

/**
 * A resolved accent ramp.
 *
 * [base] is the seed as authored; the tonal steps are what the UI actually paints
 * with, because a saturated seed at full strength is unreadable as body text on a
 * dark surface and too heavy as a large fill.
 */
data class AccentRamp(
    val base: Color,
    val bright: Color,
    val onDarkText: Color,
    val onLightText: Color,
    val container: Color,
    val containerLight: Color,
    val glow: Color,
) {
    companion object {
        fun from(accent: AccentColor): AccentRamp {
            val base = Color(accent.seed.toInt())
            return AccentRamp(
                base = base,
                bright = base.shiftLightness(+0.12f),
                // Text-weight tint: lifted and slightly desaturated so a label
                // reads as text rather than as a highlight.
                onDarkText = base.shiftLightness(+0.22f).desaturate(0.18f),
                onLightText = base.shiftLightness(-0.22f),
                container = base.copy(alpha = 0.16f),
                containerLight = base.copy(alpha = 0.12f),
                glow = base.copy(alpha = 0.28f),
            )
        }
    }
}

/** Severity colour, always accompanied in the UI by an icon and a text label. */
fun EventSeverity.color(dark: Boolean): Color = when (this) {
    EventSeverity.INFO -> if (dark) Palette.Info else Palette.LightInfo
    EventSeverity.NORMAL -> if (dark) Palette.Neutral else Palette.LightNeutral
    EventSeverity.WARNING -> if (dark) Palette.Warning else Palette.LightWarning
    EventSeverity.CRITICAL -> if (dark) Palette.Critical else Palette.LightCritical
}

fun Availability.color(dark: Boolean): Color = when (this) {
    Availability.FULL -> if (dark) Palette.Success else Palette.LightSuccess
    Availability.LIMITED -> if (dark) Palette.Warning else Palette.LightWarning
    Availability.UNAVAILABLE -> if (dark) Palette.Neutral else Palette.LightNeutral
}

/**
 * Load colour for a 0..1 utilisation fraction.
 *
 * Deliberately a step function rather than a continuous gradient: a smooth hue
 * ramp encodes the number twice, once in the digits and once in a hue nobody can
 * read precisely, and it makes "is this bad?" harder to answer at a glance than
 * three clear bands do.
 */
fun loadColor(fraction: Float, accent: AccentRamp, dark: Boolean): Color = when {
    fraction.isNaN() -> if (dark) Palette.Neutral else Palette.LightNeutral
    fraction >= 0.85f -> if (dark) Palette.Critical else Palette.LightCritical
    fraction >= 0.6f -> if (dark) Palette.Warning else Palette.LightWarning
    else -> accent.base
}

// ------------------------------------------------------------------ colour maths

/**
 * HSL lightness shift.
 *
 * Hand-rolled rather than taken from a colour library because the app ships no
 * colour dependency and this is the only transform needed. Operating in HSL keeps
 * hue fixed, so a lifted indigo is still recognisably the same accent — which
 * naive RGB scaling does not preserve.
 */
fun Color.shiftLightness(delta: Float): Color {
    val (h, s, l) = toHsl()
    return hslToColor(h, s, (l + delta).coerceIn(0f, 1f), alpha)
}

fun Color.desaturate(amount: Float): Color {
    val (h, s, l) = toHsl()
    return hslToColor(h, (s - amount).coerceIn(0f, 1f), l, alpha)
}

/** Blend towards [other]; used for chart bands and pressed states. */
fun Color.mix(other: Color, fraction: Float): Color = lerp(this, other, fraction.coerceIn(0f, 1f))

private fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, l)

    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + if (g < b) 6f else 0f)
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } / 6f
    return Triple(h, s, l)
}

private fun hslToColor(h: Float, s: Float, l: Float, alpha: Float): Color {
    if (s == 0f) return Color(l, l, l, alpha)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f), alpha)
}

/**
 * WCAG relative luminance, used to pick a readable foreground over an arbitrary
 * accent. Needed because the accent is user-chosen: amber and indigo do not take
 * the same text colour, and guessing wrong makes a button unreadable.
 */
fun Color.relativeLuminance(): Float {
    fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * lin(red) + 0.7152f * lin(green) + 0.0722f * lin(blue)
}

fun Color.contrastRatio(other: Color): Float {
    val a = relativeLuminance() + 0.05f
    val b = other.relativeLuminance() + 0.05f
    return if (a > b) a / b else b / a
}

/** Black or white, whichever is legible on this colour. */
fun Color.readableForeground(): Color =
    if (relativeLuminance() > 0.42f) Color(0xFF0A0B0F) else Color(0xFFFFFFFF)

/** Percentage contrast figure for the accessibility section of Settings. */
fun contrastLabel(foreground: Color, background: Color): String {
    val ratio = foreground.contrastRatio(background)
    return "${(ratio * 10).roundToInt() / 10.0}:1"
}

/**
 * A stable colour for an arbitrary identifier — process names on the tree and
 * per-core chart series. Derived from the hash so the same process keeps the same
 * colour across refreshes; a colour that jumped every two seconds would make the
 * chart unreadable.
 */
fun stableColorFor(key: String, accent: AccentRamp, dark: Boolean): Color {
    val hue = (abs(key.hashCode()) % 360) / 360f
    val saturation = if (dark) 0.52f else 0.58f
    val lightness = if (dark) 0.62f else 0.44f
    return hslToColor(hue, saturation, lightness, 1f).mix(accent.base, 0.12f)
}
