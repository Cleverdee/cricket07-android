@echo off
rem Builds the native Android APK from the game in ..\game. Options: -Install -PushGame -Retranslate -Opt "-O3" -Lto -Debug
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
echo.
pause
