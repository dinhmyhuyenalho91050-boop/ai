# WebAppContainer

该项目将现有的静态 HTML 页面封装到 Android WebView 中，提供全屏沉浸、软键盘自适应以及明文 HTTP 支持。仓库包含：

- `app/` Android 应用源码（Kotlin + Material 3 Edge-to-edge 主题）
- `app/src/main/assets/index.html` 经过适配的网页资源
- `.github/workflows/android.yml` GitHub Actions 流水线，会自动打包 release APK 并上传产物

## 本地构建

1. 安装 Android SDK 及 build-tools（API 34）
2. 安装 Gradle 8.7 或以上版本
3. 执行 `gradle assembleDebug` 或 `gradle assembleRelease`

生成的 APK 位于 `app/build/outputs/apk/`。

## 特性

- Edge-to-edge + `WindowInsets` 方案，沉浸式并自动处理刘海区
- WebView 与前端同时监听 VirtualKeyboard/VisualViewport，软键盘弹出时自动调整布局
- `network_security_config.xml` 放开明文 HTTP
- GitHub Actions 采用官方 `gradle-build-action` 下载指定 Gradle 版本，适配“禁止二进制文件”限制
