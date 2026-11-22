// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.itos.coui.theme.LocalCouiDarkTheme

private val OriginBandLightColorScheme = lightColorScheme(
    primary = Color(0xFF007DFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF00326B),
    secondary = Color(0xFF4B627A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEBFF),
    onSecondaryContainer = Color(0xFF00326B),
    tertiary = Color(0xFF00A870),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6F5E8),
    onTertiaryContainer = Color(0xFF003824),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = Color(0xFF5A606B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEDEFF3),
    surfaceContainerHigh = Color(0xFFE5E8EE),
    surfaceContainerHighest = Color(0xFFDDE1E8),
    outline = Color(0xFF8A919E),
    outlineVariant = Color(0xFFE3E6EC),
    error = Color(0xFFE53935),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF6B1414),
)

private val OriginBandDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4BA3FF),
    onPrimary = Color(0xFF002B5C),
    primaryContainer = Color(0xFF0C3868),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFFA7BBD4),
    onSecondary = Color(0xFF1D3248),
    secondaryContainer = Color(0xFF183657),
    onSecondaryContainer = Color(0xFFD6E8FF),
    tertiary = Color(0xFF42D6A4),
    onTertiary = Color(0xFF003825),
    tertiaryContainer = Color(0xFF0B4A35),
    onTertiaryContainer = Color(0xFFD6F5E8),
    background = Color(0xFF000000),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF26272B),
    onSurfaceVariant = Color(0xFFA2A7B2),
    surfaceContainerLowest = Color(0xFF141416),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF242428),
    surfaceContainerHigh = Color(0xFF2C2C31),
    surfaceContainerHighest = Color(0xFF35353B),
    outline = Color(0xFF6C727F),
    outlineVariant = Color(0xFF2C2C31),
    error = Color(0xFFFF6E68),
    onError = Color(0xFF680005),
    errorContainer = Color(0xFF5A1818),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val OriginBandTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun OriginBandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCouiDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) OriginBandDarkColorScheme else OriginBandLightColorScheme,
            typography = OriginBandTypography,
            content = content,
        )
    }
}

fun ComponentActivity.setOriginBandContent(content: @Composable () -> Unit) {
    enableEdgeToEdge()
    setContent {
        OriginBandTheme {
            content()
        }
    }
}
