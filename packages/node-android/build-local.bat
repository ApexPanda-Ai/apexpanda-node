@echo off
REM 若 gradlew 因 SSL 证书失败，可先手动下载 Gradle 后使用本地构建
REM 1. 从 https://services.gradle.org/distributions/gradle-7.5.1-bin.zip 下载
REM 2. 解压到任意目录，将下方 GRADLE_HOME 设为解压路径
REM 3. 运行本脚本: build-local.bat

set GRADLE_HOME=C:\gradle-7.5.1
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    echo 请先下载并解压 Gradle 7.5.1 到 %GRADLE_HOME%
    echo 或修改本脚本中的 GRADLE_HOME 指向你的 Gradle 目录
    pause
    exit /b 1
)

cd /d "%~dp0"
"%GRADLE_HOME%\bin\gradle.bat" assembleDebug --no-daemon
pause
