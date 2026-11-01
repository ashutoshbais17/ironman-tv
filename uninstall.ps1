# Undo install.ps1: the TV's own home screen comes back and the Iron Man launcher is removed. Windows.
#   powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 -TvHost 192.168.1.50
param([Parameter(Mandatory = $true)][string]$TvHost)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:TV_HOST = $TvHost
$py = Join-Path $PSScriptRoot 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { Write-Host 'Run install.ps1 once first (it sets up tools\venv).' -ForegroundColor Red; exit 1 }
function Tv([string]$cmd) { (& $py -I tools\tv.py $cmd) -replace "`r", '' }
$ours = 'com.gathin.tvhome'
$homeQ = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

Write-Host 'Removing the Iron Man theme...'
try { & (Join-Path $PSScriptRoot 'tools\theme.ps1') -TvHost $TvHost -Reset } catch { }

$disabled = (Tv 'pm list packages -d --user 0') | ForEach-Object { $_ -replace '^package:', '' }
$stock = ''
foreach ($p in 'com.google.android.apps.tv.launcherx', 'com.google.android.tvlauncher', 'com.google.android.leanbacklauncher', 'com.mitv.tvhome') {
    if ($disabled -contains $p) {
        Write-Host "Turning the stock launcher back on: $p"
        Tv "pm enable --user 0 $p" | Out-Null
        $stock = $p
    }
}
if ($stock) { Tv "cmd package set-home-activity --user 0 `$($homeQ $stock | tail -n 1)" | Out-Null }

$others = (Tv 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') |
    ForEach-Object { if ($_ -match '^\s*([A-Za-z0-9_.]+)/') { $Matches[1] } } | Sort-Object -Unique | Where-Object { $_ -ne $ours }
if (-not $others) {
    Write-Host 'No other home screen is enabled on this TV, so the Iron Man launcher stays installed (removing it would leave the TV with no Home). Re-enable your TV''s launcher in Settings > Apps, then run this again.' -ForegroundColor Yellow
    exit 1
}

Write-Host 'Removing the Iron Man launcher...'
Tv "pm uninstall $ours"
Write-Host "Home now: $((Tv $homeQ) | Select-Object -Last 1)"
Write-Host 'Done. Press Home on the remote. Remember to switch USB debugging off again on the TV.'
