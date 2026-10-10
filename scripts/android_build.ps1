# Build and unit-test the Android app. Run from Windows PowerShell:  powershell -File scripts\android_build.ps1 [tasks]
# Gradle does not work from a \\wsl.localhost path, so the project is mirrored to a local folder first.
# Needs Android Studio (its bundled JDK) and the Android SDK in the default places.
param([string]$Tasks = "testDebugUnitTest assembleDebug")
$ErrorActionPreference = "Continue"
$src = Join-Path $PSScriptRoot "..\android"
$dst = Join-Path $env:USERPROFILE "ebike-app-build"
New-Item -ItemType Directory -Force $dst | Out-Null
robocopy $src $dst /MIR /XD build .gradle .idea /XF local.properties /NFL /NDL /NJH /NJS /NP | Out-Null
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
"sdk.dir=" + ($sdk -replace '\\', '\\' -replace ':', '\:') | Set-Content (Join-Path $dst "local.properties") -Encoding ascii
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Set-Location $dst
& (Join-Path $dst "gradlew.bat") --no-daemon --console=plain $Tasks.Split(" ") 2>&1 | Select-Object -Last 40
$xml = Get-ChildItem "$dst\app\build\test-results\testDebugUnitTest" -Filter *.xml -ErrorAction SilentlyContinue
foreach ($f in $xml) { $x = [xml](Get-Content $f.FullName); "{0}: tests={1} failures={2} errors={3}" -f $x.testsuite.name, $x.testsuite.tests, $x.testsuite.failures, $x.testsuite.errors }
$apk = Join-Path $dst "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) { "APK: $apk" }
