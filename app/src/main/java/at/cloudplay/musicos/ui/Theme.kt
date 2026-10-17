package at.cloudplay.musicos.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val KaraokeColors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    secondary = Color(0xFFCE93D8),
    tertiary = Color(0xFF80DEEA),
    background = Color(0xFF120B24),
    surface = Color(0xFF120B24),
)

/** Immer dunkel – Karaoke sieht im Dunkeln einfach besser aus. */
@Composable
fun MusicOsTheme(content: @Composable () -> Unit) {
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        KaraokeColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
