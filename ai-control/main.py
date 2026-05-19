#!/usr/bin/env python3
"""
TV 分屏 AI 控制端 - 主入口

利用本地小模型（GGUF）自动识别微信群消息内容，
并根据内容选择合适的投屏方式展示到电视端。

使用方法:
    python main.py
    python main.py --config config.yaml

依赖安装:
    pip install -r requirements.txt
"""

import os
import sys
import argparse
import logging

# PyInstaller 打包后，__file__ 指向临时解压目录，不是 exe 所在目录
# 需要用 sys.executable 获取 exe 真实路径
if getattr(sys, 'frozen', False):
    BASE_DIR = os.path.dirname(os.path.abspath(sys.executable))
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# 确保可以找到核心模块
sys.path.insert(0, BASE_DIR)


def setup_logging(verbose: bool = False):
    """配置日志"""
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )


def main():
    parser = argparse.ArgumentParser(
        description="TV 分屏 AI 控制端 - 自动识别微信群消息并投屏"
    )
    default_config = os.path.join(BASE_DIR, "config.yaml")
    parser.add_argument(
        "--config", "-c",
        default=default_config,
        help=f"配置文件路径 (默认: {default_config})"
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="详细日志输出"
    )
    args = parser.parse_args()

    setup_logging(args.verbose)

    # 先加载 llama_cpp（必须在 PyQt5 之前，否则 C 扩展空指针）
    try:
        import llama_cpp
    except Exception:
        pass

    # 延迟导入 PyQt5，避免 Qt 环境问题
    from PyQt5.QtWidgets import QApplication
    from ui.main_window import MainWindow

    app = QApplication(sys.argv)
    app.setApplicationName("TV 分屏 AI 控制端")

    window = MainWindow(args.config, base_dir=BASE_DIR)
    window.show()

    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
