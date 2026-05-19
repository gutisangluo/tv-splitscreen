@echo off
set PY=%~dp0venv\Scripts\python.exe
set M=%~dp0run_main.py
if not exist "%PY%" (echo Error: python.exe & pause & exit /b)
"%PY%" "%M%"
if %errorlevel% neq 0 (echo Exit: %errorlevel% & pause)
