# Eden 0.6.2

Icon shapes that work on the phones they did not, a wallpaper picker that tells you the truth about
your device, and a drawer that opens the way you use it.

`versionCode 9`, `minSdk 29`, `targetSdk 36`. No native code, no network code.

Reported from a Motorola on Android 16, which is where most of this was found.

---

## Icon shapes apply on every phone

Choosing a shape did nothing at all on some devices. The icons were re-cut - the log said so, and
the work was really happening - and they came out looking exactly as they went in.

An adaptive icon is meant to be two oversized layers with a background reaching every edge, drawn
by the launcher into whatever outline it likes. Several manufacturer builds hand a launcher
something else: an icon already masked to the phone's own shape, then wrapped back up as an
adaptive one. A transparent background, and a circle sitting well inside the middle. Every outline
Eden offers is wider than that circle, so cutting it out removed nothing.

Eden now measures what it was actually given instead of assuming. Artwork that fills its box is
clipped to the shape as before; artwork floating in the middle of one gets the shape drawn behind
it, tinted from the icon's own colour, which is what the launcher already did for legacy icons.
Either way the shape you picked is the shape you get.

If something still looks wrong, the shaper now writes down what it was handed - the first three
icons after every shape change. Settings, Troubleshooting, Open the log.

---

## Live wallpapers on a phone that has no way to set them

Setting a live wallpaper is not something an app is allowed to do. Android reserves it for system
apps, and the only route any launcher has is to ask the system to show its own confirmation screen.

Some phones do not have that screen. Motorola's Android 16 ships a wallpaper app that handles
pictures and nothing else, and the request every launcher has made since 2012 lands nowhere. Eden
answered that with "could not set that wallpaper", which reads like a bug in Eden and invites you
to try again, five times, as the log from this release's report shows.

Eden now checks once, when the wallpaper screen opens, and says what is true:

- **The live and video sections say so up front**, instead of failing one tile at a time.
- **Tapping one explains why**, in a sentence about Android rather than about Eden, and offers the
  picture picker - which works, and which crops and positions for you.
- **Where a system screen does exist**, Eden tries the direct confirmation first and falls back to
  the older full list, so a build that ships one and not the other still works.

There is no workaround being withheld here. `setWallpaperComponent` is guarded by a permission no
ordinary app can hold, and saying so is the only honest thing left to do.

---

## Icons that vanished onto a page that was not there

`addInScreen: no page with id 2`, on every load, for one item that was nowhere on screen.

Dropping an icon on the trailing "new page" does two things: it writes the page, and it writes the
item onto it. The first goes to the database in the background; the end of the same drag tidied up
the page list from the screen immediately. Whichever finished second decided the outcome, and when
the tidy-up won it deleted the page that had just been created - leaving a row pointing at a page
that did not exist. Invisible, unreachable, and re-reported in the log on every load forever after.

Three fixes, because one of them would only have stopped it happening again:

- The drag's tidy-up now waits for the drop it belongs to.
- Reordering pages will no longer delete a page that still has something on it. Reordering is not
  how a page gets deleted, so a page missing from the order is a stale picture, not an instruction.
- Any item still naming a page that has no row **gets its page back** on the next load, with
  everything on it where it was. This heals databases that already went through the old bug, so if
  you have an app you know you placed and cannot find, it comes back on first run.

---

## The app drawer, three ways

All in Settings, App drawer. All off by default, because each is worse for somebody.

**Search field at the top or the bottom.** The search box is the one control in the drawer you
reach for rather than look at, and on a tall phone the top of the screen is not where your thumb
is.

**Open the keyboard with the drawer.** For anyone who opens the drawer to type a name rather than
to look at a grid. Off by default: an uninvited keyboard covers half of what you just opened.

**Reopen where you left off.** The drawer normally opens at the start, which is what keeps the same
apps under the same part of the screen every time. On, it keeps your place - in both the scrolling
drawer and the paged one. Searching still returns to the start, because the list you are looking at
is not the list your place was measured against.

---

## Notes

- No database migration. The page reconciliation is a read and a repair on load, not a schema
  change.
- Icon packs: Eden bundles none, and there is still no Android API for them. Any pack from the Play
  Store built for ADW, Nova, Apex, GO, Lawnchair or Launcher3 works, and Settings, Icon pack lists
  whatever you install. Choosing a picture for one app needs no pack at all - long press the icon,
  Icon, Choose a picture.
- The wallpaper picker's `<queries>` now names both live wallpaper screens explicitly. It should
  make no difference where `QUERY_ALL_PACKAGES` is honoured, and it is the documented fix where it
  is not.

Bugs, ideas, disagreements: **debadityamalakar@gmail.com**

Attach the log if something went wrong - Settings, Troubleshooting, Open the log, Share. It stays
on the device until you do.
