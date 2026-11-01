# The prompt

Open [Claude Code](https://claude.com/claude-code) (or another coding assistant with shell access) inside this
folder, on a computer on the same Wi-Fi as the TV, and paste everything below the line. Fill in your TV model.

Before you start, on the TV: Settings → Device Preferences → About → press OK on "Build" 7 times, then go back →
Developer options → turn on USB debugging (and Network debugging if the TV shows it).

---

Install the Iron Man home screen from this folder on my Android TV. My TV model is: <MODEL NAME HERE>.
Work on the real TV, step by step, and check each step with a screenshot from the TV
(`tools/tv.py --screenshot <file.png>`). Ask me before anything that depends on my preferences.

- Find the TV on my network (or ask me for its IP) and connect over ADB on port 5555 with `tools/tv.py`
  (set `TV_HOST`). Tell me when the "Allow USB debugging?" prompt will appear so I can accept it. On macOS, a
  "No route to host" while the port is open is Local Network privacy: ask me to allow my terminal app under
  System Settings → Privacy & Security → Local Network. If 5555 is closed, look for Wireless debugging
  (`_adb-tls-connect._tcp`) and ask me for the pairing code.
- If the TV isn't Android TV / Google TV (no ADB, or it's Tizen, webOS, Roku, Fire TV or VIDAA), stop and tell me
  this won't work on it.
- Check the TV runs Android 8.0 (SDK 26) or newer, and accepts sideloaded apps.
- Look in `devices/` for my model. If it isn't there, check https://github.com/Gathin23/cleanyourtv/tree/main/examples
  for a tested `config.json` for my model, and save it as `devices/<model>.json`.
- Run `./install.sh <tv ip>` (Windows: `install.ps1`), adding `--device <model>` when there's a devices file. It
  reuses cleanyourtv's install script, which only disables the stock launcher once the new one is confirmed as
  Home, and rolls back if it isn't. Don't bypass those checks (`FORCE_HOME`, `FORCE_SETUPWRAITH`) without asking me.
- After it finishes, take a screenshot, press DPAD_LEFT and DPAD_RIGHT across the dock, and confirm the beam leaves
  one of his palms and lands on the selected app. If it misses his hands, help me calibrate with
  `ironman/calibrate.html` and re-run with `--palms`.
- On Google TV, if Home goes back to the setup wizard (`com.google.android.tungsten.setupwraith`), follow
  cleanyourtv's README → Troubleshooting. Check `dumpsys wifi | grep -o 'cname=[^ ]*'` before disabling setupwraith:
  if it owns the saved Wi-Fi network, ask me to forget and re-join the network first.
- If I also want the tracking and ad apps switched off, follow cleanyourtv's PROMPT.md for my model: back up first,
  disable (never uninstall), small batches, and ask me which streaming apps, voice assistant, Chromecast and
  updates I use.
- Ask me to press my remote's real Home, Settings and Input buttons; injected keys don't prove those work.
- When done, switch Network debugging and USB debugging off on the TV (ask me to do it), and check with
  `tools/adb-closed.py <tv ip>` that port 5555 refuses the connection. Tell me how to undo: `./uninstall.sh <tv ip>`.
