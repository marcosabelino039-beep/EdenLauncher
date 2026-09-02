package app.auriel.edenlauncher.util

import android.view.View
import android.view.ViewGroup

/**
 * Whether this view, or anything inside it, can still scroll in [direction].
 *
 * Positive is the direction content moves when the finger goes *up*, matching
 * [View.canScrollVertically].
 *
 * Needed because the thing that can scroll is usually not the thing being touched. A widget is a
 * view hierarchy the launcher did not build: the list inside it is somewhere below the host view,
 * and asking the host alone always answers no. Whoever is deciding who owns a vertical drag - the
 * workspace, or a stack of widgets - has to ask the whole subtree.
 *
 * Invisible children are skipped, and the walk stops at the first view that answers yes, so the
 * common case costs one call. Nothing here allocates: it runs on the touch path.
 */
fun View.canScrollDeeply(direction: Int): Boolean {
    if (visibility != View.VISIBLE) return false
    if (canScrollVertically(direction)) return true

    val group = this as? ViewGroup ?: return false
    for (i in 0 until group.childCount) {
        if (group.getChildAt(i).canScrollDeeply(direction)) return true
    }
    return false
}
