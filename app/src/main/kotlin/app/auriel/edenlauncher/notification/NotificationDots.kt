package app.auriel.edenlauncher.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import app.auriel.edenlauncher.settings.LauncherPrefs
import app.auriel.edenlauncher.util.EdenLog
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "NotificationDots"

/**
 * Which apps currently have a notification, and nothing else about it.
 *
 * A dot is the smallest possible use of notification access: the launcher wants one bit per app -
 * something is waiting, or nothing is - and this holds exactly that. What arrives at
 * [EdenNotificationListener] is the full text of every notification on the phone, because that is
 * the only shape the API comes in; what leaves this file is a set of package names. Nothing is
 * written to disk, nothing is logged, and nothing is kept after the process ends. The keys are
 * held rather than a counter so that a removal can be matched to the posting it undoes, which a
 * counter cannot do when the same notification is updated twice.
 *
 * Process-wide and static, because the listener is a separate component with its own lifetime: the
 * system binds it when it likes, including before the launcher activity exists and after it is
 * gone. An instance owned by the launcher would be an instance the service could not reach.
 *
 * Three switches have to agree before a dot is drawn, and they belong to three different people:
 * the user's own setting in Eden, the notification access the system asks them to grant, and the
 * phone-wide "notification dots" switch in Android's own settings. Any one of them off means no
 * dots, and [enabled] is only ever the launcher's half of that answer.
 */
object NotificationDots {

    /** Notification keys per package, so a removal can be matched to what it removes. */
    private val keys = HashMap<PackageUserKey, MutableSet<String>>()

    /** Called on the main thread whenever the set of dotted packages may have changed. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Whether the user has asked for dots at all.
     *
     * Read by the service to decide whether to stay bound: with this off there is nothing for it
     * to do, and a bound [NotificationListenerService] is a process the system keeps alive to hand
     * notifications to. Turning the feature off should cost nothing, not slightly less.
     */
    @Volatile
    var enabled: Boolean = false
        private set

    /**
     * Android's own phone-wide dots switch, cached.
     *
     * Read on every foreground rather than watched: it lives in a secure setting, observing one
     * costs a `ContentObserver` for the life of the process, and the only way to change it is to
     * leave Eden for the system settings app and come back - which is a foreground either way.
     */
    @Volatile
    private var systemBadging = true

    /** Re-reads Android's own dots switch. Cheap; called when the launcher comes forward. */
    fun refreshSystemSetting(context: Context) {
        val now = systemBadgingEnabled(context)
        if (now == systemBadging) return
        systemBadging = now
        notifyChanged()
    }

    /** True once [enabled] has been read out of settings in this process. */
    @Volatile
    private var initialised = false

    /**
     * Seeds [enabled] from the stored setting.
     *
     * The listener is bound by the system, and at boot that happens before the launcher activity
     * has run - so the service can be the first thing in the process to ask whether dots are on.
     * Without this it would read the field's default, decide the answer was no, and unbind itself
     * from a user who had turned them on months ago. Called from the service rather than from
     * `EdenApplication`, because a launcher process starts far more often than this is needed and
     * a cold start should not read a preference file it may have no use for.
     */
    @Synchronized
    fun initialise(context: Context) {
        if (initialised) return
        initialised = true
        enabled = LauncherPrefs(context).notificationDots
        systemBadging = systemBadgingEnabled(context)
    }

    // ---- what the launcher asks -------------------------------------------------------------------

    /** True when [packageName] has at least one notification worth a dot. */
    fun hasDot(packageName: String?, user: UserHandle): Boolean {
        if (!enabled || !systemBadging || packageName == null) return false
        synchronized(keys) {
            return keys[PackageUserKey(packageName, user)]?.isNotEmpty() == true
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    // ---- what the settings screen asks --------------------------------------------------------------

    /**
     * Turns the feature on or off, and makes the service agree.
     *
     * `requestUnbind` is the only way an app can decline access it has been granted. Without it,
     * switching dots off in Eden would leave a service the system goes on binding, waking and
     * handing every notification on the phone to - for nothing. `requestRebind` is how it comes
     * back, and it works only once access has actually been granted, which is why the return here
     * is silent rather than an error: the caller's next move is the access screen either way.
     */
    fun setEnabled(context: Context, on: Boolean) {
        initialise(context)
        if (enabled == on) return
        enabled = on

        val component = ComponentName(context, EdenNotificationListener::class.java)
        runCatching {
            if (on) {
                NotificationListenerService.requestRebind(component)
            } else {
                synchronized(keys) { keys.clear() }
                EdenNotificationListener.unbindIfRunning()
            }
        }.onFailure { EdenLog.w(TAG, "could not change the listener binding", it) }

        notifyChanged()
    }

    /**
     * Whether the user has granted Eden notification access.
     *
     * Read out of the secure setting rather than asked of the service, because the answer is
     * needed before the service has ever run - the settings screen has to know whether to show a
     * toggle or a request. The value is a colon-separated list of flattened component names, and
     * the platform offers no API to query one entry of it.
     */
    fun accessGranted(context: Context): Boolean {
        val granted = runCatching {
            Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS)
        }.getOrNull() ?: return false

        val mine = ComponentName(context, EdenNotificationListener::class.java)
        return granted.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == mine
        }
    }

    /**
     * The system screen where notification access is granted.
     *
     * From API 30 the platform can be asked to open straight onto one app's row, which saves the
     * user finding Eden in a list of every app that has ever asked. Older builds get the list.
     */
    fun accessSettingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, EdenNotificationListener::class.java).flattenToString(),
                )
        }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    /**
     * The phone-wide "notification dots" switch, in Android's own notification settings.
     *
     * Honoured because a user who turned dots off there turned them off for the home screen, and
     * a launcher that draws them anyway is overriding a decision it was not asked about. Defaults
     * to on, which is the platform default and what a build without the setting behaves like.
     */
    fun systemBadgingEnabled(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, NOTIFICATION_BADGING, 1) == 1
    }.getOrDefault(true)

    // ---- what the listener reports ---------------------------------------------------------------

    /** Replaces everything, for a listener that has just connected or reconnected. */
    internal fun replaceAll(posted: Map<PackageUserKey, Set<String>>) {
        synchronized(keys) {
            keys.clear()
            for ((key, notifications) in posted) keys[key] = notifications.toMutableSet()
        }
        notifyChanged()
    }

    internal fun post(key: PackageUserKey, notificationKey: String) {
        val changed = synchronized(keys) {
            keys.getOrPut(key) { HashSet() }.add(notificationKey)
        }
        if (changed) notifyChanged()
    }

    internal fun remove(key: PackageUserKey, notificationKey: String) {
        val changed = synchronized(keys) {
            val set = keys[key] ?: return@synchronized false
            val removed = set.remove(notificationKey)
            if (set.isEmpty()) keys.remove(key)
            removed
        }
        if (changed) notifyChanged()
    }

    /** The service has gone away; everything it told us is now a guess. */
    internal fun clear() {
        val had = synchronized(keys) {
            val any = keys.isNotEmpty()
            keys.clear()
            any
        }
        if (had) notifyChanged()
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            runCatching { listener() }
                .onFailure { EdenLog.w(TAG, "a dot listener threw", it) }
        }
    }

    /** `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`, which is not public API. */
    private const val ENABLED_LISTENERS = "enabled_notification_listeners"

    /** `Settings.Secure.NOTIFICATION_BADGING`, which is not public API either. */
    private const val NOTIFICATION_BADGING = "notification_badging"
}

/**
 * An app, in one profile.
 *
 * Notification dots are counted per package rather than per activity: a notification names the app
 * that posted it and nothing finer, and an app with two launcher entries has one set of
 * notifications between them. That is why this exists next to
 * [app.auriel.edenlauncher.util.ComponentKey] rather than reusing it.
 */
data class PackageUserKey(val packageName: String, val user: UserHandle) {
    companion object {
        fun of(packageName: String): PackageUserKey =
            PackageUserKey(packageName, Process.myUserHandle())
    }
}
