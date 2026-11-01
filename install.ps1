# Put the Iron Man home screen on an Android TV / Google TV, over your Wi-Fi. Windows.
#
#   powershell -ExecutionPolicy Bypass -File .\install.ps1 -TvHost 192.168.1.50
#   ... -TvHost 192.168.1.50 -Device thomson-sppl-4k-sw6h          plus the settings for a TV listed in devices\
#   ... -TvHost 192.168.1.50 -Wallpaper me.jpg -Palms "0.42,0.31"   your own picture (palms from ironman\calibrate.html)
#   ... -TvHost 192.168.1.50 -Fx snow                                one background effect instead of all of them in turn
#   ... -TvHost 192.168.1.50 -Label "WELCOME MR. STARK" -Voice hello.mp3   your own greeting, written and spoken
#
# Options: -Fx fire|snow|fireice|storm|cycle|off (default cycle); -Label "<text>"|off (what his right hand
# projects, default WELCOME HOME); -Voice <file.mp3> (played at start-up) or -Say "<text>"|off with
# -SayLang <tag> (spoken by the TV's own voice, default a Hindi welcome); -NoDarken (keep your picture's
# colours); -Apk <file> (a launcher APK you built yourself).
# Before running: on the TV, Settings > Device Preferences > About > press OK on "Build" 7 times, then
# Developer options > turn on USB debugging (and Network debugging, if the TV shows it).
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Device = '',
    [string]$Wallpaper = '',
    [string]$Palms = '',
    [switch]$NoDarken,
    [string]$Apk = '',
    [ValidateSet('', 'fire', 'snow', 'fireice', 'storm', 'cycle', 'off')][string]$Fx = '',
    [string]$Label = '',
    [string]$Voice = '',
    [string]$Say = '',
    [string]$SayLang = ''
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$Root = $PSScriptRoot
$Repo = 'ashutoshbais17/ironman-tv'

function Say([string]$m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Die([string]$m) { Write-Host "`n$m" -ForegroundColor Red; exit 1 }

if ($Device -and -not (Test-Path "devices\$Device.json")) {
    $known = (Get-ChildItem devices -Filter *.json | ForEach-Object { $_.BaseName }) -join ' '
    Die "no devices\$Device.json. Known: $known"
}
if ($Voice -and -not (Test-Path $Voice)) { Die "no such file: $Voice" }
if ($Voice -and $Voice -notmatch '\.mp3$') { Die '-Voice needs an .mp3 file' }
if ($Wallpaper -and -not $Palms) { Die '-Wallpaper needs -Palms too. Open ironman\calibrate.html, load your picture and click the palms.' }

$base = $null
if (Get-Command py -ErrorAction SilentlyContinue) { $base = @('py', '-3') }
elseif (Get-Command python -ErrorAction SilentlyContinue) { $base = @('python') }
if (-not $base) { Die 'Python 3 is needed: install it from https://www.python.org/downloads/ (tick "Add to PATH") and run this again.' }

Say 'Setting up the tools (first run only takes a minute)'
$py = Join-Path $Root 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) {
    $exe = $base[0]; $rest = @($base | Select-Object -Skip 1)
    & $exe @rest -m venv tools\venv
    if ($LASTEXITCODE -ne 0) { Die 'Could not create the Python environment.' }
}
& $py -m pip install -q --disable-pip-version-check -r tools\requirements.txt pillow numpy
if ($LASTEXITCODE -ne 0) { Die 'Could not install the Python packages.' }
$env:TV_HOST = $TvHost

Say "Connecting to the TV at $TvHost"
Write-Host 'If the TV shows "Allow USB debugging?", tick "Always allow" and press OK.'
$ok = $false
for ($try = 1; $try -le 2; $try++) {
    & $py -I tools\tv.py --probe
    if ($LASTEXITCODE -eq 0) { $ok = $true; break }
    if ($try -lt 2) { Write-Host 'Retrying in 10 seconds...'; Start-Sleep 10 }
}
if (-not $ok) { Die 'Could not reach the TV. Check the IP (TV: Settings > Network), that it is on the same Wi-Fi, and that USB debugging is on.' }
$sdk = ((& $py -I tools\tv.py 'getprop ro.build.version.sdk') -join '') -replace '[^0-9]', ''
if (-not $sdk -or [int]$sdk -lt 26) { Die "This TV runs Android SDK $sdk; the launcher needs Android 8.0 (SDK 26) or newer." }

Say 'Getting the launcher app'
$dest = Join-Path $Root 'launcher\app\build\outputs\apk\release\app-release.apk'
New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
if ($Apk) {
    Copy-Item $Apk $dest -Force
} else {
    if ($Repo -eq '__REPO__') { Die 'This copy has no release to download from; build the APK and pass -Apk.' }
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$Repo/releases/latest/download/ironman-tv.apk" -OutFile $dest
    } catch {
        Die "Could not download the app. Get ironman-tv.apk from https://github.com/$Repo/releases and run again with -Apk <file>."
    }
}

Say 'Preparing the wallpaper'
$work = Join-Path $Root '.work'
$theme = Join-Path $work 'theme'
if (Test-Path $theme) { Remove-Item $theme -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $theme 'wallpapers') | Out-Null
if (-not $Wallpaper) {
    $info = Get-Content ironman\wallpaper.json -Raw | ConvertFrom-Json
    $Palms = $info.repulsor_origins
    $Wallpaper = Join-Path $work 'ironman-original.jpg'
    if (-not (Test-Path $Wallpaper) -or (Get-Item $Wallpaper).Length -eq 0) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $info.image -OutFile $Wallpaper
        } catch {
            Die "Could not download the wallpaper. Open $($info.page), download it, and run again with -Wallpaper <file> -Palms `"$Palms`"."
        }
    }
    $size = (& $py -I -c 'import sys;from PIL import Image;print("%dx%d" % Image.open(sys.argv[1]).size)' $Wallpaper) -join ''
    if ($size -ne '8900x5004') { Write-Host "Note: the wallpaper came back as $size, not 8900x5004; if the beam misses his palms, calibrate with ironman\calibrate.html." }
}
# The figure is cut out too, so the background effects can pass behind him.
$prep = @('-I', 'ironman\prep_wallpaper.py', $Wallpaper, (Join-Path $theme 'wallpapers\01-wallpaper.jpg'), '--cutout', (Join-Path $theme 'foreground.png'))
if ($NoDarken) { $prep += '--no-darken' }
$prepOut = @(& $py @prep)
if ($LASTEXITCODE -ne 0) { Die 'Could not prepare the wallpaper.' }
$prepOut | Where-Object { $_ -notmatch '^foreground_origin ' } | ForEach-Object { Write-Host $_ }
$origin = ''
$prepOut | Where-Object { $_ -match '^foreground_origin (.+)$' } | ForEach-Object { $origin = $Matches[1] }
if ($Voice) { Copy-Item $Voice (Join-Path $theme 'greeting.mp3') -Force }

# The theme: the skin's settings, the TV's own (devices\), where the palms are, and the background effect.
$merge = @'
import json, os, sys
out, palms, extra = sys.argv[1], sys.argv[2], sys.argv[3:]
cfg = json.load(open("ironman/theme/config.json"))
for path in extra:
    cfg.update(json.load(open(path)))
cfg["repulsor_origins"] = palms
# PowerShell removes a variable set to '', so a missing one means "not given".
if os.environ.get("ORIGIN"):
    cfg["foreground_origin"] = os.environ["ORIGIN"]
if os.environ.get("FX"):
    cfg["ambient_fx"] = "" if os.environ["FX"] == "off" else os.environ["FX"]
for env, key in (("LABEL", "repulsor_greeting"), ("SAY", "greeting_speech"), ("SAYLANG", "greeting_speech_lang")):
    if os.environ.get(env):
        cfg[key] = "" if os.environ[env] == "off" else os.environ[env]
json.dump(cfg, open(out, "w"), indent=2)
'@
$env:ORIGIN = $origin
$env:FX = $Fx
$env:LABEL = $Label
$env:SAY = $Say
$env:SAYLANG = $SayLang
$mergeFile = Join-Path $work 'merge.py'
Set-Content -Path $mergeFile -Value $merge -Encoding ASCII
$margs = @('-I', $mergeFile, (Join-Path $theme 'config.json'), $Palms)
if ($Device) { $margs += "devices\$Device.json" }
& $py @margs
if ($LASTEXITCODE -ne 0) { Die 'Could not write the theme settings.' }

Say 'Installing the launcher on the TV'
$env:SKIP_BUILD = '1'
$installArgs = @{ TvHost = $TvHost }
if ($Device -and (Select-String -Path "devices\$Device.json" -Pattern '"settings_key_keycodes"' -Quiet)) { $installArgs.HomeKeyService = $true }
& (Join-Path $Root 'launcher\build-and-install.ps1') @installArgs
if (-not $?) { Die 'Installing the launcher failed (see above). Your TV keeps its own home screen.' }

Say 'Applying the Iron Man theme'
& (Join-Path $Root 'tools\theme.ps1') -TvHost $TvHost $theme

Say 'Done. Press Home on the remote.'
Write-Host @"
Move between the apps: his left hand fires at whatever you pick. The arc reactor intro plays on the next power-on.
Background: $(if ($Fx) { $Fx } else { 'cycle' }) (fire, snow, fire & ice and storm in turn with "cycle"; change it with -Fx).
His right hand projects: $(if ($Label) { $Label } else { 'WELCOME HOME' }) (change it with -Label). The greeting plays with the intro.

Watch the intro now:
  `$env:TV_HOST='$TvHost'; tools\venv\Scripts\python.exe tools\tv.py 'am start -n com.gathin.tvhome/.MainActivity --ez com.gathin.tvhome.REPLAY_BOOT_INTRO true'

Undo everything (your old home screen comes back):
  powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 -TvHost $TvHost

Finally, switch USB debugging (and Network debugging) off again on the TV: while it's on, anything on your
Wi-Fi can control the TV.
"@
