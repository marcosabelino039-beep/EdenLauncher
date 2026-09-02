package app.auriel.edenlauncher.dragndrop

import android.view.View
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.LauncherAppWidgetInfo
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.model.WidgetStackInfo

/**
 * How a drop turns into a change the launcher persists.
 *
 * AOSP wired this straight into `Launcher` through static `LauncherModel` calls. Routing it
 * through an interface keeps the views free of any knowledge of the database, which is what lets
 * the workspace, the dock, and a folder share the same drop code.
 */
interface ItemPlacementHandler {

    /** [info] now lives at the given position; move the view and write the change. */
    fun onItemPlaced(
        info: ItemInfo,
        container: Long,
        screenId: Long,
        cellX: Int,
        cellY: Int,
        rank: Int,
    )

    /** [info] was dropped on the delete target. */
    fun onItemRemoved(info: ItemInfo)

    /** Two icons were stacked: replace [targetView] with a new folder holding both. */
    fun onFolderCreationRequested(
        target: ShortcutInfo,
        dropped: ShortcutInfo,
        targetView: View?,
        container: Long,
        screenId: Long,
        cellX: Int,
        cellY: Int,
    )

    /** An icon was dropped onto an existing folder. */
    fun onItemAddedToFolder(folder: FolderInfo, item: ShortcutInfo)

    /**
     * Two widgets were stacked: replace [targetView] with a stack holding both.
     *
     * The widget-side twin of [onFolderCreationRequested]. [target] keeps its cells and its size,
     * and [dropped] is resized to match - a stack is one block of the grid, and a child that kept
     * its own span would either overflow it or leave a hole in it.
     */
    fun onWidgetStackRequested(
        target: LauncherAppWidgetInfo,
        dropped: LauncherAppWidgetInfo,
        targetView: View?,
        container: Long,
        screenId: Long,
        cellX: Int,
        cellY: Int,
    )

    /** A widget was dropped onto an existing stack. */
    fun onWidgetAddedToStack(stack: WidgetStackInfo, widget: LauncherAppWidgetInfo)

    /** Page order changed; persist the new ordering. */
    fun onScreenOrderChanged(screenIds: List<Long>)
}
