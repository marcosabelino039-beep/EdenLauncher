package app.auriel.edenlauncher.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cuts an icon to an [IconShape].
 *
 * Runs on whatever the icon pipeline has already decided the artwork should be - a pack's own
 * drawing, a pack composition, an icon the user picked by hand, or the app's own - so the shape is
 * the last word and never the only one. See [IconCache.themedIcon] for where it sits in the order.
 *
 * There is no single right way to force arbitrary artwork into a silhouette, so this does not
 * pretend there is. It picks between three treatments by looking at what it was handed:
 *
 *  - **An adaptive icon** is the easy case, and the only one where the result is exactly what the
 *    icon's author intended. Adaptive icons are drawn as two oversized layers precisely so that a
 *    launcher can mask them to any outline it likes, so the layers are drawn straight into the
 *    chosen shape and the platform's own mask never runs.
 *  - **Artwork that fills its box** - a pack's icon, a composed icon, most legacy icons - is
 *    scaled to cover the shape and clipped. Anything outside the outline is lost, which is the
 *    honest cost of asking for a circle when the author drew a square.
 *  - **A glyph floating on transparency** cannot be clipped at all: a circle cut round a small
 *    mark in the middle of an empty square is just the mark, with no shape visible. Those get a
 *    plate in the shape, tinted from the artwork's own dominant colour, with the glyph set into
 *    it. This is what stops a themed home screen having holes in it.
 *
 * Everything here allocates and runs for tens of milliseconds per icon. It is called from the
 * second icon pass, on a background dispatcher, batched, and the result is cached - never from a
 * touch, layout or draw path.
 */
object IconShaper {

    /**
     * Renders [drawable] at [size] and cuts it to [shape].
     *
     * The drawable rather than a rendered bitmap is wanted wherever one can be had: an adaptive
     * icon that has already been rasterised has had the device's own mask applied to it, and a
     * circle recovered from a mask-shaped bitmap is not the same thing as one cut from the layers.
     */
    fun shape(drawable: Drawable, shape: IconShape, size: Int): Bitmap {
        if (size <= 0) return rasterise(drawable, max(size, 1))
        if (!shape.isOverride) return rasterise(drawable, size)
        if (drawable is AdaptiveIconDrawable) return shapeAdaptive(drawable, shape, size)
        return shape(rasterise(drawable, size), shape, size)
    }

    /**
     * Cuts an already-rendered icon to [shape], returning a new bitmap of [size] square.
     *
     * The input is never recycled or drawn into; callers own it either way.
     */
    fun shape(source: Bitmap, shape: IconShape, size: Int): Bitmap {
        if (size <= 0) return source
        if (!shape.isOverride) return scaled(source, size)

        val outline = Path()
        shape.outline(size.toFloat(), outline)

        val analysis = analyse(source)
        return if (analysis.fillsItsBox) {
            clipToOutline(source, analysis.content, outline, size)
        } else {
            plateAndInset(source, analysis, outline, size)
        }
    }

    // ---- the three treatments --------------------------------------------------------------------

    /**
     * Draws an adaptive icon's layers into the shape.
     *
     * An adaptive icon is 108 units square of which the middle 72 are guaranteed visible, so the
     * layers are drawn 1.5 times the size of the output and centred - which is exactly what
     * [AdaptiveIconDrawable] does to its children when its own bounds are set, so the arithmetic
     * is left to it rather than repeated here.
     */
    private fun shapeAdaptive(drawable: AdaptiveIconDrawable, shape: IconShape, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Setting the parent's bounds is what lays the two layers out over the full 108 units.
        drawable.setBounds(0, 0, size, size)
        drawable.background?.draw(canvas)
        drawable.foreground?.draw(canvas)

        maskInPlace(canvas, shape, size)
        return output
    }

    /** Scales the artwork to cover the shape, then cuts the shape out of it. */
    private fun clipToOutline(source: Bitmap, content: Rect, outline: Path, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Drawn from the content bounds rather than the whole bitmap, so a few transparent pixels
        // of margin do not leave the shape with a gap around its edge.
        canvas.drawBitmap(source, content, RectF(0f, 0f, size.toFloat(), size.toFloat()), filterPaint())
        maskInPlace(canvas, outline)
        return output
    }

    /**
     * Fills the shape with a colour taken from the artwork and sets the artwork into it.
     *
     * The plate is lightened well away from the colour it was sampled from, because the glyph
     * being drawn on top is usually made of exactly that colour and a plate the same shade as its
     * contents is an empty shape.
     */
    private fun plateAndInset(
        source: Bitmap,
        analysis: Analysis,
        outline: Path,
        size: Int,
    ): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = analysis.plateColour
        canvas.drawPath(outline, paint)

        val content = analysis.content
        val inner = size * INNER_FRACTION
        val scale = min(inner / content.width(), inner / content.height())
        val drawWidth = content.width() * scale
        val drawHeight = content.height() * scale
        val left = (size - drawWidth) / 2f
        val top = (size - drawHeight) / 2f

        canvas.drawBitmap(
            source,
            content,
            RectF(left, top, left + drawWidth, top + drawHeight),
            filterPaint(),
        )
        return output
    }

    // ---- masking ---------------------------------------------------------------------------------

    private fun maskInPlace(canvas: Canvas, shape: IconShape, size: Int) {
        val outline = Path()
        shape.outline(size.toFloat(), outline)
        maskInPlace(canvas, outline)
    }

    /**
     * Keeps only what falls inside [outline], with a soft edge.
     *
     * Done by drawing the outline over the finished icon in `DST_IN` rather than by clipping the
     * canvas: a clip path on a software canvas is not antialiased, and a circle with a staircase
     * for an edge is worse than no shape at all. The bitmap is the layer here - it starts fully
     * transparent - so no `saveLayer` is needed.
     */
    private fun maskInPlace(canvas: Canvas, outline: Path) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        paint.color = Color.BLACK
        canvas.drawPath(outline, paint)
    }

    private fun filterPaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // ---- looking at the artwork ------------------------------------------------------------------

    private class Analysis(val content: Rect, val fillsItsBox: Boolean, val plateColour: Int)

    /**
     * Measures where the artwork actually is and what colour it mostly is.
     *
     * Read off a small scaled copy rather than the icon itself: the questions being asked - how
     * much of the box is covered, what colour dominates - are answered just as well by a thousand
     * samples as by forty thousand, and the copy costs one allocation that is thrown away here
     * instead of a pixel array the size of the icon.
     *
     * Sampled without filtering on purpose. A filtered downscale spreads alpha into the
     * transparent margin and would report every icon as filling its box.
     */
    private fun analyse(source: Bitmap): Analysis {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) {
            return Analysis(Rect(0, 0, max(width, 1), max(height, 1)), true, NEUTRAL_PLATE)
        }

        val sampleW = min(width, SAMPLE)
        val sampleH = min(height, SAMPLE)
        val sample = Bitmap.createScaledBitmap(source, sampleW, sampleH, false)
        val pixels = IntArray(sampleW * sampleH)
        sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        if (sample !== source) sample.recycle()

        var left = sampleW
        var top = sampleH
        var right = -1
        var bottom = -1
        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                if (Color.alpha(pixels[y * sampleW + x]) < CONTENT_ALPHA) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }

        // A fully transparent icon has nothing to measure and nothing to shape; hand back the whole
        // box so the caller clips it and gets the same nothing it started with.
        if (right < 0) return Analysis(Rect(0, 0, width, height), true, NEUTRAL_PLATE)

        // Sample cells are whole pixels of a coarse grid, so the far edges include their own cell.
        val scaleX = width.toFloat() / sampleW
        val scaleY = height.toFloat() / sampleH
        val content = Rect(
            (left * scaleX).toInt().coerceIn(0, width - 1),
            (top * scaleY).toInt().coerceIn(0, height - 1),
            ((right + 1) * scaleX).roundToInt().coerceIn(1, width),
            ((bottom + 1) * scaleY).roundToInt().coerceIn(1, height),
        )

        val fills = content.width() >= width * FULL_BLEED && content.height() >= height * FULL_BLEED
        return Analysis(content, fills, plateColour(pixels))
    }

    /**
     * The artwork's dominant colour, lightened into something a glyph can sit on.
     *
     * Dominant by hue rather than by average: averaging a two-colour icon returns a colour that
     * appears nowhere in it, and averaging a colourful one returns mud. Buckets of ten degrees,
     * counted over the pixels that are opaque and actually coloured, then the mean of the winning
     * bucket. An icon with no colour in it at all - a black glyph on transparency - has no answer
     * to give, and gets the neutral plate.
     */
    private fun plateColour(pixels: IntArray): Int {
        val counts = IntArray(HUE_BUCKETS)
        val sumR = LongArray(HUE_BUCKETS)
        val sumG = LongArray(HUE_BUCKETS)
        val sumB = LongArray(HUE_BUCKETS)
        val hsv = FloatArray(3)

        for (pixel in pixels) {
            if (Color.alpha(pixel) < OPAQUE_ALPHA) continue
            Color.colorToHSV(pixel, hsv)
            if (hsv[1] < MIN_SATURATION || hsv[2] < MIN_VALUE) continue
            val bucket = ((hsv[0] / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            counts[bucket]++
            sumR[bucket] += Color.red(pixel).toLong()
            sumG[bucket] += Color.green(pixel).toLong()
            sumB[bucket] += Color.blue(pixel).toLong()
        }

        var best = -1
        for (i in 0 until HUE_BUCKETS) {
            if (counts[i] > 0 && (best < 0 || counts[i] > counts[best])) best = i
        }
        if (best < 0) return NEUTRAL_PLATE

        val n = counts[best]
        return lighten(
            Color.rgb((sumR[best] / n).toInt(), (sumG[best] / n).toInt(), (sumB[best] / n).toInt()),
        )
    }

    /** Blends towards white by [PLATE_LIGHTEN], keeping the hue and losing most of the depth. */
    private fun lighten(colour: Int): Int {
        fun mix(channel: Int) = (channel + (255 - channel) * PLATE_LIGHTEN).toInt().coerceIn(0, 255)
        return Color.rgb(mix(Color.red(colour)), mix(Color.green(colour)), mix(Color.blue(colour)))
    }

    // ---- shared rasterising ----------------------------------------------------------------------

    /** Draws [drawable] into a square bitmap of [size]. */
    fun rasterise(drawable: Drawable, size: Int): Bitmap {
        // A bitmap-backed drawable already at the right size needs no redraw.
        if (drawable is BitmapDrawable) {
            val source = drawable.bitmap
            if (source != null && source.width == size && source.height == size) return source
        }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** [source] at [size] square, returned as-is when it is already that. */
    fun scaled(source: Bitmap, size: Int): Bitmap =
        if (source.width == size && source.height == size) {
            source
        } else {
            Bitmap.createScaledBitmap(source, size, size, true)
        }

    // ---- constants -------------------------------------------------------------------------------

    /** Grid the artwork is measured on. */
    private const val SAMPLE = 32

    /** Alpha at or above which a pixel counts as part of the artwork. */
    private const val CONTENT_ALPHA = 24

    /** Alpha at or above which a pixel is worth taking a colour from. */
    private const val OPAQUE_ALPHA = 200

    /**
     * How much of its box the artwork has to span before it is clipped rather than plated.
     *
     * Deliberately not 1.0: plenty of icons are drawn with a pixel or two of transparent margin,
     * and treating those as floating glyphs would shrink almost every icon on the phone.
     */
    private const val FULL_BLEED = 0.86f

    /** How much of the shape a plated glyph is allowed to take up. */
    private const val INNER_FRACTION = 0.58f

    private const val HUE_BUCKETS = 36
    private const val MIN_SATURATION = 0.15f
    private const val MIN_VALUE = 0.15f

    /** How far the sampled colour is dragged towards white before it becomes a plate. */
    private const val PLATE_LIGHTEN = 0.62f

    /** For artwork with no colour to sample. */
    private val NEUTRAL_PLATE = Color.rgb(0xE4, 0xE4, 0xE6)
}
