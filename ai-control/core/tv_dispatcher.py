"""
TV 投屏调度模块

通过 WebSocket 发送指令到电视端，通过 HTTP 上传图片/视频文件。
完全复用现有 TV APP 的 WS (9527) + HTTP 上传 (9528) 协议。
"""

import os
import json
import time
import logging
import threading
from typing import Optional, List
from urllib.parse import urljoin

import requests
import websocket

logger = logging.getLogger(__name__)


class TVDispatcher:
    """
    TV 端投屏指令调度器

    通过 WebSocket 发送实时控制指令，
    通过 HTTP 上传媒体文件到电视端文件服务器。
    """

    def __init__(self, host: str = "192.168.1.100",
                 ws_port: int = 9527,
                 http_port: int = 9528):
        self.host = host
        self.ws_port = ws_port
        self.http_port = http_port
        self.ws_url = f"ws://{host}:{ws_port}"
        self.http_base = f"http://{host}:{http_port}"

        self.ws: Optional[websocket.WebSocketApp] = None
        self._connected = False
        self._ws_thread: Optional[threading.Thread] = None
        self._reconnect_lock = threading.Lock()
        self._should_reconnect = True
        self._status_callback: Optional[callable] = None

    @property
    def is_connected(self) -> bool:
        return self._connected

    def connect(self):
        """建立 WebSocket 连接（非阻塞）"""
        self._should_reconnect = True
        self._ws_thread = threading.Thread(target=self._ws_loop, daemon=True)
        self._ws_thread.start()

    def disconnect(self):
        """断开连接"""
        self._should_reconnect = False
        if self.ws:
            try:
                self.ws.close()
            except Exception:
                pass
            self.ws = None
        self._connected = False

    def set_status_callback(self, callback: callable):
        """连接状态变化回调"""
        self._status_callback = callback

    def _ws_loop(self):
        """WebSocket 连接循环（含自动重连）"""
        while self._should_reconnect:
            try:
                self.ws = websocket.WebSocketApp(
                    self.ws_url,
                    on_open=self._on_open,
                    on_close=self._on_close,
                    on_error=self._on_error,
                    on_message=self._on_message,
                )
                self.ws.run_forever(ping_interval=30, ping_timeout=10)
            except Exception as e:
                logger.warning(f"WebSocket 连接异常: {e}")

            if self._should_reconnect:
                logger.info("5 秒后重连...")
                time.sleep(5)

    def _on_open(self, ws):
        self._connected = True
        logger.info(f"已连接到 TV: {self.ws_url}")
        if self._status_callback:
            self._status_callback(True)

    def _on_close(self, ws, close_status_code, close_msg):
        self._connected = False
        logger.info("TV 连接已断开")
        if self._status_callback:
            self._status_callback(False)

    def _on_error(self, ws, error):
        logger.error(f"WebSocket 错误: {error}")

    def _on_message(self, ws, message):
        logger.debug(f"收到 TV 消息: {message}")

    def send_command(self, command: dict) -> bool:
        """发送 JSON 指令到 TV"""
        if not self._connected:
            logger.warning("TV 未连接，无法发送指令")
            return False
        try:
            payload = json.dumps(command, ensure_ascii=False)
            self.ws.send(payload)
            logger.debug(f"已发送: {payload[:100]}")
            return True
        except Exception as e:
            logger.error(f"发送指令失败: {e}")
            self._connected = False
            return False

    # ---- 便捷投屏方法 ----

    def show_scroll_text(self, text: str, direction: str = "left",
                         position: str = "bottom"):
        """显示滚动文字（跑马灯）"""
        return self.send_command({
            "content_type": "scroll",
            "params": {
                "text": text,
                "direction": direction,
                "position": position,
            }
        })

    def show_single_image(self, url: str, zone_id: int = 0):
        """显示单张图片"""
        return self.send_command({
            "type": "set_content",
            "zone_id": zone_id,
            "content_type": "image",
            "params": {"url": url}
        })

    def show_slideshow(self, urls: List[str], zone_id: int = 0,
                       interval: int = 5):
        """幻灯片播放系列图片"""
        return self.send_command({
            "type": "set_content",
            "zone_id": zone_id,
            "content_type": "slideshow",
            "params": {
                "urls": urls,
                "interval": interval,
            }
        })

    def show_video(self, url: str, zone_id: int = 0, loop: bool = True):
        """播放视频"""
        return self.send_command({
            "type": "set_content",
            "zone_id": zone_id,
            "content_type": "video",
            "params": {
                "url": url,
                "loop": loop,
            }
        })

    def show_web(self, url: str, zone_id: int = 0):
        """显示网页"""
        return self.send_command({
            "type": "set_content",
            "zone_id": zone_id,
            "content_type": "web",
            "params": {"url": url}
        })

    def apply_layout(self, layout: str):
        """应用布局"""
        return self.send_command({
            "type": "set_layout",
            "layout": layout
        })

    def upload_file(self, local_path: str) -> Optional[str]:
        """
        上传文件到 TV 端

        Args:
            local_path: 本地文件路径

        Returns:
            上传后的 URL 路径（供 TV 端引用），失败返回 None
        """
        if not os.path.isfile(local_path):
            logger.error(f"文件不存在: {local_path}")
            return None

        try:
            filename = os.path.basename(local_path)
            url = urljoin(self.http_base, "/upload")
            files = {filename: open(local_path, "rb")}
            resp = requests.post(url, files=files, timeout=30)
            files[filename].close()

            if resp.status_code == 200:
                result = resp.json()
                path = result.get("url", result.get("path", filename))
                logger.info(f"上传成功: {local_path} -> {path}")
                return path
            else:
                logger.error(f"上传失败: HTTP {resp.status_code}")
                return None

        except requests.exceptions.ConnectionError:
            logger.error(f"上传失败: 无法连接 {self.http_base}")
            return None
        except Exception as e:
            logger.error(f"上传异常: {e}")
            return None

    def apply_display_decision(self, decision):
        """
        根据分类决策自动投屏

        Args:
            decision: DisplayDecision 对象
        """
        if not decision:
            logger.warning("没有决策数据")
            return False

        display_type = decision.display
        params = decision.params

        logger.info(f"执行投屏: {display_type} | {decision.reason}")

        if display_type == "scroll_text":
            text = params.get("text", decision.reason)
            return self.show_scroll_text(text)

        elif display_type == "single_image":
            url = params.get("url", "")
            if not url:
                return self.show_scroll_text(f"[图片] {decision.reason}")
            return self.show_single_image(url)

        elif display_type == "slideshow":
            urls = params.get("urls", [])
            if not urls:
                return self.show_scroll_text(f"[幻灯片] {decision.reason}")
            return self.show_slideshow(urls)

        elif display_type == "video_player":
            url = params.get("url", "")
            if url:
                return self.show_video(url)
            return self.show_scroll_text(f"[视频] {decision.reason}")

        else:
            logger.warning(f"未知展示类型: {display_type}")
            return False
