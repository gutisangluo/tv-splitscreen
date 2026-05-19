"""
微信消息监控模块（UIAutomation 版）

使用 Windows UI Automation 读取微信窗口内容，无需 DLL 注入。
安装: pip install uiautomation
要求:
  - Windows 微信客户端已登录
  - 微信主窗口需打开（可最小化到任务栏）
  - 目标群需在最近聊天列表中
"""

import os
import re
import time
import logging
import threading
from typing import Optional, Callable, List
from dataclasses import dataclass, field
from datetime import datetime

from .content_classifier import MessageItem

logger = logging.getLogger(__name__)


@dataclass
class GroupInfo:
    """微信群信息"""
    wxid: str = ""
    name: str = ""

    def __str__(self):
        return self.name or self.wxid


class WeChatMonitor:
    """
    微信消息监控器（UIAutomation）

    通过 Windows UI Automation 读取微信聊天窗口的消息。
    无需注入 DLL，不会被杀毒软件拦截。
    """

    def __init__(self):
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._callback: Optional[Callable] = None
        self._target_group_pattern: str = ""
        self._download_dir: str = ""
        self._message_ids: set = set()  # 去重用
        self._message_queue: List[MessageItem] = []
        self._wechat_window = None

        # uiautomation 延迟导入
        self._uia = None
        self._w = None  # uiautomation.WindowControl

    @property
    def is_running(self) -> bool:
        return self._running

    @property
    def available_groups(self) -> List[GroupInfo]:
        """获取聊天列表中的群"""
        return self._scan_chat_list()

    def _lazy_import(self):
        """延迟导入 uiautomation"""
        if self._uia is None:
            import uiautomation as auto
            self._uia = auto

    def set_target_group(self, pattern: str):
        """设置要监控的群名匹配模式"""
        self._target_group_pattern = pattern
        logger.info(f"监控群模式: {pattern}")

    def set_callback(self, callback: Callable[[MessageItem], None]):
        """设置消息回调"""
        self._callback = callback

    def set_download_dir(self, path: str):
        """设置图片下载目录"""
        self._download_dir = path
        os.makedirs(path, exist_ok=True)

    def _find_wechat_window(self):
        """查找微信主窗口"""
        self._lazy_import()
        # 微信主窗口类名
        for class_name in ["WeChatMainWndForPC", "ChatWnd"]:
            w = self._uia.WindowControl(
                searchDepth=1, ClassName=class_name
            )
            if w.Exists(maxSearchSeconds=2):
                return w

        # 兜底：按标题搜索
        w = self._uia.WindowControl(
            searchDepth=1, Name=re.compile(r"微信", re.I)
        )
        if w.Exists(maxSearchSeconds=2):
            return w

        return None

    def _scan_chat_list(self) -> List[GroupInfo]:
        """扫描左侧聊天列表中的群"""
        groups = []
        try:
            self._lazy_import()
            w = self._find_wechat_window()
            if w is None:
                return groups

            w.SetActive()  # 确保窗口活跃
            time.sleep(0.5)

            # 左侧聊天列表通常是一个 ListControl
            # 微信的聊天列表结构：左侧面板 → 聊天列表
            session_list = w.ListControl(
                searchDepth=5, Name=re.compile(r".*")
            )
            if not session_list.Exists(maxSearchSeconds=1):
                # 尝试通过 ClassName 找
                session_list = w.Control(
                    searchDepth=5, ClassName="SessionList"
                )

            if session_list.Exists(maxSearchSeconds=2):
                items = session_list.GetChildren()
                for item in items:
                    name = item.Name
                    if name and self._is_group_chat(name):
                        groups.append(GroupInfo(name=name))

            groups.sort(key=lambda g: g.name)
        except Exception as e:
            logger.debug(f"扫描聊天列表失败: {e}")
        return groups

    def _is_group_chat(self, name: str) -> bool:
        """判断是否为群聊（群聊通常有多个成员，名称特征）"""
        if not name:
            return False
        # 群聊名称通常包含多人提示或在通讯录中有特殊标记
        # 简单判断：长度 > 4 且不包含文件传输助手等
        exclude = ["文件传输助手", "微信团队", "订阅号"]
        for ex in exclude:
            if ex in name:
                return False
        # 通常微信群聊名较长
        return len(name) >= 3

    def start(self) -> bool:
        """启动微信监控"""
        if self._running:
            logger.warning("微信监控已在运行")
            return True

        try:
            self._lazy_import()

            # 检查微信窗口
            w = self._find_wechat_window()
            if w is None:
                logger.error(
                    "未找到微信窗口，请确认微信已启动并登录"
                )
                return False

            self._wechat_window = w
            self._running = True

            # 启动后台监控线程
            self._thread = threading.Thread(
                target=self._monitor_loop, daemon=True
            )
            self._thread.start()

            logger.info("微信监控已启动（UIAutomation 模式）")
            return True

        except ImportError:
            logger.error("请先安装 uiautomation: pip install uiautomation")
            return False
        except Exception as e:
            logger.error(f"微信监控启动失败: {e}")
            return False

    def stop(self):
        """停止微信监控"""
        self._running = False
        if self._thread:
            self._thread.join(timeout=3)
            self._thread = None
        self._wechat_window = None
        logger.info("微信监控已停止")

    def get_recent_messages(self, group_name: str = "",
                            window_seconds: int = 3600) -> List[MessageItem]:
        """获取最近时间窗口内的消息"""
        now = time.time()
        return [
            m for m in self._message_queue
            if (now - m.timestamp) <= window_seconds
        ]

    def _monitor_loop(self):
        """后台监控循环"""
        last_check_time = 0
        check_interval = 2  # 每2秒检查一次

        while self._running:
            try:
                now = time.time()
                if now - last_check_time >= check_interval:
                    self._check_new_messages()
                    last_check_time = now
                time.sleep(0.5)
            except Exception as e:
                logger.error(f"监控循环异常: {e}")
                time.sleep(2)

    def _check_new_messages(self):
        """检查新消息"""
        self._lazy_import()

        if self._wechat_window is None:
            return

        try:
            # 消息内容通常在聊天区域的 ListItem/Text 控件中
            # 不同微信版本结构可能不同，尝试多种方式
            msg_controls = []

            # 方式1: 聊天消息列表
            msg_list = self._wechat_window.ListControl(
                searchDepth=5, ClassName="ChatMsgList"
            )
            if msg_list.Exists(maxSearchSeconds=1):
                msg_controls = msg_list.GetChildren()

            if not msg_controls:
                # 方式2: 找所有 Text 控件
                msg_controls = self._wechat_window.GetChildren()

            for ctrl in msg_controls[-20:]:  # 最近20条
                self._process_control(ctrl)

        except Exception as e:
            logger.debug(f"检查消息时异常: {e}")

    def _process_control(self, ctrl):
        """处理单个控件，提取消息"""
        try:
            ctrl_name = ctrl.Name
            if not ctrl_name:
                return

            # 生成消息ID（用于去重）
            # 使用控件名称+位置作为ID
            try:
                bounds = ctrl.BoundingRectangle
                msg_id = f"{ctrl_name}_{bounds.left}_{bounds.top}"
            except Exception:
                msg_id = ctrl_name + str(time.time())

            if msg_id in self._message_ids:
                return
            self._message_ids.add(msg_id)
            # 限制 ID 集合大小
            if len(self._message_ids) > 2000:
                self._message_ids = set(list(self._message_ids)[-1000:])

            # 检查是否匹配目标群
            if self._target_group_pattern:
                if not re.search(self._target_group_pattern, ctrl_name, re.I):
                    return

            item = MessageItem(
                msg_id=msg_id,
                sender="",
                timestamp=time.time(),
                text=ctrl_name[:500],
            )

            self._message_queue.append(item)
            if len(self._message_queue) > 500:
                self._message_queue = self._message_queue[-500:]

            logger.debug(f"捕获消息: {item.text[:50]}")
            if self._callback:
                self._callback(item)

        except Exception:
            pass

    def switch_to_group(self, group_name: str) -> bool:
        """切换到指定群聊"""
        try:
            self._lazy_import()
            w = self._find_wechat_window()
            if w is None:
                return False

            # 在聊天列表中找到目标群并点击
            session_list = w.ListControl(searchDepth=5)
            if session_list.Exists(maxSearchSeconds=2):
                for item in session_list.GetChildren():
                    if group_name in item.Name:
                        item.Click()
                        time.sleep(0.5)
                        logger.info(f"已切换到群: {group_name}")
                        return True

            logger.warning(f"未找到群: {group_name}")
            return False

        except Exception as e:
            logger.error(f"切换群失败: {e}")
            return False


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    mon = WeChatMonitor()

    def on_msg(msg):
        print(f"[{datetime.fromtimestamp(msg.timestamp).strftime('%H:%M:%S')}] {msg.text[:80]}")

    mon.set_callback(on_msg)

    if mon.start():
        print("\n可用群聊:")
        for g in mon.available_groups:
            print(f"  - {g}")
        print("\n监控中...按 Ctrl+C 停止")
        try:
            while mon.is_running:
                time.sleep(1)
        except KeyboardInterrupt:
            mon.stop()
