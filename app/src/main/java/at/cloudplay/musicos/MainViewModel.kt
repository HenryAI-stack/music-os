package at.cloudplay.musicos

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.cloudplay.musicos.history.HistoryEntry
import at.cloudplay.musicos.history.HistoryRepository
import at.cloudplay.musicos.lyrics.LrcLibClient
import at.cloudplay.musicos.lyrics.LrcParser
import at.cloudplay.musicos.lyrics.LyricLine
import at.cloudplay.musicos.media.NowPlaying
import at.cloudplay.musicos.media.NowPlayingRepository
import at.cloudplay.musicos.recognition.MicRecognizer
import at.cloudplay.musicos.recognition.RecognitionError
import at.cloudplay.musicos.update.AppUpdater
import at.cloudplay.musicos.update.UpdateChecker
import at.cloudplay.musicos.update.UpdateEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Synced(val lines: List<LyricLine>) : LyricsState
    data class Plain(val text: String) : LyricsState
    data object Instrumental : LyricsState
    data object NotFound : LyricsState
    data class Error(val message: String) : LyricsState
}

/** Meldungen rund ums Mikrofon – die UI übersetzt sie (strings.xml). */
enum class MicMessage { NO_MATCH, PERMISSION_DENIED, MIC_UNAVAILABLE, RECORDING_FAILED, RATE_LIMITED, NETWORK, BAD_RESPONSE }

sealed interface MicState {
    data object Idle : MicState
    data class Listening(val progress: Float) : MicState
    data class Message(val kind: MicMessage, val detail: String? = null) : MicState
}

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val info: UpdateChecker.UpdateInfo) : UpdateState
    data class Downloading(val progress: Float, val info: UpdateChecker.UpdateInfo) : UpdateState
    data object Installing : UpdateState
    data class Message(
        val kind: UpdateMessage,
        val detail: String?,
        val info: UpdateChecker.UpdateInfo?,
    ) : UpdateState
}

/** Meldungen rund um Updates – die UI übersetzt sie (strings.xml). */
enum class UpdateMessage { NEED_INSTALL_PERMISSION, DOWNLOAD_FAILED, INSTALL_ABORTED, INSTALL_FAILED, UP_TO_DATE, CHECK_FAILED }

/** packageName-Kennung für Songs, die über das Mikrofon erkannt wurden. */
const val MIC_SOURCE = "mic"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NowPlayingRepository(app)
    private val micRecognizer = MicRecognizer()
    private val history = HistoryRepository(app, viewModelScope)
    private val updater = AppUpdater(app)

    val historyEntries: StateFlow<List<HistoryEntry>> = history.entries
    val versionName: String = runCatching { updater.currentVersionName() }.getOrDefault("?")

    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var lastUpdateCheck = 0L
    private var dismissedUpdate = -1
    private var latestUpdate: UpdateChecker.UpdateInfo? = null

    /** Über das Mikrofon erkannter Song – hat Vorrang vor der Media-Session. */
    private val micTrack = MutableStateFlow<NowPlaying?>(null)

    val nowPlaying: StateFlow<NowPlaying?> =
        combine(micTrack, repo.nowPlaying) { mic, media -> mic ?: media }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _hasPermission = MutableStateFlow(repo.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    /** Manuelle Feinjustierung: positiv = Text läuft früher. */
    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    private val _micState = MutableStateFlow<MicState>(MicState.Idle)
    val micState: StateFlow<MicState> = _micState.asStateFlow()

    private val cache = mutableMapOf<String, LyricsState>()
    private var lyricsJob: Job? = null
    private var micJob: Job? = null

    init {
        viewModelScope.launch {
            nowPlaying
                .distinctUntilChangedBy { it?.trackKey }
                .collect { track ->
                    if (track != null) history.add(track, viaMic = track.packageName == MIC_SOURCE)
                    loadLyrics(track)
                }
        }
        viewModelScope.launch {
            UpdateEvents.failure.collect { failure ->
                if (failure != null) {
                    val info = (_update.value as? UpdateState.Downloading)?.info ?: latestUpdate
                    val kind = if (failure.aborted) UpdateMessage.INSTALL_ABORTED else UpdateMessage.INSTALL_FAILED
                    _update.value = UpdateState.Message(kind, failure.detail, info)
                    UpdateEvents.consume()
                }
            }
        }
        onResume()
    }

    /** Bei jedem Zurückkehren in die App: Berechtigung prüfen, ggf. starten. */
    fun onResume() {
        val granted = repo.hasPermission()
        _hasPermission.value = granted
        if (granted) repo.start()
        checkForUpdate()
    }

    fun retry() {
        val track = nowPlaying.value ?: return
        cache.remove(track.trackKey)
        loadLyrics(track)
    }

    fun nudgeOffset(deltaMs: Long) {
        _offsetMs.value += deltaMs
    }

    // ---------- Mikrofon ----------

    fun startListening() {
        if (micJob?.isActive == true) return
        _micState.value = MicState.Listening(0f)
        micJob = viewModelScope.launch {
            val result = try {
                micRecognizer.listen { progress ->
                    if (_micState.value is MicState.Listening) _micState.value = MicState.Listening(progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MicRecognizer.Result.Failed(RecognitionError.MIC_UNAVAILABLE, e.message)
            }
            when (result) {
                is MicRecognizer.Result.Found -> {
                    val match = result.match
                    micTrack.value = NowPlaying(
                        packageName = MIC_SOURCE,
                        title = match.title,
                        artist = match.artist,
                        album = match.album,
                        durationMs = 0L,
                        isPlaying = true,
                        positionMs = match.offsetMs,
                        positionUpdateTime = result.recordingStartedAt,
                        speed = 1f,
                    )
                    _offsetMs.value = 0L
                    _micState.value = MicState.Idle
                }
                MicRecognizer.Result.NoMatch -> _micState.value = MicState.Message(MicMessage.NO_MATCH)
                is MicRecognizer.Result.Failed -> _micState.value = MicState.Message(
                    MicMessage.valueOf(result.error.name),
                    result.detail,
                )
            }
        }
    }

    fun cancelListening() {
        micJob?.cancel()
        _micState.value = MicState.Idle
    }

    fun onMicPermissionDenied() {
        _micState.value = MicState.Message(MicMessage.PERMISSION_DENIED)
    }

    fun dismissMicMessage() {
        if (_micState.value is MicState.Message) _micState.value = MicState.Idle
    }

    /** Beendet den Mikrofon-Song – danach zählt wieder die Media-Session. */
    fun closeMicTrack() {
        micTrack.value = null
    }

    // ---------- Verlauf ----------

    fun deleteHistoryEntry(id: String) = history.delete(id)

    fun clearHistory() = history.clear()

    // ---------- Updates ----------

    /**
     * Automatisch höchstens alle 30 Minuten (Start / Zurückkehren in die App).
     * [manual] = Knopf in den Einstellungen: sofort prüfen und Ergebnis immer anzeigen.
     */
    fun checkForUpdate(manual: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!manual && lastUpdateCheck != 0L && now - lastUpdateCheck < 30 * 60_000L) return
        if (_update.value is UpdateState.Downloading || _update.value is UpdateState.Installing) return
        if (!manual && _update.value !is UpdateState.None) return
        lastUpdateCheck = now
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { UpdateChecker.fetchLatest() }.getOrNull() }
            if (info == null) {
                if (manual) _update.value = UpdateState.Message(UpdateMessage.CHECK_FAILED, null, null)
                return@launch
            }
            latestUpdate = info
            val current = runCatching { updater.currentVersionCode() }.getOrDefault(Long.MAX_VALUE)
            val newer = info.versionCode > current
            when {
                newer && (manual || info.versionCode != dismissedUpdate) -> _update.value = UpdateState.Available(info)
                manual -> _update.value = UpdateState.Message(UpdateMessage.UP_TO_DATE, null, null)
            }
        }
    }

    fun startUpdate() {
        val info = when (val state = _update.value) {
            is UpdateState.Available -> state.info
            is UpdateState.Message -> state.info
            else -> null
        } ?: return
        if (!updater.canInstallPackages()) {
            runCatching { updater.openInstallPermissionSettings() }
            _update.value = UpdateState.Message(UpdateMessage.NEED_INSTALL_PERMISSION, null, info)
            return
        }
        _update.value = UpdateState.Downloading(0f, info)
        viewModelScope.launch {
            try {
                updater.downloadAndInstall(info) { progress ->
                    if (_update.value is UpdateState.Downloading) _update.value = UpdateState.Downloading(progress, info)
                }
                if (_update.value is UpdateState.Downloading) _update.value = UpdateState.Installing
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = UpdateState.Message(UpdateMessage.DOWNLOAD_FAILED, e.message, info)
            }
        }
    }

    fun dismissUpdate() {
        when (val state = _update.value) {
            is UpdateState.Available -> dismissedUpdate = state.info.versionCode
            is UpdateState.Message -> state.info?.let { dismissedUpdate = it.versionCode }
            else -> Unit
        }
        _update.value = UpdateState.None
    }

    // ---------- Lyrics ----------

    private fun loadLyrics(track: NowPlaying?) {
        lyricsJob?.cancel()
        _offsetMs.value = 0L
        if (track == null) {
            _lyrics.value = LyricsState.Idle
            return
        }
        cache[track.trackKey]?.let {
            _lyrics.value = it
            return
        }
        _lyrics.value = LyricsState.Loading
        lyricsJob = viewModelScope.launch {
            val state = try {
                val result = LrcLibClient.find(track)
                fillMicDuration(track, result)
                toState(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LyricsState.Error(e.message ?: e.javaClass.simpleName)
            }
            if (state !is LyricsState.Error) cache[track.trackKey] = state
            _lyrics.value = state
        }
    }

    /** Shazam liefert keine Songdauer – LRCLIB schon. Damit gibt es einen Fortschrittsbalken. */
    private fun fillMicDuration(track: NowPlaying, result: LrcLibClient.Result?) {
        val durationMs = ((result?.durationSec ?: 0.0) * 1000).toLong()
        if (track.packageName != MIC_SOURCE || durationMs <= 0) return
        micTrack.update { current ->
            if (current != null && current.trackKey == track.trackKey && current.durationMs == 0L) {
                current.copy(durationMs = durationMs)
            } else {
                current
            }
        }
    }

    private fun toState(result: LrcLibClient.Result?): LyricsState {
        if (result == null) return LyricsState.NotFound
        val synced = result.synced?.let { LrcParser.parse(it) }.orEmpty()
        return when {
            synced.isNotEmpty() -> LyricsState.Synced(synced)
            result.plain != null -> LyricsState.Plain(result.plain)
            result.instrumental -> LyricsState.Instrumental
            else -> LyricsState.NotFound
        }
    }

    override fun onCleared() {
        repo.stop()
    }
}
