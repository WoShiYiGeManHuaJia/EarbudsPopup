# EarPopup X Ultimate 2.0

针对 Redmi K70 Pro / HyperOS / 无 Root 的完整源码工程。

## 核心
- 蓝牙 ACL 连接立即弹窗，不等待电量。
- 系统蓝牙电量广播到达后实时更新。
- 标准 BLE Battery Service 作为后备读取。
- 更换耳机按 BluetoothDevice 地址重新建立会话。
- 自定义 PNG/JPG/WebP/GIF，GIF 保持动画。
- 素材圆角裁剪。
- 动态 blur behind（设备支持时）+ dim fallback。
- 关闭按钮。
- X/Y/宽高/圆角/模糊/压暗/显示时间可调。
- 测试弹窗支持拖动定位并保存位置。
- connectedDevice 前台服务。
- Android 15 不从 BOOT_COMPLETED 强行启动 FGS。
- ADB 辅助脚本。

## 重要边界
无 Root 的普通 APK 无法注入 com.android.systemui / com.xiaomi.bluetooth 的系统进程，因此本项目使用 TYPE_APPLICATION_OVERLAY。真正 HyperOS Focus Island 系统注入需要 Root + LSPosed。

## 构建
需要 Android Studio / JDK 17 / Android SDK / Gradle 8.13 + AGP 8.13.0。项目不包含 Gradle wrapper jar，因此可由 Android Studio 自动导入或在已安装 Gradle 的环境构建。
