#!/usr/bin/env bash
# The weather tests on the unfolded screen, on an emulator of their own.
# $1 = "first" or "retry". On the first try, if the emulator itself dies (no test failure:
# the device is gone), the next step gets one fresh emulator to run them again.
attempt="$1"
set -x
# The host's memory through the run, to tell an out-of-memory kill from an emulator crash.
( while sleep 30; do echo "$(date +%T) $(free -m | awk '/Mem/{print $3"/"$2" MB used"}') load $(cut -d' ' -f1-3 /proc/loadavg)"; done ) > /tmp/hostmem-$attempt.log 2>&1 &
mon=$!
adb install -r testiconpack/build/outputs/apk/debug/testiconpack-debug.apk
adb push testiconpack/build/outputs/apk/debug/testiconpack-debug.apk /data/local/tmp/testiconpack.apk
if [ -f arcticons.apk ]; then adb install -r arcticons.apk || echo "Arcticons didn't install"; else echo "No Arcticons download"; fi
adb shell wm size 1840x2208
adb shell wm density 420
# Wait until the new size has really reached the display (a slow emulator can take a
# while), so it doesn't land mid-test and restart the activity under the first one.
for i in $(seq 1 60); do adb shell dumpsys window displays | grep -q "cur=1840x2208" && break; sleep 1; done
adb shell input keyevent KEYCODE_HOME
sleep 5
adb shell wm size
adb logcat -b crash -c || true
ok=1
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.shotPrefix=unfolded -Pandroid.testInstrumentationRunnerArguments.class=io.github.iamth3doctor.searchlight.WeatherSceneDeviceTest || ok=0
kill $mon 2>/dev/null || true
alive=1
timeout 20 adb get-state >/dev/null 2>&1 || alive=0
echo "host memory (last 20 samples):"; tail -n 20 /tmp/hostmem-$attempt.log || true
echo "host kernel log:"; sudo dmesg -T 2>/dev/null | grep -iE "oom|killed process|segfault|qemu|emulator|general protection" | tail -n 30 || true
ls -la /tmp/android-runner/ 2>/dev/null || true; find /tmp/android-runner -type f -newer /tmp/hostmem-$attempt.log 2>/dev/null | head -20
if [ "$ok" = 0 ] && [ "$alive" = 0 ] && [ "$attempt" = first ]; then
  echo "::warning::the emulator died during the unfolded weather tests; running them once more on a fresh one"
  echo "WEATHER_RETRY=1" >> "$GITHUB_ENV"
  exit 0
fi
[ "$ok" = 1 ] || touch /tmp/device-tests-failed
if [ "$alive" = 1 ]; then
  timeout 60 adb logcat -d -b crash | tail -n 150 || true
  timeout 60 adb logcat -d -s Watchdog:E lowmemorykiller:* lmkd:* | tail -n 120 || true
  # A stuck test's thread dump: where the render thread was waiting.
  timeout 30 adb root || true; timeout 60 adb wait-for-device || true
  timeout 60 adb shell 'for f in $(ls -t /data/anr/trace_* 2>/dev/null | head -2); do echo "== $f"; grep -A45 -E "^\"(main|RenderThread)\"" "$f" | head -120; done' || true
  timeout 60 adb shell 'cat /sdcard/Download/searchlight-shots/watchdog-*.txt' || true
  mkdir -p shots
  for i in 1 2 3 4 5 6; do timeout 300 adb pull /sdcard/Download/searchlight-shots/. shots/ && break; echo "adb pull failed, retrying"; adb reconnect || true; timeout 60 adb wait-for-device; sleep 5; done
fi
ls shots | wc -l
cat shots/widgets-*.txt || true
cat shots/watchdog-*.txt || true
exit 0
