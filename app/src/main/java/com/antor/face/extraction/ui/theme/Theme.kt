package com.antor.face.extraction.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────
//  Color Palette  (consistent with MainScreen design tokens)
// ─────────────────────────────────────────────────────────────
private val DarkColors = darkColorScheme(
    primary          = Color(0xFF4A90E2),   // MALE / main accent
    secondary        = Color(0xFFE24A90),   // FEMALE accent
    tertiary         = Color(0xFF9A6AE2),   // TOTAL / purple
    background       = Color(0xFF0A0A0F),   // matches BG token
    surface          = Color(0xFF111118),   // matches SURFACE token
    surfaceVariant   = Color(0xFF161620),   // SURFACE2
    error            = Color(0xFFE24A4A),   // ERR
    onPrimary        = Color.White,
    onSecondary      = Color.White,
    onTertiary       = Color.White,
    onBackground     = Color.White,
    onSurface        = Color.White,
    onSurfaceVariant = Color(0xFF555566),   // IDLE
    onError          = Color.White,
)

@Composable
fun FaceExtractionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content
    )
}