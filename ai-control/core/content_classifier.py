"""
内容分类器 - 对微信消息进行智能分类

将原始消息文本、图片描述、链接等，通过本地模型分析后
输出结构化的投屏指令给 TV 调度模块。
"""

import logging
import re
from typing import Optional, List
from dataclasses import dataclass, field
from datetime import datetime

from .model_manager import ModelManager

logger = logging.getLogger(__name__)


@dataclass
class MessageItem:
    """单条微信消息的抽象"""
    msg_id: str
    sender: str
    timestamp: float
    text: str = ""
    image_paths: List[str] = field(default_factory=list)
    image_descriptions: List[str] = field(default_factory=list)
    video_path: str = ""
    link_url: str = ""
    link_title: str = ""
    raw_type: int = 0  # WeChatFerry 消息类型


@dataclass
class DisplayDecision:
    """模型做出的展示决策"""
    type: str  # text_short, text_long, image_single, image_long, image_series, video, link
    display: str  # scroll_text, single_image, slideshow, video_player
    reason: str = ""
    params: dict = field(default_factory=dict)
    timestamp: float = 0.0
    source_messages: List[str] = field(default_factory=list)


class ContentClassifier:
    """使用本地模型分析消息内容并决定投屏方式"""

    def __init__(self, model_manager: ModelManager):
        self.model = model_manager
        self.buffer: List[MessageItem] = []
        self.max_buffer = 100
        self.cooldown = 30  # 秒
        self._last_decision_time = 0.0
        self._last_decision: Optional[DisplayDecision] = None

    def add_message(self, msg: MessageItem):
        """加入消息到缓冲队列"""
        self.buffer.append(msg)
        # 裁剪超出限制
        if len(self.buffer) > self.max_buffer:
            self.buffer = self.buffer[-self.max_buffer:]

    def get_recent_messages(self, window_seconds: int = 3600) -> List[MessageItem]:
        """获取时间窗口内的消息"""
        now = datetime.now().timestamp()
        return [m for m in self.buffer if now - m.timestamp <= window_seconds]

    def can_decide(self) -> bool:
        """检查是否可做新决策（冷却期检查）"""
        now = datetime.now().timestamp()
        return (now - self._last_decision_time) >= self.cooldown

    def _build_prompt_context(self, messages: List[MessageItem]) -> str:
        """将消息列表组织成模型能理解的提示文本"""
        lines = []
        for i, m in enumerate(messages[-10:], 1):  # 最多最近 10 条
            parts = []
            if m.sender:
                parts.append(f"[{m.sender}]")
            if m.text:
                parts.append(m.text)
            if m.image_descriptions:
                parts.append(f"(图片: {'; '.join(m.image_descriptions)})")
            if m.image_paths and not m.image_descriptions:
                parts.append(f"(有 {len(m.image_paths)} 张图片)")
            if m.video_path:
                parts.append("(视频文件)")
            if m.link_url:
                title = m.link_title or m.link_url
                parts.append(f"(链接: {title})")
            lines.append(f"#{i}: {' '.join(parts)}")

        return "\n".join(lines) if lines else "(无新消息)"

    def get_decision(self, recent_messages: List[MessageItem]) -> Optional[DisplayDecision]:
        """
        对最近一批消息做投屏决策

        返回 DisplayDecision 或 None（无需展示/无法决定）
        """
        if not recent_messages:
            return None

        if not self.can_decide():
            logger.debug("冷却中，跳过决策")
            return self._last_decision

        # 如果模型已加载，用 AI 分类
        if self.model.is_loaded:
            return self._ai_decision(recent_messages)

        # 否则用规则兜底分类
        return self._rule_decision(recent_messages)

        now = datetime.now().timestamp()
        decision = DisplayDecision(
            type=result.get("type", "text_short"),
            display=result.get("display", "scroll_text"),
            reason=result.get("reason", ""),
            params=result.get("params", {}),
            timestamp=now,
            source_messages=[m.msg_id for m in recent_messages[-3:]],
        )

        self._last_decision = decision
        self._last_decision_time = now
        logger.info(f"投屏决策: {decision.display} | {decision.reason}")
        return decision

    def _ai_decision(self, recent_messages: List[MessageItem]) -> Optional[DisplayDecision]:
        """AI 模型分类"""
        context = self._build_prompt_context(recent_messages)
        if not context or context == "(无新消息)":
            return None

        prompt = f"""以下是微信群最近的消息：

{context}

请分析这些消息，决定哪条（或哪些）内容最适合投屏到电视大屏展示。
考虑以下因素：
1. 文字内容：短文字滚动显示，长文字慢速滚动
2. 单张图片：直接显示
3. 多张图片：幻灯片播放
4. 视频：视频播放
5. 链接：提取标题滚动显示

输出 JSON 格式的分类结果。"""

        result = self.model.classify(prompt)
        if result is None:
            return None

        now = datetime.now().timestamp()
        decision = DisplayDecision(
            type=result.get("type", "text_short"),
            display=result.get("display", "scroll_text"),
            reason=result.get("reason", ""),
            params=result.get("params", {}),
            timestamp=now,
            source_messages=[m.msg_id for m in recent_messages[-3:]],
        )
        self._last_decision = decision
        self._last_decision_time = now
        logger.info(f"AI投屏决策: {decision.display} | {decision.reason}")
        return decision

    def _rule_decision(self, recent_messages: List[MessageItem]) -> Optional[DisplayDecision]:
        """规则兜底分类（无需 AI 模型）"""
        # 取最新一条消息
        msg = recent_messages[-1]
        text = msg.text or ""
        img_count = len(msg.image_paths)
        now = datetime.now().timestamp()

        # 1. 视频文件
        if msg.video_path:
            decision = DisplayDecision(
                type="video", display="video_player",
                reason="视频文件", params={"url": msg.video_path},
                timestamp=now, source_messages=[msg.msg_id],
            )

        # 2. 多张图片 → 幻灯片
        elif img_count >= 2:
            urls = [f"/media/{os.path.basename(p)}" for p in msg.image_paths[:10]]
            decision = DisplayDecision(
                type="image_series", display="slideshow",
                reason=f"{img_count}张图片", params={"urls": urls, "interval": 5},
                timestamp=now, source_messages=[msg.msg_id],
            )

        # 3. 单张图片
        elif img_count == 1:
            url = f"/media/{os.path.basename(msg.image_paths[0])}"
            decision = DisplayDecision(
                type="image_single", display="single_image",
                reason="单张图片", params={"url": url},
                timestamp=now, source_messages=[msg.msg_id],
            )

        # 4. 链接
        elif msg.link_url:
            title = msg.link_title or msg.link_url
            decision = DisplayDecision(
                type="link", display="scroll_text",
                reason="链接", params={"text": title, "url": msg.link_url},
                timestamp=now, source_messages=[msg.msg_id],
            )

        # 5. 长文本（>=50字）
        elif len(text) >= 50:
            decision = DisplayDecision(
                type="text_long", display="scroll_text",
                reason="长文本", params={"text": text},
                timestamp=now, source_messages=[msg.msg_id],
            )

        # 6. 短文本
        elif text:
            decision = DisplayDecision(
                type="text_short", display="scroll_text",
                reason="短消息", params={"text": text},
                timestamp=now, source_messages=[msg.msg_id],
            )

        else:
            return None

        self._last_decision = decision
        self._last_decision_time = now
        logger.info(f"规则投屏决策: {decision.display} | {decision.reason}")
        return decision

    def clear_buffer(self):
        """清空消息缓冲"""
        self.buffer.clear()
        logger.info("消息缓冲已清空")
