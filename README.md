# aw-android（个人版）

只用来记录手机使用：应用使用时长、解锁次数和 Via 等浏览器的网页访问。数据保存在本机，并通过 Webhook 发送到自己的电脑（ROG）做后续分析。

基于 [ActivityWatch for Android](https://github.com/ActivityWatch/aw-android)（MPL-2.0）修改。

## 和上游的区别

- **新的界面**：内置在 APK 里的深色仪表盘（`mobile/src/main/assets/dashboard`），只有四页：
  - 时间线：按小时分组的垂直时间线，浏览器会话下列出访问过的网页；
  - 统计：应用/网站占比环形图，加 24 小时分布；
  - 隐私：把指定应用、网站或关键词显示为统一的名称（例如“娱乐”）；
  - 设置：Webhook 状态、采集权限。
- **隐私规则同时作用于界面和 Webhook 导出**（`privacy/PrivacyRules.kt`）。命中的记录只保留时间和时长，应用名、包名、网址、标题都替换成设定的名称。还可以让所有网址只保留域名。
- **去掉的部分**：上游 Web UI（含秒表、分类、捐赠和仓库链接）、导航抽屉、桌面小部件、分类时长提醒、aw-sync 目录同步和 API 认证设置页、欢迎页。

## 构建

不需要 Rust 工具链：Kotlin 部分搭配上游 v0.14.3 发布包里的原生库编译，见 [docs/build-via-webhook.md](docs/build-via-webhook.md)。

推送到 `claude/**`、`feat/**` 或 `personal/**` 分支时，GitHub Actions 的 **Personal build** 工作流会跑测试并产出 arm64 debug APK（在运行详情页的 Artifacts 下载）。想直接覆盖安装已有的 app，需要用同一把 debug 签名：把 `base64 -w0 ~/.android/debug.keystore` 的结果存为仓库 Secret `DEBUG_KEYSTORE_BASE64`。

测试：

```sh
./gradlew :mobile:testStandardDebugUnitTest
node --test mobile/src/test/js/model.test.js integrations/rog-webhook/activitywatch.test.mjs integrations/rog-webhook/receiver.test.mjs
```

在电脑浏览器里调界面时，可以给 `assets/dashboard/index.html` 注入一个假的 `window.AWNative`（接口见 `dashboard/DashboardBridge.kt`）。
