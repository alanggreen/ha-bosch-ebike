# Rebuild the enclosure STLs and preview images from hardware/enclosure/ebike_logger_box.scad.
#   powershell -File scripts\enclosure_build.ps1 [-OpenScad <path to openscad.exe>]
# OpenSCAD 2021.01 or newer; the portable zip from openscad.org works (no install needed).
# The part to render is set in a temporary copy of the model (a -D option loses its quotes in Windows PowerShell 5.1).
param([string]$OpenScad = $(if ($env:OPENSCAD) { $env:OPENSCAD } else { "openscad" }))
$dir = Join-Path $PSScriptRoot "..\hardware\enclosure"
$w = Join-Path $env:TEMP "ebike-encl"
New-Item -ItemType Directory -Force $w | Out-Null
$model = Get-Content (Join-Path $dir "ebike_logger_box.scad") -Raw
Push-Location $w

function Render([string]$part, [string]$out, [string[]]$extra = @()) {
  $text = $model -replace '(?m)^part = "[a-z]+";', "part = `"$part`";"
  if ($text -eq $model -and $part -ne "print") { throw "could not set part = $part (is the first line still 'part = `"print`";'?)" }
  [IO.File]::WriteAllText((Join-Path $w "model_$part.scad"), $text)
  & $OpenScad -o $out @extra "model_$part.scad" 2>&1 | Select-String "ERROR|WARNING|Simple:|ECHO"
}

Render "base" "ebike_logger_base.stl"
Render "lid" "ebike_logger_lid.stl"
$img = @("--imgsize=1100,800", "--viewall", "--autocenter", "--colorscheme=Tomorrow")
Render "print"   "preview.png"         ($img + "--camera=0,0,0,50,0,30,0")
Render "base"    "preview_floor.png"   ($img + "--camera=0,0,0,40,0,20,0")
Render "section" "preview_section.png" ($img + "--camera=0,0,0,75,0,0,0")

Copy-Item ebike_logger_base.stl, ebike_logger_lid.stl (Join-Path $dir "stl") -Force
Copy-Item preview.png, preview_floor.png, preview_section.png $dir -Force
Pop-Location
"enclosure rebuilt in $((Resolve-Path $dir).Path)"
