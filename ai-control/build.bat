@echo off
REM ============================================
REM TV 分屏 AI 控制端 - 打包脚本
REM 使用 PyInstaller 打包为单个 EXE
REM ============================================
chcp 65001 >nul

echo ========================================
echo  TV 分屏 AI 控制端 打包脚本
echo ========================================
echo.

REM 检查 Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Python，请先安装 Python 3.8+
    pause
    exit /b 1
)

REM 安装依赖
echo [1/4] 安装依赖...
pip install -r requirements.txt
if %errorlevel% neq 0 (
    echo [警告] 部分依赖安装失败，尝试继续...
)

REM 安装 PyInstaller
echo [2/4] 安装 PyInstaller...
pip install pyinstaller
if %errorlevel% neq 0 (
    echo [错误] PyInstaller 安装失败
    pause
    exit /b 1
)

REM 清理旧构建
echo [3/4] 清理旧构建...
if exist dist rmdir /s /q dist
if exist build rmdir /s /q build

REM 打包
echo [4/4] 打包中...
pyinstaller --onefile --windowed ^
    --name "TV智能投屏控制端" ^
    --add-data "config.yaml;." ^
    --hidden-import PyQt5.sip ^
    --hidden-import websocket ^
    --hidden-import yaml ^
    --hidden-import PIL ^
    --hidden-import requests ^
    --hidden-import llama_cpp ^
    main.py

if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo  打包成功！
    echo  输出: dist\TV智能投屏控制端.exe
    echo ========================================
    
    REM 复制配置文件
    copy config.yaml dist\config.yaml >nul
    
    echo.
    echo 使用说明：
    echo 1. 双击 dist\TV智能投屏控制端.exe 启动
    echo 2. 在设置中配置 TV IP 地址并连接
    echo 3. 选择 GGUF 模型文件并加载
    echo 4. 启动微信监控
    echo 5. 模型文件请自行下载，推荐 Qwen2.5-1.5B-Instruct-GGUF
    echo    下载地址: https://huggingface.co/Qwen
) else (
    echo.
    echo [错误] 打包失败，请查看上方日志
)

pause
