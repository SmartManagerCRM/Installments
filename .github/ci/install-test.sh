#!/usr/bin/env bash
set +e
R=https://raw.githubusercontent.com/SmartManagerCRM/Installments
P=com.smartmanager.installments.debug
# Previously distributed APKs (each was signed by a different throw-away CI debug key):
curl -sSL -o old_random_key.apk $R/b42483e470f7b61895f79b463ce7b91ef319d7cc/SmartManagerInstallments-debug.apk
# Previous build that already uses the stable key:
curl -sSL -o old_stable_key.apk $R/b2d6ea81dd8caca89612ec15675bbd53a33c3999/SmartManagerInstallments-debug.apk
NEW=$(ls apk/*.apk)
echo "=== A) old random-key build -> new build (the user's failing case) ===" | tee -a install.log
adb install old_random_key.apk 2>&1 | tee -a install.log
adb install -r "$NEW" 2>&1 | tee -a install.log
adb uninstall $P
echo "=== B) stable-key build -> new build (update must succeed and keep data) ===" | tee -a install.log
adb install old_stable_key.apk 2>&1 | tee -a install.log
adb shell run-as $P sh -c 'echo keepme > files/marker.txt' | tee -a install.log
adb install -r "$NEW" 2>&1 | tee -a install.log
adb shell run-as $P cat files/marker.txt | tee -a install.log
adb shell am start -W -n $P/com.smartmanager.installments.MainActivity | tee start.log
sleep 8
adb shell pidof $P && echo APP_RUNNING
adb logcat -d -s AndroidRuntime:E | tail -30
