# LiteSail · 轻舟浏览器

基于系统 WebView 的轻量 Android 浏览器。当前版本是 **v0.1.0 单标签原型**：支持输入网址或搜索词、站内链接、前进、后退、刷新、停止加载及返回首页。默认首页和搜索引擎均为百度；省略协议的网址默认使用 HTTPS，也可以显式输入 `http://`。Android 8.0（API 26）及以上可安装。暂不包含多标签、隐私模式和设置。

## 构建与运行

使用 JDK 17（推荐）和 Android SDK Platform 35、Build-Tools 35.0.0。在 Android Studio 中打开仓库根目录，完成同步后连接手机或启动模拟器，运行 `app`。

也可以先配置 `ANDROID_HOME`（或由 Android Studio 生成 `local.properties`），然后在 Windows PowerShell 中执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

在 macOS / Linux 使用 `./gradlew`，参数相同。调试 APK 位于 [`app-debug.apk`](app/build/outputs/apk/debug/app-debug.apk)；连接已启用 USB 调试的设备后可用 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装。

## 手动验收

1. 打开应用，确认默认首页加载；在地址栏输入 `example.org`，确认网页可打开。
2. 点击网页链接，确认仍在应用内浏览；验证前进、后退和系统返回键。
3. 输入中文搜索词，确认打开搜索结果；检查刷新、停止和首页按钮。
4. 断网后打开一个未缓存的网址，确认出现加载失败提示；恢复网络后点击「重试」，确认能重新加载。

## 常用指令

- 「提交代码」：提交并推送源码，不打包。
- 「打调试包」：生成调试 APK，不提交或推送。
- 「发布版本」：按指定级别升级版本号，构建签名 Release APK，提交并推送代码和标签，提供可复制的发布标题、更新内容和 GitHub Release 编辑页链接。由你在浏览器手动填写、上传 APK 并发布。

版本升级规则和发布检查见[仓库约定](AGENTS.md#发布版本)。

后续阶段见[项目计划](docs/plan.md)。
