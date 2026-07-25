package com.example.netguardzte.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Green700,
    onPrimary = Color.White,
    primaryContainer = Green100,
    secondary = Blue900,
    surface = Color.White,
    surfaceVariant = Gray100,
    background = Gray50,
    error = Red500
)

private val Dark = darkColorScheme(
    primary = Green500,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF64B5F6),
    surface = DarkSurface,
    surfaceVariant = Color(0xFF2C2C2C),
    background = DarkBackground,
    error = Color(0xFFEF5350)
)

@Composable
fun NetGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
