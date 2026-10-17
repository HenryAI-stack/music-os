package at.cloudplay.musicos.lyrics

import at.cloudplay.musicos.media.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Minimaler Client für https://lrclib.net – kostenlos, ohne API-Key.
 */
object LrcLibClient {

    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "Music-os/0.1 (private Android hobby app)"

    data class Result(
        val synced: String?,
        val plain: String?,
        val instrumental: Boolean,
        /** Songdauer laut LRCLIB in Sekunden (0 = unbekannt). */
        val durationSec: Double = 0.0,
    )

    /** Sucht Lyrics zum Song; bevorzugt synchronisierte. Null = nichts gefunden. */
    suspend fun find(track: NowPlaying): Result? = withContext(Dispatchers.IO) {
        // 1) Exakte Abfrage mit allen Metadaten
        val exact = getExact(track)
        if (exact != null && (exact.synced != null || exact.instrumental)) return@withContext exact

        // 2) Suche mit bereinigtem Titel, 3) Freitextsuche als letzter Versuch
        val searched = search(
            mapOf("track_name" to cleanTitle(track.title), "artist_name" to track.artist)
                .filterValues { it.isNotBlank() },
            track.durationMs,
        ) ?: search(
            mapOf("q" to "${cleanArtist(track.artist)} ${cleanTitle(track.title)}".trim()),
            track.durationMs,
        )

        when {
            searched?.synced != null -> searched
            exact != null -> exact
            else -> searched
        }
    }

    private fun getExact(track: NowPlaying): Result? {
        if (track.artist.isBlank()) return null
        val params = buildMap {
            put("artist_name", track.artist)
            put("track_name", track.title)
            track.album?.let { put("album_name", it) }
            if (track.durationMs > 0) put("duration", (track.durationMs / 1000).toString())
        }
        val body = httpGet("$BASE/get?${query(params)}") ?: return null
        return parse(JSONObject(body))
    }

    private fun search(params: Map<String, String>, durationMs: Long): Result? {
        if (params.isEmpty()) return null
        val body = httpGet("$BASE/search?${query(params)}") ?: return null
        val array = JSONArray(body)
        if (array.length() == 0) return null

        val targetSec = durationMs / 1000.0
        val candidates = (0 until array.length()).map { array.getJSONObject(it) }
        val best = candidates.sortedWith(
            compareBy<JSONObject>(
                { if (it.optStringOrNull("syncedLyrics") != null) 0 else 1 },
                { if (targetSec > 0) abs(it.optDouble("duration", 0.0) - targetSec) else 0.0 },
            )
        ).first()
        return parse(best)
    }

    private fun parse(o: JSONObject) = Result(
        synced = o.optStringOrNull("syncedLyrics"),
        plain = o.optStringOrNull("plainLyrics"),
        instrumental = o.optBoolean("instrumental", false),
        durationSec = o.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0,
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    /** 2xx → Body, 4xx → null (nicht gefunden), sonst Exception. */
    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                in 400..499 -> null
                else -> throw IOException("LRCLIB antwortet mit HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun query(params: Map<String, String>) =
        params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private val bracketJunk = Regex(
        """\s*[(\[][^)\]]*\b(feat|ft|with|official|lyrics?|audio|video|remaster(ed)?|live|radio edit|explicit|mono|stereo)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val dashJunk = Regex(
        """\s+-\s+[^-]*\b(remaster(ed)?|version|edit|mix|live|mono|stereo|bonus)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    /** "Song - Remastered 2011 (feat. X)" → "Song" */
    fun cleanTitle(title: String): String =
        title.replace(bracketJunk, "").replace(dashJunk, "").trim().ifBlank { title }

    /** "A, B & C feat. D" → "A" */
    fun cleanArtist(artist: String): String =
        artist.split(Regex("""\s*(,|&|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }?.trim() ?: artist
}
