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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val OriginBandLightColorScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9EDE7),
    onPrimaryContainer = Color(0xFF053B36),
    secondary = Color(0xFF4A6360),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EDE7),
    onSecondaryContainer = Color(0xFF053B36),
    tertiary = Color(0xFF3B5BA9),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDBE2FF),
    onTertiaryContainer = Color(0xFF0F2A66),
    background = Color(0xFFF6F8F8),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF6F8F8),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFE3E8E7),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEDF2F1),
    surfaceContainerHigh = Color(0xFFE6ECEB),
    surfaceContainerHighest = Color(0xFFDFE6E5),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFD6DDDB),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E1),
    onErrorContainer = Color(0xFF5C110C),
)

private val OriginBandDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7FD6CA),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF0B4F48),
    onPrimaryContainer = Color(0xFFC9EDE7),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3532),
    secondaryContainer = Color(0xFF0B4F48),
    onSecondaryContainer = Color(0xFFC9EDE7),
    tertiary = Color(0xFFB3C5FF),
    onTertiary = Color(0xFF0F2A66),
    tertiaryContainer = Color(0xFF24427F),
    onTertiaryContainer = Color(0xFFDBE2FF),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF0F1514),
    onSurface = Color(0xFFDEE4E2),
    surfaceVariant = Color(0xFF2A3331),
    onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF171D1C),
    surfaceContainer = Color(0xFF1B2221),
    surfaceContainerHigh = Color(0xFF252D2C),
    surfaceContainerHighest = Color(0xFF303837),
    outline = Color(0xFF899391),
    outlineVariant = Color(0xFF2F3836),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF6B1B14),
    onErrorContainer = Color(0xFFFFDAD5),
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
    MaterialTheme(
        colorScheme = if (darkTheme) OriginBandDarkColorScheme else OriginBandLightColorScheme,
        typography = OriginBandTypography,
        content = content,
    )
}

fun ComponentActivity.setOriginBandContent(content: @Composable () -> Unit) {
    enableEdgeToEdge()
    setContent {
        OriginBandTheme {
            content()
        }
    }
}
