@echo off
set PKG=com.earpopupx
adb shell appops set %PKG% SYSTEM_ALERT_WINDOW allow
adb shell cmd deviceidle whitelist +%PKG%
adb shell cmd appops set %PKG% RUN_IN_BACKGROUND allow
adb shell cmd appops set %PKG% RUN_ANY_IN_BACKGROUND allow
echo.
echo EarPopup X ADB protection commands applied. If HyperOS still restricts autostart,
echo enable Auto-start and Battery -> No restrictions for EarPopup X in system settings.
pause
