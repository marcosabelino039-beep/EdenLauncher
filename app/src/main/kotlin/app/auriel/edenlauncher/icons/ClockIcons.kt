package app.auriel.edenlauncher.icons

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import app.auriel.edenlauncher.util.EdenLog
import java.util.Calendar

/**
 * Which layers of an app's icon are clock hands, and where they start.
 *
 * A clock app ships its icon as an adaptive icon whose foreground is a layer list - face, hour
 * hand, minute hand - and declares in its manifest which layer is which. A launcher that knows the
 * convention can turn those layers itself, so the icon on the home screen tells the time instead of
 * being a picture of a clock stopped at whatever the artist drew.
 *
 * The hands are drawn at [defaultHour], [defaultMinute] and [defaultSecond], so what gets applied
 * is the difference between now and that, not the absolute angle. An icon whose author drew the
 * hands at twelve declares nothing, the defaults are zero, and it is the same arithmetic.
 *
 * An index of -1 means "there is no such hand": most clock icons have no second hand, and turning a
 * layer that is not there is either a crash or the wrong layer moving.
 */
class ClockLayers(
    val hourIndex: Int,
    val minuteIndex: Int,
    val secondIndex: Int,
    val defaultHour: Int,
    val defaultMinute: Int,
    val defaultSecond: Int,
    /** The clock-specific drawable the app declared, or 0 to turn the app's own icon. */
    val iconRes: Int,
) {
    /** False when the app declared the metadata but named no layer to turn. */
    val hasHands: Boolean get() = hourIndex >= 0 || minuteIndex >= 0 || secondIndex >= 0

    override fun toString(): String =
        "ClockLayers(hour=$hourIndex minute=$minuteIndex second=$secondIndex " +
            "default=$defaultHour:$defaultMinute:$defaultSecond icon=$iconRes)"
}

/** How far each hand layer has to turn from where it was drawn to where it belongs now. */
class ClockRotation(
    private val hourIndex: Int,
    private val hourDegrees: Float,
    private val minuteIndex: Int,
    private val minuteDegrees: Float,
    private val secondIndex: Int,
    private val secondDegrees: Float,
) {
    /**
     * The turn for the layer at [index], or zero for a layer that is not a hand.
     *
     * A hand the icon does not have is stored with a negative index, which no real layer can match,
     * so an absent second hand needs no special case at the call site.
     */
    fun degreesFor(index: Int): Float = when (index) {
        hourIndex -> hourDegrees
        minuteIndex -> minuteDegrees
        secondIndex -> secondDegrees
        else -> 0f
    }
}

/**
 * Reads the clock-icon convention out of an installed app and works out where its hands go.
 *
 * The convention is Launcher3's, and like the icon-pack convention it is a de-facto standard rather
 * than a documented API: the keys are namespaced `com.android.launcher3` because that is what clock
 * apps are authored against, and a launcher that invents its own names supports nothing.
 *
 * AOSP only ever turns the hands of the one clock app named in an overlayable string resource.
 * Eden reads the metadata off whatever app declares it, because the convention is public, several
 * third-party clocks use it, and there is no reason a user's clock has to be the manufacturer's.
 */
object ClockIcons {

    /**
     * The clock metadata [component] declares, or null for the overwhelming majority of apps that
     * declare none.
     *
     * One package-manager call per component, so this is for a discovery pass off the critical
     * path, not for the loader. Results - including the negative ones, which are nearly all of
     * them - are memoised by the caller.
     *
     * Both the activity and the application are asked, in that order. The convention grew up on
     * `ApplicationInfo` and that is where AOSP reads it, but declaring it on the launcher activity
     * is the more natural reading of "this activity's icon is a clock" and some apps do that
     * instead. Asking both costs one extra bundle lookup and supports both.
     */
    fun layersFor(context: Context, component: ComponentName): ClockLayers? {
        val activityMeta = runCatching {
            context.packageManager
                .getActivityInfo(component, PackageManager.GET_META_DATA)
                .metaData
        }.getOrNull()

        val applicationMeta = runCatching {
            context.packageManager
                .getApplicationInfo(component.packageName, PackageManager.GET_META_DATA)
                .metaData
        }.getOrNull()

        val layers = read(activityMeta) ?: read(applicationMeta) ?: return null
        if (!layers.hasHands) {
            EdenLog.i(TAG, "$component declares clock metadata with no hands in it")
            return null
        }
        EdenLog.i(TAG, "$component is a clock: $layers")
        return layers
    }

    private fun read(metadata: Bundle?): ClockLayers? {
        if (metadata == null) return null

        // Naming none of the three indices is how "this is not a clock" is spelled. An app that
        // names any one of them meant to, so the others fall back to "no such hand" rather than to
        // layer zero, which is the face.
        if (!metadata.containsKey(KEY_HOUR_INDEX) &&
            !metadata.containsKey(KEY_MINUTE_INDEX) &&
            !metadata.containsKey(KEY_SECOND_INDEX)
        ) {
            return null
        }

        return ClockLayers(
            hourIndex = metadata.getInt(KEY_HOUR_INDEX, -1),
            minuteIndex = metadata.getInt(KEY_MINUTE_INDEX, -1),
            secondIndex = metadata.getInt(KEY_SECOND_INDEX, -1),
            defaultHour = metadata.getInt(KEY_DEFAULT_HOUR, 0),
            defaultMinute = metadata.getInt(KEY_DEFAULT_MINUTE, 0),
            defaultSecond = metadata.getInt(KEY_DEFAULT_SECOND, 0),
            iconRes = metadata.getInt(KEY_ROUND_ICON, 0),
        )
    }

    /**
     * The artwork whose layers are to be turned.
     *
     * A clock app may ship a second icon meant only for launchers that animate it - the same face
     * with the hands as separate layers, where its ordinary icon might be a flat drawing. When it
     * names one, that is the one to turn; otherwise the app's own icon already is the layered one.
     */
    fun sourceFor(
        context: Context,
        activity: LauncherActivityInfo,
        layers: ClockLayers,
        densityDpi: Int,
    ): Drawable? {
        if (layers.iconRes != 0) {
            val declared = runCatching {
                context.packageManager
                    .getResourcesForApplication(activity.componentName.packageName)
                    .getDrawableForDensity(layers.iconRes, densityDpi, null)
            }.onFailure {
                EdenLog.w(TAG, "Could not load the clock drawable for ${activity.componentName}", it)
            }.getOrNull()
            if (declared != null) return declared
        }
        return runCatching { activity.getIcon(densityDpi) }
            .onFailure { EdenLog.w(TAG, "Could not load the icon for ${activity.componentName}", it) }
            .getOrNull()
    }

    /**
     * Where the hands belong at this instant.
     *
     * Read off the wall clock rather than off [android.os.SystemClock], deliberately: monotonic
     * time knows nothing of timezones or of the user setting the clock, and a launcher that
     * computes angles from time-since-boot shows the wrong time from the moment either changes -
     * which is exactly when the redraw was asked for.
     *
     * The hour hand carries the minutes and the minute hand carries the seconds, because a clock
     * whose hour hand jumps a whole twelfth at the top of the hour reads as broken.
     */
    fun rotationNow(layers: ClockLayers): ClockRotation {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR)
        val minute = now.get(Calendar.MINUTE)
        val second = now.get(Calendar.SECOND)

        val secondAngle = second * DEGREES_PER_SECOND
        val minuteAngle = (minute + second / 60f) * DEGREES_PER_MINUTE
        val hourAngle = (hour + minute / 60f + second / 3600f) * DEGREES_PER_HOUR

        return ClockRotation(
            hourIndex = layers.hourIndex,
            hourDegrees = hourAngle - layers.defaultHour * DEGREES_PER_HOUR,
            minuteIndex = layers.minuteIndex,
            minuteDegrees = minuteAngle - layers.defaultMinute * DEGREES_PER_MINUTE,
            secondIndex = layers.secondIndex,
            secondDegrees = secondAngle - layers.defaultSecond * DEGREES_PER_SECOND,
        )
    }

    private const val TAG = "ClockIcons"

    private const val KEY_HOUR_INDEX = "com.android.launcher3.HOUR_LAYER_INDEX"
    private const val KEY_MINUTE_INDEX = "com.android.launcher3.MINUTE_LAYER_INDEX"
    private const val KEY_SECOND_INDEX = "com.android.launcher3.SECOND_LAYER_INDEX"
    private const val KEY_DEFAULT_HOUR = "com.android.launcher3.DEFAULT_HOUR"
    private const val KEY_DEFAULT_MINUTE = "com.android.launcher3.DEFAULT_MINUTE"
    private const val KEY_DEFAULT_SECOND = "com.android.launcher3.DEFAULT_SECOND"
    private const val KEY_ROUND_ICON = "com.android.launcher3.LEVEL_PER_TICK_ICON_ROUND"

    private const val DEGREES_PER_HOUR = 30f
    private const val DEGREES_PER_MINUTE = 6f
    private const val DEGREES_PER_SECOND = 6f
}
