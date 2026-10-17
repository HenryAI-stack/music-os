package at.cloudplay.musicos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * App-Sprache: Systemsprache, Deutsch oder Englisch.
 * Wird in den SharedPreferences gespeichert und in MainActivity.attachBaseContext angewendet –
 * funktioniert so auf allen Android-Versionen ab 8.
 */
object LanguageSettings {

    enum class Language(val tag: String?) { SYSTEM(null), GERMAN("de"), ENGLISH("en") }

    private const val PREFS = "settings"
    private const val KEY_LANGUAGE = "language"

    fun get(context: Context): Language {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        return Language.entries.firstOrNull { it.name == stored } ?: Language.SYSTEM
    }

    fun set(context: Context, language: Language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.name)
            .commit() // synchron, weil die Activity direkt danach neu erstellt wird
    }

    /** Liefert einen Context mit der gewählten Sprache (oder unverändert bei Systemsprache). */
    fun wrap(base: Context): Context {
        val tag = get(base).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** Sprache ändern und Activity neu aufbauen, damit alle Texte umschalten. */
    fun apply(context: Context, language: Language) {
        if (get(context) == language) return
        set(context, language)
        context.findActivity()?.recreate()
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
