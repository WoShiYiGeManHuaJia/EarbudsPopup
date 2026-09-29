# Ultimate 2.0 audit

本版针对上一版问题进行结构性修正：
1. 不从 BOOT_COMPLETED 启动 connectedDevice FGS，规避 Android 15 限制。
2. Bluetooth receiver 使用 EXPORTED 接收系统蓝牙广播。
3. ACL_CONNECTED 立即弹窗，再异步读取电量。
4. 服务启动后主动扫描 bonded + STATE_CONNECTED，避免错过已连接耳机。
5. GATT reader 超时并关闭连接。
6. 所有 popup UI 操作切到 main looper。
7. Popup 使用单例，避免测试弹窗和服务弹窗叠加。
8. 测试弹窗有关闭按钮。
9. 素材父容器使用 clipToOutline + 圆角 Outline。
10. 拖动定位保存 X/Y，设置栏可实时刷新。
11. 停止监听会主动移除 popup。
12. FGS 使用 connectedDevice 类型和对应权限。
13. ADB 仅做合法 AppOps/Doze 辅助，不声称获得 SystemUI 签名。

无法在当前环境完成真实 Android SDK/HyperOS 真机验证，因此不声称数学意义上的“零 bug”。
