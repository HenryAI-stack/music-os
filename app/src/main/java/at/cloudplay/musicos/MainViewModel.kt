package at.cloudplay.musicos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.cloudplay.musicos.lyrics.LrcLibClient
import at.cloudplay.musicos.lyrics.LrcParser
import at.cloudplay.musicos.lyrics.LyricLine
import at.cloudplay.musicos.media.NowPlaying
import at.cloudplay.musicos.media.NowPlayingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
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

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NowPlayingRepository(app)
    val nowPlaying: StateFlow<NowPlaying?> = repo.nowPlaying

    private val _hasPermission = MutableStateFlow(repo.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    /** Manuelle Feinjustierung: positiv = Text läuft früher. */
    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    private val cache = mutableMapOf<String, LyricsState>()
    private var lyricsJob: Job? = null

    init {
        viewModelScope.launch {
            repo.nowPlaying
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
                toState(LrcLibClient.find(track))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LyricsState.Error(e.message ?: "Keine Verbindung")
            }
            if (state !is LyricsState.Error) cache[track.trackKey] = state
            _lyrics.value = state
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
