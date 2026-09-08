package app.auriel.edenlauncher.notification

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.auriel.edenlauncher.util.EdenLog

private const val TAG = "NotificationListener"

/**
 * The only thing in Eden that sees a notification.
 *
 * Notification access is the widest permission this launcher asks for and the only one that has to
 * be granted by hand, on a screen the system deliberately makes uncomfortable. It is asked for one
 * reason: there is no narrower API. Android publishes no "which apps have something waiting" feed;
 * a launcher that wants a dot has to be handed every notification on the phone and throw nearly
 * all of it away. So that is what happens here, in this file, immediately - [StatusBarNotification]
 * goes in, a package name comes out, and [NotificationDots] never learns anything else. Nothing is
 * persisted, nothing is logged, and nothing survives the process.
 *
 * The service is declared in the manifest whether or not the feature is on, because it has to be
 * for the user to be able to grant access at all. It does not *run* unless asked: see
 * [NotificationDots.setEnabled], which unbinds it when dots are turned off.
 *
 * Ported in spirit rather than in code from `NotificationListener` (AOSP 10), which does far more -
 * it feeds the notification popup a long press opens, so it keeps groups, ranking and the text
 * itself. Eden draws a dot and nothing else, so it keeps a set of keys.
 */
class EdenNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // May be the first thing in this process to ask; see NotificationDots.initialise.
        NotificationDots.initialise(this)

        if (!NotificationDots.enabled) {
            // Granted, but the user has since turned dots off in Eden. The system binds a listener
            // it has been given access to whether the app wants it or not, so declining is the
            // service's own job and this is the first moment it can be done.
            EdenLog.i(TAG, "connected but dots are off; unbinding")
            runCatching { requestUnbind() }
            return
        }

        EdenLog.i(TAG, "connected")
        refreshAll()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        // Whatever was known was known through a connection that no longer exists, and dots left
        // on screen would be a snapshot of a moment that keeps receding. Better none than stale.
        main.post { NotificationDots.clear() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val notification = sbn ?: return
        if (!NotificationDots.enabled) return

        val key = keyFor(notification)
        main.post {
            if (worthADot(notification, rankingMap)) {
                NotificationDots.post(key, notification.key)
            } else {
                // An update can make a notification stop counting - a group grows a summary, a
                // channel has its dot switched off, the text is cleared for an ongoing transport
                // control - and the dot it already caused has to go with it.
                NotificationDots.remove(key, notification.key)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val notification = sbn ?: return
        val key = keyFor(notification)
        main.post { NotificationDots.remove(key, notification.key) }
    }

    /**
     * Rebuilds everything from what is actually on the phone right now.
     *
     * Only on connect. The deltas are enough for everything after that, and asking the system for
     * the full list is a binder call that parcels every notification - including the text, the
     * icons and the actions - which is a lot of work to arrive at a set of package names.
     */
    private fun refreshAll() {
        val active = runCatching { activeNotifications }
            .onFailure { EdenLog.w(TAG, "could not read the active notifications", it) }
            .getOrNull() ?: return

        val ranking = runCatching { currentRanking }.getOrNull()

        val posted = HashMap<PackageUserKey, MutableSet<String>>()
        for (notification in active) {
            if (!worthADot(notification, ranking)) continue
            posted.getOrPut(keyFor(notification)) { HashSet() }.add(notification.key)
        }

        main.post { NotificationDots.replaceAll(posted) }
    }

    private fun keyFor(sbn: StatusBarNotification): PackageUserKey =
        PackageUserKey(sbn.packageName, sbn.user)

    /**
     * Whether this notification should put a dot on its app's icon.
     *
     * Three things disqualify one, and all three are somebody else's decision rather than Eden's:
     *
     *  - **A group summary.** The summary and the notifications it stands for are all posted, so
     *    counting both counts the same thing twice. The children are the real ones.
     *  - **A channel the user or the app has excluded from badging.** `canShowBadge` is the answer
     *    to exactly the question a dot asks, and it is settled per channel in Android's own
     *    settings. A launcher that draws a dot anyway is overruling the switch labelled with the
     *    thing it is drawing.
     *  - **Nothing to say.** A notification with neither a title nor text is a transport control,
     *    a progress bar or a foreground-service placeholder: something the app needs posted, not
     *    something waiting for the user. AOSP filters these the same way.
     */
    private fun worthADot(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        val notification = sbn.notification ?: return false

        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        if (rankingMap != null) {
            val ranking = Ranking()
            if (rankingMap.getRanking(sbn.key, ranking) && !ranking.canShowBadge()) return false
        }

        val extras = notification.extras ?: return true
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        return !title.isNullOrBlank() || !text.isNullOrBlank()
    }

    companion object {
        /**
         * The connected service, or null.
         *
         * Held so that turning dots off can decline a binding the system has already made. Cleared
         * on disconnect, so it never outlives the service it points at.
         */
        @Volatile
        private var instance: EdenNotificationListener? = null

        /** Declines the current binding, if there is one. Safe to call when there is not. */
        fun unbindIfRunning() {
            val service = instance ?: return
            runCatching { service.requestUnbind() }
                .onFailure { EdenLog.w(TAG, "requestUnbind failed", it) }
        }
    }
}
