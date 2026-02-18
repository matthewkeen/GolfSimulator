package com.golfsim.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Golf Simulator color palette
val GolfGreen = Color(0xFF1B5E20)
val GolfGreenLight = Color(0xFF4CAF50)
val GolfGreenDark = Color(0xFF0A2E0A)
val FairwayGreen = Color(0xFF2E7D32)
val GoldAccent = Color(0xFFFFD700)
val SkyBlue = Color(0xFF0288D1)
val SandBunker = Color(0xFFDEB887)
val WaterHazard = Color(0xFF1976D2)
val RoughGreen = Color(0xFF388E3C)
val BallWhite = Color(0xFFF5F5F5)
val HUDBackground = Color(0xCC000000)

val DarkBackground = Color(0xFF0D1B0D)
val DarkSurface = Color(0xFF1A2E1A)
val DarkSurfaceVariant = Color(0xFF243524)

private val GolfDarkColorScheme = darkColorScheme(
    primary = GolfGreenLight,
    onPrimary = Color.White,
    primaryContainer = GolfGreenDark,
    onPrimaryContainer = GolfGreenLight,
    secondary = GoldAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3D3000),
    onSecondaryContainer = GoldAccent,
    tertiary = SkyBlue,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color(0xFFE8F5E9),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFA5D6A7),
    outline = Color(0xFF4CAF50),
    error = Color(0xFFCF6679),
    onError = Color.Black
)

@Composable
fun GolfSimulatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GolfDarkColorScheme,
        typography = Typography(),
        content = content
    )
}
