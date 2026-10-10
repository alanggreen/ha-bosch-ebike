# Install the APK built by android_build.ps1 on the attached phone and open the app.
#   powershell -File scripts\android_install.ps1 [-Serial <adb serial>]
# The phone must be unlocked. Only the Android agent installs on the phone.
param([string]$Serial = "")
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $env:USERPROFILE "ebike-app-build\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { "no APK: run scripts\android_build.ps1 first"; exit 2 }
if (-not $Serial) {
  $line = & $adb devices | Select-String "device$" | Select-Object -First 1
  if (-not $line) { "no phone attached (turn on USB or wireless debugging)"; exit 2 }
  $Serial = $line.ToString().Split("`t")[0]
}
"device: $Serial"
& $adb -s $Serial install -r $apk
$locked = & $adb -s $Serial shell "dumpsys window | grep isKeyguardShowing | head -1"
if ($locked -match "isKeyguardShowing=true") { "phone is locked: unlock it, then open the app (not launching)"; exit 0 }
& $adb -s $Serial shell am start -n app.ebikecompanion/.MainActivity
