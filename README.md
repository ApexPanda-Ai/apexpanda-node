# ApexPanda 节点客户端套件：跨平台系统/设备控制

# ApexPanda Nodes

ApexPanda 节点客户端套件，包含 Headless（无界面）、Android、桌面三种节点类型，连接 Gateway 后为 AI Agent 提供跨平台的系统/设备控制能力。Agent 可根据在线节点的能力自动筛选可用工具，实现多端协同的自动化任务执行。

## 节点类型概览

|节点类型|适用环境|核心定位|特色能力|
|---|---|---|---|
|Headless Node|Linux/Windows 无桌面环境（服务器/命令行）|系统命令/文件操作|`system.run`、文件读写、进程管理|
|Android Node|安卓设备|移动端专属能力|摄像头/定位/UI自动化（无障碍+OCR）|
|Desktop Node|Windows/macOS/Linux 桌面环境|全功能桌面控制|摄像头/录屏/Canvas/桌面UI自动化|
---

## 1. Headless Node（无界面节点）

无界面节点客户端，专为 Linux/Windows 无桌面环境设计，连接 Gateway 后暴露服务器/命令行级能力。

### 核心能力

|能力|说明|
|---|---|
|`system.run`|执行本地命令（如 `whoami`、`node --version`），支持白名单/黑名单/远程审批|
|`system.which`|检测命令可执行文件路径|
|`system.readFile` / `system.writeFile` / `system.listDir`|文件读写与列目录，受 `APEXPANDA_WORKSPACE` 路径限制|
|`system.clipboardRead` / `system.clipboardWrite`|剪贴板读写|
|`system.processList` / `system.processKill`|进程列表查询与终止|
### 环境工具自动检测

自动上报以下工具的可用性，供 Dashboard 展示及 Agent 节点选择参考：

`adb`、`docker`、`node`、`npm`、`python`、`git`、`java`

### 快速使用

#### 构建

```Bash

cd packages/node
pnpm install
pnpm run build
```

#### 运行

```Bash

# 直接指定参数
npx @apexagent/node run --gateway http://localhost:18790 --display-name "My Headless Node"

# 或通过环境变量
export APEXPANDA_GATEWAY_URL=http://localhost:18790
export APEXPANDA_NODE_DISPLAY_NAME="Build Node"
export APEXPANDA_NODE_TOKEN=已审批的token
npx @apexagent/node run
```

#### 首次配对

首次运行无 token 时，需在 Gateway Dashboard 的「节点管理」页审批配对，审批通过后 token 会自动写入 `~/.apexpanda/node.json`。

### 环境变量

|变量|说明|默认值|
|---|---|---|
|`APEXPANDA_GATEWAY_URL`|Gateway 服务地址|`http://127.0.0.1:18790`|
|`APEXPANDA_NODE_DISPLAY_NAME`|节点显示名称|-|
|`APEXPANDA_NODE_TOKEN`|已审批的配对 Token|-|
|`APEXPANDA_DATA_DIR`|数据存储目录|`~/.apexpanda`|
|`APEXPANDA_WORKSPACE`|文件操作根目录（防止路径穿越）|-|
### 安全审批

- 配置文件路径：`~/.apexpanda/exec-approvals.json`

- 审批模式：

    - `full`：全部命令放行

    - `blacklist`：按规则禁止指定命令

    - `remote-approve`：执行命令需远程审批

- 详细配置可参考 Gateway 节点管理页的「审批配置」

---

## 2. Android 节点

安卓设备专属节点客户端，为 AI Agent 提供移动端独有的硬件控制和 UI 自动化能力，与 Headless/桌面节点形成能力互补。

### 核心能力

#### 基础硬件能力

|能力|说明|跨端支持|
|---|---|---|
|`camera.snap`|手机摄像头拍照|桌面节点也支持（参数一致）|
|`camera.clip`|短视频录制（暂未支持）|桌面节点支持|
|`screen.record`|手机屏幕录制（需提前授权）|桌面节点支持电脑录屏|
|`location.get`|手机 GPS 定位|**仅 Android 支持**|
⚠️ Android 节点**不支持** `system.run`、`system.readFile` 等电脑端系统能力

#### UI 自动化能力（需开启无障碍权限）

开启路径：`设置 → 无障碍 → ApexPanda 节点`

|能力|说明|
|---|---|
|`ui.tap`|点击（支持 text/x,y/id/className/contentDesc 等选择器）|
|`ui.tapByImage`|按图片模板匹配并点击（适配图标/非文字按钮）|
|`ui.doubleTap` / `ui.longPress`|双击/长按（坐标/文字匹配）|
|`ui.input`|输入文字到指定控件|
|`ui.swipe`|屏幕滑动操作|
|`ui.back` / `ui.home`|模拟返回键/主页键|
|`ui.launch`|启动应用（支持应用名如「微信」或包名如 `com.tencent.mm`）|
|`ui.listApps`|返回设备已安装应用列表|
|`ui.wait` / `ui.waitFor`|固定时长等待 / 等待指定文字出现|
|`ui.dump`|导出无障碍控件树|
|`ui.analyze`|合并无障碍信息 + OCR 识别结果输出|
|`ui.flow`|结构化微脚本（支持条件分支/超时/重试）|
|`ui.takeOver`|人工接管（适配登录/验证码等场景）|
#### OCR 增强能力

- `screen.ocr`：截屏 + ML Kit 中文文字识别（返回文字及坐标）

- `ui.tap(text)`：无障碍未找到目标时，自动触发 OCR 兜底匹配

### 增强特性

- **应用名映射**：内置 100+ 中英文应用名 ↔ 包名映射，无需记忆包名

- **选择器增强**：支持 `resourceId`/`className`/`contentDesc`，适配无文字控件

- **图像匹配**：`ui.tapByImage` 适配图标、点赞、收藏等非文字按钮

- **微脚本**：`ui.flow` 支持 JSON 描述流程，无需编写 JS 代码

### 快速使用

1. 用 Android Studio 打开 `packages/node-android`

2. 配置 Gateway 地址（如 `https://your-gateway:18790`）

3. 点击「连接」→ 首次需在 Dashboard 节点页审批配对

4. 审批通过后节点上线，Agent 可调用 `node-invoke_cameraSnap`/`node-invoke_uiTap` 等工具

### 权限要求

|权限|必需场景|
|---|---|
|相机|`camera.snap`|
|定位|`location.get`|
|录屏|`screen.record`|
|通知|Android 13+ 前台服务必需|
### 构建打包

#### 方式 1：Android Studio

1. File → Open → 选择 `packages/node-android`

2. Build → Build Bundle(s) / APK(s) → Build APK(s)

#### 方式 2：命令行

```Bash

cd packages/node-android

# Windows
gradlew.bat assembleDebug

# Linux/macOS
./gradlew assembleDebug
```

注意：需配置 `JAVA_HOME` 和 Android SDK，或创建 `local.properties` 写入 `sdk.dir=你的SDK路径`

#### 方式 3：一键编译安装（模拟器）

```Plain Text

build-and-install.bat
```

或手动执行：

```Plain Text

gradlew.bat assembleDebug
D:\adb_tools\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

#### 常见问题：SSL 证书错误

- 原因：Java 8u31 证书库过旧

- 解决方案：

    1. 升级 Java 到 11+

    2. 或手动下载 [gradle-7.5.1-bin.zip](https://services.gradle.org/distributions/gradle-7.5.1-bin.zip)，解压后运行 `build-local.bat`（需修改 GRADLE_HOME）

### 断连自动重试

- 首次连接成功后（含配对中状态），断连后会指数退避重试（最大间隔 60 秒）

- 首次连接前连续失败 10 次则停止，可手动点击「连接」重试

---

## 3. 桌面节点

基于 Electron 开发的跨平台桌面客户端，支持 Windows/macOS/Linux，提供摄像头、录屏、Canvas、系统调用等全量桌面能力。

### 核心能力

|类别|能力|说明|
|---|---|---|
|摄像头|`camera.snap` / `camera.clip`|拍照 / 短视频录制|
|录屏|`screen.record`|电脑屏幕录制（需授权）|
|Canvas|`canvas.snapshot` / `canvas.navigate`|网页/应用快照 / 导航控制|
|系统操作|`system.run` / `system.which` / 文件/进程/剪贴板管理|同 Headless 节点，支持审批控制|
|屏幕识别|`screen.ocr`|截屏 + 文字识别|
|UI 自动化|`ui.tap` / `ui.input` / `ui.dump`|桌面 UI 控制|
|音频|`audio.record` / `audio.playback`|录音 / 音频播放|
|离线唤醒|`audio.startWake` / `audio.stopWake`|Vosk 离线唤醒词识别|
### 快速使用

1. 配置 Gateway 地址（如 `https://your-gateway:18790`）

2. 点击「连接」→ 首次需在 Dashboard 节点页审批配对

3. 审批通过后节点上线，Agent 可调用对应工具

4. 首次使用录屏功能需授权屏幕录制权限

5. 离线唤醒词功能需下载 Vosk 模型（设置页配置）

### 构建打包

#### 开发运行

```Bash

cd packages/node-desktop
pnpm install
pnpm run build
pnpm start
```

#### 打包安装包

```Bash

# 按平台打包
pnpm run package:win   # Windows (exe/nsis)
pnpm run package:mac    # macOS (dmg)
pnpm run package:linux  # Linux (AppImage)

# 或使用根目录脚本（Windows）
package-win.bat
```

输出目录：`release/`（包含 `ApexPanda 桌面节点 Setup x.x.x.exe` 等安装包）

### 环境变量

|变量|说明|
|---|---|
|`APEXPANDA_GATEWAY_URL`|Gateway 服务地址|
|`APEXPANDA_NODE_DISPLAY_NAME`|节点显示名称|
|`APEXPANDA_NODE_TOKEN`|已配对的 Token|
|`APEXPANDA_WORKSPACE`|文件操作允许的根目录|
### 断连自动重试

- 首次连接成功后（含配对中状态），断连后指数退避重试（最大间隔 60 秒）

- 首次连接前连续失败 10 次则停止，可手动点击「连接」重试

---

## 节点能力对比总表

|能力|Headless 节点|桌面节点|Android 节点|
|---|---|---|---|
|`system.run` / 文件/进程管理|✅|✅|❌|
|摄像头/录屏|❌|✅|✅|
|定位 `location.get`|❌|❌|✅|
|桌面 UI 自动化|❌|✅|❌|
|移动端 UI 自动化|❌|❌|✅|
|剪贴板操作|✅|✅|❌|
|音频录制/播放|❌|✅|❌|
### 总结

1. ApexPanda 包含 Headless、Android、桌面三种节点类型，分别适配无界面服务器、安卓设备、桌面 PC 场景，能力互补；

2. 所有节点均需连接 Gateway 并完成配对审批，Agent 会根据在线节点能力自动筛选可用工具；

3. 安全层面支持命令执行审批（白名单/黑名单/远程审批）和文件路径限制，防止越权操作。
