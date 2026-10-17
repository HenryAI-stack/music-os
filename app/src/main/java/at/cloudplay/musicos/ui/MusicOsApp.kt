package at.cloudplay.musicos.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.cloudplay.musicos.LyricsState
import at.cloudplay.musicos.MIC_SOURCE
import at.cloudplay.musicos.MainViewModel
import at.cloudplay.musicos.MicState
import at.cloudplay.musicos.UpdateState
import at.cloudplay.musicos.history.HistoryEntry
import at.cloudplay.musicos.lyrics.LrcParser
import at.cloudplay.musicos.lyrics.LyricLine
import at.cloudplay.musicos.media.NowPlaying
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MusicOsApp(vm: MainViewModel) {
    val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val offsetMs by vm.offsetMs.collectAsStateWithLifecycle()
    val micState by vm.micState.collectAsStateWithLifecycle()
    val historyEntries by vm.historyEntries.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showHistory) { showHistory = false }

    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startListening() else vm.onMicPermissionDenied() }
    val onMicClick: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            if (showHistory) {
                HistoryScreen(
                    entries = historyEntries,
                    onBack = { showHistory = false },
                    onDelete = vm::deleteHistoryEntry,
                    onClearAll = vm::clearHistory,
                )
            } else {
                TopBar(onHistoryClick = { showHistory = true })
                UpdateBanner(update, onUpdate = vm::startUpdate, onDismiss = vm::dismissUpdate)

                val track = nowPlaying
                if (track == null) {
                    Box(Modifier.weight(1f)) {
                        HomeScreen(
                            versionName = vm.versionName,
                            hasNotificationAccess = hasPermission,
                            listening = micState is MicState.Listening,
                            onMicClick = onMicClick,
                        )
                    }
                    MicBar(
                        state = micState,
                        showButton = false,
                        isMicTrack = false,
                        onMicClick = onMicClick,
                        onCancel = vm::cancelListening,
                        onDismiss = vm::dismissMicMessage,
                    )
                } else {
                    val position = rememberPlaybackPosition(track, offsetMs)
                    val isMicTrack = track.packageName == MIC_SOURCE
                    TrackHeader(track, position, isMicTrack, onClose = vm::closeMicTrack)
                    Box(Modifier.weight(1f)) {
                        LyricsPane(lyrics, position, onRetry = vm::retry)
                    }
                    if (lyrics is LyricsState.Synced) {
                        SyncBar(offsetMs, onNudge = vm::nudgeOffset)
                    }
                    MicBar(
                        state = micState,
                        showButton = true,
                        isMicTrack = isMicTrack,
                        onMicClick = onMicClick,
                        onCancel = vm::cancelListening,
                        onDismiss = vm::dismissMicMessage,
                    )
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

// ---------- Startbildschirm ----------

@Composable
private fun HomeScreen(
    versionName: String,
    hasNotificationAccess: Boolean,
    listening: Boolean,
    onMicClick: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onMicClick,
            enabled = !listening,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) { Text("🎤  Song über Mikrofon erkennen", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Halte das Handy Richtung Musik – nach 4 bis 12 Sekunden steht der Song fest, " +
                "und der Text läuft synchron mit.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("Musik auf diesem Handy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (hasNotificationAccess) {
            Text(
                "Starte einen Song in Spotify, YouTube Music oder einer anderen Musik-App – " +
                    "er erscheint hier automatisch.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        } else {
            Text(
                "Damit Music-os automatisch erkennt, was auf dem Handy läuft, braucht es den " +
                    "Benachrichtigungszugriff. Gelesen werden nur die Wiedergabe-Infos deiner " +
                    "Musik-Apps (Titel, Interpret, Album, Position).",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Zugriff erlauben") }
            Spacer(Modifier.height(12.dp))
            Text(
                "Schalter ausgegraut? Bei selbst installierten APKs (ab Android 13) zuerst die " +
                    "App-Infos öffnen, oben rechts auf ⋮ tippen und „Eingeschränkte Einstellungen " +
                    "zulassen“ wählen.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
            ) { Text("App-Infos öffnen") }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Version $versionName",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

// ---------- Kopfleiste & Updates ----------

@Composable
private fun TopBar(onHistoryClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "🎤 Music-os",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onHistoryClick) { Text("🕘 Verlauf") }
    }
}

@Composable
private fun UpdateBanner(state: UpdateState, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    if (state is UpdateState.None) return
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is UpdateState.Available -> {
                    Text("⬆️ Neue Version ${state.info.versionName}", style = MaterialTheme.typography.titleSmall)
                    val firstLine = state.info.notes.lineSequence().firstOrNull { it.isNotBlank() }
                    if (firstLine != null) {
                        Text(
                            firstLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("Später") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onUpdate) { Text("Aktualisieren") }
                    }
                }
                is UpdateState.Downloading -> {
                    Text("Update ${state.info.versionName} wird geladen …", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                UpdateState.Installing -> {
                    Text("Wird installiert …", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Die App schließt sich gleich kurz – danach einfach wieder öffnen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is UpdateState.Message -> {
                    Text(state.text, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("Schließen") }
                        Spacer(Modifier.weight(1f))
                        if (state.info != null) Button(onClick = onUpdate) { Text("Nochmal") }
                    }
                }
                UpdateState.None -> Unit
            }
        }
    }
}

// ---------- Verlauf ----------

@Composable
private fun HistoryScreen(
    entries: List<HistoryEntry>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text(
                "Verlauf",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Alle löschen") }
            }
        }
        if (entries.isEmpty()) {
            CenterMessage("Noch keine Songs erkannt. Erkannte Songs erscheinen hier – sie bleiben nur auf diesem Handy.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(entry, onDelete = { onDelete(entry.id) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Verlauf löschen?") },
            text = { Text("Alle ${entries.size} Einträge werden entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(entry.artist.takeIf { it.isNotBlank() }, entry.album).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    (if (entry.viaMic) "🎤 Mikrofon" else "📱 Handy") + " · " + formatHistoryTime(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Aus Verlauf löschen", tint = colors.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    }
}

private val historyTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
private val historyDateFormat = DateTimeFormatter.ofPattern("EE, dd.MM.yyyy", Locale.GERMANY)

private fun formatHistoryTime(timestamp: Long): String {
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (dateTime.toLocalDate()) {
        today -> "Heute"
        today.minusDays(1) -> "Gestern"
        else -> historyDateFormat.format(dateTime)
    }
    return "$day, ${historyTimeFormat.format(dateTime)}"
}

// ---------- Mikrofon-Leiste ----------

@Composable
private fun MicBar(
    state: MicState,
    showButton: Boolean,
    isMicTrack: Boolean,
    onMicClick: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is MicState.Listening -> Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🎧 Hört zu …",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("Abbrechen") }
                }
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        is MicState.Message -> Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
        MicState.Idle -> if (showButton) {
            OutlinedButton(
                onClick = onMicClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(if (isMicTrack) "🎤 Neu erkennen" else "🎤 Über Mikrofon erkennen") }
        }
    }
}

// ---------- Song-Anzeige ----------

@Composable
private fun TrackHeader(track: NowPlaying, position: State<Long>, isMicTrack: Boolean, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        if (isMicTrack) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🎤 Über Mikrofon erkannt",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.tertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("Schließen") }
            }
        }
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
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

private fun formatTime(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", totalSec / 60, totalSec % 60)
}
