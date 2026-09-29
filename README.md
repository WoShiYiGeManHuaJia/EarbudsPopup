# EarPopup X

Redmi / Xiaomi TWS 耳机弹窗（Android 10+，无需 Root）。

## 下载

最新构建（构建号不同则链接不同，避免缓存）：

```
https://cdn.jsdelivr.net/gh/WoShiYiGeManHuaJia/EarbudsPopup@apk/apk/EarPopupX.apk
```

## 源码

全部源码在 `EarPopupX-Ultimate/`，Android Studio 直接打开该目录即可。
JDK 17 / AGP 8.x / compileSdk 34。

## 说明

- 不依赖 Root、LSPosed、系统签名
- 使用 `TYPE_APPLICATION_OVERLAY` 悬浮窗
- 电量优先读取真实值，读不到显示"--"，不伪造
