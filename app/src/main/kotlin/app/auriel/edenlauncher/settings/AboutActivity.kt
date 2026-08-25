package app.auriel.edenlauncher.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.auriel.edenlauncher.R

/**
 * What Eden is, who wrote it, which version you are running, and what it owes to AOSP.
 *
 * On the device rather than only in the repository, because the two audiences are different: the
 * README is read by someone deciding whether to build this, and this screen is read by someone who
 * already has it installed and wants to know what it is, what version to quote in a bug report, or
 * what the licence says. Neither is served by being told to go and look at the other.
 *
 * Laid out top-down as an answer to "what am I looking at": the icon and the name first, because
 * that is what was tapped, then what it does, then who is responsible for it. Everything below the
 * fold is reference.
 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.about_title)
        setContentView(buildContentView())
    }

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)
        val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val iconSize = resources.getDimensionPixelSize(R.dimen.about_icon_size)
        column.addView(appIcon(), centredParams(spacing, iconSize, iconSize))
        column.addView(centredHeading(getString(R.string.app_name)), centredParams(spacing))
        column.addView(centredVersion(), centredParams(spacing / 3))
        column.addView(centredBody(getString(R.string.about_tagline)), centredParams(spacing))

        column.addView(body(getString(R.string.about_what_it_is)), spacedParams(spacing * 2))

        column.addView(sectionHeader(getString(R.string.about_section_author)), spacedParams(spacing * 2))
        column.addView(authorRow(), spacedParams(spacing))
        column.addView(body(getString(R.string.about_author_blurb)), spacedParams(spacing))
        column.addView(repositoryButton(), spacedParams(spacing))

        column.addView(sectionHeader(getString(R.string.about_section_licence)), spacedParams(spacing * 2))
        column.addView(body(getString(R.string.about_licence)), spacedParams(spacing))

        column.addView(sectionHeader(getString(R.string.about_section_contact)), spacedParams(spacing * 2))
        column.addView(body(getString(R.string.about_contact)), spacedParams(spacing))
        column.addView(emailButton(), spacedParams(spacing))

        return ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.settings_background))
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    // ---- the header ------------------------------------------------------------------------------

    /**
     * Eden's own launcher icon, asked of the package manager rather than loaded from `mipmap`.
     *
     * The same drawable the home screen and the app drawer are showing, which is the point: this
     * screen is reached from a settings row, and the icon is how you know the row led where you
     * expected. It is also the only place in Eden that draws its own icon, so nothing here is
     * cut to the user's chosen icon shape - Eden does not theme itself.
     */
    private fun appIcon(): View = ImageView(this).apply {
        contentDescription = getString(R.string.about_icon_description)
        runCatching { packageManager.getApplicationIcon(packageName) }
            .onSuccess { setImageDrawable(it) }
            .onFailure { setImageResource(R.mipmap.ic_launcher) }
    }

    /**
     * Read from the installed package rather than from `BuildConfig`.
     *
     * It is the same number, but this one is what the system believes is installed - which is the
     * number that matters when someone is telling you which build misbehaved.
     */
    private fun centredVersion(): TextView {
        val name = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        return TextView(this).apply {
            text = getString(R.string.about_version, name)
            setTextColor(getColor(R.color.accent))
            textSize = VERSION_SP
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    // ---- who made it -----------------------------------------------------------------------------

    /**
     * The maintainer, as a tappable row: avatar, name, handle.
     *
     * A row rather than a line of text with a link in it, because the whole thing is one target.
     * A link inside a paragraph is a few millimetres of tappable text on a phone; this is the
     * height of the avatar, which is what anybody actually aims at.
     */
    private fun authorRow(): View {
        val avatarSize = resources.getDimensionPixelSize(R.dimen.about_avatar_size)
        val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val name = TextView(this).apply {
            text = getString(R.string.about_author_name)
            setTextColor(getColor(R.color.settings_text))
            textSize = AUTHOR_NAME_SP
        }

        val handle = TextView(this).apply {
            text = getString(R.string.about_author_handle)
            setTextColor(getColor(R.color.accent))
            textSize = BODY_SP
        }

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name)
            addView(handle)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, spacing / 2, 0, spacing / 2)
            contentDescription = getString(R.string.about_author_handle)
            addView(
                MonogramView(this@AboutActivity, getString(R.string.about_author_name)),
                LinearLayout.LayoutParams(avatarSize, avatarSize).apply { rightMargin = spacing },
            )
            addView(text)
            setOnClickListener { open(getString(R.string.about_author_url)) }
        }
    }

    /**
     * A circle with an initial in it, standing in for a picture there is not one of.
     *
     * Drawn rather than shipped, so there is no image to keep in step with a profile that lives
     * somewhere else. If a real picture ever turns up it replaces this view and nothing else
     * changes - the row is already the right shape for one.
     */
    private class MonogramView(context: android.content.Context, name: String) : View(context) {

        private val initial = name.trim().firstOrNull()?.uppercase() ?: "?"

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.eden_deep)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.eden_mint)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = context.getColor(R.color.eden_leaf)
            strokeWidth = context.resources.displayMetrics.density * RING_WIDTH_DP
        }
        private val textBounds = Rect()

        override fun onDraw(canvas: Canvas) {
            val radius = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f

            canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, circlePaint)
            canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)

            textPaint.textSize = radius * INITIAL_RATIO
            textPaint.getTextBounds(initial, 0, initial.length, textBounds)
            // Centred on the glyph's own box rather than on the baseline, or a letter with no
            // descender sits visibly high in the circle.
            canvas.drawText(initial, cx, cy + textBounds.height() / 2f, textPaint)
        }

        private companion object {
            const val RING_WIDTH_DP = 2f
            const val INITIAL_RATIO = 0.9f
        }
    }

    // ---- links -----------------------------------------------------------------------------------

    private fun repositoryButton() = Button(this).apply {
        text = getString(R.string.about_repo_action)
        gravity = Gravity.CENTER
        setOnClickListener { open(getString(R.string.about_repo_url)) }
    }

    /**
     * Opens a mail composer already addressed. Falls back to a message rather than a crash when
     * there is no mail app, which on a phone set up for one purpose is a real configuration.
     */
    private fun emailButton() = Button(this).apply {
        text = getString(R.string.about_email_action)
        gravity = Gravity.CENTER
        setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:" + getString(R.string.about_email))
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_email_subject))
            }
            runCatching { startActivity(intent) }.onFailure {
                Toast.makeText(
                    this@AboutActivity,
                    getString(R.string.about_email),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Hands a web address to whatever the user opens links with.
     *
     * A launcher is started from the home screen and is not itself a task, so the browser is asked
     * for in a new task; without it the page opens inside Eden's own stack and Back lands the user
     * somewhere they did not come from. A phone with no browser at all says so rather than doing
     * nothing, which is the same failure the mail button already handles.
     */
    private fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    // ---- small view builders ---------------------------------------------------------------------

    private fun centredHeading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text))
        textSize = HEADING_SP
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.accent))
        textSize = SECTION_SP
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text_secondary))
        textSize = BODY_SP
        setLineSpacing(0f, LINE_SPACING)
    }

    private fun centredBody(text: String) = body(text).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun spacedParams(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun centredParams(
        top: Int,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ) = LinearLayout.LayoutParams(width, height).apply {
        topMargin = top
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private companion object {
        const val HEADING_SP = 28f
        const val VERSION_SP = 15f
        const val SECTION_SP = 15f
        const val BODY_SP = 14f
        const val AUTHOR_NAME_SP = 17f
        const val LINE_SPACING = 1.25f
    }
}
