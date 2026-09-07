package app.auriel.edenlauncher.icons

import android.content.res.Resources
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
import android.graphics.drawable.LayerDrawable
import app.auriel.edenlauncher.util.EdenLog
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
        described = drawable
        if (drawable is AdaptiveIconDrawable) return shapeAdaptive(drawable, shape, size)
        return shape(rasterise(drawable, size), shape, size)
    }

    /**
     * The drawable the shaping in flight came from, for the log line only.
     *
     * A field rather than an argument threaded through four functions: the bitmap entry point is
     * public and is called with no drawable at all in the clock and pack paths, and widening every
     * signature to carry something only the diagnostic reads would be paying for it everywhere.
     * Written and read on the icon pass, which is one job at a time.
     */
    @Volatile
    private var described: Drawable? = null

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
        described?.let {
            described = null
            describe(it, analysis, shape, size)
        }
        return if (analysis.fillsItsBox) {
            clipToOutline(source, analysis.content, outline, size)
        } else {
            plateAndInset(source, analysis, outline, size)
        }
    }

    // ---- the three treatments --------------------------------------------------------------------

    /**
     * Draws an adaptive icon's layers, then treats the result like any other artwork.
     *
     * An adaptive icon is 108 units square of which the middle 72 are guaranteed visible, so the
     * layers are drawn 1.5 times the size of the output and centred - which is exactly what
     * [AdaptiveIconDrawable] does to its children when its own bounds are set, so the arithmetic
     * is left to it rather than repeated here. Drawing the layers directly is the point: the
     * platform's own mask never runs, so the shape is cut from the artwork rather than recovered
     * from a picture the device has already rounded off.
     *
     * What it must *not* do is assume the layers fill the box. They are supposed to - a background
     * that does not reach the edges is not a legal adaptive icon - but several OEM builds hand a
     * launcher an icon that is already masked to the device's own shape and then wrapped back up as
     * an adaptive one: a transparent background, and a foreground holding a circle that stops well
     * inside the safe zone. Every outline in [IconShape] circumscribes that circle, so masking it
     * removes nothing at all, and changing the shape appeared to do nothing on those phones -
     * Motorola's Android 16 among them.
     *
     * So the layers are rendered flat and handed to the bitmap path, which measures what it was
     * actually given: artwork that fills its box is clipped to the shape as before, and artwork
     * that floats in the middle of one gets the shape drawn *behind* it. Either way the shape the
     * user chose is the shape they get.
     */
    private fun shapeAdaptive(drawable: AdaptiveIconDrawable, shape: IconShape, size: Int): Bitmap {
        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layers)

        // Setting the parent's bounds is what lays the two layers out over the full 108 units.
        drawable.setBounds(0, 0, size, size)
        drawable.background?.draw(canvas)
        drawable.foreground?.draw(canvas)

        val shaped = shape(layers, shape, size)
        if (shaped !== layers) layers.recycle()
        return shaped
    }

    // ---- diagnostics -----------------------------------------------------------------------------

    /**
     * How many icons are still to be described in the log after a shape change.
     *
     * A shape that comes out looking like the one before it is not something the user can debug and
     * not something a stack trace records, so the shaper says what it was handed - for the first
     * few icons of a pass and then never again. Bounded because the alternative is a line per app
     * per rebind, and the log is 48 hours long.
     */
    @Volatile
    private var toDescribe = 0

    /** Called when the chosen shape changes, so the next pass explains itself once more. */
    fun describeNextIcons() {
        toDescribe = DESCRIBE_LIMIT
    }

    private fun describe(drawable: Drawable, analysis: Analysis, shape: IconShape, size: Int) {
        if (toDescribe <= 0) return
        toDescribe--

        val adaptive = drawable as? AdaptiveIconDrawable
        EdenLog.i(
            TAG,
            "shaping ${drawable.javaClass.simpleName} to $shape at ${size}px: " +
                "adaptive=${adaptive != null} " +
                "background=${adaptive?.background?.javaClass?.simpleName} " +
                "foreground=${adaptive?.foreground?.javaClass?.simpleName} " +
                "content=${analysis.content.width()}x${analysis.content.height()} " +
                "fillsItsBox=${analysis.fillsItsBox}",
        )
    }

    // ---- clock hands -----------------------------------------------------------------------------

    /**
     * Returns [drawable] with its clock-hand layers turned to [rotation], ready to be shaped.
     *
     * A drawable rather than a bitmap comes back on purpose, and it is the reason this is a
     * separate step rather than an argument to [shape]. The order the three layers of the pipeline
     * run in is raw, then pack, then shape, and the hands belong at the very front of it: a shape
     * cut first would be a mask over artwork that then moves underneath it, and a pack composed
     * first would have dressed up a clock that is showing the wrong time. So this turns the hands
     * and hands the result back, still a drawable, for the rest of the pipeline to work on exactly
     * as it works on anything else.
     *
     * For an adaptive icon the turned foreground is rebuilt into another [AdaptiveIconDrawable],
     * which keeps the background and foreground separate all the way through - so a shape is still
     * cut from the layers rather than recovered from a bitmap the platform has already masked, and
     * [shapeAdaptive] needs to know nothing about clocks.
     *
     * Anything that is not a layer list has no hands to turn and comes back untouched.
     */
    fun rotateClock(
        resources: Resources,
        drawable: Drawable,
        rotation: ClockRotation,
        size: Int,
    ): Drawable {
        if (size <= 0) return drawable

        if (drawable is AdaptiveIconDrawable) {
            val foreground = drawable.foreground as? LayerDrawable ?: return drawable

            // Setting the parent's bounds is what lays the foreground out over the full 108 units;
            // the layers are then turned in that oversized space and captured there, so feeding the
            // capture back to a new adaptive icon reproduces the geometry exactly.
            drawable.setBounds(0, 0, size, size)
            val layout = Rect(foreground.bounds)
            if (layout.isEmpty) return drawable

            val turned = Bitmap.createBitmap(layout.width(), layout.height(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(turned)
            canvas.translate(-layout.left.toFloat(), -layout.top.toFloat())
            drawTurned(canvas, foreground, rotation, layout.exactCenterX(), layout.exactCenterY())

            return AdaptiveIconDrawable(drawable.background, BitmapDrawable(resources, turned))
        }

        if (drawable is LayerDrawable) {
            drawable.setBounds(0, 0, size, size)
            val turned = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawTurned(Canvas(turned), drawable, rotation, size / 2f, size / 2f)
            return BitmapDrawable(resources, turned)
        }

        return drawable
    }

    /**
     * Draws every layer, turning the ones [rotation] names about ([centreX], [centreY]).
     *
     * The layers already carry the bounds their parent gave them, so nothing here lays anything
     * out - a hand is drawn where it was drawn before, with the canvas turned under it.
     */
    private fun drawTurned(
        canvas: Canvas,
        layers: LayerDrawable,
        rotation: ClockRotation,
        centreX: Float,
        centreY: Float,
    ) {
        for (i in 0 until layers.numberOfLayers) {
            val layer = layers.getDrawable(i) ?: continue
            val degrees = rotation.degreesFor(i)
            if (degrees == 0f) {
                layer.draw(canvas)
                continue
            }
            canvas.save()
            canvas.rotate(degrees, centreX, centreY)
            layer.draw(canvas)
            canvas.restore()
        }
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

    private const val TAG = "IconShaper"

    /** How many icons a shape change is explained over. See [describeNextIcons]. */
    private const val DESCRIBE_LIMIT = 3

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
