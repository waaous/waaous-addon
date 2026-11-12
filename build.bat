@echo off
rem SmartMiner addon 一键编译脚本
rem 前提：先开启你的加速器/VPN（让 maven.fabricmc.net 可访问），再双击本脚本。
set JAVA_HOME=E:\jdk-21.0.4
set PATH=E:\jdk-21.0.4\bin;C:\Windows\System32;C:\Windows;%PATH%
cd /d Y:\waaous-addon
echo === 开始编译 SmartMiner ===
Y:\waaous-addon\gradle-dist\gradle-9.5.1\bin\gradle.bat build --no-daemon
echo.
echo === 编译结束。成品 jar 在 build\libs\waaous-addon-0.1.0.jar ===
pause
