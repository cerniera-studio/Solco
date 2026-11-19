#!/usr/bin/env bash
# Windows (adaptive quality, 19 Nov 2026; Sveglia's tools/windows.sh is the template). Solco's and
# Sipario's settings in pop-up windows and across a turn, on the main screen's shape (859 x 896
# dp). Writes windows.txt; a line starting ::error:: fails the run.
#
# 1. Each settings screen in a pop-up window, wide then narrow: two panes, then one column, with
#    the live wallpaper and Set as wallpaper in view each time (files/layout.json).
# 2. Solco's settings scrolled down keep their place across a turn (the activity is rebuilt).
#
# Pop-up windows need freeform support, which the emulator only reads at boot: this reboots it,
# so it runs last.
PKG=com.fergusonconsultancy.solco
out=windows.txt; : > $out
note() { echo "::notice::$*" | tee -a $out; }
fail() { echo "::error::$*" | tee -a $out; }
warn() { echo "::warning::$*" | tee -a $out; }
shot() { adb exec-out screencap -p > "shot-win-$1.png"; }

adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb reboot
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
sleep 20
adb shell input keyevent 82
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
for a in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $a 0; done
adb logcat -c

# The main screen, upright
adb shell wm density 280; adb shell wm size 1503x1568; sleep 3

task() {  # "<id> <mode>" of the task whose affinity is exactly $1
  adb shell dumpsys activity activities | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.search(r'\* Task\{\S+ #(\d+) .*?A=\d+:(\S+) .*?mode=(\S+)', line)
    if m and m.group(2) == '$1': print(m.group(1), m.group(3)); break"
}
layout() { for try in 1 2 3 4 5 6 7 8; do j=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null); [ -n "$j" ] && break; sleep 1; done; echo "$j"; }
check() {  # name, wide expected (true/false)
  python3 - "$1" "$2" "$(layout)" <<'PY' | tee -a windows.txt
import sys, json
name, want, raw = sys.argv[1], sys.argv[2] == "true", sys.argv[3]
try: d = json.loads(raw)
except Exception: print(f"::error::{name}: no layout recorded"); sys.exit(0)
vw, vh = d["view"]; il, it, ir, ib = d["insets"]
bad = []
for k in ("live", "action"):
    l, t, r, b = d[k]
    if l < 0 or t < 0 or r > vw or b > vh - ib: bad.append(f"{k} at {[l, t, r, b]} leaves the view {vw}x{vh}")
if d["wide"] != want: bad.append(("two panes" if d["wide"] else "one column") + " where " + ("two panes" if want else "one column") + " was expected")
lay = "two panes" if d["wide"] else "one column"
print(("::error::" if bad else "::notice::") + f"{name} ({lay}, view {vw}x{vh}): " + ("; ".join(bad) if bad else "the live wallpaper and its action in view"))
PY
}

# ---- 1. Each settings screen in a pop-up window, wide then narrow
for act in SettingsActivity SiparioActivity; do
  adb shell am force-stop $PKG
  adb shell am start -W --windowingMode 5 -n $PKG/.$act >/dev/null; sleep 5
  set -- $(task $PKG); id=$1; mode=$2
  echo "$act task: $id $mode" | tee -a $out
  if [ "$mode" != freeform ]; then warn "$act in a pop-up window: the emulator would not open one (mode ${mode:-none}); skipped"; continue; fi
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$id" 0 0 1503 1568; sleep 5; shot "$act-popup-wide"
  check "$act in a wide pop-up window" true
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$id" 0 0 740 1568; sleep 5; shot "$act-popup-narrow"
  check "$act in a narrow pop-up window" false
done
adb shell am force-stop $PKG; sleep 2

# ---- 2. Solco's settings keep their place across a turn
adb shell run-as $PKG rm -f files/layout.json
adb shell am start -W --windowingMode 1 -n $PKG/.SettingsActivity >/dev/null; sleep 5
layout >/dev/null
# Scroll the choices (the right pane on the main screen) part way down
adb shell input swipe 1150 1300 1150 700 600; sleep 2; shot scrolled
adb shell run-as $PKG rm -f files/layout.json
adb shell settings put system user_rotation 1; sleep 5; shot scrolled-turned
s=$(layout | python3 -c "import sys,json;print(json.load(sys.stdin)['scroll'])" 2>/dev/null)
if [ -z "$s" ] || [ "$s" = -1 ]; then warn "Settings across a turn: no scroll position recorded; skipped"
elif [ "$s" -gt 0 ]; then note "Settings across a turn: kept their place (scrolled $s px)"
else fail "Settings across a turn: back at the top after the turn"; fi
adb shell settings put system user_rotation 0; sleep 3
adb shell am force-stop $PKG
adb shell wm size reset; adb shell wm density reset

adb logcat -d > app-log-windows.txt
if grep -A2 "FATAL EXCEPTION" app-log-windows.txt | grep -q "Process: $PKG"; then fail "Solco crashed in the windows test"; grep -A20 "FATAL EXCEPTION" app-log-windows.txt | head -40; fi
grep -q "^::error::" $out && exit 1
exit 0
