:: 耳机弹窗 ADB 授权脚本（Windows 双击运行，手机需开启 USB 调试并已连接）
:: 如提示找不到 adb，把 platform-tools 里的 adb.exe 放到本文件同目录
@echo off
set PKG=com.yuanbao.earbuds

echo === 1/3 悬浮窗权限 ===
adb shell appops set %PKG% SYSTEM_ALERT_WINDOW allow

echo === 2/3 蓝牙 / 通知 / 存储 权限 ===
adb shell pm grant %PKG% android.permission.BLUETOOTH_CONNECT
adb shell pm grant %PKG% android.permission.BLUETOOTH_SCAN
adb shell pm grant %PKG% android.permission.POST_NOTIFICATIONS
adb shell pm grant %PKG% android.permission.READ_MEDIA_IMAGES
adb shell pm grant %PKG% android.permission.READ_EXTERNAL_STORAGE

echo === 3/3 省电白名单 ===
adb shell dumpsys deviceidle whitelist +%PKG%

echo === 可选：屏蔽小米原生快连弹窗（只禁悬浮窗，不影响蓝牙连接） ===
:: adb shell appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore

echo 完成。若某条报错，多为该权限在本系统版本不存在，可忽略。
pause
