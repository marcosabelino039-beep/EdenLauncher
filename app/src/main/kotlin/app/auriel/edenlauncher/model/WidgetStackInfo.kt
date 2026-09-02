package app.auriel.edenlauncher.model

/**
 * Several widgets sharing one block of the grid, one of them facing the user.
 *
 * The same shape as [FolderInfo]: a row of its own that sits on a page and holds cells, plus
 * children whose `container` is this row's id and whose `rank` is their position in the pile. A
 * folder holds shortcuts and opens; a stack holds widgets and is flicked through in place.
 *
 * Children are always the stack's own size. A stack is one block of the grid, and a child that
 * kept a different span would either overflow the block or leave a hole in it, so [spanX] and
 * [spanY] are pushed down to every child whenever the stack is placed or resized.
 *
 * Which widget is showing is part of the arrangement - a stack that forgets it and springs back to
 * the first widget on every launch is a stack nobody would use - so it is persisted in the
 * `options` column, the same column a folder keeps its flags in and which no other item type
 * reads. It deliberately does not use `rank`: `rank` is rewritten to 0 by every drop onto the
 * desktop, and the visible widget would reset each time the stack was moved.
 */
class WidgetStackInfo : ItemInfo() {

    init {
        itemType = ItemType.WIDGET_STACK
    }

    /** The widgets in the pile, bottom to top, ordered by their `rank`. */
    @JvmField
    val contents = ArrayList<LauncherAppWidgetInfo>(4)

    /**
     * Which child is facing the user. Clamped on read rather than on write: the contents are
     * filled in after the index is restored, and a child can be removed while the stack is open.
     */
    var visibleIndex: Int = 0
        get() = if (contents.isEmpty()) 0 else field.coerceIn(0, contents.size - 1)

    val visible: LauncherAppWidgetInfo?
        get() = contents.getOrNull(visibleIndex)

    /** Appends [widget] and renumbers, so the stored ranks always match the list order. */
    fun add(widget: LauncherAppWidgetInfo) {
        widget.container = id
        widget.screenId = -1L
        widget.cellX = -1
        widget.cellY = -1
        widget.spanX = spanX
        widget.spanY = spanY
        contents.add(widget)
        renumber()
    }

    fun remove(widget: LauncherAppWidgetInfo): Boolean {
        val index = contents.indexOf(widget)
        if (index < 0) return false
        contents.removeAt(index)
        // Keep looking at the same place in the pile rather than jumping to the bottom of it.
        if (visibleIndex > index || visibleIndex >= contents.size) visibleIndex = visibleIndex - 1
        renumber()
        return true
    }

    /** Pushes the stack's span onto every child, after a resize or a placement. */
    fun applySpanToContents() {
        for (child in contents) {
            child.spanX = spanX
            child.spanY = spanY
        }
    }

    private fun renumber() {
        for ((rank, child) in contents.withIndex()) child.rank = rank
    }

    companion object {
        /**
         * How many widgets one stack may hold.
         *
         * Every child in a stack is a live hosted widget whose provider keeps being woken, whether
         * or not it is the one on top. Eight is already more than anyone flicks through; on the
         * 4 GB phone this project targets it is the point where the cost stops being worth it.
         */
        const val MAX_CONTENTS = 8
    }
}
