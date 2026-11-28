#!/usr/bin/env bash
# Run by the emulator job. Screenshots go to the release so they can be checked from the phone.
set -x
PKG=com.fergusonconsultancy.solco
WP=$PKG/.SolcoWallpaperService

adb install -r Solco.apk
adb logcat -c

# Every shape of the phone first, so it always reports (12 Nov 2026)
bash tools/shapes.sh

# Settings: the live preview, then a swipe through the grain
adb shell am start -n $PKG/.SettingsActivity
sleep 8
adb exec-out screencap -p > shot-settings.png
adb shell input swipe 300 600 700 900 500
sleep 0.3
adb exec-out screencap -p > shot-settings-touch.png

# Concentrato's choices (13 Nov 2026): scroll to them, pick Half and Longer, then Show me on the live wallpaper
find_text() {  # prints "x y" of the first node whose text is $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  python3 - "$1" <<'PY'
import re,sys
x=open('ui.xml',encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    if m.group(1)==sys.argv[1]:
        a,b,c,d=map(int,m.groups()[1:]); print((a+c)//2,(b+d)//2); break
PY
}
# Settings in front on its own: the shape checks can leave the system's wallpaper picker open
adb shell input keyevent KEYCODE_HOME; adb shell am force-stop com.android.wallpaper.livepicker
adb shell am start -W -n $PKG/.SettingsActivity >/dev/null; sleep 6
SZ=$(adb shell wm size | grep -o "[0-9]*x[0-9]*" | tail -1); SW=${SZ%x*}; SH=${SZ#*x}
for t in Half Longer "Show me"; do
  # Swiped in the margin: the live wallpaper keeps a finger that lands on it, so the page wouldn't scroll
  xy=""; for i in 1 2 3 4 5 6 7 8 9 10; do xy=$(find_text "$t"); [ -n "$xy" ] && break; adb shell input swipe 8 $((SH*3/4)) 8 $((SH/3)) 300; sleep 1; done
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "::notice::tapped '$t' at $xy"; sleep 1; else echo "::error::no '$t' in Solco's settings" | tee -a shapes.txt; fi
done
sleep 0.2
adb exec-out screencap -p > shot-concentrato-show-me.png
sleep 4
adb exec-out screencap -p > shot-concentrato-after.png
adb shell run-as $PKG cat shared_prefs/solco.xml | grep -o 'conc_[a-z]*">[A-Z]*' | tr '\n' ' '; echo

# Notturno (19 Nov 2026): the preview held at 23:30 UT tonight, with Saturn, Jupiter and Mars up and a 74% moon in the south-west
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.SettingsActivity --es night 2026-11-19T23:30:00Z >/dev/null; sleep 7
adb exec-out screencap -p > shot-notturno.png
# Scroll (in the margin, off the live wallpaper) until the line under the Notturno switch is on screen
SZ=$(adb shell wm size | grep -o "[0-9]*x[0-9]*" | tail -1); SH=${SZ#*x}
said=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  said=$(python3 -c "
import re
x=open('ui.xml',encoding='utf-8').read()
l=[t for t in re.findall(r'text=\"([^\"]*)\"',x) if t.startswith(('Now:','Tonight'))]
print(l[0] if l else '')")
  [ -n "$said" ] && break
  adb shell input swipe 8 $((SH*3/4)) 8 $((SH/3)) 300; sleep 1
done
adb exec-out screencap -p > shot-notturno-line.png
if [ -n "$said" ]; then echo "::notice::Notturno says: $said" | tee -a shapes.txt; else echo "::error::Notturno line not found" | tee -a shapes.txt; fi
adb shell input keyevent KEYCODE_HOME

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

# The Fold's main screen at its real size: Solco centred on the crease, in Notte
prefs "<string name=\"style\">ORBITA</string><string name=\"finish\">NOTTE</string>"
adb shell wm size 2256x2504   # the Fold 8 Ultra main screen, as Diagnostics reported it
sleep 3
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 15
adb exec-out screencap -p > shot-preview-main.png
adb shell wm size reset

# 11 Nov 2026: the new styles in every finish on the cover's shape, Marea at 35% and charging (Duna retired 28 Nov)
adb shell wm size 1080x2520; sleep 2
for st in ORBITA MAREA; do for fi in VISCONTI CARBONIO NOTTE TABACCO GRAFITE BRONZO; do
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
adb shell wm size reset

# 21 Nov 2026: Delta in every finish on the cover's shape, with Verderame. Delta is given a week of
# charges. (Polvere, alongside it then, was retired on 28 Nov 2026.)
# 24 Nov 2026: Nebulosa gave way to Pioggia, Stagione and Sentiero. Pioggia is given a fresh reading of
# steady rain (so it does not wait on the network), Sentiero a day's touches on the cover.
NOW=$(date +%s%3N)
WEEK=""
for d in 6.6:0.62 5.7:0.55 4.4:0.18 3.6:0.70 2.7:0.48 1.6:0.66 0.5:0.58; do
  age=${d%%:*}; gain=${d##*:}
  WEEK="$WEEK$(python3 -c "print(int($NOW - $age * 86400000))"):$gain;"
done
# 28 Nov 2026: a strong south-westerly (a plume on Deserto, softened prints on Orma) and 14 mm fallen today (puddles)
WX="2.2:0:63:95:13:250:$(date +%s):14"
TRAIL=$(python3 - "$NOW" <<'PY'
import sys, random
now = int(sys.argv[1]); random.seed(7); out = []
for (x, y, n) in [(.2, .87, 14), (.4, .87, 9), (.6, .87, 6), (.8, .87, 12), (.2, .62, 5), (.8, .62, 3)]:
    for _ in range(n): out.append(f"{x + random.uniform(-.025, .025):.4f},{y + random.uniform(-.012, .012):.4f},0,{now - random.randint(600000, 9000000)}")
for _ in range(8):
    y = .38 + random.uniform(-.06, .06); x = .85; k = 1
    while x > .15:
        out.append(f"{x:.4f},{y:.4f},{k},{now - 3600000}"); x -= .045; k = 2
print(";".join(out))
PY
)
# Orma: a day's steps so far (a walk to the station, lunch, a run for the train), minute by minute
STEPDAY=$(python3 -c "import datetime; print((datetime.date.today() - datetime.date(1970, 1, 1)).days)")
STEPMIN=$(python3 - <<'PY'
out = []
# Fixed times, so a run near midnight still has a day to draw (prints ahead of now count as the last hour's)
for (a, b, spm) in [(490, 511, 110), (750, 772, 104), (1025, 1034, 150), (1034, 1050, 108)]:
    for t in range(a, b): out.append(f"{t}:{spm}")
for t in range(540, 1020, 31): out.append(f"{t}:45")
print(";".join(out))
PY
)
EXTRA="<string name=\"charges\">${WEEK%;}</string><string name=\"wx\">$WX</string><string name=\"trail_c\">$TRAIL</string><long name=\"steps_day\" value=\"$STEPDAY\" /><string name=\"steps_min\">$STEPMIN</string>"
adb shell wm size 1080x2520; sleep 2
for st in DELTA PIOGGIA STAGIONE SENTIERO DESERTO ORMA; do for fi in VISCONTI CARBONIO NOTTE TABACCO GRAFITE BRONZO VERDERAME; do
  prefs "<string name=\"style\">$st</string><string name=\"finish\">$fi</string>$EXTRA"
  adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
  sleep 8
  adb exec-out screencap -p > "shot-new-$st-$fi.png"
  adb shell input keyevent KEYCODE_BACK; sleep 1
done; done
# Pioggia in snow, then in a thunderstorm
prefs "<string name=\"style\">PIOGGIA</string><string name=\"finish\">NOTTE</string><string name=\"wx\">0:0.6:73:100:4:300:$(date +%s)</string>"
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 8; adb exec-out screencap -p > shot-pioggia-snow.png
adb shell input keyevent KEYCODE_BACK; sleep 1
prefs "<string name=\"style\">PIOGGIA</string><string name=\"finish\">CARBONIO</string><string name=\"wx\">9.5:0:95:100:12:220:$(date +%s)</string>"
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $WP
sleep 8; adb exec-out screencap -p > shot-pioggia-storm.png
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell wm size reset

# Every style and finish at the main screen's size, so no combination fails only on the phone
adb shell wm size 2256x2504
for st in DELTA DESERTO MAREA ORBITA ORMA PIOGGIA SENTIERO STAGIONE TRONCO; do for fi in VISCONTI CARBONIO NOTTE TABACCO GRAFITE BRONZO VERDERAME; do
  prefs "<string name=\"style\">$st</string><string name=\"finish\">$fi</string><string name=\"growth_start\">$(date -d '-12 days' +%F)</string>$EXTRA"
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
# A shape that hides the wallpaper or its action, or a turn that reads as a fold, fails the run
if grep -q "::error::" shapes.txt 2>/dev/null; then
  echo "::error::a shape check failed; see shapes.txt in the release"
  grep "::error::" shapes.txt
  exit 1
fi
# Only Solco's own failures count: the emulator's stock wallpaper picker sometimes crashes by itself
if grep -A1 "FATAL EXCEPTION" app-log.txt | grep -q "Process: $PKG" || grep -qE "build failed|frame failed" app-log.txt; then
  echo "::error::Solco failed on the emulator; see app-log.txt in the release"
  grep -A 14 -E "FATAL EXCEPTION|build failed|frame failed" app-log.txt | head -40
  exit 1
fi
exit 0
