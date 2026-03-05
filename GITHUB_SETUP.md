# 将 ApexPanda Node 发布到 GitHub

本仓库包含三种节点实现：

- **packages/node** - Headless 无界面节点
- **packages/node-desktop** - Electron 桌面节点（Windows / macOS / Linux）
- **packages/node-android** - Android 节点

## 一、准备独立仓库

在 `github` 目录下双击运行：

```
prepare-standalone-repo.bat
```

会在 `d:\project\3\apexpanda-node` 生成独立项目，包含上述三个包的源码。

**已排除内容**（不随源码提交）：

- `node_modules/`、`dist/` - 依赖与编译产物
- `release/` - Electron 打包输出
- `build/`、`.gradle/` - Android 构建缓存
- `*.exe`、`*.dmg`、`*.AppImage`、`*.apk` - 二进制安装包

上述二进制请通过 **GitHub Releases** 发布，见第四节。

## 二、创建 GitHub 仓库

1. 登录 https://github.com
2. 右上角 **+** → **New repository**
3. 仓库名：`apexpanda-node`
4. **Description**（可选）：`ApexPanda 节点端 - Headless / 桌面 / Android，连接 Gateway 暴露 system.run、clipboard、OCR 等能力给 AI Agent`
5. 设为 **Public**，**不要**勾选 "Add a README"
6. 点击 **Create repository**

## 三、认证说明（重要）

GitHub **已不再支持使用账号密码** 进行 git push，必须使用：

### 方式 A：Personal Access Token (PAT)

1. 打开 https://github.com/settings/tokens
2. **Generate new token (classic)**
3. 勾选 `repo` 权限
4. 生成后**复制并妥善保存**（只显示一次）
5. push 时：
   - 用户名：你的 GitHub 用户名
   - 密码：**粘贴 PAT**（不是账号密码）

### 方式 B：SSH Key

1. 生成密钥：`ssh-keygen -t ed25519 -C "your-email@example.com"`
2. 在 GitHub → Settings → SSH and GPG keys 中添加公钥
3. 使用 SSH 地址：`git@github.com:ApexPanda-Ai/apexpanda-node.git`

## 四、推送代码

```powershell
cd d:\project\3\apexpanda-node

# 安装依赖
pnpm install

# 初始化 git 并推送
git init
git add .
git commit -m "Initial commit: ApexPanda Node (headless, desktop, android)"
git branch -M main
git remote add origin https://github.com/ApexPanda-Ai/apexpanda-node.git
git push -u origin main
```

或双击目录内的 `push-to-github.bat`（会执行 init/add/commit/remote，然后需手动 `git push`）。

## 五、二进制发布（GitHub Releases）

以下产物不随源码提交，应通过 **Releases** 发布，供用户直接下载：

| 产物 | 来源 | 说明 |
|-----|------|------|
| `*.exe` | `packages/node-desktop/release/` | Windows 安装包（如 `ApexPanda 桌面节点 Setup x.x.x.exe`）|
| `*.dmg` | 同上 | macOS 安装包 |
| `*.AppImage` | 同上 | Linux 便携包 |
| `*.apk` | `packages/node-android/app/build/outputs/apk/` | Android 安装包 |

### 发布步骤

1. **构建**：在本地执行 `package-win.bat` 或 `./gradlew assembleRelease` 等
2. 打开仓库 → **Releases** → **Create a new release**
3. 填写 Tag（如 `v1.1.0`）、标题、说明
4. 将生成的 exe/apk 等文件拖入 **Attach binaries** 区域
5. 点击 **Publish release**

这样用户即可在 Releases 页面下载预编译安装包，而源码仓库保持精简。
