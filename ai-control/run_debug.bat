@echo off
set LOG=%~dp0debug.log
echo [Debug] > %LOG%
dir %~dp0venv\Scripts\python.exe >> %LOG% 2>&1
echo. >> %LOG%
%~dp0venv\Scripts\python.exe -c "import sys;print(sys.prefix)" >> %LOG% 2>&1
echo. >> %LOG%
%~dp0venv\Scripts\python.exe %~dp0run_main.py >> %LOG% 2>&1
echo [Done] %errorlevel% >> %LOG%
echo Log: %LOG%
pause
