@echo off
setlocal
set PKG=com.yuanbao.earbuds

echo ========================================
echo   耳机弹窗 - HyperOS 超级岛 ADB 配置
echo ========================================
echo.

echo [1/8] 检查设备...
adb get-state >nul 2>&1 || (
  echo 未检测到 ADB 设备，请开启 USB 调试后重试。
  pause
  exit /b 1
)

echo [2/8] 悬浮窗权限...
adb shell appops set %PKG% SYSTEM_ALERT_WINDOW allow

echo [3/8] 蓝牙连接权限...
adb shell pm grant %PKG% android.permission.BLUETOOTH_CONNECT 2>nul
adb shell pm grant %PKG% android.permission.BLUETOOTH_SCAN 2>nul

echo [4/8] 通知权限...
adb shell pm grant %PKG% android.permission.POST_NOTIFICATIONS 2>nul

echo [5/8] 图片权限（系统版本允许时授予）...
adb shell pm grant %PKG% android.permission.READ_MEDIA_IMAGES 2>nul
adb shell pm grant %PKG% android.permission.READ_EXTERNAL_STORAGE 2>nul

echo [6/8] 加入 Doze 白名单...
adb shell dumpsys deviceidle whitelist +%PKG%

echo [7/8] 输出 HyperOS 岛/通知诊断...
echo --- focus protocol ---
adb shell settings get system notification_focus_protocol
echo --- appops overlay ---
adb shell appops get %PKG% SYSTEM_ALERT_WINDOW

echo [8/8] 完成。
echo.
echo 注意：超级岛本身还需要在 HyperOS 的通知/焦点通知设置中允许本 App。
echo 如果你的 ROM 没有该设置，App 会自动回退到悬浮窗。
echo.
pause
