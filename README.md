# Iron Man TV Home

Turn your Android TV's home screen into Iron Man. A repulsor beam fires from his hand to whichever app you
select, switching hands as you move, and an arc reactor powers up every time the TV turns on.

<!-- Demo: add docs/demo.gif (a clip from your reel) and uncomment the next line.
![Iron Man TV Home](docs/demo.gif)
-->

**What you get**

- A full-screen Iron Man wallpaper, darkened so the clock stays readable
- A glowing beam from his nearer palm to the app you're on, with both palms pulsing
- An arc reactor power-up animation each time the TV starts
- A clean, frosted-glass dock with your apps' own colourful logos
- A big clock with rolling seconds, and the date

It runs as your TV's home screen app. Nothing is rooted or flashed, and one command undoes it all.

**Works on** Android TV and Google TV running Android 8.0 or newer (Sony, TCL, Xiaomi, Hisense Android,
OnePlus, Thomson, Nvidia Shield and most others). **Not** Samsung, LG, Roku or Fire TV.

> Unofficial fan project. Iron Man is a trademark of Marvel; this isn't affiliated with or endorsed by Marvel or
> Disney. No Marvel artwork is stored here: the installer downloads the wallpaper from its public page onto your
> own computer, for your own TV.

## Install (about 5 minutes)

You need a computer (Mac, Windows or Linux) on the same Wi-Fi as the TV, and
[Python 3](https://www.python.org/downloads/) (on Windows, tick "Add python.exe to PATH" when installing).

**1. Switch on debugging on the TV**

Settings → Device Preferences → About → press OK on **Build** 7 times ("You are now a developer").
Go back → **Developer options** → turn on **USB debugging** (and **Network debugging** too, if you see it).

**2. Find the TV's IP address**

Settings → Network & Internet → your Wi-Fi network. It looks like `192.168.1.50`.

**3. Download this project**

Click the green **Code** button above → **Download ZIP**, then unzip it.

**4. Run the installer**

On a **Mac or Linux**, open Terminal, go into the unzipped folder, and run (with your TV's IP):

```bash
./install.sh 192.168.1.50
```

On **Windows**, open PowerShell in the unzipped folder and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -TvHost 192.168.1.50
```

Watch the TV: the first time, it asks **"Allow USB debugging?"**. Tick **Always allow** and press OK.

When it says **Done**, press Home on your remote. Then **turn USB debugging off again** on the TV. While it's on,
anything on your Wi-Fi can control the TV.

> **Mac, first run:** if it says "No route to host", macOS is blocking the network for Terminal. Go to System
> Settings → Privacy & Security → Local Network, switch Terminal on, quit and reopen Terminal, and run again.

## Prefer to let an AI do it?

Using [Claude Code](https://claude.com/claude-code)? Open it in the unzipped folder and paste the prompt from
[PROMPT.md](PROMPT.md). It walks you through every step, checks the TV with screenshots, and fixes problems
along the way.

## Use your own picture

Any wallpaper works, as long as the beam knows where the hands are:

1. Open [`ironman/calibrate.html`](ironman/calibrate.html) in your browser (double-click it; it runs offline).
2. Load your picture and click the centre of each glowing palm. Move the mouse to preview the beam.
3. Copy the command it shows, for example:

```bash
./install.sh 192.168.1.50 --wallpaper my-picture.jpg --palms "0.3813,0.1898;0.6083,0.2389"
```

Add `--no-darken` (Windows: `-NoDarken`) if your picture is already dark.

## TV-specific settings

Some TVs need a few extra settings, like which app is Live TV, or a remote whose Settings button needs mapping.
These live in [`devices/`](devices/). If your TV is listed, add `--device <name>`:

```bash
./install.sh 192.168.1.50 --device thomson-sppl-4k-sw6h
```

For other TVs, the [cleanyourtv](https://github.com/Gathin23/cleanyourtv) project has tested settings for
hundreds of models in its `examples/` folder. Copy that model's `config.json` into `devices/<name>.json`.

## Undo

```bash
./uninstall.sh 192.168.1.50
```

Windows: `powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 -TvHost 192.168.1.50`

Your TV's own home screen comes back and the Iron Man app is removed.

## Also clean up your TV (optional)

TVs come with a lot of tracking and ad apps. [cleanyourtv](https://github.com/Gathin23/cleanyourtv), the project
this is built on, safely switches them off (never uninstalls) for your model. See
[`examples/thomson-sppl-4k-sw6h`](examples/thomson-sppl-4k-sw6h) for the list used on the TV in the reel, and
cleanyourtv's PROMPT.md for any other TV.

## Troubleshooting

- **"Could not reach the TV"**: check the IP, that the computer is on the same Wi-Fi, and that USB debugging is on.
  On Google TV, if Network debugging isn't offered, turn on Wireless debugging instead.
- **"Home resolves to com.google.android.tungsten.setupwraith"** (Google TV): the setup wizard is holding the
  Home button. cleanyourtv's README → Troubleshooting explains the one-time fix; then run the installer again.
- **The beam misses his hands**: the wallpaper may have changed on the site. Use `ironman/calibrate.html` and pass
  `--palms`.
- **Replay the boot animation** without restarting the TV (Mac/Linux):
  `TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'am start -n com.gathin.tvhome/.MainActivity --ez com.gathin.tvhome.REPLAY_BOOT_INTRO true'`

## How it was made

[docs/PROCESS.md](docs/PROCESS.md) covers the whole build, from debloating the TV to the beam and the arc
reactor, including what went wrong along the way.

## Credits and licence

Built on [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin: the launcher, the ADB tools, the
safety checks and the debloat lists. MIT licence, see [LICENSE](LICENSE). The Outfit typeface is under the SIL
Open Font License.
