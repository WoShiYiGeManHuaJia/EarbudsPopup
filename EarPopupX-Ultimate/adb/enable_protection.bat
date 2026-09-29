@echo off
set PKG=com.earpopupx
echo === EarPopup X Ultimate ADB Protection ===
adb wait-for-device
adb shell appops set %PKG% SYSTEM_ALERT_WINDOW allow
adb shell cmd deviceidle whitelist +%PKG%
adb shell appops set %PKG% RUN_IN_BACKGROUND allow
adb shell appops set %PKG% RUN_ANY_IN_BACKGROUND allow
adb shell pm grant %PKG% android.permission.BLUETOOTH_CONNECT 2>nul
adb shell pm grant %PKG% android.permission.BLUETOOTH_SCAN 2>nul
adb shell pm grant %PKG% android.permission.POST_NOTIFICATIONS 2>nul
echo.
echo Done. Open EarPopup X, enable Overlay, then tap Start/Resume Monitor.
pause
