package app.auriel.edenlauncher.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.backup.BackupException
import app.auriel.edenlauncher.backup.LauncherBackup
import app.auriel.edenlauncher.icons.IconPacks
import app.auriel.edenlauncher.icons.IconShape
import app.auriel.edenlauncher.util.EdenLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Launcher settings.
 *
 * A launcher ported out of AOSP has no host app to hang a preference screen off, so it needs its
 * own. Built from plain views rather than `androidx.preference`: that library would pull in
 * AppCompat and a fragment stack to render half a dozen rows.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: LauncherPrefs

    /** Held so the permission result can put it back in sync with what was actually granted. */
    private var audioToggle: android.widget.CheckBox? = null

    /** Held so the row can show the chosen pack's name without rebuilding the screen. */
    private var iconPackRow: View? = null

    /**
     * Scope for the backup reads and writes, cancelled in [onDestroy].
     *
     * Hand-rolled for the same reason the launcher's is: one `CoroutineScope` is all a lifecycle
     * dependency would buy here.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        setContentView(buildContentView())
        title = getString(R.string.settings_title)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)
        val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // Read once and used throughout: the screen is built in one pass, and a setting that
        // changed halfway down would leave the page describing two different launchers.
        val simple = prefs.simpleMode

        column.addView(header(getString(R.string.settings_section_simple)))
        column.addView(simpleModeToggle(), spacedParams(spacing))
        column.addView(summary(getString(R.string.settings_simple_mode_summary)))

        column.addView(
            header(getString(R.string.settings_section_drawer)),
            spacedParams(spacing * 2),
        )

        column.addView(label(getString(R.string.settings_drawer_mode)), spacedParams(spacing))
        column.addView(drawerModeChooser())

        column.addView(label(getString(R.string.settings_drawer_opacity)), spacedParams(spacing))
        column.addView(opacitySlider())

        column.addView(
            label(getString(R.string.settings_drawer_columns)),
            spacedParams(spacing),
        )
        column.addView(
            gridSlider(
                current = prefs.drawerColumns,
                autoLabel = R.string.settings_grid_auto,
            ) { prefs.drawerColumns = it },
        )

        column.addView(header(getString(R.string.settings_section_home)), spacedParams(spacing * 2))

        column.addView(label(getString(R.string.settings_grid_columns)), spacedParams(spacing))
        column.addView(
            gridSlider(
                current = prefs.workspaceColumns,
                autoLabel = R.string.settings_grid_auto,
            ) { prefs.workspaceColumns = it },
        )

        column.addView(label(getString(R.string.settings_grid_rows)), spacedParams(spacing))
        column.addView(
            gridSlider(
                current = prefs.workspaceRows,
                autoLabel = R.string.settings_grid_auto,
            ) { prefs.workspaceRows = it },
        )

        column.addView(label(getString(R.string.settings_hotseat_icons)), spacedParams(spacing))
        column.addView(
            gridSlider(
                current = prefs.hotseatIcons,
                autoLabel = R.string.settings_grid_auto,
            ) { prefs.hotseatIcons = it },
        )

        column.addView(folderTapOutsideToggle(), spacedParams(spacing))

        column.addView(rotationToggle(), spacedParams(spacing))

        column.addView(header(getString(R.string.settings_section_icons)), spacedParams(spacing * 2))

        // Icon packs arrived in v0.4.0, so Simple mode has no use for the chooser. The sliders
        // below stay: sizing and spacing icons was in v0.2.0 from the start.
        if (!simple) {
            column.addView(label(getString(R.string.settings_icon_pack)), spacedParams(spacing))
            column.addView(iconPackChooser())
            column.addView(summary(getString(R.string.settings_icon_pack_summary)))

            column.addView(label(getString(R.string.settings_icon_shape)), spacedParams(spacing * 2))
            column.addView(iconShapeChooser())
            column.addView(summary(getString(R.string.settings_icon_shape_summary)))
        }

        column.addView(label(getString(R.string.settings_icon_size)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.iconSizePercent,
                min = LauncherPrefs.MIN_ICON_PERCENT,
                max = LauncherPrefs.MAX_ICON_PERCENT,
                suffix = "%",
            ) { prefs.iconSizePercent = it },
        )

        column.addView(label(getString(R.string.settings_icon_padding_vertical)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.iconPaddingVerticalDp,
                min = 0,
                max = LauncherPrefs.MAX_ICON_PADDING,
                suffix = "dp",
            ) { prefs.iconPaddingVerticalDp = it },
        )

        column.addView(label(getString(R.string.settings_icon_padding_horizontal)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.iconPaddingHorizontalDp,
                min = 0,
                max = LauncherPrefs.MAX_ICON_PADDING,
                suffix = "dp",
            ) { prefs.iconPaddingHorizontalDp = it },
        )

        column.addView(label(getString(R.string.settings_icon_label_spacing)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.iconLabelSpacingDp,
                min = 0,
                max = LauncherPrefs.MAX_ICON_PADDING,
                suffix = "dp",
            ) { prefs.iconLabelSpacingDp = it },
        )

        column.addView(header(getString(R.string.settings_section_wallpaper)), spacedParams(spacing * 2))

        column.addView(
            actionRow(getString(R.string.settings_video_open_picker)) {
                startActivity(
                    android.content.Intent(
                        this,
                        app.auriel.edenlauncher.wallpaper.picker.WallpaperPickerActivity::class.java,
                    ),
                )
            },
            spacedParams(spacing),
        )

        column.addView(label(getString(R.string.settings_wallpaper_speed)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.liveWallpaperSpeedPercent,
                min = LauncherPrefs.MIN_WALLPAPER_SPEED,
                max = LauncherPrefs.MAX_WALLPAPER_SPEED,
                suffix = "%",
            ) { prefs.liveWallpaperSpeedPercent = it },
        )
        column.addView(summary(getString(R.string.settings_wallpaper_speed_summary)))

        column.addView(visualizerAudioToggle(), spacedParams(spacing))
        column.addView(videoAudioToggle(), spacedParams(spacing))

        column.addView(header(getString(R.string.settings_section_backup)), spacedParams(spacing * 2))
        column.addView(summary(getString(R.string.settings_backup_summary)))

        column.addView(
            actionRow(getString(R.string.settings_backup_export)) { exportBackup() },
            spacedParams(spacing),
        )
        column.addView(
            actionRow(getString(R.string.settings_backup_import)) { confirmImportBackup() },
            spacedParams(spacing),
        )

        column.addView(
            header(getString(R.string.settings_section_troubleshooting)),
            spacedParams(spacing * 2),
        )

        column.addView(
            actionRow(getString(R.string.settings_log_open)) {
                startActivity(android.content.Intent(this, LogViewerActivity::class.java))
            },
            spacedParams(spacing),
        )
        column.addView(summary(getString(R.string.settings_log_summary)))

        column.addView(
            header(getString(R.string.settings_section_about)),
            spacedParams(spacing * 2),
        )
        column.addView(
            actionRow(getString(R.string.about_open)) {
                startActivity(android.content.Intent(this, AboutActivity::class.java))
            },
            spacedParams(spacing),
        )

        column.addView(
            label(getString(R.string.settings_grid_restart_note)),
            spacedParams(spacing * 2),
        )

        return ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.settings_background))
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            fitsSystemWindows = true
        }
    }

    private fun drawerModeChooser(): View {
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }

        val vertical = radio(
            getString(R.string.settings_drawer_mode_vertical),
            getString(R.string.settings_drawer_mode_vertical_summary),
            id = ID_VERTICAL,
        )
        val horizontal = radio(
            getString(R.string.settings_drawer_mode_horizontal),
            getString(R.string.settings_drawer_mode_horizontal_summary),
            id = ID_HORIZONTAL,
        )
        group.addView(vertical)
        group.addView(horizontal)

        group.check(if (prefs.appDrawerMode == AppDrawerMode.VERTICAL) ID_VERTICAL else ID_HORIZONTAL)
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.appDrawerMode =
                if (checkedId == ID_HORIZONTAL) AppDrawerMode.HORIZONTAL else AppDrawerMode.VERTICAL
        }
        return group
    }

    private fun opacitySlider(): View {
        val readout = label("${prefs.appDrawerOpacity}%")

        val seekBar = SeekBar(this).apply {
            max = 100
            progress = prefs.appDrawerOpacity
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        readout.text = "$value%"
                        // Written continuously: the launcher observes the flow, so the drawer
                        // behind this screen updates as the slider moves.
                        if (fromUser) prefs.appDrawerOpacity = value
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seekBar)
            addView(readout)
        }
    }

    /**
     * A slider over the grid range where the lowest position means "auto".
     *
     * Auto is the first stop rather than a separate checkbox: a user who does not care never has
     * to think about it, and one who does gets an exact number.
     */
    private fun gridSlider(current: Int, autoLabel: Int, onChange: (Int) -> Unit): View {
        val span = LauncherPrefs.MAX_GRID - LauncherPrefs.MIN_GRID + 1
        val readout = label(gridText(current, autoLabel))

        val seekBar = SeekBar(this).apply {
            max = span
            progress = if (current <= 0) 0 else current - LauncherPrefs.MIN_GRID + 1
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        val resolved = if (value == 0) LauncherPrefs.AUTO else value + LauncherPrefs.MIN_GRID - 1
                        readout.text = gridText(resolved, autoLabel)
                        if (fromUser) onChange(resolved)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seekBar)
            addView(readout)
        }
    }

    private fun gridText(value: Int, autoLabel: Int): String =
        if (value <= 0) getString(autoLabel) else value.toString()

    /** A slider over a plain numeric range, with a live readout. */
    private fun rangeSlider(
        current: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChange: (Int) -> Unit,
    ): View {
        val readout = label("$current$suffix")

        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = (current - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        val resolved = value + min
                        readout.text = "$resolved$suffix"
                        if (fromUser) onChange(resolved)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seekBar)
            addView(readout)
        }
    }

    /**
     * Sound for the video wallpaper.
     *
     * Off by default, and worth being explicit about why: the audio track is kept in the converted
     * file either way, so this is a toggle rather than a reason to import the video again.
     */
    private fun videoAudioToggle(): View {
        val toggle = android.widget.CheckBox(this).apply {
            text = getString(R.string.settings_video_audio)
            setTextColor(getColor(R.color.settings_text))
            textSize = 16f
            isChecked = prefs.videoWallpaperAudio
            setOnCheckedChangeListener { _, checked -> prefs.videoWallpaperAudio = checked }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(
                TextView(this@SettingsActivity).apply {
                    text = getString(R.string.settings_video_audio_summary)
                    setTextColor(getColor(R.color.settings_text_secondary))
                    textSize = 13f
                },
            )
        }
    }

    /**
     * Whether the music visualiser reads real audio.
     *
     * Turning it on asks for `RECORD_AUDIO` there and then rather than at the moment the wallpaper
     * first tries to draw. A permission dialog that appears while you are looking at your home
     * screen, with no obvious cause, is how apps train people to deny things.
     */
    /**
     * The way in and out of Simple mode.
     *
     * Always the first thing on this screen, and never hidden by itself - a toggle that removes
     * settings has to stay visible in the state it creates, or there is no way back. Flipping it
     * rebuilds the screen immediately so the user can see what just changed rather than being told
     * to go and look.
     */
    private fun simpleModeToggle(): View = android.widget.CheckBox(this).apply {
        text = getString(R.string.settings_simple_mode)
        setTextColor(getColor(R.color.settings_text))
        textSize = 16f
        isChecked = prefs.simpleMode
        setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.simpleMode) return@setOnCheckedChangeListener
            prefs.simpleMode = checked
            setContentView(buildContentView())
        }
    }

    /**
     * Whether tapping beside an open folder closes it. On by default; see
     * [LauncherPrefs.closeFolderOnTapOutside] for what it costs when it is on.
     */
    private fun folderTapOutsideToggle(): View {
        val toggle = android.widget.CheckBox(this).apply {
            text = getString(R.string.settings_folder_tap_outside)
            setTextColor(getColor(R.color.settings_text))
            textSize = 16f
            isChecked = prefs.closeFolderOnTapOutside
            setOnCheckedChangeListener { _, checked -> prefs.closeFolderOnTapOutside = checked }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(summary(getString(R.string.settings_folder_tap_outside_summary)))
        }
    }

    /**
     * Whether the home screen follows the device's rotation. Off by default; see
     * [LauncherPrefs.allowRotation] for why, and why the lock is `nosensor` rather than `portrait`.
     */
    private fun rotationToggle(): View {
        val toggle = android.widget.CheckBox(this).apply {
            text = getString(R.string.settings_allow_rotation)
            setTextColor(getColor(R.color.settings_text))
            textSize = 16f
            isChecked = prefs.allowRotation
            setOnCheckedChangeListener { _, checked -> prefs.allowRotation = checked }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(summary(getString(R.string.settings_allow_rotation_summary)))
        }
    }

    private fun visualizerAudioToggle(): View {
        val toggle = android.widget.CheckBox(this).apply {
            text = getString(R.string.settings_visualizer_real_audio)
            setTextColor(getColor(R.color.settings_text))
            textSize = 16f
            isChecked = prefs.visualizerUsesRealAudio
            setOnCheckedChangeListener { button, checked ->
                if (!checked) {
                    prefs.visualizerUsesRealAudio = false
                    return@setOnCheckedChangeListener
                }

                val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    prefs.visualizerUsesRealAudio = true
                } else {
                    // Left off until the grant actually comes back, so the checkbox never claims
                    // something that is not true.
                    button.isChecked = false
                    requestPermissions(
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        REQUEST_RECORD_AUDIO,
                    )
                }
            }
        }
        audioToggle = toggle

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(summary(getString(R.string.settings_visualizer_real_audio_summary)))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return

        val granted = grantResults.firstOrNull() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        prefs.visualizerUsesRealAudio = granted
        audioToggle?.isChecked = granted
        if (!granted) {
            android.widget.Toast
                .makeText(this, R.string.visualizer_permission_needed, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun actionRow(text: String, onClick: () -> Unit): View =
        android.widget.Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    // ---- backup ----------------------------------------------------------------------------------

    /**
     * Writes the whole arrangement to a file the user chooses.
     *
     * Through the document picker rather than to a path of Eden's own: a backup the launcher keeps
     * inside its own storage is deleted with the launcher, which is one of the two occasions anyone
     * wants one. The user puts it where their other files are.
     */
    private fun exportBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(BACKUP_MIME)
            .putExtra(Intent.EXTRA_TITLE, LauncherBackup.suggestedFileName())
        startPicker(intent, REQUEST_EXPORT_BACKUP)
    }

    /**
     * Asks before restoring, because a restore is not additive.
     *
     * The one destructive action in the settings screen, and the only one worth a dialog: it
     * replaces the home screen the user is looking at, and there is no undo but another backup.
     */
    private fun confirmImportBackup() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_import)
            .setMessage(R.string.settings_backup_import_warning)
            .setPositiveButton(R.string.settings_backup_import_confirm) { _, _ -> pickBackup() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickBackup() {
        // Not filtered by MIME type on purpose. Providers disagree about what a .jsonl file is -
        // several report it as octet-stream - and a filter on application/json hides the very file
        // the user is looking for, with no way to tell them why.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startPicker(intent, REQUEST_IMPORT_BACKUP)
    }

    private fun startPicker(intent: Intent, requestCode: Int) {
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            EdenLog.w(TAG, "No document picker for a backup", e)
            toast(getString(R.string.activity_not_found))
        }
    }

    @Deprecated("Plain Activity has no result registry; the androidx one is not worth the dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_EXPORT_BACKUP -> runExport(uri)
            REQUEST_IMPORT_BACKUP -> runImport(uri)
        }
    }

    private fun runExport(uri: Uri) {
        scope.launch {
            runCatching {
                // "wt" truncates. Without it, writing a smaller backup over a larger one leaves the
                // tail of the old file behind, and the result parses right up to the junk.
                contentResolver.openOutputStream(uri, "wt")
                    ?.use { LauncherBackup.exportTo(this@SettingsActivity, it) }
                    ?: throw IOException("the document picker returned nothing to write to")
            }.onSuccess { summary ->
                toast(
                    getString(
                        R.string.settings_backup_exported,
                        summary.items,
                        summary.screens,
                        summary.icons,
                    ),
                )
            }.onFailure { failure ->
                EdenLog.w(TAG, "Backup export failed", failure)
                showFailure(failure)
            }
        }
    }

    private fun runImport(uri: Uri) {
        scope.launch {
            runCatching {
                contentResolver.openInputStream(uri)
                    ?.use { LauncherBackup.importFrom(this@SettingsActivity, it) }
                    ?: throw IOException("the document picker returned nothing to read")
            }.onSuccess { summary ->
                // The launcher behind this screen is still holding the arrangement that was just
                // replaced. Flagging it here is what makes it rebuild when it next resumes.
                LauncherAppState.getInstance(this@SettingsActivity).onDataRestored()
                val restored = getString(
                    R.string.settings_backup_imported,
                    summary.items,
                    summary.screens,
                )
                // This screen is showing the settings from before the restore, every one of which
                // may now be wrong, so it has to be rebuilt - but a rebuild takes any dialog down
                // with it. So the report waits for the dismissal when there is something to report.
                if (summary.unresolved.isEmpty()) {
                    toast(restored)
                    recreate()
                } else {
                    // A dialog rather than a toast: this is a list of things the user has to go and
                    // do something about, and a toast is gone before it can be read.
                    android.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.settings_section_backup)
                        .setMessage(
                            restored + "\n\n" + getString(
                                R.string.settings_backup_unresolved,
                                summary.unresolved.joinToString("\n") { "- $it" },
                            ),
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener { recreate() }
                        .show()
                }
            }.onFailure { failure ->
                EdenLog.w(TAG, "Backup import failed", failure)
                showFailure(failure)
            }
        }
    }

    /**
     * Says what went wrong in the user's terms.
     *
     * A [BackupException] already carries a sentence meant to be read; anything else is a stream or
     * storage failure whose message is not, so it gets a plain one.
     */
    private fun showFailure(failure: Throwable) {
        val message = (failure as? BackupException)?.message
            ?: getString(R.string.settings_backup_failed)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_section_backup)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    // ---- icon packs ------------------------------------------------------------------------------

    /**
     * A dropdown of every pack on the device, with the plain system icons first.
     *
     * A dropdown rather than a dialog because this is a value with a current state, not an action:
     * the closed control shows what is in use, which is the question people actually come to this
     * row to answer. "None" leads the list because it is the way back, and someone regretting a
     * pack should not have to read past eleven entries to find it.
     */
    private fun iconPackChooser(): View {
        val packs = IconPacks.installed(this)

        val labels = ArrayList<String>(packs.size + 1)
        labels.add(getString(R.string.settings_icon_pack_none))
        packs.mapTo(labels) { it.label }

        // A pack that has since been uninstalled is not in the list; forget it rather than show a
        // name that no longer means anything.
        val stored = prefs.iconPackPackage
        val storedIndex = packs.indexOfFirst { it.packageName == stored }
        if (stored != null && storedIndex < 0) prefs.iconPackPackage = null

        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            setBackgroundResource(R.drawable.settings_spinner_background)
            setSelection(if (storedIndex >= 0) storedIndex + 1 else 0)

            // Set after the initial selection, so restoring the current value is not itself
            // treated as a change and does not clear the icon cache on every visit.
            post {
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        applyIconPack(if (position == 0) null else packs[position - 1].packageName)
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
            }
        }
        iconPackRow = spinner

        if (packs.isEmpty()) {
            spinner.isEnabled = false
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(spinner)
                addView(summary(getString(R.string.settings_icon_pack_empty)))
            }
        }
        return spinner
    }

    // ---- icon shapes -----------------------------------------------------------------------------

    /**
     * A dropdown of every shape, each row drawn as well as named.
     *
     * The same control as the icon pack above it, for the same reason: this is a value with a
     * current state, not an action, so the closed control should show what is in use. A row of
     * swatches was tried first and it reads better while you are looking at it - but there are
     * thirteen of them, which is three screens of sideways scrolling on a phone, and a control
     * where half the options are off the edge and give no sign they exist is worse than one that
     * opens into a list.
     *
     * The swatch is kept, inside each row. Nobody knows what a squircle looks like next to a
     * rounded square from the names alone, and a dropdown of thirteen words would be asking them
     * to pick one and find out.
     *
     * Ordered by the label the user actually reads rather than by the order the shapes happen to
     * be written down in, so the list stays alphabetical whatever gets added to [IconShape] later.
     * System leads it rather than sorting into the middle, the same way "None" leads the pack
     * list: it is not a shape, it is the way back from having picked one, and somebody who has
     * just made every icon on their phone a diamond should not have to hunt for the undo.
     */
    private fun iconShapeChooser(): View {
        val ordered = ArrayList<IconShape>(IconShape.entries.size)
        ordered.add(IconShape.SYSTEM)
        IconShape.entries
            .filter { it != IconShape.SYSTEM }
            .sortedBy { getString(shapeLabel(it)) }
            .let(ordered::addAll)

        return android.widget.Spinner(this).apply {
            adapter = ShapeAdapter(this@SettingsActivity, ordered)
            setBackgroundResource(R.drawable.settings_spinner_background)
            setSelection(ordered.indexOf(prefs.iconShape).coerceAtLeast(0))

            // Set after the initial selection, so restoring the current value is not itself treated
            // as a change and does not clear the icon cache on every visit.
            post {
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        applyIconShape(ordered[position])
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
            }
        }
    }

    /** One row of the shape dropdown: the shape itself, then its name. */
    private class ShapeAdapter(
        private val activity: SettingsActivity,
        shapes: List<IconShape>,
    ) : android.widget.ArrayAdapter<IconShape>(activity, 0, shapes) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            row(position)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            row(position)

        /**
         * Built fresh rather than recycled. The list is thirteen rows long and is inflated once
         * when the dropdown opens - recycling here would save nothing and cost the bug where a row
         * shows the shape belonging to the one it replaced.
         */
        private fun row(position: Int): View {
            val shape = getItem(position) ?: IconShape.SYSTEM
            val size = activity.resources.getDimensionPixelSize(R.dimen.settings_shape_row_swatch)
            val gap = activity.resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

            val name = TextView(activity).apply {
                text = activity.getString(activity.shapeLabel(shape))
                setTextColor(activity.getColor(R.color.settings_text))
                textSize = SHAPE_ROW_SP
            }

            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(gap, gap / 2, gap, gap / 2)
                contentDescription = name.text
                addView(
                    ShapeSwatchView(activity, shape),
                    LinearLayout.LayoutParams(size, size).apply { rightMargin = gap },
                )
                addView(name)
            }
        }

        private companion object {
            const val SHAPE_ROW_SP = 16f
        }
    }

    private fun applyIconShape(shape: IconShape) {
        if (prefs.iconShape == shape) return
        prefs.iconShape = shape
        // Every themed icon was cut under the old answer.
        LauncherAppState.getInstance(this).iconCache.clear()
        android.widget.Toast
            .makeText(this, R.string.settings_icon_shape_applied, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    private fun shapeLabel(shape: IconShape): Int = when (shape) {
        IconShape.SYSTEM -> R.string.icon_shape_system
        IconShape.CIRCLE -> R.string.icon_shape_circle
        IconShape.SQUARE -> R.string.icon_shape_square
        IconShape.ROUNDED_SQUARE -> R.string.icon_shape_rounded_square
        IconShape.SQUIRCLE -> R.string.icon_shape_squircle
        IconShape.APPLE -> R.string.icon_shape_apple
        IconShape.TEARDROP -> R.string.icon_shape_teardrop
        IconShape.LEAF -> R.string.icon_shape_leaf
        IconShape.DIAMOND -> R.string.icon_shape_diamond
        IconShape.HEXAGON -> R.string.icon_shape_hexagon
        IconShape.ROUNDED_HEXAGON -> R.string.icon_shape_rounded_hexagon
        IconShape.OCTAGON -> R.string.icon_shape_octagon
        IconShape.COOKIE -> R.string.icon_shape_cookie
    }

    /**
     * One shape, filled, with a ring round it when it is the one in use.
     *
     * [IconShape.SYSTEM] has no outline of its own, so it borrows the device's: the mask an
     * [AdaptiveIconDrawable] would be cut to on this phone. That is a more useful thing to show
     * than a generic rounded square, because on a Samsung it is a squircle and on a Pixel it is a
     * circle, and "System" means whichever of those this happens to be.
     */
    private class ShapeSwatchView(context: Context, val shape: IconShape) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.eden_leaf)
        }

        private val path = Path()
        private val matrix = Matrix()

        override fun onDraw(canvas: Canvas) {
            val extent = minOf(width, height).toFloat()
            if (extent <= 0f) return

            if (shape == IconShape.SYSTEM) systemMask(extent, path) else shape.outline(extent, path)

            // The outline is generated at the origin, so it is moved into place rather than
            // regenerated at an offset.
            matrix.setTranslate((width - extent) / 2f, (height - extent) / 2f)
            path.transform(matrix)
            canvas.drawPath(path, fillPaint)
        }

        /** The mask this device cuts adaptive icons to, scaled out of its 100-unit space. */
        private fun systemMask(extent: Float, into: Path) {
            val mask = runCatching { AdaptiveIconDrawable(null, null).iconMask }.getOrNull()
            if (mask == null) {
                into.reset()
                into.addRoundRect(
                    RectF(0f, 0f, extent, extent),
                    extent * SYSTEM_FALLBACK_RADIUS,
                    extent * SYSTEM_FALLBACK_RADIUS,
                    Path.Direction.CW,
                )
                return
            }

            into.set(mask)
            matrix.setScale(extent / MASK_UNITS, extent / MASK_UNITS)
            into.transform(matrix)
        }

        private companion object {
            const val SYSTEM_FALLBACK_RADIUS = 0.18f

            /** AdaptiveIconDrawable states its mask in a 100 by 100 box. */
            const val MASK_UNITS = 100f
        }
    }

    private fun applyIconPack(packageName: String?) {
        if (prefs.iconPackPackage == packageName) return
        prefs.iconPackPackage = packageName
        // Every cached icon was rasterised under the old answer.
        LauncherAppState.getInstance(this).iconCache.clear()
        android.widget.Toast
            .makeText(this, R.string.settings_icon_pack_applied, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    // ---- small view builders ---------------------------------------------------------------------

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text))
        textSize = 22f
        gravity = Gravity.START
        setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.settings_row_spacing))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text))
        textSize = 16f
    }

    private fun summary(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text_secondary))
        textSize = 13f
    }

    private fun radio(title: String, summary: String, id: Int) = RadioButton(this).apply {
        this.id = id
        text = buildString {
            append(title)
            append('\n')
            append(summary)
        }
        setTextColor(getColor(R.color.settings_text))
        textSize = 15f
        setPadding(
            resources.getDimensionPixelSize(R.dimen.settings_padding),
            resources.getDimensionPixelSize(R.dimen.settings_row_spacing),
            0,
            resources.getDimensionPixelSize(R.dimen.settings_row_spacing),
        )
    }

    private fun spacedParams(spacing: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = spacing }

    private companion object {
        const val TAG = "SettingsActivity"

        const val ID_VERTICAL = 1
        const val ID_HORIZONTAL = 2
        const val REQUEST_RECORD_AUDIO = 10
        const val REQUEST_EXPORT_BACKUP = 11
        const val REQUEST_IMPORT_BACKUP = 12

        /**
         * What the file is created as.
         *
         * There is no registered type for JSON Lines, and `application/jsonl` is not one any
         * picker knows. `application/json` is close enough to be handled sensibly everywhere and
         * honest about what the bytes are; the `.jsonl` suffix on the suggested name is what tells
         * a person which of the two they are looking at.
         */
        const val BACKUP_MIME = "application/json"
    }
}
