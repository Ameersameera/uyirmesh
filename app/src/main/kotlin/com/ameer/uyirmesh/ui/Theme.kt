package com.ameer.uyirmesh.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph

object Palette {
    val Red = Color(0xFFD32F2F)
    val RedDark = Color(0xFFB71C1C)
    val Orange = Color(0xFFEF6C00)
    val Amber = Color(0xFFFFA000)
    val Green = Color(0xFF2E7D32)
    val Blue = Color(0xFF1565C0)
    val Teal = Color(0xFF00897B)
    val Purple = Color(0xFF6A1B9A)
    val Grey = Color(0xFF757575)

    fun priority(label: String): Color = when (label) {
        "Critical" -> Red
        "High" -> Orange
        "Medium" -> Amber
        else -> Green
    }
}

private val Light = lightColorScheme(
    primary = Palette.Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Palette.Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3FF),
    onSecondaryContainer = Color(0xFF001B3E),
    tertiary = Palette.Teal,
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF3EDEC),
    error = Palette.RedDark,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFA9C7FF),
    onSecondary = Color(0xFF003063),
    secondaryContainer = Color(0xFF00468C),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF151212),
    surface = Color(0xFF151212),
    surfaceVariant = Color(0xFF2C2524),
)

@Composable
fun UyirTheme(content: @Composable () -> Unit) {
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    val dark = when (s.theme) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val density = LocalDensity.current
    val scaled = if (s.largeText) Density(density.density, density.fontScale * 1.25f) else density
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
    }
}
