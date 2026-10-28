@echo off
chcp 65001 >nul
title 空白格 · AI 控制台
call "%~dp0tools\env.bat"
cd /d %~dp0

if not exist "backend\target\aione-backend-0.1.0.jar" (
  echo 尚未构建,请先运行 build-prod.bat
  pause & exit /b 1
)
echo 正在启动空白格 AI 控制台(生产模式,单进程,端口 8090)……
start "" http://localhost:8090
java -jar backend\target\aione-backend-0.1.0.jar
