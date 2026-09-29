#!/usr/bin/env bash
set -e
PKG=com.earpopupx
adb wait-for-device
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell cmd deviceidle whitelist +"$PKG"
adb shell appops set "$PKG" RUN_IN_BACKGROUND allow
adb shell appops set "$PKG" RUN_ANY_IN_BACKGROUND allow
adb shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT || true
adb shell pm grant "$PKG" android.permission.BLUETOOTH_SCAN || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
echo "Done. Open EarPopup X, enable Overlay, then tap Start/Resume Monitor."
