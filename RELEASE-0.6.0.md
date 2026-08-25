# Eden 0.6.0

Your icons, in whatever shape you want them, without giving up the icon pack that drew them. Pages
you can drag into the order you meant. And a screen that finally says what this is and who wrote it.

`versionCode 7`, `minSdk 29`, `targetSdk 36`. No native code, no network code.

---

## Twelve icon shapes

Settings, Icons, Icon shape. Circle, square, rounded square, squircle, Apple, teardrop, leaf,
diamond, hexagon, rounded hexagon, octagon, and a seven-sided cookie. Every one is drawn in the
list next to its name, because nobody knows what a squircle looks like next to a rounded square
from the words alone.

**This is applied on top of your icon pack, not instead of it.** The pack still decides what every
icon looks like - its own drawing for the apps it covers, its background and mask composed over the
ones it does not. The shape only decides what shape the result comes out. A pack whose author drew
everything as circles can be worn as hexagons and the pack keeps working exactly as it did. An icon
you picked by hand for one app still outranks everything, and still gets cut to the shape.

Adaptive icons are redrawn from their own two layers rather than re-cut from the shape your phone
already applied. That is the difference between a square that is square all the way into its
corners and a circle sitting in a square hole. Icons that are a small mark floating on transparency
cannot be clipped at all - a circle cut around one is just the mark - so those get a plate in the
chosen shape, tinted from the artwork's own colour, with the mark set into it.

Folders follow the shape too. A home screen of circles with rounded-rectangle folders on it looks
like something that was missed rather than something that was chosen.

**System** is the default and the way back. It means Eden cuts nothing: adaptive icons are masked
by whatever your phone masks them with, and everything else keeps the outline its author drew. The
row shows your device's actual mask, so "System" is a picture of what you already have rather than
a word.

Nothing about this runs on startup. The home screen appears at the speed it would with no shape
set, and the shapes arrive over the top of it a few icons at a time, exactly as icon packs have
done since 0.5.1. On the test phone, fifty-seven icons in under three hundred milliseconds.

## Drag your pages into order

Long-press empty space to zoom out to the cards, then hold any card and carry it left or right. The
other pages move aside as it passes, and the workspace scrolls when you reach the edge of the
screen - so a page can travel more than one place in a single gesture, which is the thing that
makes this usable at all when only one card fits on screen at a time.

The badges still work as they did: the house sets your home page, the X removes one and still asks
first. Letting go without moving anything changes nothing and stores nothing.

Your home page is stored as a page *id*, not a position, so reordering pages never silently moves
which one is home.

## An About screen worth opening

Settings, About, About Eden. The Eden icon, the version you are actually running, what this is, and
who made it - with a link straight to the source. The whole author row is the link, not a few
millimetres of underlined text in the middle of a paragraph.

---

## Notes

- Simple mode still means v0.2.0, so it cuts no shapes. Your chosen shape is remembered and comes
  straight back when you switch simple mode off.
- Changing the shape reloads the launcher rather than restarting it. The grid is already the right
  shape; only the pictures in it are wrong.
- Regular polygons - hexagon, octagon, diamond, cookie - are inscribed rather than stretched to
  fill the square, so they carry slightly less of the artwork than a circle does. Squashing a
  hexagon until it filled the box would make it something that is not a hexagon.

Bugs, ideas, disagreements: **debadityamalakar@gmail.com**

Attach the log if something went wrong - Settings, Troubleshooting, Open the log, Share. It stays
on the device until you do.
