package dev.aifih.volare.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Accent = Color(0xFF6C8CFF)
private val AccentDark = Color(0xFF9BB2FF)

private val LightScheme = lightColorScheme(
    primary = Accent,
    secondary = Color(0xFF5B6478),
    tertiary = Color(0xFF7A5EA7),
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    secondary = Color(0xFFBEC6DC),
    tertiary = Color(0xFFCFBCFF),
)

@Composable
fun VolareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
