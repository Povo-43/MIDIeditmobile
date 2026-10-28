package com.pianocanvas.midi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0E1116)
val Panel = Color(0xFF141A21)
val PanelRaised = Color(0xFF1A222B)
val PanelHigh = Color(0xFF202A35)
val Amber = Color(0xFFF2B65A)
val Teal = Color(0xFF3ED6C4)
val Paper = Color(0xFFE7EDF4)
val Mist = Color(0xFF93A1B1)
val OutlineSoft = Color(0xFF33414F)
val Coral = Color(0xFFFF6F6A)

private val PianoColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF241708),
    primaryContainer = Color(0xFF4A3418),
    onPrimaryContainer = Color(0xFFFFE2B0),
    secondary = Teal,
    onSecondary = Color(0xFF03201D),
    secondaryContainer = Color(0xFF11443F),
    onSecondaryContainer = Color(0xFFB8FFF6),
    tertiary = Color(0xFF9BC7FF),
    onTertiary = Color(0xFF05233D),
    background = Ink,
    onBackground = Paper,
    surface = Panel,
    onSurface = Paper,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = Color(0xFFB9C6D3),
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelRaised,
    surfaceContainerHighest = PanelHigh,
    surfaceContainerLow = Color(0xFF10161D),
    surfaceContainerLowest = Color(0xFF0B0F14),
    outline = OutlineSoft,
    outlineVariant = Color(0xFF26323E),
    error = Coral,
    onError = Color(0xFF2A0807),
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    inversePrimary = Color(0xFF8A5A16),
)

private val PianoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 9.5.sp,
        lineHeight = 12.sp,
    ),
)

@Composable
fun MidiEditMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PianoColors,
        typography = PianoTypography,
        content = content,
    )
}
