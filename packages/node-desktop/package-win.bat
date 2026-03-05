@echo off
chcp 65001 >nul
echo [ApexPanda] 开始打包桌面节点...
cd /d "%~dp0"

REM 关闭可能正在运行的桌面节点，避免 "Access is denied"
taskkill /f /im "ApexPanda 桌面节点.exe" 2>nul
timeout /t 2 /nobreak >nul

REM 清理旧的 release 目录，避免文件占用导致打包失败
if exist "release\win-unpacked" (
    echo 正在清理上次构建...
    rmdir /s /q "release" 2>nul
    if exist "release" (
        echo.
        echo 无法删除 release 目录，请手动关闭「ApexPanda 桌面节点」后重试。
        pause
        exit /b 1
    )
)

pnpm run package:win
if %errorlevel% neq 0 (
    echo.
    echo 打包失败，错误码: %errorlevel%
    pause
    exit /b %errorlevel%
)

echo.
echo 打包完成！输出目录: release\
echo 安装包: release\ApexPanda 桌面节点 Setup *.exe
echo.
explorer "%~dp0release"
pause
