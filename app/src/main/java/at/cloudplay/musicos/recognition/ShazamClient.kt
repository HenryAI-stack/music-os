package at.cloudplay.musicos.recognition

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.UUID

/**
 * Schickt einen Fingerabdruck an die (inoffizielle, kostenlose) Shazam-Schnittstelle –
 * dieselbe, die auch SongRec und shazamio nutzen. Es wird keine Audiodatei übertragen,
 * nur der Fingerabdruck. Kann sich jederzeit ändern, da nicht offiziell dokumentiert.
 */
object ShazamClient {

    data class Match(
        val title: String,
        val artist: String,
        val album: String?,
        /** Position im Song (ms), an der die Aufnahme begann. */
        val offsetMs: Long,
        val coverUrl: String?,
    )

    sealed interface Outcome {
        data class Found(val match: Match) : Outcome
        data object NoMatch : Outcome
        data class Failed(val message: String) : Outcome
    }

    private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 7 Build/UQ1A.240205.004)"

    fun recognize(signature: ShazamSignature.Signature): Outcome {
        if (signature.peakCount == 0) return Outcome.NoMatch // Stille – Anfrage sparen
        return try {
            val body = requestBody(signature).toString()
            val response = post(endpoint(), body)
            parse(JSONObject(response))
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: "Keine Verbindung zu Shazam")
        } catch (e: org.json.JSONException) {
            Outcome.Failed("Unerwartete Antwort von Shazam")
        }
    }

    private fun endpoint(): String {
        val a = UUID.randomUUID().toString().uppercase()
        val b = UUID.randomUUID().toString().uppercase()
        return "https://amp.shazam.com/discovery/v5/en-US/US/android/-/tag/$a/$b" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3" +
            "&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"
    }

    internal fun requestBody(signature: ShazamSignature.Signature): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("timezone", TimeZone.getDefault().id)
            .put(
                "signature",
                JSONObject()
                    .put("uri", signature.toUri())
                    .put("samplems", signature.sampleMs),
            )
            .put("timestamp", now)
            .put("context", JSONObject())
            .put("geolocation", JSONObject())
    }

    internal fun parse(json: JSONObject): Outcome {
        val matches = json.optJSONArray("matches")
        val track = json.optJSONObject("track")
        if (matches == null || matches.length() == 0 || track == null) return Outcome.NoMatch

        val offsetSec = matches.getJSONObject(0).optDouble("offset", 0.0)
            .takeIf { !it.isNaN() && it >= 0 } ?: 0.0
        val title = track.optString("title").trim()
        if (title.isEmpty()) return Outcome.NoMatch

        return Outcome.Found(
            Match(
                title = title,
                artist = track.optString("subtitle").trim(),
                album = findMetadata(track, "Album"),
                offsetMs = (offsetSec * 1000).toLong(),
                coverUrl = track.optJSONObject("images")?.optString("coverart")?.takeIf { it.isNotBlank() },
            )
        )
    }

    private fun findMetadata(track: JSONObject, key: String): String? {
        val sections = track.optJSONArray("sections") ?: return null
        for (i in 0 until sections.length()) {
            val metadata = sections.optJSONObject(i)?.optJSONArray("metadata") ?: continue
            for (j in 0 until metadata.length()) {
                val entry = metadata.optJSONObject(j) ?: continue
                if (entry.optString("title").equals(key, ignoreCase = true)) {
                    return entry.optString("text").trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "en-US")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 429) throw IOException("Zu viele Anfragen an Shazam – bitte kurz warten")
            if (code !in 200..299) throw IOException("Shazam antwortet mit HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
