@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
set PKG=com.earpopupx

echo ============================================================
echo   EarPopup X  -  ADB 后台 / 悬浮窗权限增强脚本
echo   用途：让普通 APK 能稳定地弹出全局悬浮窗
echo   说明：ADB 只能"授权"，不能把普通 APK 变成系统级 SystemUI 弹窗
echo ============================================================
echo.

echo [0/9] 检查 ADB 与设备...
adb shell "echo ok" >nul 2>&1
if errorlevel 1 (
    echo   [X] 没有检测到在线设备。
    echo       请确认：开发者选项 - USB 调试 已开启，且本机已授权。
    echo.
    pause
    exit /b 1
)
for /f "tokens=*" %%A in ('adb shell getprop ro.product.model') do set MODEL=%%A
echo   [OK] 设备已连接：!MODEL!
echo.

echo [1/9] 确认应用已安装...
adb shell pm list packages | findstr /i "%PKG%" >nul
if errorlevel 1 (
    echo   [!] 未找到 %PKG%，请先安装 APK。仍继续授权（安装后即生效）。
) else (
    echo   [OK] 已找到 %PKG%
)
echo.

echo [2/9] 授予悬浮窗权限 SYSTEM_ALERT_WINDOW（最关键）...
call :op SYSTEM_ALERT_WINDOW allow
echo.

echo [3/9] 允许后台运行 RUN_IN_BACKGROUND...
call :op RUN_IN_BACKGROUND allow
echo.

echo [4/9] 允许任意后台运行 RUN_ANY_IN_BACKGROUND...
call :op RUN_ANY_IN_BACKGROUND allow
echo.

echo [5/9] 允许后台启动前台服务 START_FOREGROUND...
call :op START_FOREGROUND allow
echo.

echo [6/9] 允许通知 POST_NOTIFICATION...
call :op POST_NOTIFICATION allow
echo.

echo [7/9] 加入 Doze / 省电白名单...
adb shell dumpsys deviceidle whitelist +%PKG% >nul 2>&1
adb shell cmd deviceidle whitelist +%PKG% >nul 2>&1
echo   [OK] 已尝试加入白名单
echo.

echo [8/9] 关闭电池优化...
adb shell dumpsys battery unplug >nul 2>&1
adb shell settings put global battery_saver_constants "" >nul 2>&1
echo   [i] 电池优化请在 设置 - 应用设置 - 应用管理 - EarPopup X - 省电策略 选"无限制"
echo.

echo [9/9] 重启监听服务...
adb shell am force-stop %PKG% >nul 2>&1
adb shell am start-foreground-service -n %PKG%/.BluetoothMonitorService >nul 2>&1
if errorlevel 1 (
    adb shell am startservice -n %PKG%/.BluetoothMonitorService >nul 2>&1
)
echo   [OK] 已尝试拉起服务
echo.

echo ============================================================
echo   最终 appops 状态：
echo ============================================================
adb shell appops get %PKG%
echo.
echo ------------------------------------------------------------
echo   HyperOS 仍需手动确认的两处（ADB 无法代替）：
echo     1. 设置 - 应用设置 - 应用管理 - EarPopup X - 权限管理
echo        -^> 后台弹出界面：允许
echo     2. 设置 - 应用设置 - 应用管理 - EarPopup X - 自启动：允许
echo ------------------------------------------------------------
echo.
pause
exit /b 0

:op
set OP=%~1
set VAL=%~2
adb shell appops set %PKG% %OP% %VAL% >nul 2>&1
if errorlevel 1 (
    adb shell cmd appops set %PKG% %OP% %VAL% >nul 2>&1
)
echo   - %OP% = %VAL%
exit /b 0
