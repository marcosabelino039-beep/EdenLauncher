package app.auriel.edenlauncher.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.model.WidgetStackInfo
import app.auriel.edenlauncher.util.canScrollDeeply
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Several widgets in one block of the grid, flicked through with a vertical swipe.
 *
 * A stack is to widgets what a folder is to icons, with one difference that decides the whole
 * design: a folder opens, and a stack does not. The widgets are stacked in place and one of them
 * is on top, so a stack of four costs four cells rather than sixteen and the widget you want is
 * one gesture away rather than a tap, a look, and a tap back.
 *
 * **Why the swipe is vertical.** Horizontal belongs to the workspace - that is how pages are
 * changed, and taking it here would mean a widget you could never swipe past. Up and down are free
 * on a page: the only other vertical gesture is the flick that opens the drawer, and that one has
 * to travel much further than the touch slop this view claims at, so the stack always wins a
 * gesture that starts on it.
 *
 * **Who gets the touch.** Three parties want the same finger. The workspace wants to page, the
 * widget on top wants its own scrolling, and the stack wants to be flicked. The order is: a widget
 * that can actually scroll in the direction being dragged keeps the gesture (a list widget you can
 * still scroll is not a stack you are trying to turn); otherwise the stack claims it at the touch
 * slop, which is well before the workspace has decided anything; and the workspace gets everything
 * else, including every horizontal drag.
 *
 * **Cost.** Every widget in a stack is live whether or not it is showing - the system pushes its
 * provider's updates at the host regardless. That is why [WidgetStackInfo.MAX_CONTENTS] is small.
 * The ones that are not showing are [INVISIBLE] rather than [GONE], so they keep their place in the
 * layout and a flick does not have to run a layout pass to reveal one.
 */
class WidgetStackView(context: Context) : ViewGroup(context) {

    /** Told when the visible widget changes, so the choice can be persisted. */
    var onVisibleIndexChanged: ((Int) -> Unit)? = null

    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop
    private val minFlingVelocity = configuration.scaledMinimumFlingVelocity
    private val density = resources.displayMetrics.density

    private var stack: WidgetStackInfo? = null

    /** Which child is on top. Always a valid index while there are children. */
    var visibleIndex: Int = 0
        private set

    /**
     * How far the pile has been dragged from its resting position, in pixels.
     *
     * Positive means the finger has moved down and the widget below is coming into view. Reset to
     * zero every time [visibleIndex] changes, so the two together always describe the pile exactly.
     */
    private var dragOffset = 0f

    /** The undamped total of the current drag, so the pull-back at the ends does not compound. */
    private var dragRaw = 0f

    private var dragging = false
    private var claimed = false
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var velocityTracker: VelocityTracker? = null

    /** Where the settle currently running is heading. */
    private var settleTarget = 0

    /**
     * Set when a settle is cancelled rather than finished.
     *
     * A cancelled animator still reports that it ended, and taking that at face value would commit
     * a flick the user interrupted - grabbing a settling pile would snap it to where it had been
     * going instead of handing it back to the finger.
     */
    private var settleCancelled = false

    /** One animator, reused. A settle happens once per gesture; a new one each time need not. */
    private val settle = ValueAnimator.ofFloat(0f, 0f).apply {
        duration = SETTLE_MS
        addUpdateListener {
            dragOffset = it.animatedValue as Float
            applyPositions()
        }
        addListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    settleCancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!settleCancelled) commitIndex(settleTarget)
                }
            },
        )
    }

    // Allocated once: this runs on every press on a stack, and the touch path must not allocate.
    private val longPressTrigger = Runnable {
        claimed = true
        cancelChildTouches()
        performLongClick()
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotActiveColor = context.getColor(R.color.accent)
    private val dotIdleColor = context.getColor(R.color.widget_stack_dot_idle)

    init {
        clipChildren = true
        isLongClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    // ---- contents --------------------------------------------------------------------------------

    /**
     * Puts [views] into the pile, in the order the stack holds them.
     *
     * The views are made by the launcher rather than here: a child can be a hosted widget or the
     * placeholder that stands in for one whose provider is gone, and only the launcher knows how to
     * build either.
     */
    fun bind(info: WidgetStackInfo, views: List<View>) {
        stack = info
        removeAllViews()
        for (view in views) {
            prepareChild(view)
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        visibleIndex = info.visibleIndex.coerceIn(0, maxOf(0, childCount - 1))
        dragOffset = 0f
        applyPositions()
        updateContentDescription()
    }

    /** Adds one more widget on top of the pile and shows it. */
    fun addWidgetView(view: View) {
        prepareChild(view)
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showIndex(childCount - 1, animate = true)
    }

    /** Takes one widget out of the pile. */
    fun removeWidgetView(view: View) {
        val index = indexOfChild(view)
        if (index < 0) return
        removeViewAt(index)
        if (visibleIndex >= childCount) visibleIndex = maxOf(0, childCount - 1)
        dragOffset = 0f
        applyPositions()
        updateContentDescription()
    }

    val visibleView: View?
        get() = if (visibleIndex in 0 until childCount) getChildAt(visibleIndex) else null

    /**
     * A widget in a stack is not the thing being touched - the stack is.
     *
     * It hands back its long press, which would otherwise start a drag of one widget instead of the
     * pile. Its own scrolling is left alone: the stack asks about that on every swipe and only
     * claims one the widget cannot use itself.
     */
    private fun prepareChild(view: View) {
        (view as? LauncherAppWidgetHostView)?.longPressEnabled = false
        view.setOnLongClickListener(null)
        view.isLongClickable = false
    }

    // ---- paging ----------------------------------------------------------------------------------

    /** Brings child [index] to the top, sliding the pile there when [animate]. */
    fun showIndex(index: Int, animate: Boolean) {
        val target = index.coerceIn(0, maxOf(0, childCount - 1))
        if (target == visibleIndex && dragOffset == 0f) return

        if (!animate) {
            settle.cancel()
            commitIndex(target)
            return
        }
        animateTo(target)
    }

    private fun animateTo(target: Int) {
        settle.cancel()
        settleTarget = target
        val end = (visibleIndex - target) * height.toFloat()
        if (height == 0) {
            commitIndex(target)
            return
        }
        settleCancelled = false
        settle.setFloatValues(dragOffset, end)
        settle.start()
    }

    private fun commitIndex(target: Int) {
        val changed = target != visibleIndex
        visibleIndex = target
        dragOffset = 0f
        dragRaw = 0f
        applyPositions()
        if (changed) {
            updateContentDescription()
            stack?.visibleIndex = target
            onVisibleIndexChanged?.invoke(target)
        }
    }

    /**
     * Puts every child where the current index and drag offset say it should be.
     *
     * Runs on every touch event of a flick, so it neither allocates nor asks for a layout pass:
     * children are laid out full-size once and moved with [View.setTranslationY] after that.
     */
    private fun applyPositions() {
        val h = height
        if (h == 0) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val ty = (i - visibleIndex) * h + dragOffset
            child.translationY = ty
            val visible = abs(ty) < h
            val wanted = if (visible) VISIBLE else INVISIBLE
            if (child.visibility != wanted) child.visibility = wanted
        }
        invalidate()
    }

    // ---- layout ----------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(childWidth, childHeight)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, width, height)
        applyPositions()

        // Every widget in the pile is shown at the stack's size, whichever one is on top, so they
        // all have to be told the same thing. A provider that is not told picks a layout for the
        // size it was last given, which is the size it had before it was stacked.
        if (changed) {
            for (i in 0 until childCount) {
                val host = getChildAt(i) as? AppWidgetHostView ?: continue
                WidgetSizes.applySize(host, context, width, height)
            }
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    // ---- touch -----------------------------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claimed = false
                dragging = false
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                acquireVelocityTracker(ev)
                // Touching a pile that is still settling grabs it, the same way touching a fling
                // stops it. Anything else would take the gesture and then be overruled mid-flick.
                if (settle.isRunning) {
                    settle.cancel()
                    beginDrag(ev.y)
                    return true
                }
                postDelayed(longPressTrigger, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (claimed || dragging) return true
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * VERTICAL_BIAS) {
                    cancelLongPress()
                    if (canFlick(dy)) {
                        beginDrag(ev.y)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                releaseVelocityTracker()
            }
        }
        return claimed || dragging
    }

    // Flicking is a drag, not a tap; there is no click here for a service to perform. The
    // accessibility scroll actions below are how this is driven without one.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (claimed) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                acquireVelocityTracker(ev)
                return childCount > 1
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (!dragging) {
                    val dy = ev.y - downY
                    if (abs(dy) > touchSlop && abs(dy) > abs(ev.x - downX) * VERTICAL_BIAS &&
                        canFlick(dy)
                    ) {
                        beginDrag(ev.y)
                    }
                    return dragging
                }
                dragBy(ev.y - lastY)
                lastY = ev.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    releaseVelocityTracker()
                    return false
                }
                val tracker = velocityTracker
                tracker?.computeCurrentVelocity(VELOCITY_UNITS)
                val velocity = tracker?.yVelocity ?: 0f
                endDrag(velocity)
                releaseVelocityTracker()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) endDrag(0f)
                releaseVelocityTracker()
                return true
            }
        }
        return dragging
    }

    private fun beginDrag(y: Float) {
        dragging = true
        lastY = y
        dragRaw = dragOffset
        cancelChildTouches()
        deferChildUpdates(true)
    }

    /**
     * Moves the pile with the finger, with the ends held back.
     *
     * Past the first or the last widget the pile still moves, at a third of the distance. Refusing
     * to move at all reads as a dropped gesture; moving freely promises a widget that is not there.
     */
    private fun dragBy(dy: Float) {
        dragRaw += dy
        val atTop = visibleIndex == 0 && dragRaw > 0f
        val atBottom = visibleIndex == childCount - 1 && dragRaw < 0f
        dragOffset = if (atTop || atBottom) {
            dragRaw * EDGE_RESISTANCE
        } else {
            dragRaw.coerceIn(-height.toFloat(), height.toFloat())
        }
        applyPositions()
    }

    private fun endDrag(velocity: Float) {
        dragging = false
        deferChildUpdates(false)

        val flicked = abs(velocity) > minFlingVelocity
        val far = abs(dragOffset) > height * COMMIT_FRACTION
        // A flick is read from its direction, a slow drag from how far it got. Both mean the same
        // thing: down uncovers the widget below, up brings up the one above.
        val step = when {
            flicked -> -sign(velocity).toInt()
            far -> -sign(dragOffset).toInt()
            else -> 0
        }
        animateTo((visibleIndex + step).coerceIn(0, maxOf(0, childCount - 1)))
    }

    /**
     * True when a flick in this direction belongs to the stack rather than to the widget on top.
     *
     * A list widget that can still scroll the way the finger is going keeps the gesture. Once it
     * has reached its end the next swipe turns the pile, which is the same handover a nested
     * scrolling view makes and the one people already expect.
     */
    private fun canFlick(dy: Float): Boolean {
        if (childCount <= 1) return false
        val child = visibleView ?: return false
        val direction = if (dy < 0) 1 else -1
        return !child.canScrollDeeply(direction)
    }

    /**
     * Says that this view takes vertical drags, so nothing above it claims one first.
     *
     * The workspace asks exactly this before reading an upward drag as the flick that opens the
     * drawer. True in both directions whenever there is more than one widget in the pile: the ends
     * still answer the gesture, by pulling back against it.
     */
    override fun canScrollVertically(direction: Int): Boolean = childCount > 1

    private fun deferChildUpdates(defer: Boolean) {
        for (i in 0 until childCount) {
            val host = getChildAt(i) as? LauncherAppWidgetHostView ?: continue
            if (defer) host.beginDeferringUpdates() else host.endDeferringUpdates()
        }
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressTrigger)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressTrigger)
        settle.cancel()
        releaseVelocityTracker()
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

    private fun acquireVelocityTracker(ev: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(ev)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---- indicator -------------------------------------------------------------------------------

    /**
     * The dots down the right edge, saying how deep the pile is and where in it you are.
     *
     * Drawn by the stack rather than added as a child view: it is four circles, and a view to hold
     * them would be measured and laid out on every pass for nothing.
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        val count = childCount
        if (count <= 1) return

        val radius = DOT_RADIUS_DP * density
        val spacing = DOT_SPACING_DP * density
        val x = width - DOT_MARGIN_DP * density - radius
        var y = (height - (count - 1) * spacing) / 2f

        // Between two widgets the dots show the crossing rather than snapping, so a half-finished
        // flick looks half-finished.
        val progress = if (height > 0) -dragOffset / height else 0f
        val position = visibleIndex + progress

        for (i in 0 until count) {
            val distance = abs(i - position).coerceAtMost(1f)
            dotPaint.color = if (distance < 0.5f) dotActiveColor else dotIdleColor
            dotPaint.alpha = (DOT_MIN_ALPHA + (255 - DOT_MIN_ALPHA) * (1f - distance)).roundToInt()
            canvas.drawCircle(x, y, radius, dotPaint)
            y += spacing
        }
    }

    // ---- accessibility ---------------------------------------------------------------------------

    private fun updateContentDescription() {
        contentDescription = if (childCount <= 1) {
            context.getString(R.string.widget_stack_name)
        } else {
            context.getString(R.string.widget_stack_position, visibleIndex + 1, childCount)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = ViewGroup::class.java.name
        if (visibleIndex < childCount - 1) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
        if (visibleIndex > 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                if (visibleIndex >= childCount - 1) return false
                showIndex(visibleIndex + 1, animate = true)
                return true
            }

            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                if (visibleIndex <= 0) return false
                showIndex(visibleIndex - 1, animate = true)
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private companion object {
        /** How much more vertical than horizontal a swipe must be before the stack claims it. */
        const val VERTICAL_BIAS = 1.2f

        /** Fraction of the block a slow drag has to cover to change the widget on top. */
        const val COMMIT_FRACTION = 0.28f

        /** How far the pile follows the finger past its first and last widget. */
        const val EDGE_RESISTANCE = 0.33f

        const val SETTLE_MS = 220L

        /** Velocities are read in pixels per second, which is what the fling threshold is in. */
        const val VELOCITY_UNITS = 1000

        const val DOT_RADIUS_DP = 2.5f
        const val DOT_SPACING_DP = 8f
        const val DOT_MARGIN_DP = 5f
        const val DOT_MIN_ALPHA = 70
    }
}
