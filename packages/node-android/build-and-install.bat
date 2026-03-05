@echo off
REM 编译 node-android APK 并通过 adb 安装到已连接的模拟器/设备
REM 使用前确保：1. 模拟器已启动 2. adb 在 PATH 或修改下方 ADB 路径

set ADB=D:\adb_tools\platform-tools\adb.exe
set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk

echo Building APK...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

echo Installing to device...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo Install failed. Check: adb devices
    exit /b 1
)

echo Done.
