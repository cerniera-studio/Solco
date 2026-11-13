#!/usr/bin/env bash
# The four shapes of the phone (12 Nov 2026), run first so they always report. The Fold's glass in
# dp, from its own screenshots: cover 411 x 960, main 859 x 896. The emulator can't go past 1920 px,
# so densities are lowered to keep the dp. Landscape is the same glass turned: the display really
# rotates (user_rotation), so the app and the wallpaper see the rotation the phone would report.
#
# For each shape: both Settings screens, each with the live wallpaper and its main action in view at
# rest (checked from files/layout.json, which each screen writes once laid out), then Solco and
# Sipario as wallpapers. Turning the cover must never count as a fold.
PKG=com.fergusonconsultancy.solco
WP=$PKG/.SolcoWallpaperService
SIP=$PKG/.SiparioWallpaperService

adb shell settings put system accelerometer_rotation 0

shape() {  # WxH (upright) dpi rotation
  adb shell wm density "$2"; adb shell wm size "$1"
  adb shell settings put system user_rotation "$3"
  adb shell wm user-rotation lock "$3" >/dev/null 2>&1
  sleep 3
}

check() {  # name: the live wallpaper and the main action end inside the view, clear of the bars
  json=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null)
  python3 - "$1" "$json" <<'PY'
import sys, json
name = sys.argv[1]
try:
    d = json.loads(sys.argv[2])
except Exception:
    print(f"::error::{name}: no layout recorded"); sys.exit(0)
vw, vh = d["view"]; il, it, ir, ib = d["insets"]
bad = []
for k in ("live", "action"):
    l, t, r, b = d[k]
    if l < 0 or t < 0 or r > vw or b > vh - ib: bad.append(f"{k} at {d[k]} leaves the view {vw}x{vh} (bottom bar {ib})")
    if r - l < 40 or b - t < 20: bad.append(f"{k} is too small: {d[k]}")
lay = "two panes" if d["wide"] else "one column"
print(("::error::" if bad else "::notice::") + f"{name} ({lay}): " + ("; ".join(bad) if bad else f"wallpaper {d['live']} and action {d['action']} in view {vw}x{vh}"))
PY
}

prefs() {
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>%s</map>\n' "$1" > solco.xml
  adb push solco.xml /data/local/tmp/solco.xml >/dev/null
  adb shell chmod 644 /data/local/tmp/solco.xml
  adb shell am force-stop $PKG
  adb shell run-as $PKG mkdir -p shared_prefs
  adb shell run-as $PKG cp /data/local/tmp/solco.xml shared_prefs/solco.xml
}

wallpaper() {  # service shot-name
  adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT "$1"
  sleep 9
  adb exec-out screencap -p > "$2.png"
  adb shell input keyevent KEYCODE_BACK; sleep 1
}

for s in "cover-portrait 823x1920 320 0" "cover-landscape 823x1920 320 1" "main-portrait 1503x1568 280 0" "main-landscape 1503x1568 280 1"; do
  set -- $s
  name=$1
  shape "$2" "$3" "$4"
  prefs "<string name=\"style\">SOLCO</string><string name=\"finish\">VISCONTI</string><string name=\"sipario\">GOCCIA</string>"
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am start -W -n $PKG/.SettingsActivity >/dev/null; sleep 6
  # The emulator sometimes ignores a rotation: ask again until the screen really is the right way round
  for try in 1 2 3; do
    wide=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;v=json.load(sys.stdin)['view'];print(int(v[0]>v[1]))" 2>/dev/null)
    want=$([ "$4" = 0 ] && echo 0 || echo 1)
    [ "$wide" = "$want" ] && break
    echo "rotation not applied (try $try), asking again"
    adb shell settings put system user_rotation 0; adb shell wm user-rotation lock 0 >/dev/null 2>&1; sleep 1
    adb shell settings put system user_rotation "$4"; adb shell wm user-rotation lock "$4" >/dev/null 2>&1; sleep 3
    adb shell run-as $PKG rm -f files/layout.json
    adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.SettingsActivity >/dev/null; sleep 6
  done
  [ "$wide" = "$want" ] || echo "::error::$name: the screen never turned" | tee -a shapes.txt
  adb exec-out screencap -p > "shot-shape-$name-solco.png"
  check "Solco settings, $name" | tee -a shapes.txt
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am start -W -n $PKG/.SiparioActivity >/dev/null; sleep 6
  adb exec-out screencap -p > "shot-shape-$name-sipario.png"
  check "Sipario settings, $name" | tee -a shapes.txt
  adb shell am force-stop $PKG
  # The system's wallpaper preview is locked upright, so on the emulator these stay upright even in
  # the landscape shapes. The turned wallpaper is seen in the Settings previews above, which draw the
  # same scenes on the turned screen's own shape.
  wallpaper $WP "shot-shape-$name-wp-solco"
  prefs "<string name=\"style\">DUNA</string><string name=\"finish\">TABACCO</string>"
  wallpaper $WP "shot-shape-$name-wp-duna"
  prefs "<string name=\"sipario\">GOCCIA</string>"
  wallpaper $SIP "shot-shape-$name-wp-goccia"
  prefs "<string name=\"sipario\">CATENARIA</string>"
  wallpaper $SIP "shot-shape-$name-wp-catenaria"
done

# Turning the cover is not a fold: with Solco in view, turn it both ways and back. No fold may be logged.
prefs "<string name=\"style\">SOLCO</string><string name=\"finish\">VISCONTI</string>"
shape 823x1920 320 0
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 9
for r in 1 0 3 0; do adb shell settings put system user_rotation $r; adb shell wm user-rotation lock $r >/dev/null 2>&1; sleep 4; done
adb exec-out screencap -p > shot-shape-cover-turned-back.png
fold=$(adb shell run-as $PKG cat shared_prefs/solco.xml 2>/dev/null | grep -o 'name="last_fold">[^<]*')
if [ -n "$fold" ]; then echo "::error::turning the cover counted as a fold: $fold"; else echo "::notice::turning the cover both ways: no fold logged"; fi | tee -a shapes.txt
# And a real unfold still counts: the cover becomes the main screen (a size change, as before)
adb shell wm size 1503x1568; sleep 8
fold=$(adb shell run-as $PKG cat shared_prefs/solco.xml 2>/dev/null | grep -o 'name="last_fold">[^<]*')
if [ -n "$fold" ]; then echo "::notice::an unfold still counts: $fold"; else echo "::error::unfolding no longer counts as a fold"; fi | tee -a shapes.txt
adb shell input keyevent KEYCODE_BACK; sleep 1

adb shell settings put system user_rotation 0; adb shell wm user-rotation lock 0 >/dev/null 2>&1
adb shell wm size reset; adb shell wm density reset
sleep 3
