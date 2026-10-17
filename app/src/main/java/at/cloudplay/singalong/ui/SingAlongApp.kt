package at.cloudplay.singalong.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.cloudplay.singalong.LyricsState
import at.cloudplay.singalong.MainViewModel
import at.cloudplay.singalong.lyrics.LrcParser
import at.cloudplay.singalong.lyrics.LyricLine
import at.cloudplay.singalong.media.NowPlaying
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun SingAlongApp(vm: MainViewModel) {
    val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val offsetMs by vm.offsetMs.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            val track = nowPlaying
            when {
                !hasPermission -> PermissionScreen()
                track == null -> CenterMessage(
                    "Starte einen Song in Spotify, YouTube Music oder einer anderen " +
                        "Musik-App – Titel und Text erscheinen dann hier."
                )
                else -> {
                    val position = rememberPlaybackPosition(track, offsetMs)
                    TrackHeader(track, position)
                    Box(Modifier.weight(1f)) {
                        LyricsPane(lyrics, position, onRetry = vm::retry)
                    }
                    if (lyrics is LyricsState.Synced) {
                        SyncBar(offsetMs, onNudge = vm::nudgeOffset)
                    }
                }
            }
        }
    }
}

/** Tickt die Wiedergabeposition hoch, solange der Song läuft. */
@Composable
private fun rememberPlaybackPosition(track: NowPlaying, offsetMs: Long): State<Long> {
    val position = remember { mutableLongStateOf(track.currentPositionMs() + offsetMs) }
    LaunchedEffect(track, offsetMs) {
        while (true) {
            position.longValue = track.currentPositionMs() + offsetMs
            if (!track.isPlaying) break
            delay(80)
        }
    }
    return position
}

@Composable
private fun TrackHeader(track: NowPlaying, position: State<Long>) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            track.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (track.artist.isNotBlank()) {
            Text(track.artist, style = MaterialTheme.typography.titleMedium, color = colors.primary)
        }
        track.album?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.durationMs > 0) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (position.value.toFloat() / track.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(position.value), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LyricsPane(state: LyricsState, position: State<Long>, onRetry: () -> Unit) {
    when (state) {
        LyricsState.Idle -> {}
        LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is LyricsState.Synced -> KaraokeLyrics(state.lines, position)
        is LyricsState.Plain -> PlainLyrics(state.text)
        LyricsState.Instrumental -> CenterMessage("Instrumental – heute nur mitsummen 🎶")
        LyricsState.NotFound -> CenterMessage("Für diesen Song wurde kein Text gefunden.", "Nochmal suchen", onRetry)
        is LyricsState.Error -> CenterMessage("Fehler beim Laden: ${state.message}", "Erneut versuchen", onRetry)
    }
}

@Composable
private fun KaraokeLyrics(lines: List<LyricLine>, position: State<Long>) {
    val current by remember(lines, position) {
        derivedStateOf { LrcParser.indexAt(lines, position.value) }
    }
    val listState = rememberLazyListState()

    // Aktuelle Zeile ins obere Drittel scrollen
    LaunchedEffect(lines, current) {
        listState.animateScrollToItem(current.coerceAtLeast(0))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = maxHeight * 0.3f, bottom = maxHeight * 0.65f),
        ) {
            itemsIndexed(lines) { index, line ->
                LyricRow(line.text, active = index == current, past = index < current)
            }
        }
    }
}

@Composable
private fun LyricRow(text: String, active: Boolean, past: Boolean) {
    val colors = MaterialTheme.colorScheme
    val color by animateColorAsState(
        targetValue = when {
            active -> colors.primary
            past -> colors.onBackground.copy(alpha = 0.3f)
            else -> colors.onBackground.copy(alpha = 0.6f)
        },
        label = "lyricColor",
    )
    val scale by animateFloatAsState(if (active) 1f else 0.85f, label = "lyricScale")
    Text(
        text = text.ifBlank { "♪ ♪ ♪" },
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
    )
}

@Composable
private fun PlainLyrics(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            "Nur Text ohne Zeitstempel gefunden – er läuft nicht automatisch mit.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SyncBar(offsetMs: Long, onNudge: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { onNudge(-500L) }) { Text("Text später") }
        Text(
            String.format(Locale.GERMANY, "%+.1f s", offsetMs / 1000.0),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { onNudge(500L) }) { Text("Text früher") }
    }
}

@Composable
private fun CenterMessage(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun PermissionScreen() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("🎤 SingAlong", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Damit die App erkennt, welcher Song gerade läuft, braucht sie den " +
                "Benachrichtigungszugriff. Sie liest damit nur die Wiedergabe-Infos deiner " +
                "Musik-Apps aus (Titel, Interpret, Album, Position).",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Zugriff erlauben") }

        Spacer(Modifier.height(32.dp))
        Text(
            "Schalter ausgegraut? Bei selbst installierten APKs (ab Android 13) zuerst " +
                "die App-Infos öffnen, oben rechts auf ⋮ tippen und " +
                "„Eingeschränkte Einstellungen zulassen“ wählen. Danach klappt es.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("App-Infos öffnen") }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", totalSec / 60, totalSec % 60)
}
