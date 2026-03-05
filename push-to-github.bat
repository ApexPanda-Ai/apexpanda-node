@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist .git (
  git init
  git add .
  git commit -m "Initial commit: ApexPanda Node ^(headless, desktop, android^)"
  git branch -M main
  git remote add origin https://github.com/ApexPanda-Ai/apexpanda-node.git
  echo.
  echo 请先在 GitHub 创建空仓库 apexpanda-node，再执行: git push -u origin main
  echo 认证需使用 PAT: https://github.com/settings/tokens
) else (
  echo 已是 git 仓库，请直接执行: git push
)
pause
