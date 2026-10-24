package com.smartmanager.installments.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Brand {
    val Navy = Color(0xFF0B2A5B)
    val Blue = Color(0xFF1E6FEB)
    val Orange = Color(0xFFFF8A00)
    val Green = Color(0xFF1FA463)
    val Red = Color(0xFFE5484D)
    val Background = Color(0xFFF3F7FC)
    val Surface = Color(0xFFFFFFFF)
    val BlueSoft = Color(0xFFE6F0FF)
    val GreenSoft = Color(0xFFE3F6EC)
    val RedSoft = Color(0xFFFCE8E9)
    val OrangeSoft = Color(0xFFFFF1DE)
    val Muted = Color(0xFF5B6B84)
}

private val Scheme = lightColorScheme(
    primary = Brand.Blue, onPrimary = Color.White, primaryContainer = Brand.BlueSoft, onPrimaryContainer = Brand.Navy,
    secondary = Brand.Navy, onSecondary = Color.White, secondaryContainer = Brand.BlueSoft, onSecondaryContainer = Brand.Navy,
    tertiary = Brand.Orange, onTertiary = Color.White, tertiaryContainer = Brand.OrangeSoft, onTertiaryContainer = Color(0xFF5C3300),
    background = Brand.Background, onBackground = Brand.Navy, surface = Brand.Surface, onSurface = Brand.Navy,
    surfaceVariant = Color(0xFFE9EFF8), onSurfaceVariant = Brand.Muted, outline = Color(0xFFB8C4D6), outlineVariant = Color(0xFFDCE4F0),
    error = Brand.Red, onError = Color.White, errorContainer = Brand.RedSoft, onErrorContainer = Color(0xFF6B1014),
    surfaceContainerLowest = Brand.Surface, surfaceContainerLow = Brand.Surface, surfaceContainer = Color(0xFFEFF4FB),
    surfaceContainerHigh = Color(0xFFE9EFF8), surfaceContainerHighest = Color(0xFFE3EAF5),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SmartManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
