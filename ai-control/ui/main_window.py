"""
TV 分屏 AI 控制端 - 主窗口

图形界面含三个标签页：
1. 监控面板 - 实时微信群消息流 + 自动投屏状态
2. 设置面板 - 模型选择、TV连接、微信配置、时间窗口
3. 日志面板 - 运行日志
"""

import os
import sys
import json
import time
import yaml
import threading
import logging
from pathlib import Path
from typing import Optional, List
from datetime import datetime
from queue import Queue

from PyQt5.QtWidgets import (
    QMainWindow, QWidget, QTabWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QComboBox, QSpinBox, QLineEdit,
    QTextEdit, QListWidget, QListWidgetItem, QFileDialog,
    QGroupBox, QFormLayout, QCheckBox, QSlider, QSplitter,
    QMessageBox, QStatusBar, QApplication, QProgressBar,
    QTableWidget, QTableWidgetItem, QHeaderView, QAbstractItemView,
    QFrame, QScrollArea,
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject, QThread
from PyQt5.QtGui import QFont, QColor, QPalette, QTextCursor, QIcon

from core.model_manager import ModelManager
from core.content_classifier import ContentClassifier, MessageItem, DisplayDecision
from core.wechat_monitor import WeChatMonitor
from core.tv_dispatcher import TVDispatcher

logger = logging.getLogger(__name__)


# ============================================================
# 工作线程（后台执行，不阻塞 UI）
# ============================================================

class WorkerSignals(QObject):
    """线程信号"""
    log = pyqtSignal(str)
    status = pyqtSignal(str)
    message = pyqtSignal(object)
    decision = pyqtSignal(object)
    groups = pyqtSignal(list)
    tv_connected = pyqtSignal(bool)
    model_loaded = pyqtSignal(bool, str)


class WeChatWorker(QThread):
    """微信监控工作线程"""

    def __init__(self):
        super().__init__()
        self.monitor = WeChatMonitor()
        self.signals = WorkerSignals()
        self._target_group = ""
        self._download_dir = ""

    def configure(self, group_pattern: str, download_dir: str):
        self._target_group = group_pattern
        self._download_dir = download_dir

    def run(self):
        self.monitor.set_callback(self._on_message)
        self.monitor.set_download_dir(self._download_dir)
        self.monitor.set_target_group(self._target_group)

        if self.monitor.start():
            self.signals.log.emit("微信监控已启动")
            self.signals.groups.emit(self.monitor.available_groups)
            # 保持线程存活
            while self.monitor.is_running:
                self.msleep(500)
        else:
            self.signals.log.emit("微信监控启动失败，请确认微信已登录")

    def stop(self):
        self.monitor.stop()

    def _on_message(self, msg: MessageItem):
        self.signals.message.emit(msg)

    def get_recent_messages(self, window: int = 3600) -> List[MessageItem]:
        return self.monitor.get_recent_messages(window_seconds=window)

    def scan_groups(self) -> list:
        return self.monitor.scan_groups()


class TVWorker(QThread):
    """TV 连接工作线程"""

    def __init__(self):
        super().__init__()
        self.dispatcher: Optional[TVDispatcher] = None
        self.signals = WorkerSignals()
        self._host = "192.168.1.100"
        self._ws_port = 9527
        self._http_port = 9528

    def configure(self, host: str, ws_port: int, http_port: int):
        self._host = host
        self._ws_port = ws_port
        self._http_port = http_port

    def run(self):
        self.dispatcher = TVDispatcher(self._host, self._ws_port, self._http_port)
        self.dispatcher.set_status_callback(self._on_tv_status)
        self.dispatcher.connect()

    def stop(self):
        if self.dispatcher:
            self.dispatcher.disconnect()

    def _on_tv_status(self, connected: bool):
        self.signals.tv_connected.emit(connected)

    def upload_and_show(self, local_path: str):
        """上传文件并返回 URL"""
        if self.dispatcher:
            return self.dispatcher.upload_file(local_path)
        return None

    def show_decision(self, decision):
        if self.dispatcher:
            return self.dispatcher.apply_display_decision(decision)
        return False


class ModelWorker(QThread):
    """模型加载工作线程"""

    def __init__(self):
        super().__init__()
        self.manager = ModelManager()
        self.signals = WorkerSignals()
        self._model_path = ""

    def configure(self, model_path: str):
        self._model_path = model_path

    def run(self):
        self.signals.log.emit(f"正在加载模型: {self._model_path}")
        try:
            success = self.manager.load(self._model_path)
            self.signals.model_loaded.emit(success, self._model_path)
        except Exception as e:
            self.signals.log.emit(f"模型加载异常: {e}")
            self.signals.model_loaded.emit(False, self._model_path)

    def stop(self):
        self.manager.unload()


# ============================================================
# 设置面板
# ============================================================

class SettingsPanel(QWidget):
    """设置标签页"""

    def __init__(self, config: dict):
        super().__init__()
        self.config = config
        self.signals = WorkerSignals()
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)

        # ----- TV 连接设置 -----
        tv_group = QGroupBox("TV 连接")
        tv_form = QFormLayout()

        self.tv_host = QLineEdit(self.config.get("tv", {}).get("host", "192.168.1.100"))
        self.tv_host.setPlaceholderText("电视 IP 地址")
        tv_form.addRow("IP 地址:", self.tv_host)

        ws_layout = QHBoxLayout()
        self.ws_port = QSpinBox()
        self.ws_port.setRange(1, 65535)
        self.ws_port.setValue(self.config.get("tv", {}).get("ws_port", 9527))
        ws_layout.addWidget(QLabel("WS:"))
        ws_layout.addWidget(self.ws_port)

        self.http_port = QSpinBox()
        self.http_port.setRange(1, 65535)
        self.http_port.setValue(self.config.get("tv", {}).get("http_port", 9528))
        ws_layout.addWidget(QLabel("HTTP:"))
        ws_layout.addWidget(self.http_port)
        ws_layout.addStretch()
        tv_form.addRow("端口:", ws_layout)

        self.tv_status_label = QLabel("未连接")
        self.tv_status_label.setStyleSheet("color: gray;")
        tv_form.addRow("状态:", self.tv_status_label)

        btn_layout = QHBoxLayout()
        self.btn_connect_tv = QPushButton("连接 TV")
        self.btn_connect_tv.clicked.connect(self._connect_tv)
        self.btn_disconnect_tv = QPushButton("断开")
        self.btn_disconnect_tv.clicked.connect(self._disconnect_tv)
        self.btn_disconnect_tv.setEnabled(False)
        btn_layout.addWidget(self.btn_connect_tv)
        btn_layout.addWidget(self.btn_disconnect_tv)
        btn_layout.addStretch()
        tv_form.addRow("", btn_layout)

        tv_group.setLayout(tv_form)
        layout.addWidget(tv_group)

        # ----- 模型设置 -----
        model_group = QGroupBox("AI 模型")
        model_form = QFormLayout()

        model_path_layout = QHBoxLayout()
        self.model_path = QLineEdit(self.config.get("model", {}).get("path", ""))
        self.model_path.setPlaceholderText("选择 GGUF 模型文件...")
        self.btn_browse_model = QPushButton("浏览...")
        self.btn_browse_model.clicked.connect(self._browse_model)
        model_path_layout.addWidget(self.model_path)
        model_path_layout.addWidget(self.btn_browse_model)
        model_form.addRow("模型文件:", model_path_layout)

        self.model_status_label = QLabel("未加载")
        self.model_status_label.setStyleSheet("color: gray;")
        model_form.addRow("状态:", self.model_status_label)

        thread_layout = QHBoxLayout()
        self.thread_count = QSpinBox()
        self.thread_count.setRange(1, 32)
        self.thread_count.setValue(self.config.get("model", {}).get("n_threads", 4))
        thread_layout.addWidget(self.thread_count)
        thread_layout.addWidget(QLabel("线程"))
        thread_layout.addStretch()
        model_form.addRow("CPU 线程:", thread_layout)

        gpu_layout = QHBoxLayout()
        self.gpu_layers = QSpinBox()
        self.gpu_layers.setRange(0, 200)
        self.gpu_layers.setValue(self.config.get("model", {}).get("n_gpu_layers", 0))
        gpu_layout.addWidget(self.gpu_layers)
        gpu_layout.addWidget(QLabel("层 (0=纯CPU)"))
        gpu_layout.addStretch()
        model_form.addRow("GPU 卸载:", gpu_layout)

        btn_model_layout = QHBoxLayout()
        self.btn_load_model = QPushButton("加载模型")
        self.btn_load_model.clicked.connect(self._load_model)
        self.btn_unload_model = QPushButton("卸载模型")
        self.btn_unload_model.clicked.connect(self._unload_model)
        self.btn_unload_model.setEnabled(False)
        btn_model_layout.addWidget(self.btn_load_model)
        btn_model_layout.addWidget(self.btn_unload_model)
        btn_model_layout.addStretch()
        model_form.addRow("", btn_model_layout)

        model_group.setLayout(model_form)
        layout.addWidget(model_group)

        # ----- 微信监控设置 -----
        wx_group = QGroupBox("微信监控")
        wx_form = QFormLayout()

        self.group_pattern = QLineEdit(self.config.get("wechat", {}).get("group_name", ""))
        self.group_pattern.setPlaceholderText("群名关键词（支持正则，留空=所有群）")
        wx_form.addRow("监控群名:", self.group_pattern)

        self.time_window = QSpinBox()
        self.time_window.setRange(60, 86400)
        self.time_window.setValue(self.config.get("wechat", {}).get("time_window", 3600))
        self.time_window.setSuffix(" 秒")
        wx_form.addRow("时间窗口:", self.time_window)

        self.download_dir = QLineEdit(
            os.path.join(os.path.expanduser("~"), "ai-control", "downloads")
        )
        self.download_dir.setPlaceholderText("图片下载目录")
        dl_layout = QHBoxLayout()
        dl_layout.addWidget(self.download_dir)
        self.btn_browse_dir = QPushButton("浏览...")
        self.btn_browse_dir.clicked.connect(self._browse_download_dir)
        dl_layout.addWidget(self.btn_browse_dir)
        wx_form.addRow("下载目录:", dl_layout)

        btn_wx_layout = QHBoxLayout()
        self.btn_start_monitor = QPushButton("启动监控")
        self.btn_start_monitor.clicked.connect(self._start_monitor)
        self.btn_stop_monitor = QPushButton("停止监控")
        self.btn_stop_monitor.clicked.connect(self._stop_monitor)
        self.btn_stop_monitor.setEnabled(False)
        btn_wx_layout.addWidget(self.btn_start_monitor)
        btn_wx_layout.addWidget(self.btn_stop_monitor)
        btn_wx_layout.addStretch()
        wx_form.addRow("", btn_wx_layout)

        self.group_list = QListWidget()
        self.group_list.setMaximumHeight(120)
        wx_form.addRow("可用群聊:", self.group_list)

        wx_group.setLayout(wx_form)
        layout.addWidget(wx_group)

        # ----- 分类参数 -----
        cls_group = QGroupBox("内容分类")
        cls_form = QFormLayout()

        self.cooldown = QSpinBox()
        self.cooldown.setRange(5, 600)
        self.cooldown.setValue(self.config.get("classifier", {}).get("cooldown", 30))
        self.cooldown.setSuffix(" 秒")
        cls_form.addRow("分类间隔:", self.cooldown)

        self.max_buffer = QSpinBox()
        self.max_buffer.setRange(10, 500)
        self.max_buffer.setValue(self.config.get("classifier", {}).get("max_buffer", 100))
        cls_form.addRow("消息缓冲:", self.max_buffer)

        cls_group.setLayout(cls_form)
        layout.addWidget(cls_group)

        layout.addStretch()

    def _browse_model(self):
        # 默认打开 models/ 目录或当前模型文件所在目录
        current = self.model_path.text()
        default_dir = os.path.dirname(current) if current and os.path.dirname(current) else ""
        if not default_dir:
            # 尝试 models/ 子目录
            for d in ["models", "."]:
                test = os.path.join(os.getcwd(), d)
                if os.path.isdir(test):
                    default_dir = test
                    break
        path, _ = QFileDialog.getOpenFileName(
            self, "选择 GGUF 模型文件", default_dir,
            "GGUF 模型 (*.gguf);;所有文件 (*.*)"
        )
        if path:
            self.model_path.setText(path)
            self.signals.log.emit(f"已选择模型: {path}")

    def _browse_download_dir(self):
        """浏览下载目录"""
        current = self.download_dir.text().strip()
        if not current or not os.path.isdir(current):
            current = os.path.expanduser("~")
        dir_path = QFileDialog.getExistingDirectory(
            self, "选择图片下载目录", current
        )
        if dir_path:
            self.download_dir.setText(dir_path)
            self.signals.log.emit(f"已设置下载目录: {dir_path}")

    def _connect_tv(self):
        host = self.tv_host.text().strip()
        if not host:
            QMessageBox.warning(self, "提示", "请输入 TV IP 地址")
            return
        self.signals.status.emit("connecting_tv")
        self.signals.log.emit(f"正在连接 TV: {host}:{self.ws_port.value()}")

    def _disconnect_tv(self):
        self.signals.status.emit("disconnect_tv")

    def _load_model(self):
        path = self.model_path.text().strip()
        if not path or not os.path.isfile(path):
            QMessageBox.warning(self, "提示", "请选择有效的 GGUF 模型文件")
            return
        if not path.lower().endswith(".gguf"):
            reply = QMessageBox.question(
                self, "确认", "文件不是 .gguf 格式，确定加载吗？",
                QMessageBox.Yes | QMessageBox.No
            )
            if reply != QMessageBox.Yes:
                return
        self.signals.status.emit("load_model")
        self.signals.log.emit(f"正在加载模型: {path}")

    def _unload_model(self):
        self.signals.status.emit("unload_model")

    def _start_monitor(self):
        group = self.group_pattern.text().strip()
        download = self.download_dir.text().strip() or os.path.join(
            os.path.expanduser("~"), "ai-control", "downloads"
        )
        self.signals.status.emit("start_monitor")
        self.signals.log.emit(f"启动微信监控 | 群: {group or '全部'} | 下载: {download}")

    def _stop_monitor(self):
        self.signals.status.emit("stop_monitor")

    def update_tv_status(self, connected: bool):
        if connected:
            self.tv_status_label.setText("已连接")
            self.tv_status_label.setStyleSheet("color: green;")
            self.btn_connect_tv.setEnabled(False)
            self.btn_disconnect_tv.setEnabled(True)
        else:
            self.tv_status_label.setText("未连接")
            self.tv_status_label.setStyleSheet("color: red;")
            self.btn_connect_tv.setEnabled(True)
            self.btn_disconnect_tv.setEnabled(False)

    def update_model_status(self, loaded: bool, path: str = ""):
        if loaded:
            name = os.path.basename(path)
            self.model_status_label.setText(f"已加载: {name}")
            self.model_status_label.setStyleSheet("color: green;")
            self.btn_load_model.setEnabled(False)
            self.btn_unload_model.setEnabled(True)
        else:
            self.model_status_label.setText("加载失败" if path else "未加载")
            self.model_status_label.setStyleSheet("color: red;")
            self.btn_load_model.setEnabled(True)
            self.btn_unload_model.setEnabled(False)

    def update_monitor_status(self, running: bool):
        self.btn_start_monitor.setEnabled(not running)
        self.btn_stop_monitor.setEnabled(running)

    def update_group_list(self, groups: list):
        self.group_list.clear()
        for g in groups:
            name = getattr(g, "name", str(g))
            wxid = getattr(g, "wxid", "")
            item = QListWidgetItem(f"{name}  ({wxid})" if wxid else name)
            self.group_list.addItem(item)

    def get_config(self) -> dict:
        return {
            "tv": {
                "host": self.tv_host.text().strip(),
                "ws_port": self.ws_port.value(),
                "http_port": self.http_port.value(),
            },
            "model": {
                "path": self.model_path.text().strip(),
                "n_threads": self.thread_count.value(),
                "n_gpu_layers": self.gpu_layers.value(),
            },
            "wechat": {
                "group_name": self.group_pattern.text().strip(),
                "time_window": self.time_window.value(),
                "download_dir": self.download_dir.text().strip(),
            },
            "classifier": {
                "cooldown": self.cooldown.value(),
                "max_buffer": self.max_buffer.value(),
            }
        }


# ============================================================
# 监控面板
# ============================================================

class MessageItemWidget(QFrame):
    """单条消息显示控件"""

    def __init__(self, msg: MessageItem):
        super().__init__()
        self.msg = msg
        self._init_ui()

    def _init_ui(self):
        self.setFrameShape(QFrame.StyledPanel)
        self.setStyleSheet("""
            MessageItemWidget {
                border: 1px solid #ddd;
                border-radius: 4px;
                margin: 2px;
                padding: 4px;
            }
        """)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 4, 8, 4)
        layout.setSpacing(2)

        # 头部: 发送者 + 时间
        header = QHBoxLayout()
        sender = QLabel(f"<b>{self.msg.sender}</b>")
        sender.setStyleSheet("color: #2b5797;")
        time_str = datetime.fromtimestamp(self.msg.timestamp).strftime("%H:%M:%S")
        time_label = QLabel(time_str)
        time_label.setStyleSheet("color: gray; font-size: 10px;")
        header.addWidget(sender)
        header.addStretch()
        header.addWidget(time_label)
        layout.addLayout(header)

        # 内容
        content = self.msg.text[:200]
        if self.msg.image_paths:
            content += f"  📷×{len(self.msg.image_paths)}"
        if self.msg.video_path:
            content += "  🎬"
        if self.msg.link_url:
            content += "  🔗"
        content_label = QLabel(content)
        content_label.setWordWrap(True)
        content_label.setStyleSheet("color: #333;")
        layout.addWidget(content_label)


class MonitorPanel(QWidget):
    """监控标签页 - 实时消息流和投屏状态"""

    def __init__(self):
        super().__init__()
        self.messages: List[MessageItem] = []
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)

        # ----- 状态栏 -----
        status_bar = QHBoxLayout()
        self.tv_indicator = QLabel("⚪ TV: 未连接")
        self.tv_indicator.setStyleSheet("color: gray;")
        self.model_indicator = QLabel("⚪ 模型: 未加载")
        self.model_indicator.setStyleSheet("color: gray;")
        self.wx_indicator = QLabel("⚪ 微信: 未监控")
        self.wx_indicator.setStyleSheet("color: gray;")
        status_bar.addWidget(self.tv_indicator)
        status_bar.addWidget(self.model_indicator)
        status_bar.addWidget(self.wx_indicator)
        status_bar.addStretch()
        self.auto_display_btn = QPushButton("⏸ 暂停自动投屏")
        self.auto_display_btn.setCheckable(True)
        self.auto_display_btn.setChecked(True)
        self.auto_display_btn.toggled.connect(self._toggle_auto_display)
        status_bar.addWidget(self.auto_display_btn)
        layout.addLayout(status_bar)

        # ----- 分割: 消息列表 + 当前决策 -----
        splitter = QSplitter(Qt.Vertical)

        # 消息流
        msg_group = QGroupBox("消息流")
        msg_layout = QVBoxLayout()
        self.msg_list = QListWidget()
        self.msg_list.setWordWrap(True)
        self.msg_list.setAlternatingRowColors(True)
        msg_layout.addWidget(self.msg_list)

        btn_layout = QHBoxLayout()
        self.btn_pause = QPushButton("⏸ 暂停滚动")
        self.btn_pause.setCheckable(True)
        self.btn_clear = QPushButton("清空")
        self.btn_clear.clicked.connect(self._clear_messages)
        btn_layout.addWidget(self.btn_pause)
        btn_layout.addWidget(self.btn_clear)
        btn_layout.addStretch()

        self.msg_count_label = QLabel("0 条消息")
        btn_layout.addWidget(self.msg_count_label)
        msg_layout.addLayout(btn_layout)
        msg_group.setLayout(msg_layout)
        splitter.addWidget(msg_group)

        # 当前决策
        decision_group = QGroupBox("当前投屏")
        decision_layout = QVBoxLayout()

        self.last_decision_label = QLabel("等待分类...")
        self.last_decision_label.setWordWrap(True)
        self.last_decision_label.setStyleSheet("font-size: 13px; padding: 8px;")
        decision_layout.addWidget(self.last_decision_label)

        self.btn_manual_scroll = QPushButton("📝 手动发滚动文字")
        self.btn_manual_scroll.clicked.connect(self._manual_scroll)
        decision_layout.addWidget(self.btn_manual_scroll)

        decision_group.setLayout(decision_layout)
        splitter.addWidget(decision_group)

        layout.addWidget(splitter)

    def add_message(self, msg: MessageItem):
        """添加一条消息到列表"""
        self.messages.append(msg)

        if not self.btn_pause.isChecked():
            item = QListWidgetItem()
            widget = MessageItemWidget(msg)
            item.setSizeHint(widget.sizeHint())
            self.msg_list.addItem(item)
            self.msg_list.setItemWidget(item, widget)
            self.msg_list.scrollToBottom()

        self.msg_count_label.setText(f"{len(self.messages)} 条消息")

    def update_decision(self, decision: Optional[DisplayDecision]):
        """更新当前投屏决策显示"""
        if decision:
            text = (
                f"<b>展示方式:</b> {decision.display}<br>"
                f"<b>类型:</b> {decision.type}<br>"
                f"<b>原因:</b> {decision.reason}<br>"
                f"<b>参数:</b> {json.dumps(decision.params, ensure_ascii=False)}"
            )
            self.last_decision_label.setText(text)
        else:
            self.last_decision_label.setText("等待分类...")

    def set_tv_status(self, connected: bool):
        color = "green" if connected else "gray"
        text = "🟢" if connected else "⚪"
        self.tv_indicator.setText(f"{text} TV: {'已连接' if connected else '未连接'}")
        self.tv_indicator.setStyleSheet(f"color: {color};")

    def set_model_status(self, loaded: bool, path: str = ""):
        color = "green" if loaded else "gray"
        text = "🟢" if loaded else "⚪"
        name = os.path.basename(path) if path else ""
        label = f"{text} 模型: {'已加载: ' + name if loaded else '未加载'}"
        self.model_indicator.setText(label)
        self.model_indicator.setStyleSheet(f"color: {color};")

    def set_wx_status(self, running: bool):
        color = "green" if running else "gray"
        text = "🟢" if running else "⚪"
        self.wx_indicator.setText(f"{text} 微信: {'监控中' if running else '未监控'}")
        self.wx_indicator.setStyleSheet(f"color: {color};")

    def _toggle_auto_display(self, checked):
        if checked:
            self.auto_display_btn.setText("▶ 恢复自动投屏")
        else:
            self.auto_display_btn.setText("⏸ 暂停自动投屏")

    def is_auto_display_paused(self) -> bool:
        return not self.auto_display_btn.isChecked()

    def _clear_messages(self):
        self.messages.clear()
        self.msg_list.clear()
        self.msg_count_label.setText("0 条消息")

    def _manual_scroll(self):
        """弹出手动输入滚动文字的对话框"""
        from PyQt5.QtWidgets import QInputDialog, QDialog, QVBoxLayout, QTextEdit, QDialogButtonBox

        dialog = QDialog(self)
        dialog.setWindowTitle("发送滚动文字")
        dialog.resize(400, 200)
        dlg_layout = QVBoxLayout(dialog)
        text_edit = QTextEdit()
        text_edit.setPlaceholderText("输入要在电视上滚动的文字...")
        dlg_layout.addWidget(text_edit)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        dlg_layout.addWidget(buttons)

        if dialog.exec_() == QDialog.Accepted:
            text = text_edit.toPlainText().strip()
            if text:
                self.parent().parent()._send_scroll_text(text)


# ============================================================
# 日志面板
# ============================================================

class LogPanel(QWidget):
    """日志标签页"""

    def __init__(self):
        super().__init__()
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        self.log_text.setFont(QFont("Consolas", 9))
        layout.addWidget(self.log_text)

        btn_layout = QHBoxLayout()
        self.btn_clear_log = QPushButton("清空日志")
        self.btn_clear_log.clicked.connect(self.log_text.clear)
        btn_layout.addWidget(self.btn_clear_log)
        btn_layout.addStretch()
        layout.addLayout(btn_layout)

    def append_log(self, msg: str):
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.log_text.append(f"[{timestamp}] {msg}")
        # 滚动到底部
        cursor = self.log_text.textCursor()
        cursor.movePosition(QTextCursor.End)
        self.log_text.setTextCursor(cursor)


# ============================================================
# 主窗口
# ============================================================

class MainWindow(QMainWindow):
    """主应用窗口"""

    def __init__(self, config_path: str, base_dir: str = None):
        super().__init__()
        self.config_path = config_path
        self.base_dir = base_dir or os.path.dirname(os.path.abspath(config_path))
        self.config = self._load_config()

        # 工作线程
        self.wechat_worker: Optional[WeChatWorker] = None
        self.tv_worker: Optional[TVWorker] = None
        self.model_worker: Optional[ModelWorker] = None

        # 分类器
        self.classifier: Optional[ContentClassifier] = None

        # 自动分类定时器
        self._classify_timer = QTimer()
        self._classify_timer.timeout.connect(self._auto_classify)
        self._classify_timer.setInterval(15000)  # 每15秒尝试分类

        self._init_ui()
        self._connect_signals()

    def _init_ui(self):
        self.setWindowTitle("TV 分屏 AI 控制端")
        self.setMinimumSize(900, 700)

        # 中央窗口
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QVBoxLayout(central)

        # 标签页
        self.tabs = QTabWidget()
        self.monitor_panel = MonitorPanel()
        self.settings_panel = SettingsPanel(self.config)
        self.log_panel = LogPanel()

        self.tabs.addTab(self.monitor_panel, "📡 监控面板")
        self.tabs.addTab(self.settings_panel, "⚙️ 设置")
        self.tabs.addTab(self.log_panel, "📋 日志")

        main_layout.addWidget(self.tabs)

        # 状态栏
        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)
        self.status_bar.showMessage("就绪")

        # 窗口图标
        self._apply_styles()

    def _apply_styles(self):
        self.setStyleSheet("""
            QMainWindow { background: #f5f5f5; }
            QGroupBox {
                font-weight: bold;
                border: 1px solid #ccc;
                border-radius: 6px;
                margin-top: 10px;
                padding-top: 10px;
            }
            QGroupBox::title {
                subcontrol-origin: margin;
                left: 10px;
                padding: 0 5px;
            }
            QPushButton {
                min-height: 28px;
                padding: 4px 12px;
            }
            QLineEdit, QSpinBox {
                min-height: 24px;
            }
        """)

    def _connect_signals(self):
        """连接各面板的信号"""
        s = self.settings_panel.signals

        # 设置面板状态信号 → 主窗口处理
        # (通过设置面板按钮点击触发 status 信号)
        # 这里用 monkey-patch 方式接入

        # 按钮点击替换为直接调用主窗口方法
        self.settings_panel.btn_connect_tv.clicked.disconnect()
        self.settings_panel.btn_connect_tv.clicked.connect(self._start_tv_connection)

        self.settings_panel.btn_load_model.clicked.disconnect()
        self.settings_panel.btn_load_model.clicked.connect(self._start_model_load)

        self.settings_panel.btn_unload_model.clicked.disconnect()
        self.settings_panel.btn_unload_model.clicked.connect(self._unload_model)

        self.settings_panel.btn_start_monitor.clicked.disconnect()
        self.settings_panel.btn_start_monitor.clicked.connect(self._start_wechat_monitor)

        self.settings_panel.btn_stop_monitor.clicked.disconnect()
        self.settings_panel.btn_stop_monitor.clicked.connect(self._stop_wechat_monitor)

        self.settings_panel.btn_disconnect_tv.clicked.disconnect()
        self.settings_panel.btn_disconnect_tv.clicked.connect(self._stop_tv_connection)

    def _load_config(self) -> dict:
        """加载 YAML 配置文件"""
        try:
            if os.path.isfile(self.config_path):
                with open(self.config_path, "r", encoding="utf-8") as f:
                    return yaml.safe_load(f) or {}
        except Exception as e:
            logger.warning(f"加载配置失败: {e}")
        return {}

    def _save_config(self):
        """保存当前配置"""
        try:
            cfg = self.settings_panel.get_config()
            with open(self.config_path, "w", encoding="utf-8") as f:
                yaml.dump(cfg, f, allow_unicode=True, default_flow_style=False)
            self._log("配置已保存")
        except Exception as e:
            self._log(f"保存配置失败: {e}")

    def _log(self, msg: str):
        logger.info(msg)
        self.log_panel.append_log(msg)
        self.status_bar.showMessage(msg, 5000)

    # ============================================================
    # TV 连接
    # ============================================================

    def _start_tv_connection(self):
        cfg = self.settings_panel.get_config()
        host = cfg["tv"]["host"]

        if self.tv_worker:
            self._stop_tv_connection()

        self.tv_worker = TVWorker()
        self.tv_worker.configure(host, cfg["tv"]["ws_port"], cfg["tv"]["http_port"])
        self.tv_worker.signals.tv_connected.connect(self._on_tv_connected)
        self.tv_worker.start()

        self._log(f"正在连接 TV: {host}:{cfg['tv']['ws_port']}")

    def _stop_tv_connection(self):
        if self.tv_worker:
            self.tv_worker.stop()
            self.tv_worker.quit()
            self.tv_worker.wait(2000)
            self.tv_worker = None
        self.settings_panel.update_tv_status(False)
        self.monitor_panel.set_tv_status(False)

    def _on_tv_connected(self, connected: bool):
        self.settings_panel.update_tv_status(connected)
        self.monitor_panel.set_tv_status(connected)
        if connected:
            self._log("TV 连接成功")
        else:
            self._log("TV 连接断开")

    # ============================================================
    # 模型加载
    # ============================================================

    def _start_model_load(self):
        cfg = self.settings_panel.get_config()
        model_path = cfg["model"]["path"]

        # 如果是相对路径，基于 exe 所在目录解析
        if model_path and not os.path.isabs(model_path):
            model_path = os.path.normpath(os.path.join(self.base_dir, model_path))
            self.settings_panel.model_path.setText(model_path)

        if not model_path or not os.path.isfile(model_path):
            QMessageBox.warning(self, "提示",
                f"模型文件不存在: {model_path}\n"
                f"请确认文件路径，或浏览选择正确的 .gguf 文件")
            return

        if self.model_worker:
            self._unload_model()

        # 直接主线程加载（不用 QThread，llama_cpp C 扩展在非主线程会空指针）
        self._log("正在加载模型，请稍候...")
        self.status_bar.showMessage("正在加载模型...")
        
        try:
            from llama_cpp import Llama
            from core.model_manager import ModelManager
            from core.content_classifier import ContentClassifier
            
            model_mgr = ModelManager()
            self.model = Llama(
                model_path=model_path,
                n_ctx=cfg["model"].get("n_ctx", 2048),
                n_threads=cfg["model"].get("n_threads", 2),
                n_gpu_layers=cfg["model"].get("n_gpu_layers", 0),
                verbose=False,
            )
            model_mgr._loaded = True
            model_mgr.model = self.model
            model_mgr.model_path = model_path
            
            self.classifier = ContentClassifier(model_mgr)
            self._classify_timer.start()
            self.settings_panel.update_model_status(True, model_path)
            self.monitor_panel.set_model_status(True, model_path)
            self._log(f"模型加载成功: {os.path.basename(model_path)}")
            self.status_bar.showMessage("模型已就绪")
            self._save_config()
        except Exception as e:
            self._log(f"模型加载失败，启用规则兜底分类: {e}")
            self.status_bar.showMessage("模型加载失败，使用规则分类")
            self.settings_panel.update_model_status(False, model_path)
            self.monitor_panel.set_model_status(False, model_path)

    def _unload_model(self):
        if hasattr(self, 'model') and self.model is not None:
            try:
                self.model.close()
            except Exception:
                pass
            self.model = None

        if self.classifier:
            self.classifier = None

        self.settings_panel.update_model_status(False)
        self.monitor_panel.set_model_status(False)
        self._log("模型已卸载")

    def _on_model_loaded(self, success: bool, path: str):
        self.settings_panel.update_model_status(success, path)
        self.monitor_panel.set_model_status(success, path)

        if success:
            self._save_config()

    # ============================================================
    # 微信监控
    # ============================================================

    def _start_wechat_monitor(self):
        cfg = self.settings_panel.get_config()

        if self.wechat_worker:
            self._stop_wechat_monitor()

        download_dir = cfg["wechat"].get("download_dir", "") or os.path.join(
            os.path.expanduser("~"), "ai-control", "downloads"
        )

        self.wechat_worker = WeChatWorker()
        self.wechat_worker.configure(cfg["wechat"]["group_name"], download_dir)
        self.wechat_worker.signals.message.connect(self._on_wechat_message)
        self.wechat_worker.signals.log.connect(self._log)
        self.wechat_worker.signals.groups.connect(self.settings_panel.update_group_list)
        self.wechat_worker.start()

        self.settings_panel.update_monitor_status(True)
        self.monitor_panel.set_wx_status(True)
        self._log("微信监控启动中...")

    def _stop_wechat_monitor(self):
        if self.wechat_worker:
            self.wechat_worker.stop()
            self.wechat_worker.quit()
            self.wechat_worker.wait(2000)
            self.wechat_worker = None
        self.settings_panel.update_monitor_status(False)
        self.monitor_panel.set_wx_status(False)
        self._log("微信监控已停止")

    def _on_wechat_message(self, msg: MessageItem):
        """收到微信消息"""
        self.monitor_panel.add_message(msg)

        # 通知分类器
        if self.classifier:
            self.classifier.add_message(msg)

        # 如果开启了自动投屏且暂���暂停，立即尝试分类
        if (self.classifier and self.tv_worker
                and self.tv_worker.dispatcher
                and self.tv_worker.dispatcher.is_connected
                and not self.monitor_panel.is_auto_display_paused()):
            self._try_classify()

    # ============================================================
    # 自动分类与投屏
    # ============================================================

    def _auto_classify(self):
        """定时器触发的自动分类"""
        if (self.classifier and self.tv_worker
                and self.tv_worker.dispatcher
                and self.tv_worker.dispatcher.is_connected
                and not self.monitor_panel.is_auto_display_paused()):
            self._try_classify()

    def _try_classify(self):
        """尝试对最近消息做分类决策"""
        if not self.classifier or not self.tv_worker:
            return

        cfg = self.settings_panel.get_config()
        window = cfg["wechat"].get("time_window", 3600)

        recent = self.classifier.get_recent_messages(window)
        if not recent:
            return

        decision = self.classifier.get_decision(recent)
        if decision is None:
            return

        self.monitor_panel.update_decision(decision)

        # 执行投屏
        if (self.tv_worker.dispatcher
                and self.tv_worker.dispatcher.is_connected):
            self.tv_worker.show_decision(decision)
            self._log(f"自动投屏: {decision.display} | {decision.reason}")

    def _send_scroll_text(self, text: str):
        """手动发送滚动文字"""
        if self.tv_worker and self.tv_worker.dispatcher:
            success = self.tv_worker.dispatcher.show_scroll_text(text)
            if success:
                self._log(f"手动投屏滚动文字: {text[:50]}...")
            else:
                self._log("发送失败：TV 未连接")

    # ============================================================
    # 生命周期
    # ============================================================

    def closeEvent(self, event):
        """窗口关闭时清理资源"""
        self._classify_timer.stop()
        self._save_config()

        self._stop_wechat_monitor()
        self._stop_tv_connection()
        self._unload_model()

        event.accept()
