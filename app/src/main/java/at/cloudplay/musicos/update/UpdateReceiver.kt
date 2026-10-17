package at.cloudplay.musicos.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ergebnis der PackageInstaller-Session. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android verlangt (noch) eine Bestätigung – Dialog anzeigen.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // App wird ersetzt und neu gestartet
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateEvents.report("Update abgebrochen.")
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateEvents.report("Installation fehlgeschlagen (Status $status${detail?.let { ": $it" } ?: ""})")
            }
        }
    }

    companion object {
        const val ACTION = "at.cloudplay.musicos.UPDATE_STATUS"
    }
}

/** Kleine Brücke vom Receiver zum ViewModel. */
object UpdateEvents {
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    fun report(message: String) {
        _failure.value = message
    }

    fun consume() {
        _failure.value = null
    }
}
