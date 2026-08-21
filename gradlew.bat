@echo off
REM Gradle wrapper (Windows, 简化版)
set DIR=%~dp0
gradle -p "%DIR%" %*
