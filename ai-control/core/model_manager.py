"""
模型管理器 - 加载和运行 GGUF 本地小模型

支持通过 llama-cpp-python 加载任意 GGUF 格式模型。
用户可在 UI 中自由切换模型文件。
"""

import os
import time
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


class ModelManager:
    """管理本地 GGUF 模型的加载与推理"""

    def __init__(self):
        self.model = None
        self.model_path: Optional[str] = None
        self.params = {
            "n_ctx": 2048,
            "n_threads": 4,
            "n_gpu_layers": 0,
        }
        self._loaded = False

    @property
    def is_loaded(self) -> bool:
        return self._loaded and self.model is not None

    def configure(self, **kwargs):
        """更新模型启动参数"""
        for k, v in kwargs.items():
            if k in self.params:
                self.params[k] = v
        logger.info(f"模型参数已更新: {kwargs}")

    def load(self, model_path: str) -> bool:
        """加载 GGUF 模型文件"""
        if not os.path.isfile(model_path):
            logger.error(f"模型文件不存在: {model_path}")
            return False

        # 如果已加载同路径模型，跳过
        if self._loaded and self.model_path == model_path:
            logger.info("模型已加载，跳过")
            return True

        try:
            from llama_cpp import Llama

            logger.info(f"正在加载模型: {model_path}")
            logger.info(f"参数: {self.params}")

            start = time.time()
            self.model = Llama(
                model_path=model_path,
                n_ctx=self.params["n_ctx"],
                n_threads=self.params["n_threads"],
                n_gpu_layers=self.params["n_gpu_layers"],
                verbose=False,
            )
            elapsed = time.time() - start
            self.model_path = model_path
            self._loaded = True
            logger.info(f"模型加载完成，耗时 {elapsed:.1f}s")
            return True

        except ImportError:
            logger.error("请先安装 llama-cpp-python: pip install llama-cpp-python")
            return False
        except Exception as e:
            logger.error(f"模型加载失败: {e}")
            self.model = None
            self._loaded = False
            return False

    def unload(self):
        """卸载模型，释放内存"""
        if self.model is not None:
            try:
                # 显式删除引用，让 GC 回收
                self.model.close()
            except Exception:
                pass
            self.model = None
        self._loaded = False
        self.model_path = None
        logger.info("模型已卸载")

    def generate(self, prompt: str, max_tokens: int = 256,
                 temperature: float = 0.1) -> str:
        """生成文本"""
        if not self.is_loaded:
            raise RuntimeError("模型未加载")

        output = self.model.create_chat_completion(
            messages=[{"role": "user", "content": prompt}],
            max_tokens=max_tokens,
            temperature=temperature,
            stop=["```", "\n\n\n"],
        )
        return output["choices"][0]["message"]["content"].strip()

    def classify(self, prompt: str) -> Optional[dict]:
        """
        调用模型分类内容，期望返回 JSON 格式的结构化输出

        返回格式:
        {
            "type": "text_short|text_long|image_single|image_long|image_series|video|link",
            "display": "scroll_text|single_image|slideshow|video_player",
            "reason": "分类原因",
            "params": {}  // 可选的额外参数
        }
        """
        system_prompt = """你是一个内容分类助手。请根据用户提供的消息内容，判断它适合在电视上如何展示。

消息可能包含：文本、图片描述、链接或视频文件。

请严格按以下 JSON 格式输出（不要加 markdown 标记）：
{
  "type": "消息类型",
  "display": "展示方式",
  "reason": "简短分类原因",
  "params": {}
}

消息类型 (type):
- text_short: 一句话或短文本（<50字）
- text_long: 长文本（>=50字）
- image_single: 单张普通图片
- image_long: 长图/宽图（需要滚动查看）
- image_series: 多张连续图片
- video: 视频文件
- link: 网址链接

展示方式 (display):
- scroll_text: 滚动文字跑马灯
- single_image: 单张图片全屏/居中展示
- slideshow: 幻灯片轮流播放系列图片
- video_player: 视频播放器

params 可选字段：
- scroll_text: {"text": "显示的文字"}
- single_image: {"url": "图片地址"}
- slideshow: {"urls": ["图片1", "图片2", ...]}
- video_player: {"url": "视频地址"}
- link: {"text": "链接标题", "url": "链接地址"}

如果消息包含多条内容（如多条消息合并），选择最新或最重要的内容分类。"""

        if not self.is_loaded:
            raise RuntimeError("模型未加载")

        try:
            full_prompt = f"{system_prompt}\n\n消息内容：\n{prompt}\n\n分类结果："
            output = self.model.create_chat_completion(
                messages=[{"role": "user", "content": full_prompt}],
                max_tokens=256,
                temperature=0.1,
                stop=["\n\n"],
            )
            text = output["choices"][0]["message"]["content"].strip()
            # 尝试提取 JSON
            import json
            # 清理可能的 markdown 包裹
            text = text.replace("```json", "").replace("```", "").strip()
            result = json.loads(text)
            return result
        except json.JSONDecodeError:
            logger.warning(f"模型输出不是合法 JSON: {text}")
            return None
        except Exception as e:
            logger.error(f"分类失败: {e}")
            return None
