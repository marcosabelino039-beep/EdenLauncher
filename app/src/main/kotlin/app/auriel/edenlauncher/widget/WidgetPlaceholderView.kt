package app.auriel.edenlauncher.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.model.LauncherAppWidgetInfo

/**
 * Stands in for a widget the launcher stores but cannot draw.
 *
 * There are two ways to end up here and they need different answers.
 *
 * The provider is **gone** - the app was uninstalled, or it stopped shipping that widget. Nothing
 * can be done about it, so the tile says which app is missing and waits to be dragged off or
 * removed. The row stays until then: silently deleting a widget because an app is being updated is
 * how an arrangement quietly loses pieces.
 *
 * The provider is **there but the widget is not bound** - the usual cause is a restore. Widget ids
 * are issued by the system to one install and mean nothing to the next, so a backup deliberately
 * does not carry them and every widget comes back unbound. That is repairable, and the tile says
 * so: tapping it runs the bind (and, if the provider asks for one, its configuration screen) and
 * the widget comes back where it was, at the size it was.
 */
class WidgetPlaceholderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.widget_placeholder_fill)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.eden_moss)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.settings_text)
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.settings_text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private val bounds = RectF()
    private val cornerRadius = 12f * resources.displayMetrics.density
    private val lineGap = 4f * resources.displayMetrics.density

    private var title: String = context.getString(R.string.widget_placeholder)
    private var detail: String? = null

    /** Set when the widget can be brought back; tapping the tile calls it. */
    var onRestore: (() -> Unit)? = null
        set(value) {
            field = value
            isClickable = value != null
            setOnClickListener(if (value == null) null else OnClickListener { value.invoke() })
        }

    fun applyFrom(info: LauncherAppWidgetInfo, restorable: Boolean) {
        val provider = info.providerName
        title = info.title?.toString()
            ?: provider?.packageName
            ?: context.getString(R.string.widget_placeholder)
        detail = if (restorable) {
            context.getString(R.string.widget_restore_hint)
        } else {
            context.getString(R.string.widget_placeholder_missing)
        }
        contentDescription = "$title. ${detail.orEmpty()}"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = strokePaint.strokeWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, strokePaint)

        val centre = width / 2f
        val titleHeight = titlePaint.descent() - titlePaint.ascent()
        val detailHeight = if (detail == null) 0f else detailPaint.descent() - detailPaint.ascent()
        val block = titleHeight + if (detail == null) 0f else detailHeight + lineGap

        var baseline = (height - block) / 2f - titlePaint.ascent()
        canvas.drawText(title, centre, baseline, titlePaint)

        val detailText = detail ?: return
        baseline += titlePaint.descent() + lineGap - detailPaint.ascent()
        canvas.drawText(detailText, centre, baseline, detailPaint)
    }
}
