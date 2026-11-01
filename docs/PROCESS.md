# How it was made

The full build log of the Iron Man home screen, from a stock TV to the version in the reel, including the
mistakes. Everything was done with [Claude Code](https://claude.com/claude-code) on a Mac, over Wi-Fi, on a real
TV, checking every step with screenshots pulled from the TV.

**The TV:** SPPL 4K GSmartTV FF (sold as Thomson; Skyworth SW6H board, MediaTek), Android TV 14 with the Google TV
home, about 1.9 GB of RAM.

**The base:** [cleanyourtv](https://github.com/Gathin23/cleanyourtv), an open-source project that debloats Android
TVs and replaces the home screen with a clean, Apple TV-style launcher. Its `PROMPT.md` was the starting point, and
it already had a tested debloat list for this exact model.

## 1. Connecting to the TV

- Developer options on (press OK on *Build* 7 times), then USB debugging and Network debugging on.
- No `adb` binary on the Mac. cleanyourtv's `tools/tv.py` talks ADB through the Python `adb-shell` package
  instead, which also sidesteps macOS's Local Network privacy blocking the stock `adb`.
- First connection: `TV_HOST=<tv ip> tools/tv.py --probe` printed the model, Android 14, build UTT1.250517.001.
- Checked the TV was on Wi-Fi, not a USB Ethernet adapter. Some TVs cut USB power while debugging is on, which
  would drop the connection mid-session.

## 2. Backup, then debloat

- Saved the package list, the disabled list, `getprop` and all settings before touching anything.
- Applied cleanyourtv's tested list for this model (`examples/thomson-sppl-4k-sw6h/debloat.txt`) with
  `tools/disable.sh`: Skyworth telemetry, recommendation rows, retail demo players, after-sale push, Android ad
  services, Play auto-installs. Everything is *disabled*, never uninstalled, so it's fully reversible.
- Kept on purpose: Live TV and HDMI inputs, remote pairing, keyboard, Play Store, Assistant, Chromecast,
  Miracast, firmware updates, and the streaming apps actually used.
- The script's safety checks did their job. It skipped the stock launcher (it still owned Home, and nothing
  else could take over yet), and it checked that the setup wizard didn't own the saved Wi-Fi network before
  disabling it.
- Waited 15 seconds (Android writes package state about 10 seconds after a change), rebooted, and compared
  `pm list packages -d` with the list: an exact match, so this firmware re-enables nothing at boot.

## 3. The launcher

- Built cleanyourtv's launcher (plain Java, Android SDK 34) with a portable JDK 17 and the Android command-line
  tools, no Homebrew needed.
- `launcher/build-and-install.sh` installed it, started it, and only then disabled the stock Google TV home and
  set the new one as Home. It confirms with `dumpsys activity activities | grep mFocusedApp` and rolls back if Home
  lands anywhere else.
- This TV's remote sends its Settings button as keycode 83, which only the old launcher handled. Mapping it needs
  `settings_key_keycodes: [83]` plus the `HOME_KEY_SERVICE=1` helper (that's `devices/thomson-sppl-4k-sw6h.json`).

## 4. First round of styling, and what was wrong with it

The first brief: dark wallpapers, white logo-only icons, a spotlight glow, a clock with rolling seconds, and a
Sharingan eye that tracks the selected app and blinks.

- **Preview first.** An interactive mockup in the browser to agree on the look before writing Android code.
- **Rolling seconds** (`RollingSecondsView`): a custom view next to the normal clock, ticking on real second
  boundaries so it never drifts. Two bugs found on the TV:
  - The digits sat too far apart. The width had a 25% padding fudge; it now measures exactly "00" and uses the
    clock's own letter spacing.
  - Mid-roll, digits showed above and below the clock line. The roll used the font's full line height; it now
    clips to the digits' actual height (`getTextBounds`).
- **White logo icons**: built as vector tiles, then dropped. On the TV they read as generic, YouTube and Prime
  looked alike, and the apps' real colourful logos looked better. The tiles now use each app's own banner.
- **The date was cut off** ("SUNDAY…") once the seconds and the eye took space in the header. It now sits on its
  own line (`date_below_clock`).
- **Spotlight**: the launcher already had per-app focus glow (`app_glow`); it just needed switching on.

## 5. The Sharingan, version two

Feedback: the eye was too small, tucked next to the clock. It needed to be big and centred, so it reads as
watching where you go.

- Moved to the centre between the header and the dock, about 280 dp wide, fading out with the dock title when
  you scroll into the grid.
- It follows *any* focus change (dock, grid, top buttons) through a global focus listener. The iris moves toward
  the focused item's position on screen, horizontally and a little vertically, and blinks when it looks
  somewhere new.
- The tomoe (the three commas) are built like half a yin-yang: half a disc, plus a head, minus a cut-out
  (`Path.op`). The first attempt drew a circle and a tail in one path, and their opposite winding punched red
  holes through the heads.
- A HUD ring, scan lines and a red glow for the futuristic look. Frosted-glass dock, round glass buttons and a
  halo focus ring came from existing theme keys.

## 6. Iron Man

The idea, from a reel where a character's Sharingan eyes follow the cursor: instead of an eye, Iron Man, with
the light from his hand following the app you select.

**Picking the wallpaper.** What makes a picture work for this:
- Raised hands or open palms in the upper half of the screen, so the beam has a clear path down.
- The figure not covering the clock in the top-left.
- A plain area at the bottom, where the dock sits.
- 16:9 and high resolution.

The chosen one (8900×5004, both palms glowing, light grey background) is linked in `ironman/wallpaper.json`.

**Darkening it** (`ironman/prep_wallpaper.py`): the light grey made the white clock unreadable. Rather than
darkening everything, the script keeps saturated pixels (red and gold armour, blue repulsors) and pushes
near-grey ones down to charcoal through a blurred saturation mask, then adds a vignette.

**Finding the palms.** An automatic "brightest blue spot" search latched onto shiny highlights on the helmet and a
finger, so the palms were marked by hand and checked with zoomed crosshair crops. They're stored as fractions of
the screen: `0.3813,0.1898;0.6083,0.2389`. For other pictures there's `ironman/calibrate.html`.

**The beam** (`RepulsorBeamView`, a full-screen layer under the tiles):
- It fires from whichever palm is horizontally nearer the focused item. Netflix and Prime get the left hand,
  JioHotstar the right, and the top-right buttons the right too.
- It slides when focus moves, and when it switches hands it fades out and back in at the new palm, a quick flash.
- Every move gives it a short "charge": brighter, thicker, a bigger palm flare. Both palms pulse, and sparks run
  down the beam.
- It ends behind the tile it points at, with an impact halo spilling around the tile's edges.
- **Performance:** the first version used `BlurMaskFilter`, which forces software drawing: the CPU would
  redraw a 1920×1080 layer 60 times a second, far too heavy for a TV. The glow is now stacked translucent strokes
  on the GPU. The gradients are built once and moved into place with a matrix, so a frame allocates nothing, and
  everything stops when Home isn't on screen.

**One bug:** after switching to the single Iron Man image, the background was plain black. The launcher only
reads the `wallpapers/` folder while `wallpaper_rotation_minutes` is set, even for one picture.

## 7. The arc reactor boot intro

`ArcReactorBootView`, about 2.6 seconds on black. The housing traces itself, ten segments light one by one with a
two-layer blue glow, the core charges and flares, then a shockwave rolls to the corners as Home fades in.

- It plays once per power-on, tracked with `Settings.Global.BOOT_COUNT` (the same signal cleanyourtv uses for
  "open on start"), so it never replays when the app merely restarts.
- If Home is interrupted mid-intro, the overlay still hides itself, so a black screen can never get stuck over
  Home.
- **Testing it:**
  - Screenshots take about 1.4 seconds each on this TV, too slow for a 2.6-second animation. A replay
    command (`--ez com.gathin.tvhome.REPLAY_BOOT_INTRO true`) plus `screenrecord` and frame extraction showed
    every stage.
  - On a real restart, ADB only comes back after Home is up, so the proof is a log line ("arc reactor intro
    finished"), 2.6 seconds after Home's window appeared.
  - The TV's log only holds the last half-minute or so, so it has to be read straight away.

## 8. Packaging it for everyone

- The skin's settings (`repulsor_beam`, `repulsor_origins`, `clock_seconds`, `sharingan`) can come from a theme's
  `config.json`, not just from the compiled `config.xml`. One prebuilt APK then works on every TV, and the
  wallpaper brings its own palm positions.
- `install.sh` / `install.ps1`:
  1. Set up the Python tools and connect to the TV.
  2. Check the Android version.
  3. Download the APK from this repo's Releases.
  4. Download and darken the wallpaper (on your own computer, so no Marvel art lives in the repo).
  5. Build the theme, merging in the TV's `devices/` file.
  6. Install with cleanyourtv's script (keeping its rollback safety) and push the theme.
- `uninstall.sh` / `uninstall.ps1` bring the stock launcher back and check it owns Home before removing this one.

## 9. Version 1.1: a real beam, and fire and snow behind him

Feedback on 1.0: the beam looked like a lightsaber, one even line. And could the background have fire, snow
and so on?

**Preview first.** A browser preview built from real frames of the TV: the wallpaper, the tiles and the clock as
separate layers, with the old and new beam and every background switchable, and the selection moving like the
remote. The first try at the new beam (three strands twisting round the axis) read as lightning, not light; the
version that went in is a cone.

**The beam** (`RepulsorBeamView`, rewritten):
- A cone of light, about 22 dp wide at the palm and 150 dp at the app, in five layers from a wide faint blue
  to a narrow near-white. Each layer fades along its length: brightest at the hand, dimmer mid-way, picking up
  at the target.
- Streaks of light flowing down it at different depths, three repulsor rings riding along with a glow, and
  sparks inside.
- Each move fires a shot: a bright bolt racing from the palm with a ring bursting off the hand, then a ripple
  where it lands. The palms get a lens streak.

**The backgrounds** (`AmbientFxView`, new): `fire` (embers rising on a breathing glow from below, with soft
tongues of flame), `snow` (three depths), `fireice` (both) and `storm` (slanting rain, and lightning every few
seconds that lights the sky behind him). `cycle` plays each in turn, cross-fading every 3 minutes
(`ambient_fx_minutes`) and moving on each time you go Home.

**Behind him, not over him.** `prep_wallpaper.py --cutout` cuts Iron Man out of the finished wallpaper: pixels
saturated enough to be armour, small gaps closed, then everything the grey background can't reach from the
picture's edges filled in (his grey and dark parts). The launcher draws the particles, then the cut-out on top
(`foreground.png`, placed by `foreground_origin`), so the effects pass behind him; a few near flakes and
embers still cross in front for depth. Like the wallpaper, the cut-out is made on your own computer, so no
Marvel art is in this repo. `wallpaper_true_color` keeps the wallpaper in 32-bit colour, so the cut-out's edges
match it exactly.

**Making it cheap enough for a TV.** The first build kept the launcher's render thread at about 75% of a CPU
core while Home was on screen:
- Every frame, Android redraws all of Home, not just the parts that moved. So the steady motion (particles,
  the beam's flow) now redraws at 30 frames a second, with both layers on the same frames
  (`AmbientFxView.ambientFrame`). The beam's moves still run at 60.
- On this TV's CPU, each separate draw call costs, so the sparks, streaks, embers, snowflakes and raindrops
  are drawn in batches by brightness and depth (`drawPoints`, `drawLines`): about 30 calls instead of 300.
- The cone's shapes are kept between frames and only rebuilt when the beam moves.
- The result: about 53% of one core of four, only while Home is showing. What's left is mostly redrawing
  Home itself, plus the rolling clock seconds, which still animate at full speed.

**Two bugs on the way:**
- The first "every other frame" rule counted frames by number. When a frame ran late, two in a row passed,
  giving 41 frames a second instead of 30. It now uses the time since the last redraw.
- Soft glow bitmaps around each snowflake came out as hard rings once batched as points. The snow lost its
  halo; the embers got three glow steps, which reads as fire seen out of focus.

## 10. Version 1.2: a hologram greeting and a spoken welcome

The ask: the left hand follows the selection, the right hand shines into the centre with WELCOME MR. ASHU under
the light, and a voice at the arc reactor start-up.

**One hand each.** With `repulsor_greeting` set, the first palm fires at whatever has focus, everywhere, and the
second palm projects the text. The trade-off, accepted: for apps on the right, the beam now crosses in front of
him.

**Making the beam and the words one thing.** The first preview had a short beam ending in a ring, with the words
underneath, and it looked like two separate things. What went in is a projector: a fan of light from the palm
out to the whole text block, streaks running from the hand into the letters, a bright plate under the words with
corner ticks, and the words as a hologram (a faint cyan ghost, a glow, scan lines drifting down, an occasional
flicker). It sits in the open space right of his body, the one clear area that covers neither him nor the clock,
the buttons or the apps. The font is Audiowide (SIL Open Font License), picked from four in the preview over
Orbitron, Rajdhani and Exo 2.

**The voice** (`BootGreeting`):
- Tried first: the Mac's built-in voices. A British male voice with a "suit" treatment (a little deeper, a
  tighter sound, a short echo), then Indian English voices, then the Mac's only Hindi voice. None sounded
  natural; they're older, robotic-style voices. JARVIS's real voice was never an option: it's an actor's voice.
- What worked: a neural voice made on ElevenLabs ("Angad"), saying "राम राम जी! आज क्या देखना पसंद करेंगे?".
  The clip only had its loudness evened out, to keep it natural.
- The theme carries it as `greeting.mp3`, and it plays 0.45 s into the intro, as the reactor's segments light.
  Without a file, the TV's own text-to-speech reads `greeting_speech` in `greeting_speech_lang`. Every follower's
  TV has that, so it's the default: the Hindi line above.
- Right after a boot the theme folder can still be unreadable (shared storage not mounted yet), so the launcher
  keeps a copy of the clip in its own storage and plays that.

## What changed in the launcher

| File | What it does |
|---|---|
| `RepulsorBeamView.java` | The beam, the palm flares, the shot, the hand switching, the hologram greeting |
| `BootGreeting.java` | The spoken welcome with the arc reactor intro: the theme's clip, or the TV's own voice |
| `AmbientFxView.java` | Fire, snow, fire & ice and storm, drawn behind the cut-out of the figure |
| `ThemeFiles.java` | Lets a theme carry `foreground.png` and `greeting.mp3` |
| `ArcReactorBootView.java` | The boot intro |
| `RollingSecondsView.java` | The rolling seconds |
| `SharinganView.java` | The eye (off by default; `"sharingan": true` in a theme brings it back) |
| `Config.java` | `Config.Skin`: reads the skin's keys (`repulsor_*`, `ambient_fx*`, `foreground_origin`, `greeting_speech*`) from `config.xml` and the theme |
| `MainActivity.java` | Wires them in: focus tracking, fading with the dock title, boot-once logic, replay intent, start/stop with Home |
| `activity_main.xml` | The new views |
| `res/values/config.xml` | The new keys, all off by default |
