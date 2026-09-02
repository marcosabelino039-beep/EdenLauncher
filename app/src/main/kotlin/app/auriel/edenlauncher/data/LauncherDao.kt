package app.auriel.edenlauncher.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Replaces AOSP's `LauncherProvider`. Every read the loader performs is a single query; the
 * workspace is small enough that paging would cost more than it saves.
 *
 * An abstract class rather than an interface so `@Transaction` bodies are real methods on the
 * generated implementation.
 */
@Dao
abstract class LauncherDao {

    // ---- favorites -------------------------------------------------------------------------

    @Query("SELECT * FROM favorites ORDER BY container, screen, cellY, cellX")
    abstract suspend fun loadFavorites(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE container = :container AND screen = :screen ORDER BY cellY, cellX")
    abstract suspend fun favoritesOnScreen(container: Long, screen: Long): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE container = :folderId ORDER BY rank")
    abstract suspend fun folderContents(folderId: Long): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE _id = :id")
    abstract suspend fun favoriteById(id: Long): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFavorite(item: FavoriteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFavorites(items: List<FavoriteEntity>): List<Long>

    @Update
    abstract suspend fun updateFavorite(item: FavoriteEntity)

    @Update
    abstract suspend fun updateFavorites(items: List<FavoriteEntity>)

    @Delete
    abstract suspend fun deleteFavorite(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE _id = :id")
    abstract suspend fun deleteFavoriteById(id: Long)

    /** Deletes a container row - a folder or a widget stack - and everything inside it. */
    @Query("DELETE FROM favorites WHERE _id = :containerId OR container = :containerId")
    abstract suspend fun deleteContainerAndContents(containerId: Long)

    /**
     * The widget ids held by the children of one container.
     *
     * Read before the container is deleted: a stack's children are hosted widgets, and their ids
     * have to be handed back to the system once their rows are gone or they are leaked for the
     * life of the install.
     */
    @Query("SELECT appWidgetId FROM favorites WHERE container = :containerId AND appWidgetId >= 0")
    abstract suspend fun widgetIdsInContainer(containerId: Long): List<Int>

    /** Every widget id the database still refers to, wherever the row sits. */
    @Query("SELECT appWidgetId FROM favorites WHERE appWidgetId >= 0")
    abstract suspend fun allWidgetIds(): List<Int>

    @Query("DELETE FROM favorites WHERE container = :container AND screen = :screen")
    abstract suspend fun deleteScreenContents(container: Long, screen: Long)

    @Query(
        "SELECT _id FROM favorites " +
            "WHERE container = :container AND screen = :screen AND itemType IN (:containerTypes)",
    )
    abstract suspend fun containerIdsOnScreen(
        container: Long,
        screen: Long,
        containerTypes: List<Int>,
    ): List<Long>

    /** Widget ids belonging to a page: the widgets on it, and the ones inside its stacks. */
    @Query(
        """
        SELECT appWidgetId FROM favorites
        WHERE appWidgetId >= 0
          AND (
            (container = :container AND screen = :screen)
            OR container IN (
                SELECT _id FROM favorites
                WHERE container = :container AND screen = :screen AND itemType IN (:containerTypes)
            )
          )
        """,
    )
    abstract suspend fun widgetIdsOnScreen(
        container: Long,
        screen: Long,
        containerTypes: List<Int>,
    ): List<Int>

    @Query("DELETE FROM favorites WHERE container IN (:containerIds)")
    abstract suspend fun deleteItemsInContainers(containerIds: List<Long>)

    @Query("SELECT COUNT(*) FROM favorites")
    abstract suspend fun favoriteCount(): Int

    @Query("DELETE FROM favorites")
    abstract suspend fun clearFavorites()

    // ---- workspace screens -----------------------------------------------------------------

    @Query("SELECT * FROM workspaceScreens ORDER BY screenRank")
    abstract suspend fun loadScreens(): List<WorkspaceScreenEntity>

    @Query("SELECT * FROM workspaceScreens ORDER BY screenRank")
    abstract fun observeScreens(): Flow<List<WorkspaceScreenEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertScreen(screen: WorkspaceScreenEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertScreens(screens: List<WorkspaceScreenEntity>): List<Long>

    @Query("DELETE FROM workspaceScreens WHERE _id = :screenId")
    abstract suspend fun deleteScreen(screenId: Long)

    @Query("DELETE FROM workspaceScreens")
    abstract suspend fun clearScreens()

    /** Rewrites the page order atomically so a crash mid-reorder cannot leave gaps. */
    @Transaction
    open suspend fun replaceScreens(screens: List<WorkspaceScreenEntity>) {
        clearScreens()
        insertScreens(screens)
    }

    /**
     * Removes a page and everything on it, folders included.
     *
     * The children of a folder or a stack have to go first and by hand. The container's own row
     * sits on the page and is swept by [deleteScreenContents], but its children do not - their
     * container is that row's id, not the desktop, so they survive the page that held them and
     * become rows pointing at a parent that no longer exists. Invisible, permanent, and carried
     * forward forever.
     */
    @Transaction
    open suspend fun deleteScreenWithContents(
        screenId: Long,
        desktopContainer: Long,
        containerTypes: List<Int>,
    ) {
        val containerIds = containerIdsOnScreen(desktopContainer, screenId, containerTypes)
        if (containerIds.isNotEmpty()) deleteItemsInContainers(containerIds)
        deleteScreenContents(desktopContainer, screenId)
        deleteScreen(screenId)
    }

    /**
     * Swaps the entire workspace for another one, atomically.
     *
     * What a restore needs and the only safe way to do it: a crash or a kill between the wipe and
     * the insert would otherwise leave the user with no home screen at all, having asked to get
     * their old one back. Either the whole arrangement lands or none of it does.
     *
     * The rows keep the ids they were exported with. They have to: a folder's children name it by
     * its `_id`, and the home-page preference names a screen the same way, so renumbering on the
     * way in would quietly scatter every folder and forget which page was home.
     */
    @Transaction
    open suspend fun replaceAll(
        screens: List<WorkspaceScreenEntity>,
        favorites: List<FavoriteEntity>,
    ) {
        clearFavorites()
        clearScreens()
        insertScreens(screens)
        insertFavorites(favorites)
    }

    /**
     * Deletes rows whose container no longer exists.
     *
     * Self-healing rather than a migration: databases in the wild already contain orphans left by
     * the page delete above, and there is no schema change to hang a `Migration` off. Cheap enough
     * to run on every load - one indexed scan - and it is the only thing that will ever clear them.
     */
    @Query(
        """
        DELETE FROM favorites
        WHERE container NOT IN (:rootContainers)
          AND container NOT IN (SELECT _id FROM favorites WHERE itemType IN (:containerTypes))
        """,
    )
    abstract suspend fun deleteOrphans(rootContainers: List<Long>, containerTypes: List<Int>): Int
}
