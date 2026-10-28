@echo off
chcp 65001 >nul
title 空白格 · AI 控制台
call "%~dp0tools\env.bat"
echo 正在启动空白格 AI 控制台……
start "aione-backend" cmd /c "cd /d %~dp0backend && mvn -q spring-boot:run"
start "aione-frontend" cmd /c "cd /d %~dp0frontend && npm run dev"
timeout /t 12 /nobreak >nul
start http://localhost:5180
echo 已启动:后台 8090,前端 http://localhost:5180
