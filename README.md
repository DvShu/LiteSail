# LiteSail · 轻舟浏览器

基于系统 WebView 的轻量 Android 浏览器。当前版本是 **v0.1.0**。启动后显示轻舟首页，点击「搜索或输入网址」进入搜索页。搜索页可直接打开网址、使用 Bing 搜索关键词，也可点选本机的搜索历史。浏览页支持站内链接、前进、后退和刷新。点地址栏可在搜索页查看当前网页，复制或编辑链接。省略协议的网址默认使用 HTTPS，也可以显式输入 `http://`。Android 8.0（API 26）及以上可安装。暂不包含多标签、隐私模式和设置。

## 构建与运行

使用 JDK 17、Android SDK Platform 35、Build-Tools 36.0.0、NDK 27.1.12297006 和 CMake 3.22.1。APK 仅支持 ARM 64 位（`arm64-v8a`）。在 Android Studio 中打开仓库根目录，完成同步后连接支持该架构的设备，运行 `app`。

也可以先配置 `ANDROID_HOME`（或由 Android Studio 生成 `local.properties`），然后在 Windows PowerShell 中执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

在 macOS / Linux 使用 `./gradlew`，参数相同。APK 按 `LiteSail-v<versionName>.apk` 命名，调试包和正式包分别位于 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/release/`。连接已启用 USB 调试的设备后，可用 `adb install -r app/build/outputs/apk/debug/LiteSail-v0.1.0.apk` 安装当前版本的调试包。

## 源码结构

- `app/src/main/java/com/joel/litesail/` 按功能组织。`main/` 管理应用入口和页面切换，`browser/` 处理网址，`search/` 管理搜索历史，`scan/` 包含扫码界面、相机和解码器。
- `app/src/main/cpp/CMakeLists.txt` 注册原生模块。`cpp/quirc/` 包含自己的构建配置、JNI 接口和 `upstream/` 源码。新增原生模块时，在 `cpp/` 下为它建独立目录，再从顶层 CMake 注册。

## 手动验收

首页点「扫一扫」，授权后分别扫描网址和文本二维码。扫码结果页的网址显示「立即访问」并在浏览页打开，文本显示「立即搜索」并打开 Bing 搜索。

1. 打开应用并点击「搜索或输入网址」。在搜索页输入 `example.org` 后点「搜索」，确认浏览页地址栏显示网址。
2. 点击网页链接，确认仍在应用内浏览；验证前进、后退和系统返回键。
3. 从首页重新进入搜索页，输入中文搜索词，确认打开 Bing 搜索结果。再次进入搜索页，确认两条历史可点选。检查浏览页的刷新和首页按钮。
4. 断网后打开一个未缓存的网址，确认出现加载失败提示；恢复网络后点击「重试」，确认能重新加载。

## 常用指令

- 「提交代码」：提交并推送源码，不打包。
- 「打调试包」：生成调试 APK，不提交或推送。
- 「发布版本」：按指定级别升级版本号，构建签名 Release APK，提交并推送代码和标签，提供可复制的发布标题、更新内容和 GitHub Release 编辑页链接。由你在浏览器手动填写、上传 APK 并发布。

版本升级规则和发布检查见[仓库约定](AGENTS.md#发布版本)。

正式签名从本机环境读取 `LITESAIL_RELEASE_KEYSTORE`、`LITESAIL_RELEASE_KEY_ALIAS` 和 `LITESAIL_RELEASE_STORE_PASSWORD`。如果密钥密码与密钥库密码不同，可通过 `LITESAIL_RELEASE_KEY_PASSWORD` 单独提供。

后续阶段见[项目计划](docs/plan.md)。
