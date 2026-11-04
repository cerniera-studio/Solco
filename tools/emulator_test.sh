#!/usr/bin/env bash
# Run by the emulator job. Screenshots go to the release so they can be checked from the phone.
set -x
PKG=com.fergusonconsultancy.solco
WP=$PKG/.SolcoWallpaperService

adb install -r Solco.apk
adb logcat -c

# Settings: the live preview, then a swipe through the grain
adb shell am start -n $PKG/.SettingsActivity
sleep 8
adb exec-out screencap -p > shot-settings.png
adb shell input swipe 300 600 700 900 500
sleep 0.3
adb exec-out screencap -p > shot-settings-touch.png

# The system's live wallpaper preview: what the home screen will show
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 15
adb exec-out screencap -p > shot-preview-solco.png
adb shell input swipe 200 900 800 1300 400
sleep 0.25
adb exec-out screencap -p > shot-preview-touch.png
adb shell input keyevent KEYCODE_BACK
sleep 2

# Tronco in Avorio with 20 days of record, written into the app's settings (a debug build allows run-as)
prefs() {
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>%s</map>\n' "$1" > solco.xml
  adb push solco.xml /data/local/tmp/solco.xml
  adb shell chmod 644 /data/local/tmp/solco.xml
  adb shell am force-stop $PKG
  adb shell run-as $PKG mkdir -p shared_prefs
  adb shell run-as $PKG cp /data/local/tmp/solco.xml shared_prefs/solco.xml
}
prefs "<string name=\"style\">TRONCO</string><string name=\"finish\">AVORIO</string><string name=\"growth_start\">$(date -d '-20 days' +%F)</string>"
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 15
adb exec-out screencap -p > shot-preview-tronco.png
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell am start -n $PKG/.SettingsActivity
sleep 6
adb exec-out screencap -p > shot-settings-tronco.png
adb shell input swipe 500 1800 500 400 300
sleep 1.5
adb exec-out screencap -p > shot-settings-scrolled.png

# The Fold's main screen at its real size: Solco centred on the crease, in Rosso
prefs "<string name=\"style\">SOLCO</string><string name=\"finish\">ROSSO</string>"
adb shell wm size 2256x2504   # the Fold 8 Ultra main screen, as Diagnostics reported it
sleep 3
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 15
adb exec-out screencap -p > shot-preview-main.png
adb shell wm size reset

# Every style and finish at the main screen's size, so no combination fails only on the phone
adb shell wm size 2256x2504
for st in SOLCO TRONCO; do for fi in VISCONTI AVORIO CARBONIO ROSSO; do
  prefs "<string name=\"style\">$st</string><string name=\"finish\">$fi</string><string name=\"growth_start\">$(date -d '-12 days' +%F)</string>"
  adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
  sleep 9
  adb shell input keyevent KEYCODE_BACK
  sleep 1
done; done
adb shell wm size reset

adb logcat -d > logcat.txt
grep -E "Solco|solco|AndroidRuntime|FATAL" logcat.txt > app-log.txt || true
if grep -qE "FATAL EXCEPTION|build failed|frame failed" app-log.txt; then
  echo "::error::The app crashed on the emulator; see app-log.txt in the release"
  grep -A 12 -E "FATAL EXCEPTION|build failed|frame failed" app-log.txt | head -30
  exit 1
fi
exit 0
