#!/usr/bin/env bash
# Drives the bottom navigation on the emulator with real animations ON and prints frame statistics (jank) per build.
# Informational only (emulator rendering is slow in absolute terms; compare debug vs release and before/after).
tap_text() {  # tap the centre of the first UI node whose text equals $1
  adb shell uiautomator dump /sdcard/u.xml 2>&1 | head -2 | sed 's/^/  dump: /'; adb pull /sdcard/u.xml u.xml 2>&1 | head -1 | sed 's/^/  pull: /'; [ -n "$DBG" ] || { head -c 600 u.xml; echo; DBG=1; }
  xy=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as ET
t=sys.argv[1]
for n in ET.parse('u.xml').iter('node'):
    if n.get('text')==t:
        m=re.findall(r'\d+',n.get('bounds')); x1,y1,x2,y2=map(int,m); print((x1+x2)//2,(y1+y2)//2); break
PY
)
  [ -n "$xy" ] && adb shell input tap $xy || echo "  (no '$1' on screen)"
}
run() {
  local pkg=$1 label=$2
  echo "=================== PERF: $label ($pkg) ==================="
  adb shell pm clear $pkg >/dev/null
  adb shell am start -W -n $pkg/com.smartmanager.installments.MainActivity >/dev/null; sleep 5
  tap_text "Continue in read-only mode"; sleep 3
  adb shell dumpsys gfxinfo $pkg reset >/dev/null
  for round in 1 2 3; do
    for tab in Customers Payments Contracts Reports Home; do tap_text "$tab"; sleep 1.2; done
  done
  adb shell dumpsys gfxinfo $pkg | grep -E "Total frames rendered|Janky frames|50th|90th|95th|99th|Number Slow"
}
adb shell settings put global window_animation_scale 1.0
adb shell settings put global transition_animation_scale 1.0
adb shell settings put global animator_duration_scale 1.0
run com.smartmanager.installments.debug "DEBUG build"
run com.smartmanager.installments "RELEASE build (customer-style, R8 + baseline profile)"
