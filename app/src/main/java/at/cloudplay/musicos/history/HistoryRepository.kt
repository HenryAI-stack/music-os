package at.cloudplay.musicos.history

import android.content.Context
import at.cloudplay.musicos.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class HistoryEntry(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val viaMic: Boolean,
    val timestamp: Long,
)

/** Verlauf erkannter Songs – liegt nur lokal im App-Speicher (history.json). */
class HistoryRepository(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.applicationContext.filesDir, "history.json")
    private val mutex = Mutex()
    private var loaded = false

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        mutate { it }
    }

    fun add(track: NowPlaying, viaMic: Boolean) {
        val now = System.currentTimeMillis()
        mutate { list ->
            val newest = list.firstOrNull()
            if (newest != null && HistoryJson.isSameSong(newest, track.title, track.artist) &&
                now - newest.timestamp < DUPLICATE_WINDOW_MS
            ) {
                list
            } else {
                val entry = HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    viaMic = viaMic,
                    timestamp = now,
                )
                (listOf(entry) + list).take(MAX_ENTRIES)
            }
        }
    }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun clear() = mutate { emptyList() }

    private fun mutate(change: (List<HistoryEntry>) -> List<HistoryEntry>) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (!loaded) {
                    _entries.value = runCatching { HistoryJson.decode(file.readText()) }.getOrDefault(emptyList())
                    loaded = true
                }
                val before = _entries.value
                val after = change(before)
                if (after !== before) {
                    _entries.value = after
                    runCatching {
                        val tmp = File(file.parentFile, "history.json.tmp")
                        tmp.writeText(HistoryJson.encode(after))
                        if (!tmp.renameTo(file)) {
                            file.writeText(HistoryJson.encode(after))
                            tmp.delete()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 300
        const val DUPLICATE_WINDOW_MS = 15 * 60 * 1000L
    }
}

/** JSON-(De-)Serialisierung – reines Kotlin, damit es per Unit-Test prüfbar ist. */
object HistoryJson {

    fun encode(entries: List<HistoryEntry>): String {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("title", e.title)
                    .put("artist", e.artist)
                    .put("album", e.album ?: JSONObject.NULL)
                    .put("viaMic", e.viaMic)
                    .put("timestamp", e.timestamp)
            )
        }
        return array.toString()
    }

    fun decode(text: String): List<HistoryEntry> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            HistoryEntry(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = if (o.isNull("album")) null else o.optString("album"),
                viaMic = o.optBoolean("viaMic", false),
                timestamp = o.optLong("timestamp", 0L),
            )
        }
    }

    fun isSameSong(entry: HistoryEntry, title: String, artist: String): Boolean =
        entry.title.equals(title, ignoreCase = true) && entry.artist.equals(artist, ignoreCase = true)
}
