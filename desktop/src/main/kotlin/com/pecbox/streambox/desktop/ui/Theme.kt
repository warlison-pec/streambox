package com.pecbox.streambox.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* Paleta BOX: preto e laranja (a mesma do app Android) */
val Orange = Color(0xFFFF7A00)
val OrangeSoft = Color(0xFFFF9A3D)
val Black = Color(0xFF0A0A0A)
val Surface = Color(0xFF141414)
val Surface2 = Color(0xFF1E1E1E)
val Surface3 = Color(0xFF2A2A2A)
val TextMain = Color(0xFFF2F2F2)
val TextMuted = Color(0xFF9A9A9A)
val Success = Color(0xFF34C77B)
val Danger = Color(0xFFFF5A5F)

private val Scheme = darkColorScheme(
    primary = Orange,
    onPrimary = Color.Black,
    secondary = OrangeSoft,
    onSecondary = Color.Black,
    background = Black,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    outline = Surface3,
    error = Danger,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun StreamBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
