#!/usr/bin/env bash
# Runs inside the Android emulator. Exits non-zero if any REQUIRED step fails (the A step is expected to fail by design).
R=https://raw.githubusercontent.com/SmartManagerCRM/Installments
P=com.smartmanager.installments.debug
REL=com.smartmanager.installments
FAILS=0
need() { if ! grep -q "$1" "$2"; then echo "REQUIRED STEP FAILED: expected '$1' in $2"; FAILS=$((FAILS+1)); fi; }
NEW=$(ls apk/*.apk)
RELAPK=$(ls rel/*.apk)

echo "=== A) old random-key build -> new build: Android must refuse (explains the old 'App not installed') ==="
curl -sSL -o old_random_key.apk $R/b42483e470f7b61895f79b463ce7b91ef319d7cc/SmartManagerInstallments-debug.apk
adb install old_random_key.apk 2>&1 | tee a1.log
adb install -r "$NEW" 2>&1 | tee a2.log
need INSTALL_FAILED_UPDATE_INCOMPATIBLE a2.log
adb uninstall $P

echo "=== B) debug build: clean install, launch, same-key update keeps data ==="
adb install "$NEW" 2>&1 | tee b1.log; need Success b1.log
adb shell "run-as $P sh -c 'mkdir -p /data/data/$P/files && echo keepme > /data/data/$P/files/marker.txt'"
adb install -r "$NEW" 2>&1 | tee b2.log; need Success b2.log
adb shell "run-as $P cat /data/data/$P/files/marker.txt" | tee b3.log; need keepme b3.log
adb shell am start -W -n $P/com.smartmanager.installments.MainActivity | tee b4.log; need "Status: ok" b4.log
sleep 8
adb shell pidof $P | tee b5.log; [ -s b5.log ] || { echo "REQUIRED STEP FAILED: debug app not running"; FAILS=$((FAILS+1)); }

echo "=== C) CUSTOMER-style release build (R8, signed by the License Manager signer): clean install + launch ==="
adb install "$RELAPK" 2>&1 | tee c1.log; need Success c1.log
adb shell am start -W -n $REL/com.smartmanager.installments.MainActivity | tee c2.log; need "Status: ok" c2.log
sleep 8
adb shell pidof $REL | tee c3.log; [ -s c3.log ] || { echo "REQUIRED STEP FAILED: release app not running"; FAILS=$((FAILS+1)); }
adb logcat -d -s AndroidRuntime:E | tail -40
adb logcat -d | grep -c "FATAL EXCEPTION" | tee crash.count
[ "$(cat crash.count)" = "0" ] || { echo "REQUIRED STEP FAILED: crash during launch"; FAILS=$((FAILS+1)); }
[ "$MATRIX_API" = "34" ] && bash .github/ci/perf-test.sh
echo "FAILS=$FAILS"
exit $FAILS
