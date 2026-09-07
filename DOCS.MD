# Eden internals

How the pieces fit together, for anyone reading the code. `README.MD` has the why.

## The one rule that shapes everything

Views never touch the database. A gesture reaches a view, the view calls an interface, and
`Launcher` is the only class that turns that into a write. This is why the workspace, the dock, and
an open folder can share one drop path, and why no view needs a `Context` that is really an
`Activity`.

```
touch -> DragLayer -> DragController -> DropTarget -> ItemPlacementHandler -> Launcher
                                                                                |
                                                                  LauncherRepository -> Room
```

## Startup

1. `EdenApplication` does nothing. The launcher process is resident from boot; work here is charged
   to every cold start.
2. `Launcher.onCreate` inflates the hierarchy and wires the controllers.
3. `LauncherModel.load()` runs once on `Dispatchers.IO`: enumerate activities for every user
   profile, read `favorites` and `workspaceScreens`, resolve icons, write a first-run dock layout
   if the database is empty.
4. The result is bound on the main thread. One disk read, one bind.
5. `Launcher.applyIconTheme` puts the icon pack on afterwards, in batches, if there is one. See
   [Icons](#icons).

`LauncherAppState` holds everything process-wide and builds it lazily: the database, the icon
cache, the grid, preferences.

Which page the launcher opens on is resolved inside `Launcher.bind`, after `bindScreens`, and
nowhere else. The home page is stored as a page *id* (so reordering pages does not move it), and
turning an id into an index needs the id list to exist. Resolved before the bind - which is what
the caller used to do - the only answer an empty workspace can give is "no such page", so the
launcher fell back to the leftmost one and the user's choice was ignored on every cold start.
`Workspace` remembers the id for the same reason: `bindOverviewCallbacks` used to take it as an
argument defaulting to -1, and every caller that did not happen to know it cleared the home badge.

## Data

Room, in `data/`. Column names match AOSP Launcher3's so a database lifted from a stock launcher
still reads, and so the eventual backup agent has nothing to translate.

| Table | Holds |
|-------|-------|
| `favorites` | Every placed item: apps, shortcuts, folders, widgets. `container` is `-100` (desktop), `-101` (dock), or a folder's row id. |
| `workspaceScreens` | Page order. A page exists here even with nothing on it, which is what lets a deliberately empty page survive a reboot. |

No destructive-migration fallback. Losing someone's home screen to a schema change is not an
acceptable failure mode.

A page and the items on it are two tables, and nothing in SQLite ties them together. `loadWorkspace`
reconciles them on every load: an item naming a page with no row of its own gets that page back,
appended at the end, rather than being re-homed onto another one and losing the arrangement it was
part of. `LauncherDao.replaceScreens` will not delete a page that still holds items either -
reordering is not how a page is deleted, so a page missing from the order is a caller working from a
stale picture of the workspace, not an instruction.

Both exist because of one race, fixed in v0.6.2. Dropping an icon on the trailing "new page" wrote
the page and its item from a coroutine while the end of the same drag rewrote the page list from the
workspace synchronously, and whichever finished second decided whether the item still had a page to
sit on. When the drag won, the row was written to a page that had just been deleted: invisible on
every screen, impossible to reach, and `E/Workspace: addInScreen: no page with id N` on every load
afterwards. `Launcher.commitScreenChanges` now waits for the drop it belongs to, and the two
reconciliations above heal the databases that already went through it.

Deep shortcuts reuse the `intent` column: the package and shortcut id ride inside a stored intent,
so pinned shortcuts needed no schema change.

## Views

| Class | Job |
|-------|-----|
| `DragLayer` | Root container and the coordinate space every drag is expressed in. Owns window insets and the transforms that map a point between any descendant and the root. |
| `PagedView` | Horizontal paging. AOSP's fling and significant-move thresholds and velocity-scaled snap duration are kept exactly - that is what makes it feel like Launcher3. |
| `Workspace` | Pages, drops onto them, and overview. |
| `CellLayout` | One page: grid placement, occupancy, and its own overview card decoration. |
| `Hotseat` | The dock. Maps rank to cell so the rest of the launcher stays orientation-agnostic. |
| `BubbleTextView` | An icon with a label, as a compound drawable rather than child views. Cell padding lives here, so every grid in the app spaces identically from one setting. |
| `PageIndicatorDots` | Drawn directly. The home page gets a ring. |

## State transitions

There is no state machine yet, deliberately. Each overlay knows how to open and close itself, and
`Launcher.onBackRequested` decides the order: cancel a drag, else dismiss the icon menu, else leave
overview, else close the drawer, else close a folder, else go to the home page. When the next state
appears, that is the moment to build one.

## Reordering pages

`Workspace.reorderScreen` has existed since Phase 1 and had no caller until now; the gesture is the
new part.

A card in overview is not an ordinary view. `CellLayout.onInterceptTouchEvent` returns true so the
icons on it stop taking clicks, and `onTouchEvent` swallows everything and acts on the up - which
means `View`'s own long-press timer never runs, because that is driven from the `onTouchEvent` this
never calls up to. So the hold is timed by hand there, and cancelled by any movement past the slop:
sideways movement is paging, and `PagedView` above has already had its chance to claim it.

When the hold fires, `Workspace.onInterceptTouchEvent` starts returning true. The card gets a
cancel and the rest of the gesture arrives at the workspace, which is the only place that can move
a page relative to its siblings.

The carried card is positioned by `translationX` off whatever slot it currently occupies, so both
the finger's travel and the workspace's scroll have to be added back in - scroll included, or the
card would slide away with the pages it is being carried past. When its centre becomes nearest a
different slot, `reorderScreen` runs with `persist = false`, and the translation is corrected by the
difference between the two slots' resting scrolls so the card does not jump a page's width away
from the finger holding it. The pages it displaced are given the translation they just lost and
animated to zero, so they slide rather than appear.

Overview shows one card and slivers of its neighbours, so without an edge scroll a page could only
ever move one slot per gesture - the finger would run out of screen first. The edge zone is 16% of
the viewport and the speed rises with how far into it the finger is.

The order is written once, on the up, and only when something actually changed. A hold and release
stores nothing.

## Long press does two things

A long press on an icon has to mean both "show me the options" and "pick this up", and neither can
be given up. `DragController.preparePendingDrag` arms a drag without starting one and the launcher
shows `IconOptionsPopup`; the first movement past the touch slop, seen in
`DragLayer.onInterceptTouchEvent`, promotes it into a real drag and dismisses the menu. Hold still
and you get the menu; move and you get the drag. This is what Launcher3 has done since Android 8.

The popup fills the drag layer and handles its own dismissal rather than using
`DragLayer.setTouchCompleteListener`. That hook fires on the `ACTION_UP` of the very long press
that opened the menu, so routing dismissal through it made the popup appear and vanish inside one
gesture.

The same gesture works inside an open folder, through `Folder.onItemLongClick`. Without it a folder
was the one arrangement in the launcher that could not be undone: apps went in and there was no
gesture that took them out again. Dragging out closes the panel from `DragController.onDragStart` -
after the drag view has been snapshotted from it, not before - and `Launcher.dragSourceFolder`
remembers where the item came from so `onItemPlaced` and `onItemRemoved` can take it out of the
folder's contents wherever it lands. The in-memory folder is a separate list from the database row;
one left holding an app that is now on the workspace shows it twice until the next load, and puts
it back in the folder on the one after that.

## Where a drag is going to land

`Workspace.onDragOver` resolves the target cell on every move and hands it to
`CellLayout.setDropPreview`, which draws it under the icons. `setDropPreview` compares against what
it is already showing and only invalidates on a change, so a finger moving inside one cell costs a
hit test and nothing else. Landing on another icon makes a folder rather than taking that cell, and
is drawn in the accent colour to say so.

Both the preview and the drop itself go through `Workspace.resolveDropCell`, which starts with a
rule the plain nearest-vacant search cannot express: the cell an item was picked up from is still
marked occupied for the length of the drag, by the hidden item itself. Without the exception,
letting go exactly where you picked up pushed the icon one cell along. Single-cell items only - a
widget moved by less than its own size needs its whole new span checked, not just the corner.

## Closing a folder by tapping beside it

`DragLayer` owns this, the same way it owns the widget resize frame's outside-tap: the panel floats
over the workspace, so a tap meant for "put this away" lands on the workspace, which the folder
never sees. The gesture is then *swallowed* rather than passed on - closing the folder and
launching the icon behind it is not what anyone meant by tapping off a folder. `Folder` is also
clickable so it absorbs taps on its own padding.

Suspended while `IconOptionsPopup` is up: the popup fills the drag layer and handles its own
outside taps, and the drag layer sees the touch first, so leaving the hook armed would swallow the
tap meant for a menu row. Back still closes a folder either way; the tap is a second route, and
`LauncherPrefs.closeFolderOnTapOutside` turns it off for anyone who wants the icon underneath back.

## Widgets

There is no single call for "add this widget". `widget/WidgetBinding` walks the three steps a
launcher has to do itself:

1. **Allocate** an id from Eden's own `AppWidgetHost`. Until something is bound to it, it is a
   number.
2. **Bind** it to a provider. `bindAppWidgetIdIfAllowed` succeeds silently for a provider the user
   has already approved and returns false otherwise - which is not an error, it is the ordinary
   answer, and the next move is the system's `ACTION_APPWIDGET_BIND` prompt.
3. **Configure**, if the provider declared a configuration activity. Started through the host,
   because a configuration activity is usually not exported and only the host may launch it.

Any of the three can be cancelled, and every path that gives up calls
`LauncherAppWidgetHost.releaseWidgetId`. An id that is allocated and then forgotten is never
reclaimed and its provider goes on being told a widget exists.

`startListening` / `stopListening` are tied to `onStart` / `onStop`, not to the activity's whole
life: a launcher sitting behind another app has nothing to draw updates into, and a provider woken
to push RemoteViews at a screen nobody is looking at is exactly the background cost this project
avoids. Both are guarded - AOSP documents them as able to throw while the system server restarts.

**Sizes.** A provider states its size two incompatible ways. Since API 31 it can say what it wants
in cells (`targetCellWidth`), which needs no translation. Everything older states pixels, and
`WidgetSizes` works back from that against *this user's* grid - so the same widget is 4x1 on one
grid and 3x1 on another, which is correct. The picker shows the result per entry and refuses to
offer a provider that cannot fit at all, rather than letting a user through an id allocation and a
permission prompt to be told at the end that there is nowhere to put it.

**Touch.** A widget is a foreign view hierarchy that consumes everything.
`LauncherAppWidgetHostView` detects the long press above the widget's own children and sends them a
cancel when it fires. Without that, a widget containing a button can never be picked up. The host
view also consumes every gesture its own children ignored, as AOSP's does. Consuming only from the
long press onwards leaves the workspace as the touch target for any press that lands on a widget's
empty space, and a press-and-drag on a widget is then read by the pager as a swipe rather than by
the launcher as a drag. Consuming takes nothing away from the workspace: a parent's
`onInterceptTouchEvent` runs before its children on every event, which is how paging, the drawer
flick and the drag layer all still get their chance.

**Who owns a vertical drag.** A widget containing a list has to be able to scroll it, and the flick
that opens the drawer must not read the same drag. That is settled by asking rather than by
claiming: `LauncherAppWidgetHostView` publishes `hasScrollableContent`, recomputed on every layout,
and `Workspace` asks whatever is under the finger - once, on the way down, through
`View.canScrollDeeply` - whether it can still scroll upward before treating an upward drag as the
drawer. A `WidgetStackView` answers the same question by reporting that it takes vertical drags.

`requestDisallowInterceptTouchEvent` is deliberately **not** used for this, and the reason is worth
keeping: that flag reaches every ancestor, and one of those ancestors is the drag layer, which is
where a long press is turned into a drag (`DragController.onInterceptTouchEvent` promotes the
pending drag). A view that asks for it can never be picked up again - and because the gesture then
falls through to the workspace, dragging such a widget opens the app drawer instead.

**Corners and padding.** Providers draw square backgrounds and declare their own padding, both
authored for a launcher that is not this one. `RoundedCorners` (ported from AOSP 14's
`RoundedCornerEnforcement`, by way of the Lawnchair checkout in `.ref/`) finds the widget's real
background view and clips to *its* rectangle rather than to the host view's, because the background
is usually inset and clipping the outer box leaves the inner square corners showing. A widget that
has already rounded itself - a clipped `android.R.id.background` at the root, which is the contract
Android 12 introduced - is left alone. The provider's padding is dropped: the launcher owns the
space between cells.

**Frames the user is watching.** Applying RemoteViews is the most expensive thing that happens to a
widget, so updates are held back for the length of a drag or a flick through a stack and the last
one is applied when the gesture ends. A provider whose views throw during `updateAppWidget` or
`onLayout` is caught and shown as the error tile: that is the provider's bug, and it must not be the
launcher's crash.

**Leaked ids.** Every path that abandons an add releases its id, but ids also go missing in ways no
single call site sees - a page deleted with a widget on it, a stack torn down, a restore that
replaced the whole workspace, a kill between allocating an id and writing the row that names it. So
once per load the launcher asks the host what it still holds, compares that against every
`appWidgetId` in the database, and hands back the difference.

**Widgets that come back.** A widget id belongs to one install of one launcher, which is why a
backup carries the provider and the geometry but never the id - and why every widget in a restored
workspace arrives unbound. Those draw as a placeholder that says so and offers to repair itself:
tapping it runs the ordinary bind, permission prompt and configuration screen included, and the
widget returns to the cells it left at the size it was. A placeholder whose provider is *not*
installed says that instead and offers nothing, because there is nothing to offer.

**Settings after the fact.** A provider's configuration activity is normally run once, on the way
in, and is unreachable afterwards - which is where "I cannot change what my widget shows" comes
from. The long-press menu offers it again for any placed widget whose provider declares one.

**Resizing.** `WidgetResizeFrame` lives in the drag layer, not the page, so handles can be drawn
outside the widget's cell without being clipped. Resizing snaps to whole cells - a widget that can
sit between cells is a widget that can overlap an icon - and every candidate size is tested against
the page's occupancy before it is applied. The widget's own cells are released from that occupancy
while the frame is up, or every candidate overlapping where it already is would be rejected.

### Stacks

Several widgets in one block of the grid, one on top, changed with a swipe up or down. A stack is
to widgets what a folder is to icons, with the one difference that decides the design: a folder
opens and a stack does not. Four widgets cost four cells instead of sixteen, and the one you want is
a gesture away rather than a tap, a look, and a tap back.

Modelled the way a folder is: `WidgetStackInfo` is a row of its own that holds the cells, and its
children are ordinary widget rows whose `container` is that row's id and whose `rank` is their depth
in the pile. That reuses the loader, the orphan sweep and the page delete rather than adding a
second kind of nesting; `ItemType.WIDGET_STACK` is code 100, because everything below that belongs
to AOSP Launcher3 and it goes on adding to them.

Which widget is on top is part of the arrangement, so it is persisted - in `options`, the column
folders keep their flags in, and deliberately not in `rank`, which every drop onto the desktop
rewrites to zero and which would reset the pile each time it was moved.

**Why vertical.** Horizontal belongs to the workspace. Up and down are free on a page: the only
other vertical gesture is the flick that opens the drawer, and that has to travel much further than
the touch slop the stack claims at.

**Who wins a contested gesture.** Three parties want the same finger. A widget that can still
scroll in the direction being dragged keeps it - a list you have not finished scrolling is not a
stack you are trying to turn - and once it reaches its end the next swipe turns the pile, which is
the handover people already expect from nested scrolling. Otherwise the stack claims at the touch
slop, before the workspace has decided anything. Everything else, horizontal included, goes to the
workspace. Inside a stack a widget gives up one thing only: its long press, because the pile is what
gets dragged and two timers on one press would race to decide which. Its scrolling is left alone -
the stack asks about that on every swipe.

**Making and unmaking one.** Dropping a widget on another widget stacks the pair - the same gesture
that makes a folder out of two icons - and dropping one on a stack joins it. The stack's own menu
adds a widget straight from the picker, which is the route that still works when the home screen has
no free cells left. Taking the top one out puts it back on the page, and a stack with one widget left
collapses into a plain widget: a pile of one draws an indicator it does not need and eats swipes that
cannot go anywhere.

**Size.** A stack is one block, so every child is shown at the stack's span and is resized with it.
Its resize limits are the strictest of its children's - as small as the largest minimum, as large as
the smallest maximum - and a widget that could not render the stack's size is refused at the drop
rather than added broken. Eight is the ceiling: every widget in a stack is live whether or not it is
showing, because the system pushes its provider's updates at the host either way.

**No migration was needed.** `favorites` has carried `appWidgetId`, `appWidgetProvider`, `spanX`,
`spanY`, `restored` and `options` since Phase 1, and a stack is a row with a container id exactly as
a folder is. Widgets stored before hosting existed come back bound.

`Workspace.viewInfo` falls back to the view's tag. A host view belongs to the provider and cannot be
given a typed field, so it carries its `ItemInfo` as a tag - which is what AOSP does for every
workspace child. Without it `findViewFor` cannot locate a widget, and resize, remove and move all
silently do nothing.

## The app drawer

`AllAppsContainerView` owns the search field, the filter and the background; the list is whichever
`AppDrawerView` the current mode selects. Three things about it are the user's choice rather than
the launcher's, and all three are settings because each of them is genuinely worse for somebody.

**Which end the search field is at.** Moving it reorders the three children rather than rebuilding
the panel: detaching the list would throw away its scroll position and every view it has recycled.
The page dots stay next to the list in both arrangements - they say where in the list you are, and
a row of dots stranded below the search field reads as decoration on the field. The keyboard is
handled by the activity's `adjustPan`, which is what pans a focused field at the bottom of a window
clear of the IME.

**Whether the keyboard opens with the drawer.** Off by default: the drawer is opened to look at
apps at least as often as to search, and an unasked keyboard covers half of what was just opened.
The request is posted rather than made immediately, because a view that is still animating in from
`GONE` is one the input method manager will quietly refuse.

**Whether it reopens where it was left.** Off by default, because a drawer that always opens at the
top is a drawer where the same apps are always under the same part of the screen. On, `open()`
skips `resetScroll`. Two paths conspire against this and both are handled: `close()` only clears the
search field when something was actually typed - setting the text notifies the watcher either way,
and the filter it re-runs would send the list back to the top - and `AppDrawerHorizontal.submitApps`
now restores the page it was on instead of always page 0, leaving the start to `resetScroll` for
the callers that actually want it. Any filter change still resets: the list on screen is not the
list the remembered position was measured against.

## Simple mode

One switch in settings turns Eden back into what it was at v0.2.0. The scope was taken from the
0.2.0 tag rather than from memory: a home screen with pages, both drawer styles with search, drag
and drop including stacking two icons into a folder, and settings for the drawer, the grid and icon
sizing. Wallpapers are kept on top of that, because a launcher that cannot set your wallpaper is
not simpler, only less useful.

| Turned off | Why |
|------------|-----|
| Icon packs and per-app icons | v0.4.0. `IconCache.refreshIconSources` stops consulting either. |
| The long-press icon menu | v0.3.0. A long press arms a drag and nothing else, as it did in 0.2.0. Remove and Uninstall are still on the drop targets, where 0.2.0 had them. |
| Widgets | The overview button is hidden. Hosting itself stays on, so a widget placed earlier still renders rather than turning into a placeholder. |

Nothing is deleted. Renames, custom icon files, the chosen pack and placed widgets all stay in the
database and the preferences, and turning the switch off brings them back untouched - it changes
what the launcher offers, never what it has stored.

Two things it deliberately does **not** undo, because they are fixes rather than features: the
drawer still notices apps being installed and removed, and the log is still reachable. A user who
wants a simpler launcher has not asked for a more broken one, or for one they cannot file a bug
against.

The toggle is the first row on the settings screen and is never hidden by itself. A switch that
removes settings has to remain visible in the state it creates.

## Orientation

The home activity is `nosensor`, and the setting is applied again in `onCreate` and `onResume` with
`setRequestedOrientation` so it takes effect on the way back from settings rather than the next time
the process is killed.

`nosensor` rather than `portrait` on purpose: it pins the activity to the device's *natural*
orientation, which is portrait on a phone and landscape on some tablets and foldables. Hard-coding
portrait would force those to be sideways-wrong. None of the landscape code is gone - the setting
can turn rotation back on, and a tablet's natural orientation may be landscape to begin with, so
`DeviceProfile.profileFor(isLandscape)` still matters. `configChanges` already covers orientation,
so nothing recreates either way.

The wallpaper crop screen is locked too, for a different reason: it frames a picture against the
real shape of the screen, so rotating mid-crop would change the target aspect underneath the user.

One caveat worth knowing rather than fighting: from Android 16, fixed orientations are ignored on
large screens - `setRequestedOrientation` included. On a tablet or an unfolded foldable the setting
therefore does nothing, and the launcher rotates. That is the platform's call and the right one;
the lock is aimed at phones, which is where an accidental rotation is annoying.

## Destroying things

Two rules, both from the project's fourth rule - never lose the user's arrangement.

Deleting a page asks first, and says how many items are about to go with it, because "delete this
page" and "delete these nine things" are different decisions. An empty page goes without asking;
there is nothing to lose and the prompt would be noise.

Deleting a page also has to sweep the *contents* of any folder on it. A folder's own row sits on
the page and is caught by the page delete, but its children are not - their container is the
folder's id, not the desktop - so they used to survive the page that held them and become rows
pointing at a parent that does not exist. `LauncherDao.deleteOrphans` runs on every load and clears
any that already exist: self-healing rather than a migration, because there is no schema change to
hang a `Migration` off and databases in the wild already contain them.

## Icons

Three sources, most specific first:

1. an icon the user picked for that one app (`icons/CustomIcons`),
2. the icon pack in force (`icons/IconPack`),
3. what the app ships.

That order is deliberate. Choosing an icon for one app is a more specific instruction than choosing
a pack for all of them, so switching packs must not silently undo it.

A fourth thing then happens to whatever those three produced: it is cut to the user's chosen shape.

### Shapes

`icons/IconShape` is twelve outlines plus `SYSTEM`, which is the default and means "cut nothing".
Each writes itself into a `Path` filling a square. `icons/IconShaper` applies one.

The shape is the *last* step, never a competing one. A pack decides what an app's icon looks like;
a shape decides what shape it comes out. Both apply, in that order, which is what lets someone keep
a pack they like and still have hexagons.

Three treatments, picked by looking at what arrived:

| Artwork | Treatment | Why |
|---------|-----------|-----|
| An adaptive icon | Its two layers are drawn flat, then measured and treated as one of the two rows below | Adaptive icons exist to be masked to an arbitrary shape, so the layers are drawn with the platform's own mask never running. What cannot be assumed is that they fill the box - see below. |
| Anything that fills its box - a pack's icon, a composition, most legacy icons | Scaled to cover, then clipped | Whatever falls outside is lost. That is the honest cost of asking for a circle when the author drew a square. |
| A glyph floating on transparency | A plate in the shape, tinted from the artwork's own dominant hue, with the glyph set into it | Clipping a small mark in the middle of an empty square to a circle produces the mark and no visible shape at all. |

Two details that are not obvious and are load-bearing:

- **The drawable is fetched a second time.** `IconCache.resolveThemed` asks `LauncherActivityInfo`
  for the icon again rather than reusing the bitmap the first pass cached. It has to: an adaptive
  icon rasterised by the first pass has already had *the device's* mask applied, and a squircle
  recovered from a circle is a circle. One package-manager call per app, on the second pass, once.
- **Masked with `DST_IN`, not `clipPath`.** A clip path on a software canvas has no antialiasing,
  and a circle with a staircase for an edge is worse than no shape. The output bitmap is its own
  layer, so no `saveLayer` is needed.
- **An adaptive icon is measured, not trusted.** A legal adaptive icon has a background that reaches
  every edge, and until v0.6.2 `shapeAdaptive` masked the layers on that assumption. Several OEM
  builds - Motorola's Android 16 among them - hand a launcher an icon that has already been masked
  to the device's own shape and then wrapped back into an adaptive one: transparent background, and
  a foreground holding a circle that stops well inside the safe zone. Every outline here
  circumscribes that circle, so the mask removed nothing and changing the shape did nothing at all
  on those phones. The layers are now rendered flat and handed to the same analysis as any other
  artwork, which plates them when they do not fill their box.
- **A shape change explains itself in the log.** `IconShaper.describeNextIcons`, called from
  `IconCache.refreshIconSources`, logs what the shaper was actually handed for the first three
  icons of the next pass: the drawable class, whether it is adaptive, its two layers, and the
  content bounds that decided the treatment. Bounded, because the alternative is a line per app per
  rebind and the log is 48 hours long. An icon that comes out looking like the one before it leaves
  no other trace.

Shapes ride the second pass (below) rather than the load, so a cold start costs nothing for them
and they arrive in waves like a pack does. `hasThemedSource` is what gates that, and it now counts
a shape as a source. Simple mode cuts no shapes - it is v0.2.0, which had none. `FolderIcon` reads
the shape too: a home screen of circles with rounded-rectangle folders on it reads as an oversight
rather than a choice.

### Two passes

Sources 1 and 2 are resolved *after* the home screen is on screen, not during the load.

The reason is cost. Asking the package manager for an app's own icon and rasterising it is cheap.
Resolving a themed one is not: the first call parses the pack's `appfilter.xml`, and every app the
pack has no entry for gets a background, a mask and an overlay composed under and over it. Charged
per app across a phone's whole app list, that is seconds of work in front of a blank launcher -
which is what a launcher with a pack selected used to look like it was hanging on.

So `IconCache` keeps two caches and offers two calls:

| Call | Cost | Who |
|------|------|-----|
| `getIcon` | One package-manager icon, rasterised. Never opens a pack. | `LauncherModel.load`, on the critical path. |
| `themedIcon` | Custom icon, then pack entry, then composition. Returns null when nothing applies. | `Launcher.applyIconTheme`, after the bind. |

`Launcher.applyIconTheme` walks the placed icons first - they are the ones being looked at - then
the drawer, twelve at a time, redrawing between batches so the pack visibly arrives in waves. It
does not start at all unless `IconCache.hasThemedSource` says a pack or a custom icon is actually
in force, so the common case pays nothing for the machinery.

The base cache entry for a component is dropped once its themed icon exists: the app's own icon was
only wanted as the layer underneath, and holding both would double what a themed home screen costs.
Changing the pack evicts only the themed cache - an app's own icon does not change because the user
picked a different pack.

Icon packs have no Android API and never did - they are a convention that grew out of ADW, in which
an ordinary APK ships `appfilter.xml` mapping `ComponentInfo{pkg/cls}` to a drawable name. A pack
advertises itself with whichever theme action its author's launcher used, so `icons/IconPacks`
queries all seven common ones; checking only one loses a third of the packs a user has installed.
`appfilter.xml` lives in assets, `res/xml`, or `res/raw` depending on the pack, and all three are
tried.

| Class | Job |
|-------|-----|
| `IconPack` | Parses `appfilter.xml` and `drawable.xml` for one pack, resolves a component to a drawable, and composes an unthemed icon from the pack's background, mask, overlay, and scale. |
| `IconPacks` | Finds installed packs across every theme action. |
| `IconPickerActivity` | The whole pack in a searchable grid, for apps a pack has no entry for. Decodes lazily as rows scroll; a pack has thousands of icons and only twenty are on screen. |
| `CustomIcons` | Per-app PNGs under `filesDir/icons`, one file per component. Not in preferences - preferences are read whole at startup, and a dozen base64 icons in there is a dozen blobs charged against every cold start. |

Icons are resolved during the load, so changing the pack changes nothing until something reloads.
`Launcher.onResume` compares the stored pack against the one it last bound with and reloads when
they differ - the same shape as the grid-signature check next to it, but a rebind rather than a
recreate, because the views are still the right shape and only hold the wrong bitmaps.

## Packages appearing and disappearing

A `LauncherApps.Callback` registered for the whole life of the activity, not between `onStart` and
`onStop`: an app is almost always installed with the launcher in the background, which is exactly
the case that has to work.

Events arrive in bursts, so the rebind is coalesced behind a short delay and waits out any drag in
progress rather than deleting the view under a finger. Genuine removals accumulate across the burst
and go to `LauncherModel.purgePackages`, which deletes the rows - including folder children, and
the folder itself if that empties it. A cold load takes the opposite view and only flags a missing
item disabled, because there the cause might be an unmounted card; here `LauncherApps` has said the
package was removed, and it does not say that for an update or an unmount.

## The log

`util/EdenLog` writes to `filesDir/eden.log` and keeps 48 hours. Everything also goes to logcat, so
`adb logcat` behaves exactly as before.

It exists because logcat is not good enough for this app in particular: a launcher crash takes the
home screen with it, the user's next move is a reboot, and a reboot is what clears logcat. Lines go
into an unbounded channel drained by one coroutine, so no caller ever waits on the filesystem;
losing the last few lines to a hard kill is the accepted cost. The crash handler is the exception
and writes directly, because the process is about to end and the drain will not get another turn.

Pruning happens on startup and whenever the file passes 512 KB: lines older than the window are
dropped, continuation lines (stack frames, which carry no timestamp) share the fate of the line
that introduced them, and the file is rewritten. `settings/LogViewerActivity` shows it, with Share
through a `FileProvider` scoped to that one directory.

## Wallpapers

`wallpaper/` holds a small GL engine and eleven renderers. The split that matters is
`WallpaperRenderer`: it knows how to draw and nothing else, so the same code serves both the live
`GLWallpaperService` and `WallpaperStillRenderer`, which draws one frame into a pbuffer and reads
it back as a bitmap for the picker. The preview is therefore the wallpaper, not a screenshot of it
that can go stale.

| Class | Job |
|-------|-----|
| `GLWallpaperService` | EGL, a render thread, 30fps pacing, visibility gating, and the scene clock (scaled by the speed setting). |
| `EglContextHolder` | EGL14 for both window and pbuffer surfaces. Written directly because `GLSurfaceView` insists on being a `View`, and a wallpaper engine has a `SurfaceHolder` and no hierarchy to put one in. |
| `SoftPointField` | Batched soft round points, for particles and stars. |
| `SoftQuadField` | The same, as quads. Exists because `gl_PointSize` is capped by the driver - commonly 63 or 255 px - and oversized point sprites are **silently clamped**. Anything larger than a dot must be a quad. |
| `WallpaperStillRenderer` | Offscreen still frames for the picker. |

Sizes and speeds in every renderer are fractions of the surface, never pixel counts. That keeps a
wallpaper looking the same at 720p and 1440p, and it is what makes the picker's small still an
honest preview rather than the same scene at a quarter scale.

Nothing draws while hidden. The launcher additionally sends `COMMAND_PAUSE` through
`WallpaperManager.sendWallpaperCommand` when an opaque app drawer covers the wallpaper, because the
system still considers it visible there.

### Devices with no live wallpaper screen

Setting a live wallpaper is not something an app does. `WallpaperManager.setWallpaperComponent` is
guarded by a signature permission, so the only route is to ask the system to show its own
confirmation - `ACTION_CHANGE_LIVE_WALLPAPER` for one named wallpaper, or the older
`ACTION_LIVE_WALLPAPER_CHOOSER` for a list of all of them. Both are declared in `<queries>`, because
package visibility applies to resolving them like anything else.

Some builds ship neither. Motorola's Android 16 has a wallpaper app that handles pictures and
nothing else, and the intent every launcher has used since Android 4.1 resolves to nothing: an
`ActivityNotFoundException` per tap, which the picker used to answer with "could not set that
wallpaper" - a message that reads like a bug in Eden and invites a second attempt.

`WallpaperPickerActivity.liveWallpapersSupported` resolves both actions once, when the screen is
built, and the answer changes what the screen says rather than what one tap does: the live and video
sections carry a line explaining that this phone cannot turn them on, and a tap gets a dialog that
says the same thing and opens the picture picker, which is the one thing here that still works.
There is no fallback to write and none to look for.

## Static wallpapers

`wallpaper/picker/CropView` frames a picture against the real display shape: the bright rectangle is
what will be visible, the dimmed surround is what gets cut, and the constraint in `constrain()`
makes it impossible to pan far enough to leave a blank edge. Nothing allocates in its `onDraw` or
`onTouchEvent` - the matrix and its scratch array are fields, and the scrim is four `drawRect` calls
rather than a clip-difference that would cost a `saveLayer` every frame of a drag.

Two resolutions on purpose. The preview is downsampled to at most 2048px so panning stays smooth on
a two-core phone; the applied wallpaper is decoded again through `BitmapRegionDecoder` at the framed
region, so zooming in does not throw away the original's detail. `CropView.normalisedCrop` returns
fractions rather than pixels, which is what decouples the two.

Eden keeps its own copy of the source picture for the same reason it keeps the transcoded video: a
content URI belongs to another app and can be revoked. It also keeps a tile-sized copy of what it
applied, because reading the wallpaper back out of the system needs a storage permission a launcher
has no business holding.

## Video wallpapers

`media/` converts a picked video with `MediaCodec`: extractor to decoder, decoder to an external
texture, texture drawn into the encoder's input surface (which is where the scale to 1080p happens
for free), encoder to muxer. Frames are dropped before the GPU touches them, so reducing to 24fps
makes the conversion faster as well as smaller. Audio is copied through the muxer untouched.

`VideoTranscoder` is an interface with one implementation on purpose. Bundling FFmpeg would have
meant a GPL relicence of an MIT app, tens of megabytes on a launcher aimed at 4 GB phones, and a
dependency retired in January 2025. A second backend can be added behind the interface later.

## Performance notes

- Nothing allocates in `onTouchEvent`, `onLayout`, `dispatchDraw`, or `computeScroll`. Scratch
  `IntArray`s and `Rect`s are fields.
- `PagedView.dispatchDraw` draws only the pages intersecting the viewport.
- `IconCache` is an `LruCache` bounded in bytes, not entries. No disk cache: on a 4 GB phone it
  mostly buys a slower cold start and a second thing to keep coherent.
- The vertical drawer recycles. The horizontal one rebuilds pages on filter change - that cost is
  paid on a keystroke, not on a fling.

## What is deliberately missing

| Missing | Why |
|---------|-----|
| Work-profile widgets | Binding one goes through a different call; it belongs with proper multi-profile support rather than smuggled into the picker. |
| Search in the widget picker | Fine at sixty widgets, not at three hundred. |
| Notification dots | The AOSP 7 to 8 feature still to be mined. Nothing is built for it. |
| Overview zoom and free-scroll in `PagedView` | AOSP's oversized 2x measurement existed to support them; dropping both made measurement simpler and cheaper. |
| Icon disk cache, app prediction, Quickstep | Later phases, or never. |

## Testing on a device

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n app.auriel.edenlauncher/.Launcher
adb logcat -s Launcher:D Workspace:D LauncherModel:W
```

Synthetic long presses need `adb shell input swipe X Y X Y 1200` - `input motionevent DOWN`/`UP`
does not trip the long-press timer.

## Reference sources

`.ref/aosp7`, `.ref/aosp8`, `.ref/aosp10` are unmodified AOSP Launcher3 checkouts, gitignored and
never compiled. Ported files name their origin in their KDoc; `NOTICE` lists them.

The wallpapers are not in those checkouts and never were - Launcher3 is one repository and the
wallpapers are nine others (`packages/wallpapers/*`). `Basic` on `main` retains only polarclock and
walkaround; fall, galaxy, grass, and nexus survive on `gingerbread-release`. All of them are
RenderScript, which the Android Gradle plugin removed in 9.0, so they are reference material for
composition and palette only and cannot be built.
