#!/usr/bin/env bash
# EarPopup X - ADB 后台 / 悬浮窗权限增强脚本
# 说明：ADB 只能"授权"，不能把普通 APK 变成系统级 SystemUI 弹窗。
set -u
PKG=com.earpopupx

echo "============================================================"
echo "  EarPopup X  -  ADB 后台 / 悬浮窗权限增强脚本"
echo "============================================================"
echo

if ! adb shell "echo ok" >/dev/null 2>&1; then
  echo "  [X] 没有检测到在线设备。请确认 USB 调试已开启且已授权。"
  exit 1
fi
echo "  [OK] 设备已连接：$(adb shell getprop ro.product.model | tr -d '\r')"

if adb shell pm list packages | grep -qi "$PKG"; then
  echo "  [OK] 已找到 $PKG"
else
  echo "  [!] 未找到 $PKG，请先安装 APK（授权仍会保留）。"
fi
echo

op() {
  adb shell appops set "$PKG" "$1" "$2" >/dev/null 2>&1 || \
    adb shell cmd appops set "$PKG" "$1" "$2" >/dev/null 2>&1
  echo "  - $1 = $2"
}

echo "[1/6] 悬浮窗 SYSTEM_ALERT_WINDOW（最关键）"
op SYSTEM_ALERT_WINDOW allow
echo "[2/6] 后台运行"
op RUN_IN_BACKGROUND allow
op RUN_ANY_IN_BACKGROUND allow
echo "[3/6] 后台启动前台服务"
op START_FOREGROUND allow
echo "[4/6] 通知"
op POST_NOTIFICATION allow
echo "[5/6] Doze 白名单"
adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1
adb shell cmd deviceidle whitelist "+$PKG" >/dev/null 2>&1
echo "  - 已加入白名单"
echo "[6/6] 拉起监听服务"
adb shell am force-stop "$PKG" >/dev/null 2>&1
adb shell am start-foreground-service -n "$PKG/.BluetoothMonitorService" >/dev/null 2>&1 \
  || adb shell am startservice -n "$PKG/.BluetoothMonitorService" >/dev/null 2>&1
echo "  - 已尝试拉起"
echo
echo "============================================================"
echo "  最终 appops 状态："
adb shell appops get "$PKG"
echo "============================================================"
echo "  HyperOS 仍需手动：后台弹出界面 + 自启动 允许"
echo "============================================================"
