package at.cloudplay.musicos.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fragt bei GitHub nach dem neuesten Release (kostenlos, ohne Login). */
object UpdateChecker {

    private const val LATEST_RELEASE = "https://api.github.com/repos/HenryAI-stack/music-os/releases/latest"

    data class UpdateInfo(
        /** Entspricht versionCode (= Build-Nummer aus GitHub Actions). */
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
    )

    fun fetchLatest(): UpdateInfo? {
        val conn = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Music-os-Updater")
            if (conn.responseCode != 200) throw IOException("GitHub HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    /** Tag "v0.1.12" → versionCode 12. */
    internal fun parse(json: JSONObject): UpdateInfo? {
        val tag = json.optString("tag_name").trim()
        val versionCode = tag.substringAfterLast('.').toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                break
            }
        }
        return UpdateInfo(
            versionCode = versionCode,
            versionName = tag.removePrefix("v"),
            apkUrl = apkUrl ?: return null,
            notes = json.optString("body").trim(),
        )
    }
}
