# 创维TV投屏分屏软件

创维电视（酷开系统）自定义分区投屏系统。手机/电脑端控制电视屏幕分屏显示，支持多分区、多种内容类型、实时更新。

## 系统架构

```
┌─────────────────┐      WebSocket + HTTP       ┌──────────────────────┐
│  控制端          │ ◄─────────────────────────► │  电视端 (接收端)      │
│                  │                              │                      │
│ - 手机 Web App  │      指令/内容/布局           │ - Android APK        │
│ - 电脑 Web App  │                              │ - 全屏 Activity      │
│ (PWA/响应式)     │                              │ - 前台服务保活        │
└─────────────────┘                              └──────────────────────┘
```

## 技术栈

### 电视端 (Android APK)
- **语言**: Java/Kotlin
- **最低 SDK**: Android 7.0 (API 24)
- **核心依赖**: 
  - WebSocket 客户端 (okhttp3/Java-WebSocket)
  - ExoPlayer (视频分区)
  - WebView (网页/HTML5分区)
- **架构**: Activity + 前台服务, 不使用悬浮窗

### 控制端 (Web App)
- **纯前端** HTML + CSS + JavaScript
- WebSocket 客户端
- 响应式设计 (手机/电脑均可用)
- 可 PWA 安装

## 通讯协议

### WebSocket API (实时控制)

**电视端 → 控制端 (状态)**
- `{type: "status", zones: number, device: string, version: string}`

**控制端 → 电视端 (指令)**

指令类型:
| type | 说明 | payload |
|------|------|---------|
| `set_layout` | 设置分区布局 | `{layout: "2x1"|"2x2"|"custom", zones: [{id, x, y, w, h}]}` |
| `set_content` | 设置分区内容 | `{zone_id: number, content_type: "image"|"video"|"web"|"text"|"slideshow"|"scroll", url: string, params: {...}}` |
| `update_content` | 更新分区内容 | 同 set_content |
| `clear_zone` | 清空分区 | `{zone_id: number}` |
| `set_bg` | 设置背景 | `{color: string, url?: string}` |
| `ping` | 心跳 | `{}` |

### HTTP API (大文件内容上传)
- `POST /upload` - 上传图片/视频
- `GET /media/{id}` - 获取媒体文件

## 分区内容类型

| 类型 | 渲染方式 | 说明 |
|------|---------|------|
| `image` | ImageView / Glide | 静态图片，自适应填充 |
| `video` | ExoPlayer / TextureView | 视频播放，含基本控制 |
| `web` | WebView | 加载任意 URL 或 HTML |
| `slideshow` | ImageView + Timer | 轮播多张图片 |
| `text` | TextView / Canvas | 固定文字展示 |
| `scroll` | WebView / ScrollView | 滚动文字/内容 |
| `clock` | 自定义 View | 实时时钟 |
| `weather` | 自定义 View | 天气信息 |

## 分区布局模板

| 模板 | 分区数 | 说明 |
|------|--------|------|
| `full` | 1 | 全屏单一内容 |
| `2h` | 2 | 水平二等分 |
| `2v` | 2 | 垂直二等分 |
| `2+1` | 2 | 主区占 2/3, 副区占 1/3 |
| `2x2` | 4 | 田字格四等分 |
| `3+1` | 4 | 左列3行 + 右侧大区 |
| `2x3` | 6 | 2行3列 |
| `3x3` | 9 | 九宫格 |
| `custom` | 自定义 | 手动指定各分区坐标 |

## 项目结构

```
tv-splitscreen/
├── tv-app/                    # 电视端 Android APK
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/splitscreen/tv/
│   │   │   │   ├── MainActivity.java      # 全屏主界面
│   │   │   │   ├── SplitScreenService.java # 前台服务 (保活)
│   │   │   │   ├── ZoneManager.java        # 分区管理器
│   │   │   │   ├── ZoneView.java           # 分区 View
│   │   │   │   ├── WebSocketClient.java    # 通信客户端
│   │   │   │   ├── ContentRenderer.java    # 内容渲染器
│   │   │   │   └── zones/                  # 各分区类型实现
│   │   │   │       ├── ImageZone.java
│   │   │   │       ├── VideoZone.java
│   │   │   │       ├── WebZone.java
│   │   │   │       ├── SlideshowZone.java
│   │   │   │       ├── TextZone.java
│   │   │   │       └── ScrollZone.java
│   │   │   ├── res/
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle
│   ├── gradle/
│   └── settings.gradle
├── control-web/               # 控制端 Web App
│   ├── index.html
│   ├── css/
│   │   └── style.css
│   ├── js/
│   │   ├── app.js             # 主逻辑
│   │   ├── websocket.js       # WS 通信
│   │   └── layouts.js         # 布局模板
│   └── manifest.json          # PWA manifest
├── server/                    # 可选中转服务器 (开发用)
│   ├── index.js               # WebSocket + HTTP 中转
│   └── package.json
└── README.md
```

## 开发路线

### Phase 1 — MVP (核心功能)
1. 电视端 APK: 基础 Activity + WebSocket 接收 + 分区渲染 (图片/文字)
2. 控制端 Web App: 布局选择 + 内容推送
3. 局域网直连通信

### Phase 2 — 增强
1. 视频分区 (ExoPlayer)
2. WebView 分区
3. 幻灯片分区
4. 多手机协同控制

### Phase 3 — 完善
1. 离线配置缓存
2. 预设场景模板
3. 内存优化
4. 创维各型号适配

## 创维电视注意事项

- 无需 root，普通 APK 安装即可
- 不要在酷开系统替换 Launcher
- 使用 `全屏 Activity + 前台服务` 模式
- 目标 SDK ≤ 28 (Android 9) 兼容最佳
- 最低兼容 Android 7.0 (API 24)
- 注意后台进程可能被杀死，使用前台通知保活

## 快速开始

### 1. 编译电视端 APK

参考 `android-apk-build` skill 在 WSL 或 Windows 上编译：

```bash
# 在 WSL 中（首次需安装 Android SDK）
apt install -y openjdk-17-jdk
cd tv-app
echo "sdk.dir=/opt/android-sdk" > local.properties
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
./gradlew assembleDebug --no-daemon
```

APK 输出：`tv-app/app/build/outputs/apk/debug/app-debug.apk`

### 2. 安装到创维电视

方案 A — U盘安装（推荐）：
1. 将 APK 复制到 U 盘
2. U盘插入电视 USB 口
3. 在电视上打开「文件管理器」→ 找到 APK → 安装

方案 B — ADB 安装：
```bash
# 电视开启「开发者模式」→ 打开「USB调试」
adb connect <TV_IP_ADDRESS>
adb install -t -r app-debug.apk
```

### 3. 启动电视端

在电视上找到「TV分屏」应用并打开。
屏幕显示「等待控制端连接...」及本机 IP 地址。

电视端默认 WebSocket 端口：**9527**

### 4. 打开控制端

用手机/电脑浏览器打开 `control-web/index.html`
或部署到任何 Web 服务器。

### 5. 连接控制

在控制端页面输入电视的 IP 地址（如 192.168.1.100），点「连接」。
连接成功后，即可：

1. 选择布局模板 → 电视屏幕自动分区
2. 点击任意分区 → 从底部面板选择内容类型 → 填写内容 URL/文字
3. 点「发送」→ 该分区立即更新

### 6. 自定义场景模板

在 `control-web/js/app.js` 的 `SCENE_TEMPLATES` 数组中添加预设场景：

```javascript
const SCENE_TEMPLATES = [
  {
    name: "监控中心",
    layout: "3+1",
    zones: [
      {zone: 3, type: "video", url: "rtsp://..."},
      {zone: 0, type: "web", url: "http://..."},
      {zone: 1, type: "clock", format: "HH:mm:ss"},
      {zone: 2, type: "scroll", text: "注意：..."}
    ]
  },
  // ...
];
```

## 项目文件结构

```
tv-splitscreen/
├── README.md                           # 本文档
├── tv-app/                             # 电视端 Android APK
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/values/
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── java/com/splitscreen/tv/
│               ├── MainActivity.java    # 全屏主界面
│               ├── SplitScreenService.java # 前台服务(保活)
│               ├── WSClient.java        # WebSocket 通信
│               ├── ZoneManager.java     # 分区管理
│               ├── BaseZone.java        # 分区视图(含所有类型)
│               └── ContentRenderer.java # 渲染接口
└── control-web/                        # 控制端 Web App
    ├── index.html                      # 主页面
    ├── manifest.json                   # PWA 配置
    ├── css/
    │   └── style.css                   # 深色主题样式
    └── js/
        └── app.js                      # 主逻辑
```
