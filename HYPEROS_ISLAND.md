# HyperOS 原生超级岛接入说明

本版本增加了 **HyperOS 原生超级岛通知引擎**。核心不是自己画一个 Overlay，而是向 Android `Notification` 写入小米公开的 `miui.focus.param` / `miui.focus.pics` 扩展，由 HyperOS SystemUI 负责顶部胶囊、展开、收起和层级。

## 引擎

- `0` 系统级透明 Activity
- `1` Overlay
- `2` 原有智能模式
- `3` HyperOS 原生超级岛
- `4` HyperOS 智能（默认）：HyperOS 超级岛 → Overlay/Activity 回退

## 为什么默认用 4

HyperOS 3 才提供“小米超级岛”完整形态；小米公开文档同时说明 OS2/OS3 使用焦点通知数据接口，但 OS3 才支持超级岛。App 会查询 `notification_focus_protocol`，并通过 `content://miui.statusbar.notification.public` 检查焦点通知权限。检查失败不会让 APP 崩溃，而是自动回退到旧弹窗。

## 自定义图片 / GIF

- PNG/JPG/WEBP 等静态图片：会尝试通过 `miui.focus.pics` 传给 SystemUI，作为小岛/大岛图片。
- GIF：原生超级岛协议没有在公开文档中承诺“任意 GIF 动画播放”。因此本版本检测到 GIF 时，HyperOS 智能模式自动使用原有 Overlay GIF 引擎，保证 GIF 功能不丢。
- 如果未来实机确认某个 HyperOS 3 模板支持动画资源，可以再单独增加动态模板适配，不把它写死成所有 ROM 都支持。

## ADB

运行 `一键授权ADB权限.bat` 可完成常用的：

```bash
adb shell appops set com.yuanbao.earbuds SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_SCAN
adb shell pm grant com.yuanbao.earbuds android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +com.yuanbao.earbuds
adb shell settings get system notification_focus_protocol
```

ADB **不会伪造系统签名，也不会替换 SystemUI**。它只是辅助授予普通权限、检查协议版本和降低后台回收概率。

## 重要：原生超级岛不需要关闭 Xiaomi SystemUI

与旧 Overlay 方案不同，HyperOS 原生超级岛路线不需要屏蔽小米 SystemUI 的弹窗窗口。你的通知本身交给 SystemUI 渲染，因此不会出现“两个第三方悬浮窗互相抢位置”的设计问题。

如果小米自己的“快连”弹窗仍然与第三方耳机通知同时出现，这是两个独立业务：本项目只能控制自己的通知，不能保证所有 HyperOS 版本都允许第三方关闭 OEM 私有快连业务。
