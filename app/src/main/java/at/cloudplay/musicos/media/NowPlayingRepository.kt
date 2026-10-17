package at.cloudplay.musicos.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Beobachtet alle aktiven Media-Sessions und liefert den Song, der gerade läuft.
 */
class NowPlayingRepository(context: Context) {

    private val appContext = context.applicationContext
    private val manager: MediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, NowPlayingListener::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            setControllers(list ?: emptyList())
        }

    fun hasPermission(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)

    fun start() {
        if (started || !hasPermission()) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            started = true
            refreshSessions()
        } catch (e: SecurityException) {
            started = false
        }
    }

    fun stop() {
        if (!started) return
        manager.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = emptyList()
        started = false
    }

    private fun refreshSessions() {
        val list = try {
            manager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            emptyList()
        }
        setControllers(list)
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = list
        controllers.forEach { it.registerCallback(controllerCallback, handler) }
        publish()
    }

    /** Wählt die Session, die gerade spielt – sonst die oberste mit Metadaten. */
    private fun publish() {
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        _nowPlaying.value = chosen?.toNowPlaying()
    }

    private fun MediaController.toNowPlaying(): NowPlaying? {
        val md = metadata ?: return null
        val title = firstNonBlank(
            md,
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        ) ?: return null
        val artist = firstNonBlank(
            md,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        ) ?: ""
        val album = firstNonBlank(md, MediaMetadata.METADATA_KEY_ALBUM)

        val state = playbackState
        val now = SystemClock.elapsedRealtime()
        val updateTime = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: now

        return NowPlaying(
            packageName = packageName,
            title = title,
            artist = artist,
            album = album,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position?.coerceAtLeast(0) ?: 0L,
            positionUpdateTime = updateTime,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }

    private fun firstNonBlank(md: MediaMetadata, vararg keys: String): String? {
        for (key in keys) {
            val value: String? = md.getString(key)
            if (!value.isNullOrBlank()) return value.trim()
        }
        return null
    }
}
