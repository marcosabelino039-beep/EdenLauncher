# Eden 0.7.0

Notification dots, in sixteen colours. Google's widgets rendering the way their authors drew them.
And Eden set in a typeface it chose.

`versionCode 10`, `minSdk 29`, `targetSdk 36`. No native code, no network code.

---

## Notification dots

A dot on an app that has something waiting. Settings, Notification dots.

Off until you turn it on, and it has to be: turning it on is a request for notification access,
which is the widest permission a launcher can hold and the only one Android makes you grant on a
screen of its own. A launcher that arrived already asking for it would be a launcher asking to read
every message on your phone before it had done anything for you.

**What Eden does with that access is the short version of this release.** Android publishes no feed
of "which apps have something waiting". There is no narrower API and there never has been - to draw
a dot you have to be handed every notification on the phone, in full, including the text. So the
reduction happens immediately, inside one file: a notification goes in, a package name comes out,
and nothing else leaves. Nothing is written to disk. Nothing is written to the log. There is no
network code anywhere in Eden to send it with. It is one file and you can read it.

Turning dots back off does not leave the service running and idle - it declines the binding, so the
system stops waking Eden to hand it notifications at all.

**Three switches have to agree, and two of them are not Eden's.** Your setting here, the access
grant, and Android's own phone-wide notification dots switch. Per notification, the channel's own
"show a dot" setting decides too - so an app you have told the system not to badge does not get
badged here either. If any of them is off, the settings screen says which one rather than leaving
you with a switch that is on and a home screen that never changes.

**Sixteen colours, no colour wheel.** A wheel is a decision with no wrong answers and therefore no
answer, and what it produces is a dot that nearly matches something else. Sixteen is a palette:
white, black, grey, red, maroon, orange, yellow, lime, green, teal, cyan, blue, navy, purple,
magenta, brown. Every one of them is legible on top of an app icon, which is the only thing a dot's
colour has to be. The ring behind it flips from dark to light depending on which you picked, so
black and white both work.

The dot sits on the edge of whichever icon shape you chose rather than in the corner of a square -
on the circle if your icons are circles, on the flat of a hexagon if they are hexagons - and moves
to the other side in a right-to-left layout.

---

## Google's widgets

Reported by somebody whose Google widget came out looking wrong. It was three things, and all of
them were Eden's end of a contract rather than anything the widget did.

**Corners cut twice.** A widget that rounds its own corners says so, in a way Android 12 made
official. Eden noticed, and then rounded it again anyway - the launcher's radius applied to the
outer edge, cutting through whatever the widget had drawn inside its own inset background. On a
background shaped like a pill, which is what the Google search widget ships, that is a widget with
its ends sliced off. A widget that has rounded itself is now left exactly alone.

**Widgets told the wrong size.** A widget is bound before it is asked for its first views, and Eden
was binding without telling providers how big they were about to be. They fall back to the size in
their manifest, which is a minimum rather than a measurement, so anything that picks a layout by
size picked the wrong one - and the frame you see at the moment you add a widget, and judge it by,
was the wrong frame. Providers are now given the exact size, both orientations at once.

**Widgets that never turned over.** A widget with a stack or a flipper in it does not animate
itself. It names the view and expects the launcher to advance it on a timer, and nothing in Android
does this for a launcher that has not implemented it - so every such widget sat on its first page
forever. That is not obviously a bug; it just looks like a widget that stopped working. They turn
over now, twenty seconds apart, staggered so a page full of them does not stutter.

Two more, quieter: a widget whose saved state no longer matches its views can no longer take the
launcher down on a rotation, and widget layouts are inflated off the main thread on Android 12 and
up.

---

## Clock icons stopped resetting the home screen

A live clock icon is redrawn once a minute for as long as you are looking at the home screen. Eden
was redrawing *everything* to do it - every icon on every page, and regenerating every folder tile,
once a minute, to move one minute hand.

Now it redraws the clocks. Nothing else on the screen changed, so nothing else is touched.

---

## Open Sans, and JetBrains Mono for the log

Eden was using whatever the phone called its default font, which on any two phones is two different
fonts. It now brings its own: Open Sans for everything that is words, in two real weights rather
than one weight and a synthesised bold.

The log gets JetBrains Mono. A log is a column of timestamps, tags and stack traces - things that
line up when the characters are the same width and stop lining up when they are not. It is also
what you are reading at the moment something has gone wrong and you are about to copy a line of it
into an email, which is the moment a 1 that looks like an l costs somebody an afternoon.

Both fonts are open licensed and shipped unmodified; see NOTICE. They are why the APK went from
872 KB to about 1.2 MB, and they are essentially all of the difference.

---

## Notes

- No database migration.
- Notification access is requested by nothing except turning the dots setting on. If you never turn
  it on, the listener is never bound and never runs.
- The dot colour is stored by name rather than as a colour value, so adjusting one of the sixteen
  in a later release adjusts it for everyone who picked it.
- Screenshots in the README are refreshed for the home screen, drawer, drawer search, icon settings
  and the new dot settings. The rest are still from 0.4.0.

Bugs, ideas, disagreements: **debadityamalakar@gmail.com**

Attach the log if something went wrong - Settings, Troubleshooting, Open the log, Share. It stays
on the device until you do.
