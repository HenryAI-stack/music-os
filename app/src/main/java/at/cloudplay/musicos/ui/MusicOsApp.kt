package at.cloudplay.musicos.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.cloudplay.musicos.LanguageSettings
import at.cloudplay.musicos.LyricsState
import at.cloudplay.musicos.MIC_SOURCE
import at.cloudplay.musicos.MainViewModel
import at.cloudplay.musicos.MicMessage
import at.cloudplay.musicos.MicState
import at.cloudplay.musicos.R
import at.cloudplay.musicos.UpdateMessage
import at.cloudplay.musicos.UpdateState
import at.cloudplay.musicos.history.HistoryEntry
import at.cloudplay.musicos.history.YouTubeLauncher
import at.cloudplay.musicos.lyrics.LrcParser
import at.cloudplay.musicos.lyrics.LyricLine
import at.cloudplay.musicos.media.NowPlaying
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Screen { MAIN, HISTORY, SETTINGS }

@Composable
fun MusicOsApp(vm: MainViewModel) {
    val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val offsetMs by vm.offsetMs.collectAsStateWithLifecycle()
    val micState by vm.micState.collectAsStateWithLifecycle()
    val historyEntries by vm.historyEntries.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }

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
            when (screen) {
                Screen.HISTORY -> HistoryScreen(
                    entries = historyEntries,
                    onBack = { screen = Screen.MAIN },
                    onDelete = vm::deleteHistoryEntry,
                    onClearAll = vm::clearHistory,
                )
                Screen.SETTINGS -> SettingsScreen(
                    versionName = vm.versionName,
                    update = update,
                    onBack = { screen = Screen.MAIN },
                    onCheckUpdate = { vm.checkForUpdate(manual = true) },
                    onUpdate = vm::startUpdate,
                    onDismissUpdate = vm::dismissUpdate,
                )
                Screen.MAIN -> {
                    TopBar(
                        onHistoryClick = { screen = Screen.HISTORY },
                        onSettingsClick = { screen = Screen.SETTINGS },
                    )
                    UpdateBanner(update, onUpdate = vm::startUpdate, onDismiss = vm::dismissUpdate)

                    val track = nowPlaying
                    if (track == null) {
                        Box(Modifier.weight(1f)) {
                            HomeScreen(
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
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

// ---------- Kopfleisten ----------

@Composable
private fun TopBar(onHistoryClick: () -> Unit, onSettingsClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onHistoryClick) {
            Icon(HistoryIcon, contentDescription = stringResource(R.string.cd_history))
        }
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_settings))
        }
    }
}

@Composable
private fun ScreenHeader(
    @StringRes title: Int,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
        }
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

// ---------- Startbildschirm ----------

@Composable
private fun HomeScreen(
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
        ) { Text(stringResource(R.string.mic_button), style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.mic_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text(
            stringResource(R.string.phone_music_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (hasNotificationAccess) {
            Text(
                stringResource(R.string.phone_music_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.phone_music_permission),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.grant_access)) }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.restricted_hint),
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
            ) { Text(stringResource(R.string.open_app_info)) }
        }
    }
}

// ---------- Updates ----------

@Composable
private fun updateMessageText(state: UpdateState.Message): String {
    val base = stringResource(
        when (state.kind) {
            UpdateMessage.NEED_INSTALL_PERMISSION -> R.string.update_need_permission
            UpdateMessage.DOWNLOAD_FAILED -> R.string.update_download_failed
            UpdateMessage.INSTALL_ABORTED -> R.string.update_aborted
            UpdateMessage.INSTALL_FAILED -> R.string.update_install_failed
            UpdateMessage.UP_TO_DATE -> R.string.update_up_to_date
            UpdateMessage.CHECK_FAILED -> R.string.update_check_failed
        }
    )
    return if (state.detail.isNullOrBlank()) base else "$base (${state.detail})"
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
                    Text(
                        stringResource(R.string.update_available, state.info.versionName),
                        style = MaterialTheme.typography.titleSmall,
                    )
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
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onUpdate) { Text(stringResource(R.string.update_now)) }
                    }
                }
                is UpdateState.Downloading -> {
                    Text(
                        stringResource(R.string.update_downloading, state.info.versionName),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                UpdateState.Installing -> {
                    Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.update_installing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is UpdateState.Message -> {
                    Text(updateMessageText(state), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                        Spacer(Modifier.weight(1f))
                        if (state.info != null) {
                            Button(onClick = onUpdate) { Text(stringResource(R.string.try_again)) }
                        }
                    }
                }
                UpdateState.None -> Unit
            }
        }
    }
}

// ---------- Einstellungen ----------

@Composable
private fun SettingsScreen(
    versionName: String,
    update: UpdateState,
    onBack: () -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val current = remember { LanguageSettings.get(context) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(R.string.settings_title, onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle(R.string.settings_language)
            for (language in LanguageSettings.Language.entries) {
                val label = stringResource(
                    when (language) {
                        LanguageSettings.Language.SYSTEM -> R.string.language_system
                        LanguageSettings.Language.GERMAN -> R.string.language_german
                        LanguageSettings.Language.ENGLISH -> R.string.language_english
                    }
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = language == current,
                            role = Role.RadioButton,
                            onClick = { LanguageSettings.apply(context, language) },
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = language == current, onClick = null)
                    Spacer(Modifier.width(16.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))

            SectionTitle(R.string.settings_about)
            Text(
                stringResource(R.string.version_label, versionName),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCheckUpdate,
                enabled = update !is UpdateState.Downloading && update !is UpdateState.Installing,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) { Text(stringResource(R.string.check_for_updates)) }
            UpdateBanner(update, onUpdate = onUpdate, onDismiss = onDismissUpdate)
        }
    }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    )
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
        ScreenHeader(R.string.history_title, onBack) {
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear_all)) }
            }
        }
        if (entries.isEmpty()) {
            CenterMessage(stringResource(R.string.history_empty))
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
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_text, entries.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val openLabel = stringResource(R.string.history_open_youtube)
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = openLabel) {
                    YouTubeLauncher.open(context, entry.title, entry.artist)
                }
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
                val source = stringResource(if (entry.viaMic) R.string.source_mic else R.string.source_phone)
                Text(
                    "$source · ${historyTimeLabel(entry.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary,
                )
            }
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null, // ganze Zeile ist klickbar, Label siehe onClickLabel
                tint = colors.primary,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.history_delete_entry),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun historyTimeLabel(timestamp: Long): String {
    val locale = currentLocale()
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = DateTimeFormatter.ofPattern("HH:mm", locale).format(dateTime)
    val day = when (dateTime.toLocalDate()) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> DateTimeFormatter.ofPattern(stringResource(R.string.history_date_pattern), locale).format(dateTime)
    }
    return "$day, $time"
}

// ---------- Mikrofon-Leiste ----------

@Composable
private fun micMessageText(state: MicState.Message): String {
    val base = stringResource(
        when (state.kind) {
            MicMessage.NO_MATCH -> R.string.mic_no_match
            MicMessage.PERMISSION_DENIED -> R.string.mic_permission_denied
            MicMessage.MIC_UNAVAILABLE -> R.string.mic_unavailable
            MicMessage.RECORDING_FAILED -> R.string.mic_recording_failed
            MicMessage.RATE_LIMITED -> R.string.mic_rate_limited
            MicMessage.NETWORK -> R.string.mic_network
            MicMessage.BAD_RESPONSE -> R.string.mic_bad_response
        }
    )
    return if (state.detail.isNullOrBlank()) base else "$base (${state.detail})"
}

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
                        stringResource(R.string.listening),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        is MicState.Message -> Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(micMessageText(state), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        }
        MicState.Idle -> if (showButton) {
            OutlinedButton(
                onClick = onMicClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(if (isMicTrack) R.string.identify_again else R.string.identify_short)) }
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
                    stringResource(R.string.via_mic),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.tertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
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
        LyricsState.Instrumental -> CenterMessage(stringResource(R.string.lyrics_instrumental))
        LyricsState.NotFound -> CenterMessage(
            stringResource(R.string.lyrics_not_found),
            stringResource(R.string.lyrics_search_again),
            onRetry,
        )
        is LyricsState.Error -> CenterMessage(
            stringResource(R.string.lyrics_error, state.message),
            stringResource(R.string.try_again),
            onRetry,
        )
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
            stringResource(R.string.lyrics_plain_hint),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SyncBar(offsetMs: Long, onNudge: (Long) -> Unit) {
    val locale = currentLocale()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { onNudge(-500L) }) { Text(stringResource(R.string.sync_later)) }
        Text(
            String.format(locale, "%+.1f s", offsetMs / 1000.0),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { onNudge(500L) }) { Text(stringResource(R.string.sync_earlier)) }
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
