@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
set "PATH=%JAVA_HOME%\bin;C:\Users\keera\tools\gradle-8.10.2\bin;%PATH%"
cd /d "%~dp0"
rem Mirror everything to run.log so the trace survives even if the console closes.
call gradle desktop:runGame > run.log 2>&1
type run.log
echo.
echo === run.log saved next to run.bat ===
endlocal
pause
