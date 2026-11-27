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
    primary = Color(0xFFC6AEFF),
    onPrimary = Color(0xFF28164B),
    primaryContainer = Color(0xFF38265B),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFD0C2F5),
    onSecondary = Color(0xFF30234C),
    secondaryContainer = Color(0xFF40345E),
    onSecondaryContainer = Color(0xFFE8DEFF),
    tertiary = Color(0xFFE2B9D2),
    onTertiary = Color(0xFF44263B),
    background = Color(0xFF090F16),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF0D151E),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF1A2733),
    onSurfaceVariant = Color(0xFFAAB8C6),
    surfaceContainerLowest = Color(0xFF070C12),
    surfaceContainerLow = Color(0xFF101923),
    surfaceContainer = Color(0xFF15212C),
    surfaceContainerHigh = Color(0xFF1B2935),
    surfaceContainerHighest = Color(0xFF243441),
    outline = Color(0xFF536574),
    outlineVariant = Color(0xFF2C3B48),
    error = Color(0xFFFF6B78),
    onError = Color(0xFF390009)
)

private val SniperLight = lightColorScheme(
    primary = Color(0xFF7652B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF28164B),
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
