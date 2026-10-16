#!/usr/bin/env bash
# Click-through smoke test, run by .github/workflows/smoke.yml inside the emulator runner.
set -x
OUT=smoke-out
PKG=app.aivoicetotext
mkdir -p $OUT
adb logcat -c

shot() { adb exec-out screencap -p > "$OUT/$1.png"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }

# tap the centre of a node whose text/content-desc equals $1 (else contains $1); scrolls down if needed
tap() {
  for attempt in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    python3 - "$1" <<'EOF2' > /tmp/xy
import re, sys
want = sys.argv[1]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
nodes = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n).group(1)
    d = re.search(r'content-desc="([^"]*)"', n).group(1)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    nodes.append((t, d, tuple(map(int, b.groups()))))
hit = [x for x in nodes if x[0] == want or x[1] == want] or [x for x in nodes if want in x[0] or want in x[1]]
if hit:
    x1, y1, x2, y2 = hit[0][2]
    print((x1 + x2) // 2, (y1 + y2) // 2)
EOF2
    if [ -s /tmp/xy ]; then adb shell input tap $(cat /tmp/xy); echo "tapped '$1'"; sleep 2; return 0; fi
    read W H < <(adb shell wm size | tail -1 | sed 's/.*: //; s/x/ /')
    adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300
    sleep 1
  done
  echo "NOT FOUND: '$1'" | tee -a $OUT/missing.txt
  return 1
}

adb install -r app.apk 2>&1 | tee $OUT/install.txt
adb shell getprop ro.product.cpu.abilist > $OUT/abi.txt
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS

adb shell am start -n $PKG/.ui.MainActivity
sleep 10
shot 01-onboarding
tap "Пропустить"
sleep 3
shot 02-notes

# record ~8 s
tap "Запись"
sleep 8
shot 03-recording
tap "Метка"
tap "Пауза"
sleep 2
tap "Продолжить"
sleep 3
tap "Готово"
sleep 12
shot 04-after-record

# open the note
tap "Запись "
sleep 4
shot 05-note
adb shell input keyevent KEYCODE_BACK
sleep 2

# settings and models
tap "Настройки"
sleep 2
shot 06-settings
tap "Модели"
sleep 4
shot 07-models
adb shell input keyevent KEYCODE_BACK
sleep 1
tap "Шаблоны ИИ-действий"
sleep 2
shot 08-templates
adb shell input keyevent KEYCODE_BACK
sleep 1
tap "О приложении"
sleep 2
shot 09-about
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
sleep 1

# quick-record activity (widget / tile path)
adb shell am start -n $PKG/.record.QuickRecordActivity
sleep 6
shot 10-quickrecord
adb shell am start -n $PKG/.record.QuickRecordActivity
sleep 8
shot 11-quickrecord-stopped

adb shell dumpsys activity services $PKG > $OUT/services.txt
adb shell run-as $PKG ls -la files files/audio files/pcm > $OUT/files.txt 2>&1 || true
adb logcat -d > $OUT/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime|$PKG.*(Exception|Error)" -A 30 $OUT/logcat.txt > $OUT/crashes.txt || true
echo "crash lines: $(wc -l < $OUT/crashes.txt)"
exit 0
