package at.cloudplay.singalong.media

import android.os.SystemClock

/** Momentaufnahme dessen, was eine Musik-App gerade abspielt. */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val isPlaying: Boolean,
    /** Position zum Zeitpunkt [positionUpdateTime]. */
    val positionMs: Long,
    /** Zeitstempel in SystemClock.elapsedRealtime()-Basis. */
    val positionUpdateTime: Long,
    val speed: Float,
) {
    /** Identifiziert den Song – ändert sich, wenn ein neuer Titel läuft. */
    val trackKey: String get() = "${artist.trim()}|${title.trim()}".lowercase()

    /** Hochgerechnete aktuelle Position in Millisekunden. */
    fun currentPositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return positionMs
        val elapsed = (now - positionUpdateTime).coerceAtLeast(0)
        val pos = positionMs + (elapsed * speed).toLong()
        return if (durationMs > 0) pos.coerceAtMost(durationMs) else pos
    }
}
