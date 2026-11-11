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
prefs "<string name=\"style\">TRONCO</string><string name=\"finish\">TABACCO</string><string name=\"growth_start\">$(date -d '-20 days' +%F)</string>"
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
prefs "<string name=\"style\">SOLCO</string><string name=\"finish\">NOTTE</string>"
adb shell wm size 2256x2504   # the Fold 8 Ultra main screen, as Diagnostics reported it
sleep 3
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 15
adb exec-out screencap -p > shot-preview-main.png
adb shell wm size reset

# 11 Nov 2026: the new styles in every finish on the cover's shape, Marea at 35% and charging, Duna tilted
adb shell wm size 1080x2520; sleep 2
for st in SOLCO MAREA DUNA; do for fi in VISCONTI CARBONIO NOTTE TABACCO GRAFITE BRONZO; do
  prefs "<string name=\"style\">$st</string><string name=\"finish\">$fi</string>"
  adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
  sleep 8
  adb exec-out screencap -p > "shot-new-$st-$fi.png"
  adb shell input keyevent KEYCODE_BACK; sleep 1
done; done
adb shell dumpsys battery set level 35
prefs "<string name=\"style\">MAREA</string><string name=\"finish\">NOTTE</string>"
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 8; adb exec-out screencap -p > shot-marea-35.png
adb shell dumpsys battery set status 2; adb shell dumpsys battery set ac 1; sleep 3
adb exec-out screencap -p > shot-marea-35-charging.png
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_BACK; sleep 1
prefs "<string name=\"style\">DUNA</string><string name=\"finish\">TABACCO</string>"
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 8; adb exec-out screencap -p > shot-duna-level.png
adb emu sensor set acceleration 6.5:4.0:6.0; sleep 5
adb exec-out screencap -p > shot-duna-tilted.png
adb emu sensor set acceleration 0:9.81:0.5; sleep 6
adb exec-out screencap -p > shot-duna-after.png
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell wm size reset

# Every style and finish at the main screen's size, so no combination fails only on the phone
adb shell wm size 2256x2504
for st in SOLCO TRONCO MAREA DUNA; do for fi in VISCONTI CARBONIO NOTTE TABACCO GRAFITE BRONZO; do
  prefs "<string name=\"style\">$st</string><string name=\"finish\">$fi</string><string name=\"growth_start\">$(date -d '-12 days' +%F)</string>"
  adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
  sleep 9
  adb shell input keyevent KEYCODE_BACK
  sleep 1
done; done
adb shell wm size reset

# Sipario: its settings, then each scene on the cover's shape, the moment it unfolds, and at rest unfolded
SIP=$PKG/.SiparioWallpaperService
adb shell am start -n $PKG/.SiparioActivity
sleep 6
adb exec-out screencap -p > shot-sipario-settings.png
for act in CATENARIA STAGIONI PONTE FANALE GOCCIA; do
  prefs "<string name=\"sipario\">$act</string>"
  adb shell wm size 1080x2520   # the Fold 8 Ultra cover screen
  sleep 2
  adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $SIP
  sleep 8
  adb exec-out screencap -p > shot-sipario-$act-cover.png
  # The fold: the same wallpaper, the screen becomes the main one
  adb shell wm size 2256x2504
  sleep 0.6
  adb exec-out screencap -p > shot-sipario-$act-unfolding.png
  sleep 5
  adb exec-out screencap -p > shot-sipario-$act-main.png
  adb shell input tap 900 1500
  sleep 0.3
  adb exec-out screencap -p > shot-sipario-$act-touch.png
  adb shell input keyevent KEYCODE_BACK
  sleep 1
  adb shell wm size reset
  sleep 2
done

adb logcat -d > logcat.txt
grep -E "Solco|solco|AndroidRuntime|FATAL" logcat.txt > app-log.txt || true
# Only Solco's own failures count: the emulator's stock wallpaper picker sometimes crashes by itself
if grep -A1 "FATAL EXCEPTION" app-log.txt | grep -q "Process: $PKG" || grep -qE "build failed|frame failed" app-log.txt; then
  echo "::error::Solco failed on the emulator; see app-log.txt in the release"
  grep -A 14 -E "FATAL EXCEPTION|build failed|frame failed" app-log.txt | head -40
  exit 1
fi
exit 0
