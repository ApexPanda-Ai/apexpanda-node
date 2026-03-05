# ApexPanda Node 节点客户端

ApexPanda 的节点端实现，连接 Gateway 后将设备的系统调用、剪贴板、摄像头、OCR 等能力暴露给 AI Agent 使用。

## 三种实现

- **packages/node** - Headless 无界面节点，连接 Gateway 暴露 system.run、clipboard 等能力
- **packages/node-desktop** - Electron 桌面节点，支持摄像头、录屏、Canvas
- **packages/node-android** - Android 节点，支持 OCR、定位等

## 安装 (Headless / Desktop)

```bash
pnpm install
pnpm --filter @apexpanda/node run build
pnpm --filter @apexpanda/node-desktop run build
```

## 使用

### Headless 节点
```bash
cd packages/node
pnpm run build
apexpanda-node run --gateway http://localhost:18790 --display-name "My Node"
```

### 桌面节点
```bash
cd packages/node-desktop
pnpm run package:win
```
打包后的安装包在 `release/` 目录。

### Android 节点
用 Android Studio 打开 `packages/node-android`，或使用 `./gradlew assembleRelease` 构建 APK。

## 二进制发布 (GitHub Releases)

以下产物**不随源码提交**，应通过 [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository) 发布：

- exe - Windows 桌面安装包
- dmg - macOS 安装包
- AppImage - Linux 便携包
- apk - Android 安装包

构建完成后，在仓库的 Releases 页面创建新版本并上传对应文件即可。

## 关于

节点是 ApexPanda 体系中的执行端，负责在本地执行 AI 下发的任务。Gateway 负责调度，节点负责在真实环境中完成 system.run、剪贴板读写、OCR、录屏等操作。
