import sys
sys.path.insert(0, r'E:\桌面\Hermes jb\脚本\tv-splitscreen\ai-control-build')

# 测试所有 import
from PyQt5.QtWidgets import QApplication
from core.model_manager import ModelManager
from core.content_classifier import ContentClassifier
from core.tv_dispatcher import TVDispatcher
from core.wechat_monitor import WeChatMonitor
import yaml
import websocket
import requests
import PIL
print('全部导入成功')
print(f'Python: {sys.version}')
