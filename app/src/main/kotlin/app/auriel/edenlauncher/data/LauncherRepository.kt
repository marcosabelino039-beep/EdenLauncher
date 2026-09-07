package app.auriel.edenlauncher.data

import android.util.LongSparseArray
import app.auriel.edenlauncher.model.Containers
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ItemType
import app.auriel.edenlauncher.model.LauncherAppWidgetInfo
import app.auriel.edenlauncher.model.NO_ID
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.model.WidgetStackInfo
import app.auriel.edenlauncher.util.EdenLog
import app.auriel.edenlauncher.util.UserCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the launcher persists, in one place.
 *
 * All work runs on [io]; callers stay on the main thread. The loader in Phase 2 consumes
 * [loadWorkspace] once at startup and then applies incremental writes as the user rearranges.
 */
class LauncherRepository(
    private val dao: LauncherDao,
    private val users: UserCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * One pass over both tables, with folder children already attached to their folders.
     *
     * @param screenIds page ids in left-to-right order.
     * @param workspaceItems items on a workspace page, in row-major order.
     * @param hotseatItems items in the dock, ordered by rank.
     */
    class WorkspaceData(
        val screenIds: List<Long>,
        val workspaceItems: List<ItemInfo>,
        val hotseatItems: List<ItemInfo>,
    )

    suspend fun loadWorkspace(): WorkspaceData = withContext(io) {
        val screens = dao.loadScreens().map { it.id }

        // Clear anything whose parent folder is gone before reading. Deleting a page used to take
        // the folder's own row and leave its children behind, and those rows are unreachable: they
        // are not on a page, not in the dock, and the folder they name does not exist. Left alone
        // they are carried forward forever.
        val orphans = dao.deleteOrphans(ROOT_CONTAINERS, CONTAINER_TYPES)
        if (orphans > 0) EdenLog.i(TAG, "swept $orphans row(s) whose folder no longer exists")

        val rows = dao.loadFavorites()

        // Rows are converted once. Folder children are held aside because a child can be read
        // before its folder; they are attached in a second pass over that much smaller list.
        val folders = LongSparseArray<FolderInfo>()
        val stacks = LongSparseArray<WidgetStackInfo>()
        val workspace = ArrayList<ItemInfo>(rows.size)
        val hotseat = ArrayList<ItemInfo>(8)
        val pendingFolderChildren = ArrayList<ShortcutInfo>()
        val pendingStackChildren = ArrayList<LauncherAppWidgetInfo>()

        for (row in rows) {
            val item = row.toItemInfo(users) ?: continue
            if (item is FolderInfo) folders.put(item.id, item)
            if (item is WidgetStackInfo) stacks.put(item.id, item)
            when (item.container) {
                Containers.DESKTOP -> workspace.add(item)
                Containers.HOTSEAT -> hotseat.add(item)
                // A child of a folder or of a widget stack. Which one it is follows from what the
                // child is: only shortcuts go in folders and only widgets go in stacks.
                else -> when (item) {
                    is ShortcutInfo -> pendingFolderChildren.add(item)
                    is LauncherAppWidgetInfo -> pendingStackChildren.add(item)
                    else -> Unit
                }
            }
        }

        for (child in pendingFolderChildren) {
            folders.get(child.container)?.contents?.add(child)
        }
        for (child in pendingStackChildren) {
            stacks.get(child.container)?.contents?.add(child)
        }
        for (i in 0 until folders.size()) {
            folders.valueAt(i).contents.sortBy { it.rank }
        }
        for (i in 0 until stacks.size()) {
            val stack = stacks.valueAt(i)
            stack.contents.sortBy { it.rank }
            // The stack owns the block; a child that came back with a stale span from before a
            // resize would be measured wrong on the very first layout.
            stack.applySpanToContents()
        }

        hotseat.sortBy { it.rank }
        WorkspaceData(reconcileScreens(screens, workspace), workspace, hotseat)
    }

    /**
     * Gives back any page an item still names but that has no row of its own.
     *
     * A row pointing at a page that does not exist is invisible: the bind has nowhere to put it,
     * and the launcher's only recourse is to move it somewhere else, which loses the arrangement it
     * was part of. Recreating the page instead puts everything on it back exactly where it was, and
     * costs an empty page in the one case where the item was the last trace of a page that really
     * was deleted.
     *
     * Databases in the wild already contain these - see `LauncherDao.replaceScreens` for how they
     * got there - so this heals rather than merely preventing. Negative ids are left alone: the
     * only one that can appear is the transient trailing page, which is not a page anybody asked
     * for and must not become one.
     */
    private suspend fun reconcileScreens(
        screens: List<Long>,
        workspaceItems: List<ItemInfo>,
    ): List<Long> {
        val known = screens.toHashSet()
        val missing = workspaceItems
            .map { it.screenId }
            .filter { it > 0 && it !in known }
            .distinct()
            .sorted()
        if (missing.isEmpty()) return screens

        val stamp = now()
        dao.insertScreens(
            missing.mapIndexed { index, id ->
                WorkspaceScreenEntity(id = id, screenRank = screens.size + index, modified = stamp)
            },
        )
        EdenLog.i(TAG, "restored page(s) $missing that items still referred to")
        return screens + missing
    }

    /** Inserts [item], assigning its database id in place. */
    suspend fun addItem(item: ItemInfo): Long = withContext(io) {
        val id = dao.insertFavorite(item.toEntity(users, now()))
        item.id = id
        id
    }

    suspend fun updateItem(item: ItemInfo) = withContext(io) {
        check(item.id != NO_ID) { "updateItem called on an unsaved item: $item" }
        dao.updateFavorite(item.toEntity(users, now()))
    }

    /** Batched position write used after a drag settles or a page is reordered. */
    suspend fun updateItems(items: List<ItemInfo>) = withContext(io) {
        val stamp = now()
        dao.updateFavorites(items.map { it.toEntity(users, stamp) })
    }

    /**
     * Deletes [item], and everything inside it when it is a folder or a widget stack.
     *
     * Returns the widget ids that went with it, which the caller has to hand back to the
     * `AppWidgetHost`. The repository cannot do that itself - the host belongs to the activity -
     * and an id whose row is gone but which is never released is leaked for the life of the
     * install, with its provider still being woken for a widget nobody can see.
     */
    suspend fun deleteItem(item: ItemInfo): List<Int> = withContext(io) {
        when (item) {
            is FolderInfo -> {
                dao.deleteContainerAndContents(item.id)
                emptyList()
            }

            is WidgetStackInfo -> {
                val ids = dao.widgetIdsInContainer(item.id)
                dao.deleteContainerAndContents(item.id)
                ids
            }

            else -> {
                dao.deleteFavoriteById(item.id)
                if (item is LauncherAppWidgetInfo && item.appWidgetId >= 0) {
                    listOf(item.appWidgetId)
                } else {
                    emptyList()
                }
            }
        }
    }

    /** Every widget id the workspace still refers to. Anything else the host owns is an orphan. */
    suspend fun widgetIdsInUse(): Set<Int> = withContext(io) { dao.allWidgetIds().toSet() }

    // ---- screens ---------------------------------------------------------------------------

    suspend fun loadScreenIds(): List<Long> = withContext(io) { dao.loadScreens().map { it.id } }

    /** Appends a new empty page and returns its id. */
    suspend fun addScreen(): Long = withContext(io) {
        val rank = dao.loadScreens().size
        dao.insertScreen(WorkspaceScreenEntity(screenRank = rank, modified = now()))
    }

    /** Removes a page and everything on it; returns the widget ids that went with it. */
    suspend fun removeScreen(screenId: Long): List<Int> = withContext(io) {
        val widgetIds = dao.widgetIdsOnScreen(Containers.DESKTOP, screenId, CONTAINER_TYPES)
        dao.deleteScreenWithContents(screenId, Containers.DESKTOP, CONTAINER_TYPES)
        widgetIds
    }

    /**
     * Persists a new page order; [screenIds] is the full list, left to right.
     *
     * A page the caller does not know about but that still holds items is kept and appended, not
     * deleted. See `LauncherDao.replaceScreens`.
     */
    suspend fun reorderScreens(screenIds: List<Long>) = withContext(io) {
        val stamp = now()
        val rescued = dao.replaceScreens(
            screenIds.mapIndexed { rank, id ->
                WorkspaceScreenEntity(id = id, screenRank = rank, modified = stamp)
            },
            Containers.DESKTOP,
            stamp,
        )
        if (rescued.isNotEmpty()) {
            EdenLog.w(TAG, "page order omitted $rescued, which still hold items; kept them")
        }
    }

    /**
     * Guarantees at least one page exists. Called before the first bind so an empty database
     * still produces a usable home screen.
     */
    suspend fun ensureDefaultScreen(): List<Long> = withContext(io) {
        val existing = dao.loadScreens()
        if (existing.isNotEmpty()) return@withContext existing.map { it.id }
        listOf(dao.insertScreen(WorkspaceScreenEntity(screenRank = 0, modified = now())))
    }

    suspend fun isEmpty(): Boolean = withContext(io) { dao.favoriteCount() == 0 }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "LauncherRepository"

        /** Containers that are not rows: the desktop and the dock. */
        val ROOT_CONTAINERS = listOf(Containers.DESKTOP, Containers.HOTSEAT)

        /** Item kinds whose row id can legitimately appear as another row's `container`. */
        val CONTAINER_TYPES = listOf(ItemType.FOLDER.code, ItemType.WIDGET_STACK.code)
    }
}
