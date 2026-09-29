# Security notes

- 不请求 Root。
- 不修改 SELinux、系统分区或 SystemUI APK。
- 不使用 Accessibility Service 绕过权限。
- ADB 脚本只针对本应用包名设置 AppOps / Doze 白名单。
- 电量数据没有可靠来源时显示未知，不生成随机或固定百分比。
- BLE GATT 读取仅尝试标准 Battery Service，不读取通讯录、消息等无关数据。
