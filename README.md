# 耳机弹窗 · 自定义弹窗动画（红米 / 小米 免 Root）

> 仓库：<https://github.com/WoShiYiGeManHuaJia/EarbudsPopup>
> 已配好云端自动编译：push 或手动触发 Actions，几分钟后就能拿到 APK。

一个给红米、小米手机做的**自定义耳机连接弹窗**应用：耳机一连上，就用你自己的图片 / GIF / 文案在屏幕上弹出动画卡片。

- **不需要 Root**，只需要一次 ADB 授权或直接点开关
- **不依赖小米快连**：任何蓝牙耳机都能触发（小米 Buds、AirPods、QCY、漫步者、三星……）
- 有线耳机（3.5mm / Type-C）插入也能弹
- 弹窗内容完全自定义：图片或 GIF、主副标题、背景色、文字色、圆角、宽度、位置、入场动画、显示时长
- 支持设备白名单：只给指定的耳机弹窗

---

## 〇、直接下载编译好的 APK（最快）

- 仓库 Actions 页面：<https://github.com/WoShiYiGeManHuaJia/EarbudsPopup/actions> → 点进最新一次成功的运行 → 最下方 **Artifacts** → `earbuds-popup-debug`
- 或直接取仓库 `apk` 分支里的文件：
  <https://github.com/WoShiYiGeManHuaJia/EarbudsPopup/blob/apk/apk/earbuds-popup-debug.apk>
  （点右上角 `⋯` / `Download raw file` 即可下载）

## 一、重新编译：用 GitHub 免费编译（不需要电脑）

我这边无法直接帮你编译出 APK，但 GitHub Actions 可以在云端免费编好，你用手机就能下载安装。

1. 手机上访问 <https://github.com/new> 注册/登录，新建仓库，名字随意，勾选 **Add a README**。
2. 把本压缩包里的**全部文件**上传上去（GitHub 网页上点 `Add file` → `Upload files`，整个文件夹拖进去即可；`gradle/` 和 `gradlew` 也要一起传）。
3. 上传完，点仓库顶部的 **Actions** 标签页 → 左侧选 `Build APK` → 右侧点 **Run workflow** → 再点绿色按钮确认。
4. 等 3–8 分钟，变绿后点进这次运行，最下方 **Artifacts** 里的 `earbuds-popup-debug` 就是 APK，点一下下载。
5. 手机上安装（会提示未知来源，允许即可）。

> 想重新编译：改完代码再 push 一次也会自动触发编译。

## 二、想在电脑上编译

- 安装 Android Studio（自带 JDK 17 + Android SDK），`File → Open` 选这个文件夹，等 Gradle 同步完成，点 `Build → Build Bundle(s)/APK(s) → Build APK`。
- 或者命令行：`./gradlew assembleDebug`，产物在 `app/build/outputs/apk/debug/`。

## 三、装好之后必做的 4 步（重点）

### 1. 给悬浮窗权限（最关键）
打开 App → 点「去授权」→ 打开「显示在其他应用上层 / 悬浮窗」。
红米上如果开关是灰的或找不到，用 ADB（见下）。

### 2. 关掉小米原生快连弹窗，否则会两个弹窗打架
- 路径 A：设置 → 蓝牙 → 点耳机名字右边的 `>` 或 `i` → 关闭「连接弹窗 / 弹窗动画」。
- 路径 B：设置 → 通知与控制中心 → 通知管理 → 蓝牙 → 关掉悬浮通知。
- 路径 C（ADB，温和、不影响连接）：
  ```
  adb shell appops set com.xiaomi.bluetooth SYSTEM_ALERT_WINDOW ignore
  ```

### 3. 让它别被系统杀掉
设置 → 应用设置 → 应用管理 → 耳机弹窗 →
- 自启动：开
- 省电策略 / 电池优化：选「无限制」
- 权限：允许后台运行、允许显示悬浮窗

### 4. 选图、调样式、点「立即测试弹窗」
App 里「选择图片/GIF」挑一张图或动图，勾上要触发的耳机，点测试就能看到效果。之后每次连接自动弹。

---

## 四、ADB 授权命令（一次性执行即可）

手机开「开发者选项 → USB 调试（安全设置）」，连电脑后逐行执行（包名已填好）：

```bash
adb shell appops set com.yuanbao.earbuds SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.yuanbao.earbuds android.permission.BLUETOOTH_SCAN
adb shell pm grant com.yuanbao.earbuds android.permission.POST_NOTIFICATIONS
adb shell pm grant com.yuanbao.earbuds android.permission.READ_MEDIA_IMAGES
adb shell pm grant com.yuanbao.earbuds android.permission.READ_EXTERNAL_STORAGE
adb shell dumpsys deviceidle whitelist +com.yuanbao.earbuds
```

没有电脑也能用：手机装 **Shizuku**（无线调试激活）+ **aShell / 终端模拟器**，在 aShell 里选 Shizuku 模式，把上面每行前面的 `adb shell ` 去掉后执行。

> 注意：小米开发者选项里「USB 调试（安全设置）」要单独打开，否则 `appops` 命令会报权限不足。

---

## 五、常见问题

**Q：耳机连上了但不弹？**
先点 App 里的「立即测试弹窗」：
- 测试也不弹 → 悬浮窗权限没给，或服务被杀了，回头看第三节。
- 测试能弹、实际连接不弹 → 该耳机只在真正建立音频连接时才广播，先播放一秒音乐再试；或在 App 里把该设备勾选上（白名单为空 = 所有设备都弹）。

**Q：电量不显示？**
安卓没有公开的耳机电量接口，这里用反射读系统隐藏接口，能读到就显示，读不到就自动隐藏电量行，属正常。小米/红米 Buds 一般能读到。

**Q：开盖就弹（不等音频连接）能做到吗？**
小米 Buds 的「开盖即弹」走的是小米私有快连协议，第三方 App 拿不到。本方案在蓝牙真正连上那一刻弹窗，这是免 Root 的通用上限。

**Q：想改成仿小米原生那种毛玻璃卡片？**
改 `app/src/main/res/layout/popup_card.xml`，把背景换成带模糊的样式，或在 `PopupService.render()` 里把 `GradientDrawable` 换成你自己的布局与动画。GIF 直接由 Glide 播放，不用额外写动画代码。

---

## 六、代码结构

| 文件 | 作用 |
|---|---|
| `PopupService.java` | 前台常驻服务，监听蓝牙/有线连接，负责弹出和移除悬浮窗 |
| `MainActivity.java` | 设置界面：权限、设备白名单、样式参数、测试弹窗 |
| `WiredReceiver.java` | 有线耳机插入广播（静态注册，豁免广播） |
| `BootReceiver.java` | 开机 / 应用更新后自启服务 |
| `Prefs.java` | 所有配置读写 |
| `res/layout/popup_card.xml` | 弹窗长什么样，改这里最直接 |

## 七、现成 App 备选（不想折腾源码的话）

- **轻弹窗 v6**：国产，支持 GIF、动画仓库、绑定蓝牙设备，酷安 / 应用商店可搜。
- **Bear Pop-up**：老牌弹窗工具，预设多款耳机动画，支持自定义图片与尺寸。
- **初音弹窗**：二次元角色立绘 + 音效，开箱即用。

本工程的优势是：源码在你手里，样式、触发逻辑、包名签名都能随便改，也不会有第三方 App 的广告和付费墙。

## 八、许可

代码可自由使用与修改，仅供个人学习与自用，请勿用于商业分发。
