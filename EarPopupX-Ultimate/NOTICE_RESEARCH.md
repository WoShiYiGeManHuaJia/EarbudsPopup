# Research basis

本工程的架构依据 Android 官方文档以及公开 HyperOS 耳机项目设计：
- Android Foreground Service connectedDevice
- SYSTEM_ALERT_WINDOW / TYPE_APPLICATION_OVERLAY
- Android 12+ blur APIs
- 公开 HyperOS 耳机集成项目所展示的 MiLink / Xiaomi Bluetooth Hook 链路

本工程不复制第三方项目的私有代码；仅采用公开的系统架构事实，并在无 Root 条件下实现独立的 Overlay 方案。
