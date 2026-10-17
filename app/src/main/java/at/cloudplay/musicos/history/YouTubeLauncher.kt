package at.cloudplay.musicos.history

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** Öffnet die YouTube-Suche zu einem Song – in der YouTube-App, sonst im Browser. */
object YouTubeLauncher {

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    /** "Artist Titel" – ohne Zusätze wie "(feat. …)" ist die Suche treffsicherer. */
    fun query(title: String, artist: String): String =
        listOf(artist.trim(), title.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    fun searchUrl(title: String, artist: String): String =
        "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query(title, artist), "UTF-8")

    fun open(context: Context, title: String, artist: String) {
        val app = Intent(Intent.ACTION_SEARCH)
            .setPackage(YOUTUBE_PACKAGE)
            .putExtra(SearchManager.QUERY, query(title, artist))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(app)
        } catch (e: ActivityNotFoundException) {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl(title, artist)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(web)
            } catch (ignored: ActivityNotFoundException) {
                // Weder YouTube noch Browser vorhanden – nichts zu tun.
            }
        }
    }
}
