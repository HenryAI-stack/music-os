package at.cloudplay.singalong.media

import android.service.notification.NotificationListenerService

/**
 * Leerer Benachrichtigungs-Listener. Android rückt die Liste der aktiven
 * Media-Sessions (Spotify, YouTube Music, …) nur an Apps heraus, die als
 * Benachrichtigungs-Listener freigegeben sind. Inhalte lesen wir nicht.
 */
class NowPlayingListener : NotificationListenerService()
