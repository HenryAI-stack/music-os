package at.cloudplay.musicos.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lädt die neue APK direkt in eine PackageInstaller-Session und installiert sie.
 * Ab Android 12 klappt das ohne Bestätigungsdialog, sobald Music-os sich einmal
 * selbst aktualisiert hat (dann ist die App "Installer of record").
 */
class AppUpdater(context: Context) {

    private val appContext = context.applicationContext

    fun currentVersionCode(): Long = PackageInfoCompat.getLongVersionCode(packageInfo())

    fun currentVersionName(): String = packageInfo().versionName ?: "?"

    /** Darf Music-os APKs installieren? (Einstellung "Unbekannte Apps installieren") */
    fun canInstallPackages(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    suspend fun downloadAndInstall(
        info: UpdateChecker.UpdateInfo,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("User-Agent", "Music-os-Updater")
                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    session.openWrite("music-os.apk", 0, if (total > 0) total else -1).use { out ->
                        conn.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                done += read
                                if (total > 0) onProgress(done.toFloat() / total)
                            }
                        }
                        session.fsync(out)
                    }
                } finally {
                    conn.disconnect()
                }

                val callback = Intent(appContext, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(appContext, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo() = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
}
