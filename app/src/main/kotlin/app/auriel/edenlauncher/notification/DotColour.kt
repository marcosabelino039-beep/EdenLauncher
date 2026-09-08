package app.auriel.edenlauncher.notification

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import app.auriel.edenlauncher.R

/**
 * The sixteen colours a notification dot may be.
 *
 * Sixteen because that is what a palette is. A colour picker with a wheel and a hex field is a
 * decision with no wrong answers and therefore no answer, and what it produces on a home screen is
 * a dot that nearly matches something else. A fixed set of sixteen is chosen from at a glance, and
 * every one of them is legible on top of an app icon, which is the only requirement the colour has.
 *
 * The set is an eight-bit palette rather than a designed one: fully saturated primaries, the two
 * ends of the greyscale and the neutral between them, and the secondaries that sit between the
 * primaries. It is the palette a machine with four bits per pixel would have had, which is the
 * point - these are colours that were picked to be told apart from each other on bad displays.
 *
 * Stored by [name], so reordering this list or inserting into it never silently repaints somebody's
 * dots. [GREEN] is Eden's own green and the default; the rest are the palette's.
 */
enum class DotColour(@ColorRes val colourRes: Int, @StringRes val labelRes: Int) {
    GREEN(R.color.dot_green, R.string.dot_colour_green),
    LIME(R.color.dot_lime, R.string.dot_colour_lime),
    YELLOW(R.color.dot_yellow, R.string.dot_colour_yellow),
    ORANGE(R.color.dot_orange, R.string.dot_colour_orange),
    RED(R.color.dot_red, R.string.dot_colour_red),
    MAROON(R.color.dot_maroon, R.string.dot_colour_maroon),
    MAGENTA(R.color.dot_magenta, R.string.dot_colour_magenta),
    PURPLE(R.color.dot_purple, R.string.dot_colour_purple),
    NAVY(R.color.dot_navy, R.string.dot_colour_navy),
    BLUE(R.color.dot_blue, R.string.dot_colour_blue),
    CYAN(R.color.dot_cyan, R.string.dot_colour_cyan),
    TEAL(R.color.dot_teal, R.string.dot_colour_teal),
    BROWN(R.color.dot_brown, R.string.dot_colour_brown),
    WHITE(R.color.dot_white, R.string.dot_colour_white),
    GREY(R.color.dot_grey, R.string.dot_colour_grey),
    BLACK(R.color.dot_black, R.string.dot_colour_black),
    ;

    companion object {
        /** Eden's own green. What a dot is until somebody says otherwise. */
        val DEFAULT = GREEN

        fun fromName(name: String?): DotColour =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
