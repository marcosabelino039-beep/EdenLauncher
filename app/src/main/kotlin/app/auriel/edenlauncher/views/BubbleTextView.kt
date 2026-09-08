package app.auriel.edenlauncher.views

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.device.DeviceProfile
import app.auriel.edenlauncher.icons.DotRenderer
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.notification.NotificationDots

/**
 * An icon with a label under it: the view used for every app on the workspace and in the dock.
 *
 * Ported from `BubbleTextView` (AOSP 7). The icon is a compound drawable rather than a child
 * `ImageView`, which is what keeps a full page of icons at one view each instead of three.
 */
class BubbleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {

    /** The model object this view stands for; the click handler reads its intent. */
    var itemInfo: ItemInfo? = null
        private set

    private val profile: DeviceProfile = deviceProfile(context)
    private val prefs = LauncherAppState.getInstance(context).preferences

    // ---- notification dot ------------------------------------------------------------------------

    /** How much of the dot is showing: 0 when there is nothing waiting, 1 when there is. */
    private var dotScale = 0f

    private var dotAnimator: ValueAnimator? = null

    /** Allocated once; this is read on every draw of every icon that has a dot. */
    private val iconBounds = Rect()

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false

        setTextColor(context.getColor(R.color.workspace_icon_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, profile.iconTextSizePx.toFloat())
        // A shadow is what keeps labels readable over an arbitrary wallpaper.
        setShadowLayer(2f, 0f, 1f, context.getColor(R.color.workspace_icon_text_shadow))
        compoundDrawablePadding = dp(prefs.iconLabelSpacingDp)

        isFocusable = true
        isClickable = true
        isLongClickable = true
        setBackgroundResource(0)

        // Cell padding lives on the icon rather than on each container: the workspace grid, the
        // dock, folders, and both drawer styles then space identically from one setting, without
        // four copies of the same numbers.
        setPadding(
            dp(prefs.iconPaddingHorizontalDp),
            dp(prefs.iconPaddingVerticalDp),
            dp(prefs.iconPaddingHorizontalDp),
            dp(prefs.iconPaddingVerticalDp),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Shows or hides the label. The dock hides it - a single row has no space for one - and the
     * same view shows it again when it is dragged back onto the workspace.
     */
    fun setLabelVisible(visible: Boolean) {
        val padH = dp(prefs.iconPaddingHorizontalDp)
        if (visible) {
            text = itemInfo?.title
            setPadding(padH, dp(prefs.iconPaddingVerticalDp), padH, dp(prefs.iconPaddingVerticalDp))
        } else {
            text = null
            setPadding(padH, 0, padH, 0)
        }
    }

    /** Binds a drawer entry. The [AppInfo] itself is the tag, so a drag can copy it out. */
    fun applyFromApp(info: AppInfo) {
        itemInfo = info
        text = info.title
        contentDescription = info.title
        setIconBitmap(info.icon)
        alpha = if (info.isDisabled) DISABLED_ALPHA else 1f
        refreshDot(animate = false)
    }

    /** Binds an app or shortcut, sizing the icon to the current grid. */
    fun applyFrom(info: ShortcutInfo, icon: Bitmap?) {
        itemInfo = info
        text = info.title
        contentDescription = info.contentDescription ?: info.title
        setIconBitmap(icon)
        alpha = if (info.isDisabled) DISABLED_ALPHA else 1f
        refreshDot(animate = false)
    }

    /**
     * Re-reads whether this app has a notification waiting.
     *
     * Pulled rather than pushed: the launcher is told once that something changed and asks every
     * icon on screen, instead of the notification store holding a reference to every view that
     * might want to know. A view that has scrolled out of the drawer is one the store would then
     * have to be told about, and the one it was never told about is the leak.
     *
     * [animate] is false on bind - an icon appearing with its dot already grown is a home screen
     * that was always like that, and a page of icons all pulsing on their way in is not.
     */
    fun refreshDot(animate: Boolean = true) {
        val target = if (hasNotification()) 1f else 0f
        if (target == dotScale && dotAnimator == null) {
            // Same answer, but not necessarily the same dot: the colour is read at draw time, and
            // changing it in settings leaves every showing dot correct in state and wrong in
            // pixels. Cheap, and only reaches views that are actually showing one.
            if (dotScale > 0f) invalidate()
            return
        }

        dotAnimator?.cancel()
        dotAnimator = null

        if (!animate || !isAttachedToWindow) {
            dotScale = target
            invalidate()
            return
        }

        dotAnimator = ValueAnimator.ofFloat(dotScale, target).apply {
            duration = DOT_ANIMATION_MS
            addUpdateListener {
                dotScale = it.animatedValue as Float
                invalidate()
            }
            // Cleared on end so the early return above can tell a settled view from an animating
            // one; without it an icon whose dot came and went would stop redrawing.
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        dotAnimator = null
                    }
                },
            )
            start()
        }
    }

    /** The package this icon launches, or null for anything that does not name one. */
    private fun launchedPackage(): String? = when (val info = itemInfo) {
        is AppInfo -> info.componentName?.packageName
        is ShortcutInfo -> info.targetComponent?.packageName
            ?: info.deepShortcutPackage
        else -> null
    }

    private fun hasNotification(): Boolean {
        val info = itemInfo ?: return false
        return NotificationDots.hasDot(launchedPackage(), info.user)
    }

    override fun onDetachedFromWindow() {
        dotAnimator?.cancel()
        dotAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dotScale <= 0f) return

        // Where the compound drawable actually lands: centred across the view, at the top padding.
        // The same arithmetic AOSP's `getIconBounds` does, and it has to match or the dot sits
        // beside the icon rather than on it.
        val size = profile.iconSizePx
        val left = (width - size) / 2
        iconBounds.set(left, paddingTop, left + size, paddingTop + size)

        // Undo the view's scroll before drawing, exactly as AOSP's `drawDotIfNecessary` does.
        //
        // A canvas handed to `onDraw` has already been translated by the negative scroll offset by
        // the parent, and `TextView` puts that back itself when it positions its own content - so
        // "the top of the icon" in the bounds above and "the top of the icon" on the canvas are
        // two different points as soon as the view has scrolled at all. Skipping this looked
        // harmless right up until a drawer entry turned up with a `scrollX` of 524197, at which
        // point the dot was drawn a third of a mile to the left of the phone and every icon simply
        // had no dot on it.
        val scrollX = scrollX
        val scrollY = scrollY
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        DotRenderer.forShape(context, prefs.iconShape, prefs.notificationDotColour, size)
            .draw(canvas, iconBounds, width, height, dotScale, leftAlign = isLayoutRtl)
        canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
    }


    fun setIconBitmap(icon: Bitmap?) {
        if (icon == null) {
            setCompoundDrawables(null, null, null, null)
            return
        }
        val drawable = BitmapDrawable(resources, icon)
        drawable.setBounds(0, 0, profile.iconSizePx, profile.iconSizePx)
        setCompoundDrawables(null, drawable, null, null)
    }

    /**
     * True when this view lays out right to left.
     *
     * The dot follows the reading direction, as it does in AOSP: in a mirrored layout the corner
     * that reads as "the start of the icon" is the other one, and a dot pinned to the right of it
     * is a dot in the middle of the next icon's whitespace.
     */
    private val isLayoutRtl: Boolean
        get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private companion object {
        const val DISABLED_ALPHA = 0.4f

        /** Long enough to read as an arrival, short enough not to be in the way of a tap. */
        const val DOT_ANIMATION_MS = 160L

        fun deviceProfile(context: Context): DeviceProfile {
            val landscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            return LauncherAppState.getInstance(context).invariantDeviceProfile.profileFor(landscape)
        }
    }
}
