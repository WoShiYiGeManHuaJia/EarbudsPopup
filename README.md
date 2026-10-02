# EarbudsPopup2

蓝牙耳机开盖弹窗（悬浮窗），支持竖屏 / 横屏两种形态、自定义图片与 GIF、卡片内毛玻璃模糊、自定义提示音。

当前版本：**1.6.1**（包名 `com.woshiyigemanhuajia.btpopup`）

## 构建

推送到 `main` 即触发 GitHub Actions 构建，产物：
- Artifact：`app-release-apk`
- `build-logs` 分支：`app.b64`（APK 的 base64）、`build_log.txt`

签名密钥 `release.keystore` 在库内，构建时自动使用。

## 结构

| 目录 | 说明 |
|---|---|
| `overlay/PopupOverlayManager` | 弹窗渲染、入场动画、朝向处理 |
| `widget/LiveBlurView` | 卡片内模糊层（Android 12+ 走 RenderEffect GPU 模糊） |
| `bluetooth/BluetoothPopupTrigger` | 连接事件触发与去重 |
| `battery/` | 电量读取（系统反射 / HFP / GATT） |
| `service/` | 监听服务、守护服务、保活 |
| `util/Prefs` | 全部外观与行为参数 |
