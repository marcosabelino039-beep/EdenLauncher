# Eden 0.6.1

Several widgets in one block of the grid, swapped with a flick. Widgets that look like they belong
on the screen they are sitting on. And a copy of your whole arrangement you can put back.

`versionCode 8`, `minSdk 29`, `targetSdk 36`. No native code, no network code.

---

## Widget stacks

Drop one widget onto another and they share the block, one of them facing you. Swipe up or down on
it to change which. Four widgets in the space of one, no folder to open, and the one you left on
top is the one there when you come back.

A stack is to widgets what a folder is to icons, with the difference that decides everything: a
folder opens and a stack does not. The dots down the right edge say how deep the pile is and where
in it you are, and they follow the swipe rather than snapping, so a half-finished flick looks
half-finished. The ends pull back against the finger instead of refusing to move.

**Making one.** Drop a widget on a widget, or on a stack that already exists - the same gesture
that makes a folder out of two icons. The stack's own long-press menu also adds a widget straight
from the picker, which is the route that still works when the home screen has no free cells left.

**Unmaking one.** Take this one out puts the widget on top back on the page. A stack down to its
last widget becomes a plain widget again: a pile of one draws an indicator it does not need and
eats swipes that cannot go anywhere.

**Size.** A stack is one block, so every widget in it is shown at the stack's size and resizes with
it. What it can be resized to is the strictest of what its widgets accept - as small as the largest
minimum, as large as the smallest maximum - and a widget that could not render the stack's size is
refused at the drop rather than added broken. Eight to a stack: every widget in one is live whether
or not it is showing, because the system pushes its provider's updates either way.

**Who gets the swipe.** A widget you can still scroll keeps it - a list you have not finished
scrolling is not a stack you are trying to turn - and once it reaches its end the next swipe turns
the pile. Horizontal always belongs to the workspace, so a stack never costs you a page.

---

## Widgets that behave

- **Rounded to match everything else.** The provider's real background is found and clipped to,
  rather than the box around it, because that background is usually inset and clipping the outer
  box leaves the square corners showing. A widget that has already rounded itself is left alone.
- **No more floating in its own cell.** The padding a provider asks for is dropped; Eden owns the
  space between cells.
- **A widget with a list in it scrolls** instead of turning the page under it.
- **Its settings stay reachable.** A widget's own configuration screen is normally a one-time
  question on the way in and unreachable after that, which is where "I cannot change what my widget
  shows" comes from. It is in the long-press menu now, for any widget that has one.
- **Fewer dropped frames.** Redrawing a widget is the most expensive thing that happens to it, so
  updates wait until your gesture ends rather than landing in the middle of it.
- **A widget that breaks does not take the launcher with it.** A provider whose views will not load
  gets its own tile saying so.
- **Widgets stop flickering on rotation.** Both orientations' sizes are sent at once, so the
  provider stops re-picking a layout every time the phone turns.
- **A widget nudged an inch stays where it was**, instead of jumping to the nearest free cell.
- **The dock refuses widgets**, rather than laying one across the icons next to it.

---

## Backup and restore

Settings, Backup. Everything small and irreplaceable in one file: your pages, what is on them,
folders, the dock, every preference, your renames, and the icons you picked by hand.

Restore puts the whole arrangement back at once - either all of it lands or none of it does, so a
restore that is interrupted cannot leave you with no home screen at all.

Wallpapers are noted, not carried: a transcoded video is tens of megabytes, and a backup that takes
a minute to write is one nobody makes twice. If the file is still on the phone the restore
reconnects it; if it is gone, it says so and leaves the wallpaper you have now alone. The icon pack
is remembered by name and label, so a pack that is no longer installed can be named rather than
silently dropped.

**Widgets come back as tiles that offer to rebind themselves.** A widget id is issued by Android to
one install and means nothing to another, so a backup carries which widget it was and where it sat,
but never the id. Tap the tile and it binds again, asks whatever the widget asks, and returns to
the cells it left at the size it was.

---

## Clock icons that tell the time

A clock app whose icon is drawn in layers gets its hands moved to the actual time, on the minute,
on the home screen and in the drawer. Nothing runs at all on a phone whose clock app does not ship
one.

---

## Notes

- Widget stacks need no migration and change nothing about widgets you already have. A stack is
  stored the way a folder is, in columns the database has carried since the first release.
- Widget ids that nothing refers to any more are handed back to Android once per load. Pages
  deleted with widgets on them, in any version before this one, are cleaned up on first run.
- Simple mode still means v0.2.0: it puts the way in to widgets away, stacks included. Widgets you
  have already placed go on working and go on drawing, and the way back to them returns the moment
  you switch simple mode off.

Bugs, ideas, disagreements: **debadityamalakar@gmail.com**

Attach the log if something went wrong - Settings, Troubleshooting, Open the log, Share. It stays
on the device until you do.
