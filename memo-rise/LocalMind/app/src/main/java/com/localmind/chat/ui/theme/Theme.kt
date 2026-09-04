package com.localmind.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Two accents do real work in this app, so they're first-class tokens rather than
 * decoration: Mint marks data that has never left the phone, Slate-blue marks data
 * that has a server copy. Once those two carry meaning, nothing else is allowed to
 * be saturated — otherwise the signal stops reading.
 */
object Ink {
    val Base = Color(0xFF16202A)      // deep blue-grey, not a tinted black
    val Surface = Color(0xFF1E2A35)
    val Raised = Color(0xFF26343F)
    val Hairline = Color(0xFF33434F)

    val Text = Color(0xFFE4EAF0)
    val Muted = Color(0xFF8A9BA8)

    val Mint = Color(0xFF7FD4B0)      // on this device only
    val Cloud = Color(0xFF7FA9E8)     // backed up
    val Warn = Color(0xFFE08A7A)
}

object Paper {
    val Base = Color(0xFFF7F9FA)
    val Surface = Color(0xFFFFFFFF)
    val Raised = Color(0xFFEDF1F4)
    val Hairline = Color(0xFFD5DEE4)

    val Text = Color(0xFF16202A)
    val Muted = Color(0xFF5C6B77)

    val Mint = Color(0xFF1F8F68)
    val Cloud = Color(0xFF2E6CB8)
    val Warn = Color(0xFFB4503C)
}

/** Colours the app needs that Material's scheme has no slot for. */
data class ChatColors(
    val local: Color,
    val cloud: Color,
    val hairline: Color,
    val muted: Color,
    val raised: Color
)

private val darkScheme = darkColorScheme(
    background = Ink.Base,
    onBackground = Ink.Text,
    surface = Ink.Surface,
    onSurface = Ink.Text,
    surfaceVariant = Ink.Raised,
    onSurfaceVariant = Ink.Muted,
    primary = Ink.Mint,
    onPrimary = Ink.Base,
    secondary = Ink.Cloud,
    error = Ink.Warn,
    outline = Ink.Hairline
)

private val lightScheme = lightColorScheme(
    background = Paper.Base,
    onBackground = Paper.Text,
    surface = Paper.Surface,
    onSurface = Paper.Text,
    surfaceVariant = Paper.Raised,
    onSurfaceVariant = Paper.Muted,
    primary = Paper.Mint,
    onPrimary = Color.White,
    secondary = Paper.Cloud,
    error = Paper.Warn,
    outline = Paper.Hairline
)

/**
 * One family, four sizes. Message text sits at 16sp with generous leading because it's
 * read in long runs; metadata drops to 12sp and relies on colour, not caps, to recede.
 *
 * To swap in a bundled typeface, drop the .ttf into res/font and replace
 * FontFamily.Default here — every size below is defined relative to body text.
 */
private val family = FontFamily.Default

private val typography = Typography(
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp // ~1.55, comfortable for multi-line replies
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun LocalMindTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val chatColors = if (dark) {
        ChatColors(Ink.Mint, Ink.Cloud, Ink.Hairline, Ink.Muted, Ink.Raised)
    } else {
        ChatColors(Paper.Mint, Paper.Cloud, Paper.Hairline, Paper.Muted, Paper.Raised)
    }

    MaterialTheme(
        colorScheme = if (dark) darkScheme else lightScheme,
        typography = typography
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalChatColors provides chatColors,
            content = content
        )
    }
}

val LocalChatColors = androidx.compose.runtime.staticCompositionLocalOf {
    ChatColors(Ink.Mint, Ink.Cloud, Ink.Hairline, Ink.Muted, Ink.Raised)
}
