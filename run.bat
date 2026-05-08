@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
set "PATH=%JAVA_HOME%\bin;C:\Users\keera\tools\gradle-8.10.2\bin;%PATH%"
cd /d "%~dp0"
gradle desktop:runGame
endlocal
pause
