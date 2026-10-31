package app.auriel.edenlauncher.icons

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.withSign

/**
 * The silhouette every app icon is cut to, independent of where the artwork came from.
 *
 * An icon pack decides what an app's icon looks like; this decides what shape it is. They are
 * separate choices and Eden treats them that way: the pack's artwork is resolved first - its own
 * drawing for that app, or its background, mask and overlay composed over the app's own icon - and
 * only then is the chosen outline cut out of the result. A pack whose author drew everything as
 * circles can be worn as squircles, and the pack keeps working exactly as it did.
 *
 * [SYSTEM] is the way out, and it is the default. It means "do not cut anything", so an adaptive
 * icon is masked by whatever the device's own mask is and a legacy icon keeps the outline its
 * author drew. Nothing in this file runs at all in that state.
 *
 * The paths are generated rather than shipped as vector drawables for the same reason the overview
 * panel draws its own glyphs: a dozen outlines are a few lines of arithmetic each, and a resource
 * file per shape per density is a lot of APK for shapes that are already exactly describable.
 */
enum class IconShape {
    /** The device's own mask. Adaptive icons follow the platform, legacy icons keep their edges. */
    SYSTEM,

    CIRCLE,

    /** Corner to corner, no rounding at all. */
    SQUARE,

    /** The classic Android rounded square. */
    ROUNDED_SQUARE,

    /** A true superellipse: flat sides that turn late. What One UI puts on a Samsung. */
    SQUIRCLE,

    /** ColorOS-inspired soft superellipse, with fuller corners than the standard squircle. */
    COLOROS,

    /** The iOS icon outline: rounder than [SQUIRCLE], and smoother into the corner. */
    APPLE,

    /** A circle with one square corner, from the shapes Android 8 shipped for adaptive icons. */
    TEARDROP,

    /** Two opposite corners pulled fully round and the other two left nearly square. */
    LEAF,

    /** A square stood on its point. */
    DIAMOND,

    /** Six sides, point up. */
    HEXAGON,

    /** [HEXAGON] with the points taken off it. */
    ROUNDED_HEXAGON,

    /** Eight sides, flat on top. */
    OCTAGON,

    /** Seven sides, rounded hard. The shape Android 12 called a cookie. */
    COOKIE,
    ;

    /** True when this shape replaces whatever outline the artwork arrived with. */
    val isOverride: Boolean get() = this != SYSTEM

    /**
     * Writes this shape into [into] as a closed outline filling a [size] by [size] box.
     *
     * The path is reset first, so a caller may keep one [Path] and reuse it rather than allocate
     * per icon. Nothing is written for [SYSTEM]: it has no outline of its own by definition.
     */
    fun outline(size: Float, into: Path) {
        into.reset()
        if (size <= 0f) return

        val bounds = RectF(0f, 0f, size, size)
        when (this) {
            SYSTEM -> Unit
            CIRCLE -> into.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            SQUARE -> into.addRect(bounds, Path.Direction.CW)
            ROUNDED_SQUARE -> {
                val radius = size * ROUNDED_SQUARE_RADIUS
                into.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            }

            SQUIRCLE -> superellipse(size, SQUIRCLE_EXPONENT, into)
            COLOROS -> superellipse(size, COLOROS_EXPONENT, into)
            APPLE -> continuousRoundedRect(size, into)
            TEARDROP -> teardrop(size, bounds, into)
            LEAF -> leaf(size, bounds, into)
            DIAMOND -> polygon(size, sides = 4, startAngle = TOP, corner = 0f, into = into)
            HEXAGON -> polygon(size, sides = 6, startAngle = TOP, corner = 0f, into = into)
            ROUNDED_HEXAGON ->
                polygon(size, sides = 6, startAngle = TOP, corner = HEXAGON_CORNER, into = into)

            OCTAGON -> polygon(size, sides = 8, startAngle = OCTAGON_TOP, corner = 0f, into = into)
            COOKIE -> polygon(size, sides = 7, startAngle = TOP, corner = COOKIE_CORNER, into = into)
        }
        into.close()
    }

    /**
     * The superellipse, walked as a polygon.
     *
     * Sampled rather than approximated with Beziers because the curve has no exact Bezier form and
     * the sampling error at [SEGMENTS] steps is well under a pixel at any icon size a phone will
     * ask for - the icons here are around 190px on the test device.
     */
    private fun superellipse(size: Float, exponent: Float, into: Path) {
        val half = size / 2f
        val power = 2f / exponent
        for (i in 0 until SEGMENTS) {
            val t = (2.0 * Math.PI * i / SEGMENTS).toFloat()
            val cosT = cos(t)
            val sinT = sin(t)
            val x = half + half * abs(cosT).pow(power).withSign(cosT)
            val y = half + half * abs(sinT).pow(power).withSign(sinT)
            if (i == 0) into.moveTo(x, y) else into.lineTo(x, y)
        }
    }

    /**
     * A circle with the top-left corner left square.
     *
     * One of the outlines Android 8 shipped as adaptive icon masks, and the only one of them that
     * is not a rounded rectangle of some kind. Drawn as two straight edges into a 270 degree arc
     * rather than as an arc with a corner grafted on, so the joins land exactly on the circle.
     */
    private fun teardrop(size: Float, bounds: RectF, into: Path) {
        val half = size / 2f
        into.moveTo(0f, half)
        into.lineTo(0f, 0f)
        into.lineTo(half, 0f)
        // Sweeps from the top of the circle all the way round to the left, where the corner began.
        into.arcTo(bounds, 270f, 270f)
    }

    /**
     * Two opposite corners pulled fully round, the other two left nearly square.
     *
     * The asymmetry is the whole shape: a rounded rectangle reads as a rounded rectangle whatever
     * the radius, and it is only when the diagonal is broken that the eye starts calling it a leaf.
     * The small radius is not zero because a hard point next to a half-width curve reads as a
     * mistake rather than a contrast.
     */
    private fun leaf(size: Float, bounds: RectF, into: Path) {
        val big = size * LEAF_LARGE_RADIUS
        val small = size * LEAF_SMALL_RADIUS
        // Corner radii run clockwise from the top left, x then y for each.
        val radii = floatArrayOf(big, big, small, small, big, big, small, small)
        into.addRoundRect(bounds, radii, Path.Direction.CW)
    }

    /**
     * A regular polygon of [sides] sides, inscribed in the circle that fills the box.
     *
     * Inscribed rather than stretched to the box: a hexagon is narrower than it is tall and
     * squashing it until it fills the square would make it something that is not a hexagon. The
     * cost is that these shapes carry a little less artwork than a circle does, which is what a
     * hexagon costs everywhere it is offered.
     *
     * [corner] is how much of each edge, at each end, is given over to rounding it - so 0 leaves
     * the points sharp and 0.5 rounds every edge away entirely. The rounding is a quadratic
     * through the vertex, which is not quite a circular arc but is within a fraction of a pixel of
     * one at these radii and costs two numbers instead of a trigonometric solve per corner.
     */
    private fun polygon(size: Float, sides: Int, startAngle: Double, corner: Float, into: Path) {
        val half = size / 2f
        val xs = FloatArray(sides)
        val ys = FloatArray(sides)
        for (i in 0 until sides) {
            val angle = Math.toRadians(startAngle + 360.0 * i / sides)
            xs[i] = half + half * cos(angle).toFloat()
            ys[i] = half + half * sin(angle).toFloat()
        }

        if (corner <= 0f) {
            for (i in 0 until sides) {
                if (i == 0) into.moveTo(xs[i], ys[i]) else into.lineTo(xs[i], ys[i])
            }
            return
        }

        for (i in 0 until sides) {
            val prev = (i + sides - 1) % sides
            val next = (i + 1) % sides
            val enterX = xs[i] + (xs[prev] - xs[i]) * corner
            val enterY = ys[i] + (ys[prev] - ys[i]) * corner
            val leaveX = xs[i] + (xs[next] - xs[i]) * corner
            val leaveY = ys[i] + (ys[next] - ys[i]) * corner
            if (i == 0) into.moveTo(enterX, enterY) else into.lineTo(enterX, enterY)
            into.quadTo(xs[i], ys[i], leaveX, leaveY)
        }
    }

    /**
     * The iOS icon outline: a rounded rectangle whose curvature is continuous.
     *
     * A plain rounded rectangle joins a straight edge to a circular arc, and the curvature jumps
     * from zero to one over the radius at that join. The eye reads the discontinuity as a slight
     * flattening just before each corner. Apple's shape spends a longer run of the edge easing
     * into the arc, so the curvature ramps up instead of stepping, which is the whole of why it
     * looks softer - and why it is not the same shape as [SQUIRCLE], which gets its roundness from
     * a different family of curve entirely.
     *
     * Each corner is therefore three pieces rather than one: a cubic that leaves the straight edge
     * and bends into the turn, a short circular arc of [ARC_MEASURE] degrees through the diagonal,
     * and the mirror of the first cubic leaving into the next edge. The lengths below are the
     * standard construction for a corner radius of [APPLE_RADIUS] of the width at Apple's own
     * smoothing of [APPLE_SMOOTHING], and they sum to exactly p, which is what puts the corner's
     * far end back on the edge where the next straight run starts.
     *
     * The corner is built once for the top-right and the other three are the same points rotated
     * about the centre, which keeps this to arithmetic rather than four transcriptions.
     */
    private fun continuousRoundedRect(size: Float, into: Path) {
        val r = size * APPLE_RADIUS

        // How much of each edge one corner consumes.
        val p = (1f + APPLE_SMOOTHING) * r

        // The straight-line span of the circular section, along one axis.
        val arcSection = sin(Math.toRadians(ARC_MEASURE / 2.0)).toFloat() * r * SQRT_2

        // The two cubics either side of the arc. Lengths a and b run along the edge; c and d are
        // the offsets that bend it off the edge and onto the arc's starting tangent.
        val p3ToP4 = r * tan(Math.toRadians(APPLE_ANGLE_BETA / 2.0)).toFloat()
        val c = p3ToP4 * cos(Math.toRadians(APPLE_ANGLE_ALPHA)).toFloat()
        val d = c * tan(Math.toRadians(APPLE_ANGLE_ALPHA)).toFloat()
        val b = (p - arcSection - c - d) / 3f
        val a = 2f * b

        // The arc is short enough that one cubic is exact to well under a pixel. The control arms
        // run along the tangents at each end, at the classic 4/3 * tan(theta / 4) length.
        val armLength = (4.0 / 3.0 * tan(Math.toRadians(ARC_MEASURE / 4.0))).toFloat() * r
        val alphaCos = cos(Math.toRadians(APPLE_ANGLE_ALPHA)).toFloat()
        val alphaSin = sin(Math.toRadians(APPLE_ANGLE_ALPHA)).toFloat()

        // The top-right corner, in absolute coordinates, entering along the top edge.
        val startX = size - p
        val startY = 0f
        val arcStartX = startX + a + b + c
        val arcStartY = d
        val arcEndX = arcStartX + arcSection
        val arcEndY = arcStartY + arcSection

        val corner = floatArrayOf(
            // Cubic into the turn.
            startX + a, startY, startX + a + b, startY, arcStartX, arcStartY,
            // The arc, as a cubic. Its tangents sit alpha degrees off each edge.
            arcStartX + armLength * alphaCos, arcStartY + armLength * alphaSin,
            arcEndX - armLength * alphaSin, arcEndY - armLength * alphaCos,
            arcEndX, arcEndY,
            // Cubic out of the turn and onto the right edge, the mirror of the first.
            arcEndX + d, arcEndY + c, arcEndX + d, arcEndY + b + c, arcEndX + d, arcEndY + b + c + a,
        )

        val point = FloatArray(2)
        for (quarter in 0 until 4) {
            rotate(startX, startY, quarter, size, point)
            // The straight run between one corner's end and the next one's start.
            if (quarter == 0) into.moveTo(point[0], point[1]) else into.lineTo(point[0], point[1])

            var i = 0
            while (i < corner.size) {
                rotate(corner[i], corner[i + 1], quarter, size, point)
                val c1x = point[0]
                val c1y = point[1]
                rotate(corner[i + 2], corner[i + 3], quarter, size, point)
                val c2x = point[0]
                val c2y = point[1]
                rotate(corner[i + 4], corner[i + 5], quarter, size, point)
                into.cubicTo(c1x, c1y, c2x, c2y, point[0], point[1])
                i += 6
            }
        }
    }

    /** Rotates a point about the box centre by [quarter] right angles, clockwise on screen. */
    private fun rotate(x: Float, y: Float, quarter: Int, size: Float, out: FloatArray) {
        val centre = size / 2f
        var dx = x - centre
        var dy = y - centre
        repeat(quarter) {
            // Screen coordinates put y downwards, so a clockwise quarter turn sends (dx, dy) to
            // (-dy, dx).
            val nextX = -dy
            dy = dx
            dx = nextX
        }
        out[0] = centre + dx
        out[1] = centre + dy
    }

    companion object {
        /** Stored and read back by name, so adding a shape cannot renumber anyone's choice. */
        fun fromName(name: String?): IconShape = entries.firstOrNull { it.name == name } ?: SYSTEM

        /** Corner radius of [ROUNDED_SQUARE], as a fraction of the icon's width. */
        private const val ROUNDED_SQUARE_RADIUS = 0.18f

        /**
         * The exponent of the [SQUIRCLE] superellipse. Four is what the name usually means and
         * what One UI's icons match: noticeably squarer than a rounded rectangle of the same
         * visual weight.
         */
        private const val SQUIRCLE_EXPONENT = 4f

        /** Softer, more rounded-square profile for the ColorOS-inspired icon treatment. */
        private const val COLOROS_EXPONENT = 5.2f

        /** Apple's corner radius, as a fraction of the icon's width. */
        private const val APPLE_RADIUS = 0.2237f

        /** Apple's corner smoothing: how much of the corner is eased rather than arc. */
        private const val APPLE_SMOOTHING = 0.6f

        /** Degrees of true circular arc left in an [APPLE] corner: 90 * (1 - smoothing). */
        private const val ARC_MEASURE = 36.0

        /** Half of what the arc does not cover: (90 - ARC_MEASURE) / 2. */
        private const val APPLE_ANGLE_ALPHA = 27.0

        /** 45 * smoothing. */
        private const val APPLE_ANGLE_BETA = 27.0

        private const val SQRT_2 = 1.4142136f

        /** Where a polygon's first vertex goes. Screen degrees, so -90 is straight up. */
        private const val TOP = -90.0

        /** Half a step further round, which puts an edge on top instead of a point. */
        private const val OCTAGON_TOP = -67.5

        /**
         * How much of each edge a rounded corner eats, at each end.
         *
         * Both were tried higher and both lost their shape: at 0.5 every edge is rounded away
         * entirely and a seven-sided cookie is just a circle with extra arithmetic. These leave a
         * visible flat between the corners, which is the only thing that says how many sides it
         * has.
         */
        private const val HEXAGON_CORNER = 0.2f
        private const val COOKIE_CORNER = 0.3f

        /** The two radii of [LEAF], as fractions of the icon's width. */
        private const val LEAF_LARGE_RADIUS = 0.5f
        private const val LEAF_SMALL_RADIUS = 0.1f

        /** Polygon steps around a superellipse. */
        private const val SEGMENTS = 96
    }
}
