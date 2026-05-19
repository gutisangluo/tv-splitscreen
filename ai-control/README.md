# TV 分屏 AI 控制端

用本地 1.5B 小模型自动识别微信群消息，智能投屏到电视大屏。

## 功能

- 📡 **微信群消息监控** — 监听指定微信群，捕获文字、图片、链接、视频
- 🤖 **本地 AI 分类** — 用 GGUF 小模型分析消息内容，自动决定展示方式
- 🖥️ **智能投屏** — 根据内容选择滚动文字、单图展示、幻灯片、视频播放
- ⚙️ **灵活配置** — 可自由切换模型、设置时间窗口、选择监控群

## 安装

### 1. 安装依赖

```bash
pip install -r requirements.txt
```

#### llama-cpp-python 特殊处理

Windows 下建议下载预编译 whl：

```bash
pip install llama-cpp-python --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu
```

或从 [GitHub Releases](https://github.com/abetlen/llama-cpp-python/releases) 下载对应 Python 版本的 `.whl` 文件安装。

#### WeChatFerry 特殊处理

需要先安装 WeChatFerry 客户端：

```bash
pip install wcf
```

然后确保本机微信已登录。

### 2. 下载模型

推荐模型（1.5B 级别，CPU 可跑）：

| 模型 | 大小 | 下载地址 |
|------|------|---------|
| Qwen2.5-1.5B-Instruct-Q4_K_M.gguf | ~1GB | [HuggingFace](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) |
| Qwen2.5-1.5B-Instruct-Q8_0.gguf | ~1.6GB | [同上](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) |
| Qwen2.5-0.5B-Instruct-Q4_K_M.gguf | ~350MB | [HuggingFace](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) |

将下载的 `.gguf` 文件放到 `models/` 目录下（或任意位置），在软件设置中指定路径。

> **国内用户**：如 HuggingFace 访问慢，可以用镜像：
> https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF

## 使用

### 方式一：直接运行

```bash
python main.py
```

### 方式二：打包为 exe

在 Windows 上运行 `build.bat`，输出在 `dist/TV智能投屏控制端.exe`

### 操作流程

1. **启动软件** → 界面打开
2. **连接 TV** → 设置面板输入电视 IP（默认 192.168.1.100:9527）
3. **加载模型** → 选择 GGUF 文件点击加载（约几秒到几十秒）
4. **启动微信监控** → 输入群名关键词（留空=监控所有群）
5. **自动投屏** → 收到消息后模型自动分类并投屏

### 配置说明

编辑 `config.yaml` 或通过 UI 设置面板修改：

```yaml
model:
  path: "models/qwen2.5-1.5b-q4_k_m.gguf"
  n_threads: 4
  n_gpu_layers: 0

tv:
  host: "192.168.1.100"
  ws_port: 9527
  http_port: 9528

wechat:
  group_name: "工作群"     # 监控群名（支持正则）
  time_window: 3600       # 时间窗口（秒）

classifier:
  cooldown: 30            # 分类间隔（秒）
  max_buffer: 100         # 消息缓冲上限
```

## 投屏规则

模型根据消息内容自动选择展示方式：

| 消息类型 | 投屏方式 | 示例 |
|---------|---------|------|
| 短文本（<50字） | 滚动文字（跑马灯） | "开会了"、"通知：下午3点开会" |
| 长文本 | 慢速滚动文字 | 文章、公告 |
| 单张普通图 | 居中展示 | 照片、截图 |
| 长图 | 可滚动图片 | 长截图、表格 |
| 多张图片 | 幻灯片轮流播放 | 系列照片 |
| 视频 | 视频播放器 | 短视频 |
| 链接 | 提取标题滚动 | 文章链接 |

## 项目结构

```
ai-control/
├── main.py                      # 入口
├── config.yaml                  # 默认配置
├── requirements.txt             # Python 依赖
├── build.bat                    # Windows 打包脚本
├── core/
│   ├── model_manager.py         # 模型加载管理
│   ├── content_classifier.py    # 内容分类器
│   ├── wechat_monitor.py        # 微信消息监控
│   └── tv_dispatcher.py         # TV 投屏调度
└── ui/
    └── main_window.py           # PyQt5 图形界面
```

## 前提条件

- Windows 系统（WeChatFerry 需要）
- 微信已登录（WeChatFerry 依赖）
- TV 端已安装分屏 APK 并启动服务（端口 9527/9528）
- 电脑和电视在同一网络
