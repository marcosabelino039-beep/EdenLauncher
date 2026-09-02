package app.auriel.edenlauncher.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AdapterView
import android.widget.RemoteViews
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.util.EdenLog
import kotlin.math.abs

private const val TAG = "WidgetHostView"

/**
 * The view a hosted widget draws into.
 *
 * Ported from `LauncherAppWidgetHostView` (AOSP 7), then brought forward against AOSP 14 by way of
 * Lawnchair's fork in `.ref/lawnchair`. It solves the four problems a foreign view hierarchy
 * creates for a launcher:
 *
 *  - **Long press.** A widget wants every touch it can get, so the long press that starts a drag
 *    is detected here, above the widget's own children. A press that becomes a long press is
 *    claimed - the widget never sees the rest of it - and anything shorter, or anything that moves
 *    past the touch slop, is handed straight through and the widget behaves normally.
 *  - **Scrolling.** A widget containing a list has to be able to scroll it, which means the flick
 *    that opens the drawer must not read the same drag. Such content is detected on every layout
 *    and published as [hasScrollableContent]; the workspace asks before it claims an upward drag.
 *    Deliberately *not* done with `requestDisallowInterceptTouchEvent`: that reaches every ancestor
 *    including the drag layer, which is where a long press is turned into a drag, so a widget that
 *    asked for it could never be picked up again.
 *  - **Corners.** Providers draw square backgrounds; the launcher rounds them. See [RoundedCorners].
 *  - **Crashes and blank frames.** A provider's RemoteViews can throw during layout, and updates
 *    that arrive mid-drag cost frames exactly when they are most visible. Both are handled rather
 *    than allowed to reach the user as a launcher that stutters or dies.
 */
class LauncherAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** True once [longPressTrigger] has fired, so the rest of the gesture stays claimed. */
    private var claimed = false

    /**
     * Turned off for a widget inside a [WidgetStackView].
     *
     * The stack is what gets dragged, not the widget showing on top of it, and two long-press
     * timers running on the same press would race to decide which.
     */
    var longPressEnabled = true

    /**
     * True when the provider's views contain something that scrolls. Recomputed on every layout.
     *
     * Read by the workspace, which asks the whole subtree whether it can still scroll before it
     * reads an upward drag as the flick that opens the drawer. Kept as a field rather than
     * recomputed per touch because the answer only changes when the provider's views do.
     */
    var hasScrollableContent = false
        private set

    // Allocated once. This runs on every press on a widget, and the touch path must not allocate.
    private val longPressTrigger = Runnable {
        claimed = true
        // Cancel the widget's own view of the gesture, or a button under the finger stays visibly
        // pressed for as long as the drag lasts.
        cancelChildTouches()
        performLongClick()
    }

    // ---- rounded corners -------------------------------------------------------------------------

    private val cornerRadius = RoundedCorners.radiusFor(context)
    private val cornerRect = Rect()

    private val cornerOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (cornerRect.isEmpty || cornerRadius <= 0f) {
                outline.setEmpty()
            } else {
                outline.setRoundRect(cornerRect, cornerRadius)
            }
        }
    }

    // ---- deferred updates ------------------------------------------------------------------------

    private var deferUpdatesUntil = 0L
    private var lastRemoteViews: RemoteViews? = null
    private var missedUpdate = false

    /**
     * Set while the platform is applying the provider's own padding.
     *
     * `AppWidgetHostView.setAppWidget` pushes the provider's declared padding onto this view, and
     * on top of the launcher's own cell spacing that leaves the widget floating inside its block.
     * The launcher owns the space between cells, so the provider's copy is dropped - the same
     * thing `NavigableAppWidgetHostView` does in AOSP.
     */
    private var suppressPadding = false

    init {
        // A widget's internal focus order is its own business, and letting it into the launcher's
        // focus traversal makes the workspace unnavigable with a keyboard.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isLongClickable = true
        clipToOutline = true
        outlineProvider = cornerOutline
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        suppressPadding = true
        try {
            super.setAppWidget(appWidgetId, info)
        } finally {
            suppressPadding = false
        }
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        if (suppressPadding) return
        super.setPadding(left, top, right, bottom)
    }

    // ---- updates ---------------------------------------------------------------------------------

    /**
     * Holds off applying RemoteViews until [endDeferringUpdates].
     *
     * Used for the length of a drag or a resize. Inflating a provider's views is the most expensive
     * thing that happens to a widget, and a clock ticking over mid-gesture spends that cost on the
     * one frame the user is watching most closely. Nothing is lost: the last update to arrive is
     * applied when the gesture ends.
     */
    fun beginDeferringUpdates() {
        deferUpdatesUntil = SystemClock.uptimeMillis() + DEFER_TIMEOUT_MS
    }

    fun endDeferringUpdates() {
        deferUpdatesUntil = 0L
        if (missedUpdate) {
            missedUpdate = false
            updateAppWidget(lastRemoteViews)
        }
    }

    private val isDeferring: Boolean get() = SystemClock.uptimeMillis() < deferUpdatesUntil

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        lastRemoteViews = remoteViews
        if (isDeferring) {
            missedUpdate = true
            return
        }
        // A provider can hand over views this process cannot inflate: a resource id that moved in
        // an update, a layout referencing something it no longer ships. That is the provider's bug,
        // and it must not become the launcher's crash.
        runCatching { super.updateAppWidget(remoteViews) }
            .onFailure { EdenLog.w(TAG, "provider views would not apply", it) }
    }

    // ---- layout ----------------------------------------------------------------------------------

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Same reasoning one layer down: a RemoteViews hierarchy can throw from its own onLayout,
        // and the whole launcher would go with it.
        runCatching { super.onLayout(changed, left, top, right, bottom) }
            .onFailure {
                EdenLog.w(TAG, "provider views would not lay out", it)
                post { switchToErrorView() }
            }

        hasScrollableContent = containsScrollable(this)
        applyCornerClip()
    }

    /** Replaces the provider's views with the error tile, for views that cannot be laid out. */
    private fun switchToErrorView() {
        val provider = appWidgetInfo?.provider?.packageName ?: return
        runCatching { super.updateAppWidget(RemoteViews(provider, 0)) }
    }

    private fun applyCornerClip() {
        if (cornerRadius <= 0f) {
            cornerRect.setEmpty()
            return
        }
        val background = RoundedCorners.findBackground(this)
        if (background == null) {
            // The widget rounds itself. Clip to the plain bounds so nothing spills out of the
            // cell, and let its own corners stand.
            cornerRect.set(0, 0, width, height)
        } else {
            RoundedCorners.boundsIn(this, background, cornerRect)
        }
        invalidateOutline()
    }

    private fun containsScrollable(group: ViewGroup): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is AdapterView<*> || child.isScrollContainer) return true
            if (child is ViewGroup && containsScrollable(child)) return true
        }
        return false
    }

    // ---- touch -----------------------------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        trackLongPress(ev)
        return claimed
    }

    /**
     * A widget owns any gesture its own children did not take.
     *
     * Always true, as in AOSP. The alternative - consuming only once the long press has fired -
     * leaves the workspace as the touch target for every press that lands on a widget's empty
     * space, so a press-and-drag on a widget is read by the pager as a swipe rather than by the
     * launcher as a drag.
     *
     * Consuming does not take anything away from the workspace: a parent's
     * `onInterceptTouchEvent` runs before its children on every event, which is how paging, the
     * drawer flick and the drag layer all still get their chance. What it does is stop those
     * gestures from *falling through* to the workspace by default.
     */
    // The widget's own children keep their clicks and their accessibility actions - this view
    // never becomes a button in front of them.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Once this view consumes a gesture, its intercept stops being consulted for the rest of
        // it, so the long press has to be tracked from here as well.
        trackLongPress(event)
        return true
    }

    /**
     * Times the long press, wherever the events happen to be arriving.
     *
     * Idempotent on the way down: a press that this view ends up consuming passes through both
     * [onInterceptTouchEvent] and [onTouchEvent], and two timers on one press would fire two long
     * presses.
     */
    private fun trackLongPress(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claimed = false
                downX = ev.x
                downY = ev.y
                removeCallbacks(longPressTrigger)
                if (longPressEnabled) {
                    postDelayed(longPressTrigger, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    cancelLongPress()
                }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressTrigger)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressTrigger)
        super.onDetachedFromWindow()
    }

    private fun cancelChildTouches() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            for (i in 0 until childCount) getChildAt(i).dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    /**
     * Shown when the provider's RemoteViews fail to inflate - a broken update, or a provider
     * upgraded underneath us. The platform default is a bare grey box with no explanation, which on
     * a dark wallpaper reads as a rendering bug in the launcher rather than one in the widget.
     */
    override fun getErrorView(): View = ErrorView(context)

    private class ErrorView(context: Context) : View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.widget_placeholder_fill)
        }

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.eden_moss)
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }

        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.settings_text_secondary)
            textAlign = Paint.Align.CENTER
            textSize = 12f * resources.displayMetrics.scaledDensity
        }

        private val bounds = RectF()
        private val radius = 12f * resources.displayMetrics.density
        private val label = context.getString(R.string.widget_error)

        override fun onDraw(canvas: Canvas) {
            val inset = stroke.strokeWidth / 2f
            bounds.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(bounds, radius, radius, fill)
            canvas.drawRoundRect(bounds, radius, radius, stroke)
            val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(label, width / 2f, baseline, text)
        }
    }

    private companion object {
        /**
         * How long updates may be held back before one is applied anyway.
         *
         * A gesture that never ends - the process is paused, the system cancels it - would
         * otherwise leave the widget frozen on its last frame for good.
         */
        const val DEFER_TIMEOUT_MS = 1000L
    }
}
