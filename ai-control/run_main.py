"""
启动器 - 修复 venv 路径后导入主程序

PyInstaller 打包的 exe 不存在此问题，仅源码版需要。
此脚本负责:
1. 确定当前真是目录（无论 venv 被移到哪）
2. 修复 sys.path 指向正确的 site-packages
3. 导入并运行 main
"""

import os
import sys
import site


def fix_paths():
    """修复 Python 路径，使移动后的 venv 可用"""
    # 当前脚本所在目录 = 应用根目录
    app_dir = os.path.dirname(os.path.abspath(__file__))

    # 确定 venv 路径
    venv_dir = os.path.join(app_dir, "venv")

    # Python 版本目录
    py_version = f"python{sys.version_info.major}.{sys.version_info.minor}"

    # site-packages 候选路径
    sp_candidates = [
        os.path.join(venv_dir, "Lib", "site-packages"),
        os.path.join(venv_dir, "lib", py_version, "site-packages"),
    ]

    site_packages = None
    for sp in sp_candidates:
        if os.path.isdir(sp):
            site_packages = sp
            break

    # 修复 site-packages 路径
    if site_packages:
        # 移除旧的 venv 路径
        sys.path = [p for p in sys.path if "site-packages" not in p]
        # 插入正确的路径
        sys.path.insert(0, site_packages)
        sys.path.insert(0, os.path.join(venv_dir, "Lib"))
        sys.path.insert(0, os.path.join(venv_dir, "DLLs"))
        # 通知 site 模块
        site.addsitedir(site_packages)

    # 修复 sys.prefix 指向正确的 venv
    sys.prefix = venv_dir
    sys.exec_prefix = venv_dir

    # 确保应用目录在路径中
    if app_dir not in sys.path:
        sys.path.insert(0, app_dir)

    return app_dir


def main():
    app_dir = fix_paths()
    os.chdir(app_dir)

    # 先加载 llama_cpp（必须在 PyQt5 之前，否则空指针）
    try:
        import llama_cpp
    except Exception:
        pass

    # 导入并运行主程序
    import main as app_main
    app_main.main()


if __name__ == "__main__":
    main()
