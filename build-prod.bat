@echo off
chcp 65001 >nul
title 空白格 · AI 控制台 - 生产模式构建
call "%~dp0tools\env.bat"
cd /d %~dp0

echo [1/3] 构建前端……
pushd frontend && call npm run build && popd
if errorlevel 1 (echo 前端构建失败 & pause & exit /b 1)

echo [2/3] 打包后端……
cd backend && call mvn -q package -DskipTests && cd ..
if errorlevel 1 (echo 后端打包失败 & pause & exit /b 1)

echo [3/3] 完成。运行 start-prod.bat 启动服务(单进程,端口 8090)
pause
