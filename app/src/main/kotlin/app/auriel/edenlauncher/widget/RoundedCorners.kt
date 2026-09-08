package app.auriel.edenlauncher.widget

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup

/**
 * Rounding the corners of a widget the launcher did not draw.
 *
 * Ported from `RoundedCornerEnforcement` (AOSP 14, via Lawnchair's fork in `.ref/lawnchair`).
 *
 * A provider ships its own background - usually a square one, because most widgets were authored
 * long before anything rounded them. Clipping the host view's own bounds is not enough: the
 * background inside it is often inset, and clipping the outer rectangle leaves the square corners
 * of the inner one visible. So the background view is found first and the clip is applied to
 * *its* rectangle, expressed in the host view's coordinates.
 *
 * A widget that has already rounded itself is left alone - it says so by putting a clipped view
 * with the platform's `android.R.id.background` id at the root, which is the contract Android 12
 * introduced for exactly this.
 */
object RoundedCorners {

    /**
     * The radius to clip to, in pixels.
     *
     * From API 31 the platform publishes the radius the system itself uses for widget backgrounds,
     * so a widget in Eden matches one in the stock launcher on the same phone. Below that there is
     * nothing to ask, and 16dp is the value AOSP hard-codes as its own maximum.
     */
    fun radiusFor(context: Context): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val systemRadius = context.resources
                .getDimension(android.R.dimen.system_app_widget_background_radius)
            if (systemRadius > 0f) return minOf(systemRadius, maxRadius(context))
        }
        return maxRadius(context)
    }

    /**
     * The view whose rectangle should be clipped, or null when there is nothing to clip to.
     *
     * A widget that declares *exactly one* `android.R.id.background` has said where its background
     * is and that is the answer. Two or more is not a background, it is a layout that happens to
     * reuse the id, and guessing between them is how a clip ends up around a widget's content -
     * so the search falls through to the shape of the view tree instead, which is what AOSP
     * settled on after finding that deeper searches picked up content rather than backgrounds.
     */
    fun findBackground(host: View): View? {
        val tagged = ArrayList<View>(2)
        accumulateBackgroundIds(host, tagged)
        if (tagged.size == 1) return tagged[0]

        val group = host as? ViewGroup ?: return host
        if (group.childCount == 0) return host
        return firstDrawingView(group.getChildAt(0)) ?: host
    }

    /**
     * True when the widget has rounded itself and the launcher must not round it again.
     *
     * The contract Android 12 introduced: a view with `android.R.id.background` that clips to its
     * own outline is a widget saying "these are my corners". Rounding that a second time takes a
     * second bite out of the same corner - the launcher's radius applied to the *outer* bounds,
     * cutting through whatever the widget had drawn inside its own inset background. On a
     * background that is a pill rather than a rounded square, which is what Google's search and
     * At a Glance widgets ship, that reads as a widget with its ends sliced off.
     *
     * Until v0.7.0 this was detected and then ignored: the enforcement fell through to clipping
     * the host view's full bounds *to the same radius*, which is the double-rounding it was meant
     * to avoid. The widget is now left alone, clipped square to its cell and no further.
     */
    fun hasOptedOut(background: View): Boolean =
        background.id == android.R.id.background && background.clipToOutline

    /** Writes [background]'s bounds into [out], in [host]'s coordinate space. */
    fun boundsIn(host: View, background: View, out: Rect) {
        out.set(0, 0, background.width, background.height)
        var view = background
        while (view !== host) {
            out.offset(view.left, view.top)
            view = view.parent as? View ?: return
        }
    }

    private fun maxRadius(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            MAX_RADIUS_DP,
            context.resources.displayMetrics,
        )

    /** Collects every `android.R.id.background` in the tree, not descending into one it found. */
    private fun accumulateBackgroundIds(view: View, into: MutableList<View>) {
        if (view.id == android.R.id.background) {
            into.add(view)
            return
        }
        val group = view as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            accumulateBackgroundIds(group.getChildAt(i), into)
            // Two is already more than one, and the caller only asks whether there was exactly one.
            if (into.size > 1) return
        }
    }

    /**
     * The first descendant that actually paints something, or null.
     *
     * A view group that draws nothing and holds one child is a wrapper, and clipping to it is the
     * same as clipping to that child; one that holds two is a layout, and its own bounds are the
     * background. That distinction is what stops the search from descending into content.
     */
    private fun firstDrawingView(view: View): View? {
        if (view.visibility != View.VISIBLE) return null
        if (!view.willNotDraw() || view.background != null || view.foreground != null) return view

        val group = view as? ViewGroup ?: return null
        var found: View? = null
        for (i in 0 until group.childCount) {
            val candidate = firstDrawingView(group.getChildAt(i)) ?: continue
            if (found != null) return group
            found = candidate
        }
        return found
    }

    /** AOSP's `enforced_rounded_corner_max_radius`. */
    private const val MAX_RADIUS_DP = 16f
}
