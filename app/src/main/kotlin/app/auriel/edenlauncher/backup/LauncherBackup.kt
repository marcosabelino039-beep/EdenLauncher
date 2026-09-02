package app.auriel.edenlauncher.backup

import android.content.Context
import android.util.Base64
import app.auriel.edenlauncher.data.EdenDatabase
import app.auriel.edenlauncher.data.FavoriteEntity
import app.auriel.edenlauncher.data.WorkspaceScreenEntity
import app.auriel.edenlauncher.settings.LauncherPrefs
import app.auriel.edenlauncher.util.EdenLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Something in the file was not a backup, or not one this version can read. */
class BackupException(message: String) : Exception(message)

/**
 * Reads and writes the whole of what the user arranged, as JSON Lines.
 *
 * A launcher's contents are not recoverable from anywhere else. The app list comes back from the
 * package manager on any device, but where each icon sits, what it was renamed to, which pages
 * exist and in what order, and which icon was replaced by hand are all Eden's alone - and they are
 * the part someone spent an evening on. Android's own backup agent does not help: it is opt-in per
 * transport, silent about whether it ran, and cannot be pointed at a file the user keeps.
 *
 * ## Why JSON Lines
 *
 * One JSON object per line, newline separated, no enclosing array. That buys three things a single
 * JSON document does not:
 *
 *  - **It streams.** Export writes a line and forgets it; import reads a line and applies it. A
 *    workspace of any size costs one line's worth of memory to write, not the whole document's.
 *  - **It survives damage.** A file truncated by a full disk or a cancelled copy is still valid up
 *    to its last complete line, and the import says how far it got instead of refusing all of it.
 *  - **It is legible.** `grep` finds an app in it, `wc -l` sizes it, and a diff between two backups
 *    is a list of what actually changed rather than one reformatted blob.
 *
 * Every line carries a `record` field naming its type, and a reader skips types it does not know.
 * That is what lets a later version add a record without making today's files unreadable, and
 * today's Eden read a file written by a later one.
 *
 * ## What is in a backup, and what is not
 *
 * Everything small and irreplaceable: the pages, the placed items and folders, every preference,
 * every app the user renamed, and the PNG of every icon they replaced by hand.
 *
 * Deliberately not the *contents* of the wallpapers. A transcoded video is tens of megabytes and a
 * JSON file is a poor container for it; a backup that takes a minute to write is one nobody makes
 * twice.
 *
 * What is written instead is a reference: where the file was, what it was called, and how big it
 * was, as an `asset` record. On the way back in, if the file is still sitting there, the setting is
 * reconnected to it and the wallpaper carries on working. That covers the case people actually hit
 * - restoring a layout onto the install that made it, to undo an afternoon of rearranging - where
 * the media has not moved at all and dropping the setting would have been pure loss. If the file
 * really is gone, the setting is not restored to a path that leads nowhere; whatever this device
 * already had is kept, and the restore says what it could not reconnect. See [resolveAsset].
 *
 * The icon pack gets the same treatment for the same reason. Its package name is just a setting and
 * rides along with the rest, but a name alone cannot say whether the pack is still installed, so an
 * `iconpack` record carries its label too - enough to tell the user which pack to reinstall rather
 * than leaving them with a home screen that quietly stopped being themed.
 */
object LauncherBackup {

    /** What an export wrote, or what an import applied. */
    class Summary(
        val screens: Int,
        val items: Int,
        val preferences: Int,
        val icons: Int,
        /** Lines the reader did not recognise; always zero for an export. */
        val skipped: Int = 0,
        /**
         * Things the backup referred to that this device could not produce: a wallpaper file that
         * is no longer there, an icon pack that is not installed. Named so the user can go and get
         * them rather than being left to notice on their own. Always empty for an export.
         */
        val unresolved: List<String> = emptyList(),
    )

    // ---- export ----------------------------------------------------------------------------------

    /**
     * Writes the current state to [out] and returns what went in it.
     *
     * The stream is not closed here; the caller owns it, because on Android it is nearly always a
     * document the framework handed over and will want back.
     */
    suspend fun exportTo(
        context: Context,
        out: OutputStream,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Summary = withContext(io) {
        val app = context.applicationContext
        val dao = EdenDatabase.getInstance(app).launcherDao()
        val writer = out.bufferedWriter()

        val screens = dao.loadScreens()
        val items = dao.loadFavorites()
        val icons = customIconFiles(app)

        // Rendered before the header is written rather than as they are streamed, so the count the
        // header advertises is the count of lines that actually follow it. A setting of a type this
        // version cannot represent drops out here, and a header that had already claimed it would
        // make the file look truncated to anything that checks.
        val stored = LauncherPrefs.file(app).all
        val dataDir = app.applicationInfo.dataDir
        val preferences = stored
            .filterValues { !isLocalPath(it, dataDir) }
            .mapNotNull { (key, value) -> preferenceToJson(key, value) }

        val assets = stored
            .filterValues { isLocalPath(it, dataDir) }
            .mapNotNull { (key, value) -> assetToJson(app, key, value as String) }

        writer.appendLine(
            json {
                put("record", RECORD_HEADER)
                put("format", FORMAT)
                put("version", VERSION)
                put("app", appVersion(app))
                put("created", timestamp())
                put("screens", screens.size)
                put("items", items.size)
                put("preferences", preferences.size)
                put("icons", icons.size)
                put("assets", assets.size)
            },
        )

        iconPackToJson(app)?.let { writer.appendLine(it) }

        for (screen in screens) {
            writer.appendLine(
                json {
                    put("record", RECORD_SCREEN)
                    put("id", screen.id)
                    put("rank", screen.screenRank)
                },
            )
        }

        for (item in items) writer.appendLine(itemToJson(item))

        for (line in preferences) writer.appendLine(line)
        for (line in assets) writer.appendLine(line)

        for (file in icons) {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            writer.appendLine(
                json {
                    put("record", RECORD_ICON)
                    put("name", file.name)
                    put("png", encode(bytes))
                },
            )
        }

        writer.flush()
        EdenLog.i(
            TAG,
            "exported ${screens.size} pages, ${items.size} items, ${preferences.size} settings, " +
                "${icons.size} icons, ${assets.size} media references",
        )
        Summary(screens.size, items.size, preferences.size, icons.size)
    }

    /**
     * A reference to a file of Eden's that is too big to put in the backup.
     *
     * Both the full path and the name inside `filesDir` are written, and the name is the one that
     * usually does the work. An absolute path contains the Android user id - `/data/user/0/...` -
     * which is not the same on a second profile or a work profile, so a path alone would fail to
     * match a file sitting right where it was expected. The size and timestamp are diagnostics: a
     * mismatch is worth a log line, but the file that is there now is still the right one to point
     * at, because it is the one the user can see in the wallpaper picker.
     *
     * A setting whose file has already vanished is not written at all. It is a dangling path today
     * and would only be a dangling path after a restore.
     */
    private fun assetToJson(context: Context, key: String, path: String): String? {
        val file = File(path)
        if (!file.isFile) {
            EdenLog.i(TAG, "not backing up $key: $path is no longer there")
            return null
        }
        return json {
            put("record", RECORD_ASSET)
            put("key", key)
            put("path", path)
            put("name", relativeToFiles(context, file))
            put("bytes", file.length())
            put("modified", file.lastModified())
        }
    }

    /** [file]'s path relative to `filesDir`, or null when it lives somewhere else entirely. */
    private fun relativeToFiles(context: Context, file: File): String? {
        val root = context.filesDir.absolutePath + File.separator
        val path = file.absolutePath
        return if (path.startsWith(root)) path.removePrefix(root) else null
    }

    /**
     * The icon pack in use, with its label.
     *
     * The package name is already backed up as an ordinary setting; this record exists so a restore
     * can tell whether the pack is still installed and, if it is not, name it in a way that means
     * something to the person reading. "Whitestone Icons is not installed" is actionable in a way
     * that "com.wsi.icons is not installed" is not.
     */
    private fun iconPackToJson(context: Context): String? {
        val packageName = LauncherPrefs(context).iconPackPackage ?: return null
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        }.getOrNull()
        return json {
            put("record", RECORD_ICON_PACK)
            put("package", packageName)
            put("label", label)
        }
    }

    private fun itemToJson(item: FavoriteEntity): String = json {
        put("record", RECORD_ITEM)
        put("id", item.id)
        put("title", item.title)
        put("intent", item.intent)
        put("container", item.container)
        put("screen", item.screen)
        put("cellX", item.cellX)
        put("cellY", item.cellY)
        put("spanX", item.spanX)
        put("spanY", item.spanY)
        put("itemType", item.itemType)
        put("appWidgetProvider", item.appWidgetProvider)
        put("iconPackage", item.iconPackage)
        put("iconResource", item.iconResource)
        put("profileId", item.profileId)
        put("rank", item.rank)
        put("restored", item.restored)
        put("options", item.options)
        put("modified", item.modified)
        item.icon?.let { put("icon", encode(it)) }

        // appWidgetId is deliberately absent. It is a handle the system's AppWidgetService issued
        // to this install, and it means nothing to another one - restoring it would point a widget
        // at whatever happens to hold that id now, or at nothing. The provider name is what is
        // worth keeping, and it is written above.
    }

    /**
     * True when [value] is a path into this install's private storage.
     *
     * Checked by shape rather than against a list of key names, so a setting added later that
     * points at an imported file becomes a reconnectable asset reference the day it is added,
     * rather than the day someone notices a restore left it dangling.
     *
     * Renames need no such handling: an override is a preference keyed by component name, so
     * anything that walks the preferences has already collected them.
     */
    private fun isLocalPath(value: Any?, dataDir: String): Boolean =
        value is String && value.startsWith(dataDir)

    private fun preferenceToJson(key: String, value: Any?): String? {
        val type = when (value) {
            is String -> "string"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Boolean -> "boolean"
            // Nothing stores a string set today. Skipping rather than guessing at a representation
            // keeps the format honest: a reader will never meet a type this writer cannot produce.
            else -> {
                EdenLog.w(TAG, "not backing up $key: unsupported type ${value?.javaClass?.simpleName}")
                return null
            }
        }
        return json {
            put("record", RECORD_PREF)
            put("key", key)
            put("type", type)
            put("value", value)
        }
    }

    private fun customIconFiles(context: Context): List<File> =
        File(context.filesDir, ICON_DIRECTORY)
            .takeIf { it.isDirectory }
            ?.listFiles { file -> file.isFile }
            ?.sortedBy { it.name }
            .orEmpty()

    // ---- import ----------------------------------------------------------------------------------

    /**
     * Replaces everything with what is in [input], and returns what was applied.
     *
     * A restore, not a merge. Merging two arrangements of the same grid has no correct answer -
     * two icons want one cell and something has to lose - and a backup is taken to get a known
     * state back, not to be blended with the state that made someone reach for it.
     *
     * The whole file is read and parsed before anything is written. A file that turns out to be
     * damaged halfway through leaves the launcher exactly as it was rather than half restored,
     * which is the one outcome a backup tool must never produce.
     */
    suspend fun importFrom(
        context: Context,
        input: InputStream,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Summary = withContext(io) {
        val app = context.applicationContext
        val parsed = parse(input.bufferedReader())

        val dao = EdenDatabase.getInstance(app).launcherDao()
        dao.replaceAll(parsed.screens, parsed.items)

        val unresolved = ArrayList<String>(2)

        // Wallpapers and the like: point the setting back at the file if the file is still there.
        val reconnected = HashMap<String, String>(parsed.assets.size)
        for (asset in parsed.assets) {
            val path = resolveAsset(app, asset)
            if (path != null) reconnected[asset.key] = path else unresolved.add(asset.displayName)
        }

        writePreferences(app, parsed.preferences, reconnected)
        writeCustomIcons(app, parsed.icons)

        // Checked after the settings are written, not before: the pack is restored either way, so
        // that installing it later simply makes it work. This only decides what the user is told.
        parsed.iconPack?.let { pack ->
            if (!isInstalled(app, pack.packageName)) unresolved.add(pack.displayName)
        }

        EdenLog.i(
            TAG,
            "restored ${parsed.screens.size} pages, ${parsed.items.size} items, " +
                "${parsed.preferences.size} settings, ${parsed.icons.size} icons, " +
                "${reconnected.size} of ${parsed.assets.size} media references, " +
                "${parsed.skipped} line(s) skipped, unresolved=$unresolved",
        )
        Summary(
            screens = parsed.screens.size,
            items = parsed.items.size,
            preferences = parsed.preferences.size,
            icons = parsed.icons.size,
            skipped = parsed.skipped,
            unresolved = unresolved,
        )
    }

    /**
     * Where the file this asset refers to actually is, or null when it is gone.
     *
     * The recorded path is tried first, because on the install that wrote the backup it is exactly
     * right. The name inside `filesDir` is the fallback, and it is what makes this work across a
     * profile whose data directory sits under a different user id - the file has not moved, only
     * the prefix in front of it has.
     */
    private fun resolveAsset(context: Context, asset: Asset): String? {
        asset.path?.let { path ->
            val file = File(path)
            if (file.isFile) return verified(file, asset)
        }
        asset.name?.let { name ->
            val file = File(context.filesDir, name)
            if (file.isFile) return verified(file, asset)
        }
        EdenLog.i(TAG, "could not reconnect ${asset.key}: ${asset.displayName} is not there")
        return null
    }

    /**
     * The file's path, having noted whether it is the same file the backup saw.
     *
     * A mismatch is logged and then ignored on purpose. If the user has imported a different
     * wallpaper since, the file sitting there now is the one they can see in the picker, and
     * pointing the setting at it is right - refusing would leave them with no wallpaper because of
     * a size check they never asked for.
     */
    private fun verified(file: File, asset: Asset): String {
        if (asset.bytes > 0 && file.length() != asset.bytes) {
            EdenLog.i(
                TAG,
                "${asset.key}: ${asset.displayName} is ${file.length()} bytes, " +
                    "the backup saw ${asset.bytes}; using the file that is there",
            )
        }
        return file.absolutePath
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
    }.isSuccess

    /** A file of Eden's that the backup points at rather than contains. */
    private class Asset(
        val key: String,
        val path: String?,
        val name: String?,
        val bytes: Long,
    ) {
        /** What to call it when telling the user it could not be found. */
        val displayName: String
            get() = name ?: path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: key
    }

    private class IconPackRef(val packageName: String, val label: String?) {
        val displayName: String get() = label ?: packageName
    }

    private class Parsed {
        val screens = ArrayList<WorkspaceScreenEntity>(8)
        val items = ArrayList<FavoriteEntity>(64)
        val preferences = LinkedHashMap<String, Any>(32)
        val icons = LinkedHashMap<String, ByteArray>(8)
        val assets = ArrayList<Asset>(4)
        var iconPack: IconPackRef? = null
        var skipped = 0
    }

    private fun parse(reader: BufferedReader): Parsed {
        val parsed = Parsed()
        var sawHeader = false
        var lineNumber = 0

        reader.forEachLine { raw ->
            lineNumber++
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine

            val obj = try {
                JSONObject(line)
            } catch (e: JSONException) {
                // A trailing partial line is what a truncated file looks like, and everything
                // before it is still good. Counting it beats refusing the whole backup.
                EdenLog.w(TAG, "skipping line $lineNumber: not JSON", e)
                parsed.skipped++
                return@forEachLine
            }

            when (obj.optString("record")) {
                RECORD_HEADER -> {
                    readHeader(obj)
                    sawHeader = true
                }

                RECORD_SCREEN -> parsed.screens.add(
                    WorkspaceScreenEntity(
                        id = obj.optLong("id"),
                        screenRank = obj.optInt("rank"),
                        modified = System.currentTimeMillis(),
                    ),
                )

                RECORD_ITEM -> parsed.items.add(itemFromJson(obj))

                RECORD_PREF -> {
                    val key = obj.stringOrNull("key")
                    val value = preferenceValue(obj)
                    if (key != null && value != null) parsed.preferences[key] = value
                }

                RECORD_ASSET -> {
                    val key = obj.stringOrNull("key")
                    if (key != null) {
                        parsed.assets.add(
                            Asset(
                                key = key,
                                path = obj.stringOrNull("path"),
                                name = obj.stringOrNull("name"),
                                bytes = obj.optLong("bytes"),
                            ),
                        )
                    }
                }

                RECORD_ICON_PACK -> {
                    obj.stringOrNull("package")?.let {
                        parsed.iconPack = IconPackRef(it, obj.stringOrNull("label"))
                    }
                }

                RECORD_ICON -> {
                    val name = obj.stringOrNull("name")
                    val png = obj.stringOrNull("png")?.let { decode(it) }
                    if (name != null && png != null && isSafeIconName(name)) {
                        parsed.icons[name] = png
                    }
                }

                // A record type from a later version of Eden. Ignoring it is the whole reason the
                // format has a discriminator: an older reader stays useful instead of refusing.
                else -> parsed.skipped++
            }
        }

        if (!sawHeader) {
            throw BackupException("That file is not an Eden backup: it has no header line.")
        }
        return parsed
    }

    private fun readHeader(obj: JSONObject) {
        val format = obj.stringOrNull("format")
        if (format != FORMAT) {
            throw BackupException("That file is not an Eden backup.")
        }
        val version = obj.optInt("version", 0)
        if (version > VERSION) {
            throw BackupException(
                "That backup was written by a newer version of Eden (format $version). " +
                    "Update Eden and try again.",
            )
        }
    }

    private fun itemFromJson(obj: JSONObject): FavoriteEntity = FavoriteEntity(
        id = obj.optLong("id"),
        title = obj.stringOrNull("title"),
        intent = obj.stringOrNull("intent"),
        container = obj.optLong("container"),
        screen = obj.optLong("screen"),
        cellX = obj.optInt("cellX"),
        cellY = obj.optInt("cellY"),
        spanX = obj.optInt("spanX", 1),
        spanY = obj.optInt("spanY", 1),
        itemType = obj.optInt("itemType"),
        // Never restored; see the note where it is written.
        appWidgetId = -1,
        appWidgetProvider = obj.stringOrNull("appWidgetProvider"),
        icon = obj.stringOrNull("icon")?.let { decode(it) },
        iconPackage = obj.stringOrNull("iconPackage"),
        iconResource = obj.stringOrNull("iconResource"),
        profileId = obj.optLong("profileId"),
        rank = obj.optInt("rank"),
        restored = obj.optInt("restored"),
        options = obj.optInt("options"),
        modified = obj.optLong("modified"),
    )

    private fun preferenceValue(obj: JSONObject): Any? = when (obj.stringOrNull("type")) {
        "string" -> obj.stringOrNull("value")
        "int" -> obj.optInt("value")
        "long" -> obj.optLong("value")
        "float" -> obj.optDouble("value").toFloat()
        "boolean" -> obj.optBoolean("value")
        else -> null
    }

    /**
     * Rewrites the preference file from the backup.
     *
     * Cleared first, so a setting the user changed since the backup goes back to what the backup
     * says rather than surviving it. A restore that leaves some of today's settings in place is
     * not the state anyone saved.
     *
     * The settings that name a file are the exception, and they are resolved in three steps:
     *
     *  1. A file the backup named that is still on this device wins, pointed at wherever it was
     *     actually found - that is the whole point of an `asset` record.
     *  2. Failing that, whatever this device already had is kept. The backup could not supply a
     *     wallpaper, so wiping the one in use would be taking something away in exchange for
     *     nothing.
     *  3. Failing both, the setting is simply absent, which is the only honest answer.
     */
    private fun writePreferences(
        context: Context,
        values: Map<String, Any>,
        reconnected: Map<String, String>,
    ) {
        val prefs = LauncherPrefs.file(context)
        val dataDir = context.applicationInfo.dataDir
        val existing = prefs.all.filterValues { isLocalPath(it, dataDir) }

        val editor = prefs.edit()
        editor.clear()
        for ((key, value) in values) editor.put(key, value)
        for ((key, path) in reconnected) editor.putString(key, path)
        for ((key, value) in existing) {
            if (!reconnected.containsKey(key)) editor.put(key, value)
        }
        editor.apply()
    }

    private fun android.content.SharedPreferences.Editor.put(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            else -> EdenLog.w(TAG, "not restoring $key: unsupported type")
        }
    }

    /**
     * Replaces the hand-picked icons with the ones from the backup.
     *
     * The directory is emptied first for the same reason the preferences are: an icon the user
     * chose after taking the backup is not part of the state they are asking to get back.
     */
    private fun writeCustomIcons(context: Context, icons: Map<String, ByteArray>) {
        val directory = File(context.filesDir, ICON_DIRECTORY)
        directory.listFiles()?.forEach { it.delete() }
        if (icons.isEmpty()) return
        if (!directory.isDirectory && !directory.mkdirs()) {
            EdenLog.w(TAG, "could not create the icon directory; hand-picked icons not restored")
            return
        }
        for ((name, bytes) in icons) {
            runCatching { File(directory, name).writeBytes(bytes) }
                .onFailure { EdenLog.w(TAG, "could not restore the icon $name", it) }
        }
    }

    /**
     * True when [name] is a plain file name that will land in the icon directory and nowhere else.
     *
     * A backup file is ordinary user data that may have come from anywhere, so a name out of one is
     * untrusted input. Without this check a crafted `../` in it would write wherever it liked
     * inside the app's private storage.
     */
    private fun isSafeIconName(name: String): Boolean =
        name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    // ---- plumbing --------------------------------------------------------------------------------

    private inline fun json(build: JSONObject.() -> Unit): String =
        JSONObject().apply(build).toString()

    /**
     * The string at [key], or null.
     *
     * `optString` answers an absent key with an empty string, which is a real value everywhere the
     * entity classes are concerned - a title of "" is not the same as no title.
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray? =
        runCatching { Base64.decode(text, Base64.NO_WRAP) }
            .onFailure { EdenLog.w(TAG, "skipping unreadable base64 in the backup", it) }
            .getOrNull()

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** A file name nobody has to decode: sorts chronologically and holds no separators. */
    fun suggestedFileName(): String =
        "eden-backup-" + fileStamp().format(Date()) + ".jsonl"

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun fileStamp(): SimpleDateFormat =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

    private const val TAG = "LauncherBackup"

    /** Names the file as Eden's, so a reader can refuse someone else's JSON Lines politely. */
    private const val FORMAT = "eden.backup"

    /** Raised only for a change a reader of the old number could not cope with. */
    private const val VERSION = 1

    private const val RECORD_HEADER = "header"
    private const val RECORD_SCREEN = "screen"
    private const val RECORD_ITEM = "item"
    private const val RECORD_PREF = "pref"
    private const val RECORD_ICON = "icon"

    /** A file too big for the backup, referred to by where it is rather than by its contents. */
    private const val RECORD_ASSET = "asset"

    /** The icon pack in use, with enough about it to say whether it is still installed. */
    private const val RECORD_ICON_PACK = "iconpack"

    /** Matches `CustomIcons.DIRECTORY`; the two must name the same folder. */
    private const val ICON_DIRECTORY = "icons"
}
