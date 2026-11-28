package com.thisisvista.dnssniperx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SniperDark = darkColorScheme(
    primary = Color(0xFFB34CFF),
    onPrimary = Color(0xFF260B3E),
    primaryContainer = Color(0xFF321447),
    onPrimaryContainer = Color(0xFFF4E7FF),
    secondary = Color(0xFFD6B4F4),
    onSecondary = Color(0xFF2C153A),
    secondaryContainer = Color(0xFF3B214C),
    onSecondaryContainer = Color(0xFFF1DEFF),
    tertiary = Color(0xFFE2B9D2),
    onTertiary = Color(0xFF44263B),
    background = Color(0xFF100F14),
    onBackground = Color(0xFFF1EDF5),
    surface = Color(0xFF141118),
    onSurface = Color(0xFFF1EDF5),
    surfaceVariant = Color(0xFF211B27),
    onSurfaceVariant = Color(0xFFBDB4C5),
    surfaceContainerLowest = Color(0xFF0D0B10),
    surfaceContainerLow = Color(0xFF17131B),
    surfaceContainer = Color(0xFF1D1822),
    surfaceContainerHigh = Color(0xFF261F2C),
    surfaceContainerHighest = Color(0xFF302638),
    outline = Color(0xFF6D5A78),
    outlineVariant = Color(0xFF3B3042),
    error = Color(0xFFFF6B78),
    onError = Color(0xFF390009)
)

private val SniperLight = lightColorScheme(
    primary = Color(0xFF7652B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF260B3E),
    secondary = Color(0xFF66558A),
    onSecondary = Color.White,
    background = Color(0xFFF5F8FA),
    onBackground = Color(0xFF17212B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17212B),
    surfaceVariant = Color(0xFFE5EDF1),
    onSurfaceVariant = Color(0xFF465660),
    surfaceContainer = Color(0xFFEBF1F4),
    surfaceContainerHigh = Color(0xFFE3EBEF),
    outline = Color(0xFF71818A),
    outlineVariant = Color(0xFFD0DCE2)
)

@Composable
fun DnsSniperXTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) SniperDark else SniperLight
    val baseTypography = androidx.compose.material3.Typography()
    val typography = baseTypography.copy(
        headlineMedium = baseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = baseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        content = content
    )
}
