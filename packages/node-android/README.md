# ApexPanda Android 节点

Android 设备节点客户端，连接 Gateway 后暴露移动端专属能力。与 Headless / 桌面节点能力互补，Agent 会根据在线节点能力自动筛选可用工具。

## 能力概览

### 基础能力（与桌面节点对比）

| 能力 | 说明 | 对比 |
|------|------|------|
| `camera.snap` | 手机摄像头拍照 | 桌面节点也有，参数相同 |
| `camera.clip` | 短视频（暂不支持） | 桌面可录 |
| `screen.record` | 手机屏幕录制（需先授权） | 桌面可录电脑屏幕 |
| `location.get` | 手机 GPS 定位 | **仅 Android 支持**，桌面无此能力 |

Android 节点 **不支持** `system.run`、`system.readFile` 等电脑端能力。

### UI 自动化能力（无障碍 + OCR + 图像匹配）

开启 **设置 → 无障碍 → ApexPanda 节点** 后支持：

| 能力 | 说明 |
|------|------|
| `ui.tap` | 点击：支持 `text`、`x,y`、`id`、`className`、`contentDesc` 等选择器 |
| `ui.tapByImage` | 按图片模板匹配并点击（图标、非文字按钮） |
| `ui.doubleTap` | 双击：按坐标或文字 |
| `ui.longPress` | 长按 |
| `ui.input` | 输入文字 |
| `ui.swipe` | 滑动 |
| `ui.back` / `ui.home` | 返回键 / 主页键 |
| `ui.launch` | 启动应用：支持 `app`（如「微信」「美团」）或 `package`（如 `com.tencent.mm`） |
| `ui.listApps` | 返回支持的应用名列表，便于 Agent 选择 |
| `ui.wait` | 固定时长等待（秒） |
| `ui.waitFor` | 等待某文字出现 |
| `ui.dump` | 导出无障碍树 |
| `ui.analyze` | 合并 accessibility + OCR 一次输出 |
| `ui.flow` | 结构化微脚本：`waitFor` + `then`、条件分支、超时、重试 |
| `ui.takeOver` | 人工接管：登录、验证码等场景暂停，用户完成后再继续 |

**OCR**：`screen.ocr` 截屏 + ML Kit 文字识别（中文），返回文字及坐标。`ui.tap(text)` 在无障碍未找到时会自动用 OCR 兜底。

### 增强能力说明

- **应用名映射**：内置 100+ 中英文应用名 → 包名映射，Agent 可直接传「微信」「Chrome」等，无需记忆包名。
- **选择器增强**：除 `text` 外，支持 `resourceId`、`className`、`contentDesc`，适配无文字控件。
- **图像匹配**：`ui.tapByImage` 适合图标、点赞、收藏等非文字按钮。
- **微脚本**：`ui.flow` 用 JSON 描述流程，支持 `waitFor`（text/image/selector）+ `then`，无需执行任意 JS。

详见项目内增强方案文档：
- [`docs/ANDROID_NODE_ENHANCEMENT_PLAN.md`](../../docs/ANDROID_NODE_ENHANCEMENT_PLAN.md) — 应用名、双击、等待、人工接管等
- [`docs/ANDROID_AUTOMATION_ENHANCEMENT_PLAN.md`](../../docs/ANDROID_AUTOMATION_ENHANCEMENT_PLAN.md) — 图像匹配、选择器、微脚本

## 使用

1. 用 Android Studio 打开 `packages/node-android`
2. 配置 Gateway 地址（如 `https://your-gateway:18790`）
3. 点击「连接」→ 首次需在 Dashboard 节点页审批配对
4. 审批通过后节点上线，Agent 可通过 `node-invoke_cameraSnap`、`node-invoke_uiTap`、`node-invoke_uiTapByImage` 等工具调用

## 权限

- **相机**：camera.snap 必需
- **定位**：location.get 必需
- **录屏**：需在 App 内点击「授权录屏」并允许
- **通知**：Android 13+ 前台服务需通知权限

## 构建

**方式一：Android Studio**
1. File → Open → 选择 `packages/node-android`
2. Build → Build Bundle(s) / APK(s) → Build APK(s)

**方式二：命令行**
```bash
cd packages/node-android
# 需设置 JAVA_HOME 和 Android SDK（或创建 local.properties 写入 sdk.dir=路径）
gradlew.bat assembleDebug   # Windows
./gradlew assembleDebug     # Linux/macOS
```
若 SDK 未配置，可创建 `local.properties` 填入：`sdk.dir=你的AndroidSDK路径`（如 `Q\:\\Android\\android-sdk`）

**若遇 SSL 证书错误**（PKIX path building failed）：
- 当前 Java 8u31 的证书库较旧，可升级到 Java 11+ 或导入新证书
- 或手动下载 [gradle-7.5.1-bin.zip](https://services.gradle.org/distributions/gradle-7.5.1-bin.zip)，解压后运行 `build-local.bat`（需修改其中 GRADLE_HOME）

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

**方式三：一键编译并安装到模拟器**
```bat
# 使用 D:\adb_tools\platform-tools\adb.exe
build-and-install.bat
```
或手动：
```bat
gradlew.bat assembleDebug
D:\adb_tools\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## 断连自动重试

首次连接成功（含配对中 needPairing）后，若网络断连或服务重启，客户端会自动重试连接（指数退避，最大间隔 60 秒）。首次连接前若连续失败 10 次则停止，可点击「连接」手动重试。
