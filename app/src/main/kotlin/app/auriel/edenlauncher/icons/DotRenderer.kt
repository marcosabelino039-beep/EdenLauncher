package app.auriel.edenlauncher.icons

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.notification.DotColour

/**
 * The notification dot, drawn on the edge of whatever silhouette the user chose.
 *
 * Ported from `DotRenderer` (AOSP 10), which is where the idea of finding the position on the
 * icon's own outline comes from. A dot pinned to the top-right corner of the icon's *box* floats
 * off a circle and buries itself in a diamond; a dot placed where the outline actually passes sits
 * on the edge of every shape Eden offers, which is the whole reason the shapes are described as
 * paths rather than as drawables.
 *
 * The position is found by intersecting the outline with a thin triangle drawn from the centre of
 * the icon out to the top-right corner and taking the first point of what survives - AOSP's trick,
 * and cheaper than walking the path looking for a maximum. It happens once per shape and size, not
 * once per icon: see [forShape].
 *
 * Drawing is two circles: the dot, and a ring around it. A single flat dot disappears against
 * artwork of a similar colour, and an app's icon is exactly as likely to be the colour the user
 * chose as any other. The ring is dark behind a light dot and light behind a dark one - a black
 * dot on a dark icon needs the opposite of what a white one needs, and picking one of the two for
 * everything would leave a quarter of the palette invisible.
 */
class DotRenderer private constructor(
    private val iconSizePx: Int,
    private val rightOfIcon: FloatArray,
    private val leftOfIcon: FloatArray,
    private val dotRadius: Float,
    private val ringWidth: Float,
    dotColour: Int,
    ringColour: Int,
) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColour }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColour
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
    }

    /**
     * Draws the dot over an icon occupying [iconBounds] inside a view [viewWidth] by [viewHeight].
     *
     * The dot is nudged back inside the view rather than allowed to be cut in half: an icon at the
     * top of a cell with the vertical padding turned down to zero has no room above it, and a dot
     * with its top sliced off reads as a rendering fault rather than a tight fit.
     *
     * The view's own size is passed in rather than taken from `canvas.getClipBounds`, which is what
     * AOSP reads. The clip is reported in the canvas's coordinate space, and for a `TextView` that
     * space is offset by the view's scroll - so on a drawer entry whose `scrollX` had run away it
     * came back as `Rect(524197, 0 - 524400, 304)`: the right size, at an x offset of half a
     * million. The caller knows how big it is and cannot be wrong about it. See
     * `BubbleTextView.onDraw` for the other half of that, which is undoing the scroll.
     */
    fun draw(
        canvas: Canvas,
        iconBounds: Rect,
        viewWidth: Int,
        viewHeight: Int,
        scale: Float,
        leftAlign: Boolean = false,
    ) {
        if (scale <= 0f) return
        // Nothing sensible to draw on, and every division below would be against zero.
        if (iconBounds.isEmpty) return

        val corner = if (leftAlign) leftOfIcon else rightOfIcon
        val extent = dotRadius + ringWidth
        var x = iconBounds.left + iconBounds.width() * corner[0]
        var y = iconBounds.top + iconBounds.height() * corner[1]

        // A view too small to hold the dot at all leaves it centred rather than throwing.
        if (viewWidth > 2 * extent) x = x.coerceIn(extent, viewWidth - extent)
        if (viewHeight > 2 * extent) y = y.coerceIn(extent, viewHeight - extent)

        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.drawCircle(0f, 0f, dotRadius, dotPaint)
        canvas.drawCircle(0f, 0f, dotRadius + ringWidth / 2f, ringPaint)
        canvas.restore()
    }

    /** True when this renderer was built for exactly this icon size. */
    fun matches(sizePx: Int): Boolean = iconSizePx == sizePx

    companion object {

        /** The dot's diameter as a fraction of the icon. AOSP's number, and it looks right. */
        private const val SIZE_RATIO = 0.228f

        /** The contrast ring, in dp. Thin enough to read as an edge rather than a second dot. */
        private const val RING_DP = 1.5f

        /** `AdaptiveIconDrawable` states its mask in a 100 by 100 box. */
        private const val MASK_UNITS = 100f

        /** Used when the platform will not hand over its mask; matches the settings swatches. */
        private const val SYSTEM_FALLBACK_RADIUS = 0.18f

        /**
         * One renderer per shape and size, kept for as long as they stay the same.
         *
         * Finding the point on the outline costs a `Path.op` and a `PathMeasure`, which is not
         * much but is far too much to do per icon per draw. Changing the shape reloads the
         * launcher, which comes back through here once.
         */
        @Volatile
        private var cached: DotRenderer? = null

        @Volatile
        private var cachedShape: IconShape? = null

        @Volatile
        private var cachedColour: DotColour? = null

        fun forShape(
            context: Context,
            shape: IconShape,
            colour: DotColour,
            iconSizePx: Int,
        ): DotRenderer {
            val existing = cached
            if (existing != null &&
                cachedShape == shape &&
                cachedColour == colour &&
                existing.matches(iconSizePx)
            ) {
                return existing
            }
            val built = build(context, shape, colour, iconSizePx)
            cached = built
            cachedShape = shape
            cachedColour = colour
            return built
        }

        private fun build(
            context: Context,
            shape: IconShape,
            colour: DotColour,
            iconSizePx: Int,
        ): DotRenderer {
            val extent = iconSizePx.toFloat()
            val outline = Path()
            if (shape.isOverride) {
                shape.outline(extent, outline)
            } else {
                systemMask(extent, outline)
            }

            val density = context.resources.displayMetrics.density
            val dotColour = context.getColor(colour.colourRes)
            return DotRenderer(
                iconSizePx = iconSizePx,
                rightOfIcon = pointOnOutline(outline, toTheRight = true),
                leftOfIcon = pointOnOutline(outline, toTheRight = false),
                dotRadius = SIZE_RATIO * iconSizePx / 2f,
                ringWidth = RING_DP * density,
                dotColour = dotColour,
                ringColour = context.getColor(ringFor(dotColour)),
            )
        }

        /**
         * The ring that keeps [dotColour] visible: dark behind a light dot, light behind a dark one.
         *
         * Relative luminance rather than a simple average, because the eye is far more sensitive to
         * green than to blue and a plain mean calls Eden's own green darker than it looks. The
         * threshold is the usual one, and the two ends of the palette - white and black - are the
         * cases it exists for.
         */
        private fun ringFor(dotColour: Int): Int {
            val red = android.graphics.Color.red(dotColour) / 255f
            val green = android.graphics.Color.green(dotColour) / 255f
            val blue = android.graphics.Color.blue(dotColour) / 255f
            val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            return if (luminance > LIGHT_DOT_THRESHOLD) {
                R.color.notification_dot_ring_dark
            } else {
                R.color.notification_dot_ring_light
            }
        }

        /** Above this a dot is light enough to need a dark ring behind it. */
        private const val LIGHT_DOT_THRESHOLD = 0.45f

        /**
         * Where the outline passes closest to the top corner it will be drawn in, as a fraction of
         * the box - the right one normally, the left one in a right-to-left layout.
         *
         * The outline is walked and the point furthest along `x - y` wins, which is the corner of
         * the box the dot belongs in for every shape: the forty-five degree point of a circle, the
         * actual corner of a square, the middle of the upper-right edge of a diamond.
         *
         * AOSP finds the same point by intersecting the outline with a sliver of a triangle aimed
         * at the corner and taking the first point of the result. That is fewer operations and it
         * was tried first, but the point a `Path.Op.INTERSECT` chooses to begin its contour at is
         * not defined to be any particular end of it - on this device it came back with the far
         * end, and the dot appeared on the top *left* of every icon. Sampling is a hundred and
         * eighty `getPosTan` calls once per shape per size, and it cannot be wrong.
         *
         * The result is expressed against the path's own bounding box rather than against the box
         * it was asked to fill. The generated shapes do fill it exactly; the platform's adaptive
         * mask is another app's path scaled by a guess about what units it came in, and that guess
         * has been wrong before. Measuring what actually arrived costs one `computeBounds` and
         * makes the answer independent of it.
         */
        private fun pointOnOutline(outline: Path, toTheRight: Boolean): FloatArray {
            val fallbackX = if (toTheRight) FALLBACK_X else 1f - FALLBACK_X
            val bounds = RectF()
            outline.computeBounds(bounds, true)
            if (bounds.width() <= 0f || bounds.height() <= 0f) {
                return floatArrayOf(fallbackX, FALLBACK_Y)
            }

            val measure = PathMeasure(outline, false)
            val length = measure.length
            if (length <= 0f) return floatArrayOf(fallbackX, FALLBACK_Y)

            val sample = FloatArray(2)
            var bestX = if (toTheRight) bounds.right else bounds.left
            var bestY = bounds.top
            var best = -Float.MAX_VALUE

            for (i in 0 until SAMPLES) {
                if (!measure.getPosTan(length * i / SAMPLES, sample, null)) continue
                // Furthest towards the top-right corner, or the top-left one for a mirrored layout.
                val score = if (toTheRight) sample[0] - sample[1] else -sample[0] - sample[1]
                if (score <= best) continue
                best = score
                bestX = sample[0]
                bestY = sample[1]
            }

            return floatArrayOf(
                ((bestX - bounds.left) / bounds.width()).coerceIn(0f, 1f),
                ((bestY - bounds.top) / bounds.height()).coerceIn(0f, 1f),
            )
        }

        /** Enough to land within a degree or two of the corner on any of the shapes. */
        private const val SAMPLES = 180

        /** Where the dot goes when the outline cannot be intersected: the adaptive safe corner. */
        private const val FALLBACK_X = 0.85f
        private const val FALLBACK_Y = 0.15f

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
            into.transform(Matrix().apply { setScale(extent / MASK_UNITS, extent / MASK_UNITS) })
        }
    }
}
