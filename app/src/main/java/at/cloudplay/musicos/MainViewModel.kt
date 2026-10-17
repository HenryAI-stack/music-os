package at.cloudplay.musicos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.cloudplay.musicos.lyrics.LrcLibClient
import at.cloudplay.musicos.lyrics.LrcParser
import at.cloudplay.musicos.lyrics.LyricLine
import at.cloudplay.musicos.media.NowPlaying
import at.cloudplay.musicos.media.NowPlayingRepository
import at.cloudplay.musicos.recognition.MicRecognizer
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

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Synced(val lines: List<LyricLine>) : LyricsState
    data class Plain(val text: String) : LyricsState
    data object Instrumental : LyricsState
    data object NotFound : LyricsState
    data class Error(val message: String) : LyricsState
}

sealed interface MicState {
    data object Idle : MicState
    data class Listening(val progress: Float) : MicState
    data class Message(val text: String) : MicState
}

/** packageName-Kennung für Songs, die über das Mikrofon erkannt wurden. */
const val MIC_SOURCE = "mic"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NowPlayingRepository(app)
    private val micRecognizer = MicRecognizer()

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
                .collect { loadLyrics(it) }
        }
        onResume()
    }

    /** Bei jedem Zurückkehren in die App: Berechtigung prüfen, ggf. starten. */
    fun onResume() {
        val granted = repo.hasPermission()
        _hasPermission.value = granted
        if (granted) repo.start()
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
                MicRecognizer.Result.Failed(e.message ?: "Mikrofon nicht verfügbar")
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
                MicRecognizer.Result.NoMatch -> _micState.value = MicState.Message(
                    "Kein Song erkannt. Geh näher an die Musik heran und versuch es nochmal."
                )
                is MicRecognizer.Result.Failed -> _micState.value = MicState.Message(result.message)
            }
        }
    }

    fun cancelListening() {
        micJob?.cancel()
        _micState.value = MicState.Idle
    }

    fun onMicPermissionDenied() {
        _micState.value = MicState.Message("Ohne Mikrofon-Berechtigung kann ich nicht mithören.")
    }

    fun dismissMicMessage() {
        if (_micState.value is MicState.Message) _micState.value = MicState.Idle
    }

    /** Beendet den Mikrofon-Song – danach zählt wieder die Media-Session. */
    fun closeMicTrack() {
        micTrack.value = null
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
                LyricsState.Error(e.message ?: "Keine Verbindung")
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
