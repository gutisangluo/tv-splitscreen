package com.splitscreen.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MainActivity - 电视端全屏主界面
 * 通过前台服务保持后台存活
 * WebSocket 连接控制端接收指令
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int WS_PORT = 9527;  // 默认WebSocket端口
    private static final int HTTP_PORT = 9528; // 文件上传HTTP端口

    private FrameLayout container;
    private TextView statusText;
    private ZoneManager zoneManager;
    private WSServer wsServer;
    private FileServer fileServer;
    private Handler mainHandler;
    private boolean showingInfo = false;
    private TextView tickerText;
    private ObjectAnimator tickerAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());

        // 全屏设置
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // UI 初始化
        container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);
        setContentView(container);

        // 初始提示文字
        statusText = new TextView(this);
        statusText.setText("TV分屏 - 等待控制端连接...");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(24);
        statusText.setGravity(android.view.Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.CENTER;
        statusText.setLayoutParams(lp);
        container.addView(statusText);

        // 分区管理器
        zoneManager = new ZoneManager(this, container);

        // 启动前台服务
        startSplitScreenService();

        // 请求通知权限 (Android 13+)
        requestNotificationPermission();

        // 启动文件服务器
        startFileServer();

        // 初始化 WebSocket 通信
        initWebSocket();

        // 显示IP信息和端口（5秒后隐藏）
        String ip = getLocalIpAddress();
        showConnectionInfo("IP: " + ip + ":" + WS_PORT + " 等待连接...", false);
        Log.d(TAG, "电视端已启动, IP: " + ip + " 端口: " + WS_PORT);

        // 创建屏幕顶部/底部滚动文字条（初始隐藏）
        tickerText = new TextView(this);
        tickerText.setTextColor(Color.WHITE);
        tickerText.setTypeface(Typeface.DEFAULT_BOLD);
        tickerText.setTextSize(16);
        tickerText.setPadding(20, 8, 20, 8);
        tickerText.setSingleLine(true);
        tickerText.setEllipsize(null);
        tickerText.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP; // 默认顶部
        tickerText.setLayoutParams(tlp);
        tickerText.setBackgroundColor(Color.parseColor("#88000000"));
        container.addView(tickerText);
    }

    private void initWebSocket() {
        try {
            // 电视端作为服务端，等待控制端连接
            wsServer = new WSServer(WS_PORT, new WSServer.WsListener() {
                @Override
                public void onConnected() {
                    Log.d(TAG, "控制端已连接");
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "已连接控制端", Toast.LENGTH_SHORT).show();
                        showConnectionInfo("已连接 ✓", false);
                    });
                }

                @Override
                public void onDisconnected() {
                    Log.d(TAG, "控制端断开");
                    mainHandler.post(() -> {
                        showConnectionInfo("控制端已断开", true);
                    });
                }

                @Override
                public void onMessage(String message, org.java_websocket.WebSocket conn) {
                    // WebSocket 消息在后台线程，UI 操作必须切到主线程
                    mainHandler.post(() -> handleMessage(message));
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "WS错误: " + error);
                }
            });

            wsServer.start();
            Log.d(TAG, "WebSocket 服务已启动, 等待控制端连接端口 " + WS_PORT);
        } catch (Exception e) {
            Log.e(TAG, "WebSocket服务启动失败: " + e.getMessage());
        }
    }

private void handleMessage(String message) {
    try {
        JSONObject msg = new JSONObject(message);
        String type = msg.getString("type");

            switch (type) {
                case "set_layout": {
                    String layout = msg.getString("layout");
                    if ("custom".equals(layout) && msg.has("zones")) {
                        zoneManager.setCustomLayout(msg.getJSONArray("zones"));
                    } else {
                        zoneManager.setLayout(layout);
                    }
                    sendStatus();
                    break;
                }

                case "set_content": {
                    int zoneId = msg.getInt("zone_id");
                    String contentType = msg.getString("content_type");
                    JSONObject params = msg.getJSONObject("params");

                    // 滚动文字：显示在屏幕顶部/底部跑马灯条，不在分区内
                    if ("scroll".equals(contentType) || "scrolltext".equals(contentType)) {
                        String scrollText = params.optString("text", "");
                        String position = params.optString("position", "top");
                        String direction = params.optString("direction", "left");
                        showTicker(scrollText, position, direction);
                        break;
                    }

                    zoneManager.setZoneContent(zoneId, contentType, params);
                    sendStatus();
                    break;
                }

                case "clear_zone": {
                    int zoneId = msg.getInt("zone_id");
                    zoneManager.clearZone(zoneId);
                    sendStatus();
                    break;
                }

                case "set_bg": {
                    String color = msg.getString("color");
                    zoneManager.setBackground(color);
                    break;
                }

                case "ping": {
                    JSONObject pong = new JSONObject();
                    pong.put("type", "pong");
                    pong.put("device", "创维TV");
                    pong.put("zones", zoneManager.getZoneCount());
                    pong.put("layout", zoneManager.getCurrentLayout());
                    pong.put("http_port", HTTP_PORT);
                    if (wsServer != null) {
                        wsServer.broadcastMessage(pong.toString());
                    }
                    break;
                }

                case "get_status": {
                    sendStatus();
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "消息处理失败: " + e.getMessage());
        }
    }

    private void sendStatus() {
        if (wsServer != null) {
            wsServer.broadcastMessage(zoneManager.getStatus().toString());
        }
    }

    /** 显示屏幕顶部/底部滚动文字条 */
    private void showTicker(String text, String position, String direction) {
        if (tickerText == null) return;

        // 取消旧动画
        if (tickerAnim != null) {
            tickerAnim.cancel();
            tickerAnim = null;
        }
        tickerText.setTranslationX(0);
        tickerText.setTranslationY(0);
        tickerText.setVisibility(View.GONE);

        // 清空文字再设
        tickerText.setText("");

        // 设置位置（顶部/底部）
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) tickerText.getLayoutParams();
        tlp.gravity = "bottom".equals(position) ? Gravity.BOTTOM : Gravity.TOP;
        tickerText.setLayoutParams(tlp);

        // 设置背景色
        tickerText.setBackgroundColor(Color.parseColor("#88000000"));
        tickerText.setText(text);
        tickerText.setVisibility(View.VISIBLE);

        tickerText.postDelayed(() -> {
            // 等布局完成获取宽度
            final int barW = tickerText.getWidth();
            if (barW <= 0) {
                tickerText.post(() -> startTickerAnim(direction));
            } else {
                startTickerAnim(direction);
            }
        }, 100);
    }

    private void startTickerAnim(String direction) {
        if ("up".equals(direction) || "down".equals(direction)) {
            // 上下滚动：从底部往上（或从顶部往下）
            // 简单实现：当前支持向上滚动
            tickerText.setSingleLine(false);
            tickerText.setMaxLines(1);
            tickerText.setSingleLine(true);
            // 水平跑马灯更合适，上下滚动用整屏滚动
            // 这里用水平滚动
            startMarquee();
        } else {
            // 默认水平滚动（跑马灯）
            startMarquee();
        }
    }

    private void startMarquee() {
        tickerText.setSingleLine(true);
        tickerText.setHorizontallyScrolling(true);
        tickerText.setSelected(true);
        // Android 原生 marquee 需要 focus
        tickerText.setFocusable(true);
        tickerText.setFocusableInTouchMode(true);
        tickerText.requestFocus();
        // 也可以用 ObjectAnimator 手动控制
        // 但如果文本长度小于屏幕宽度，原生 marquee 不滚动
        // 用 ObjectAnimator 确保始终滚动
        tickerText.post(() -> {
            int w = tickerText.getWidth();
            int tw = (int) tickerText.getPaint().measureText(tickerText.getText().toString());
            if (tw > w) {
                // 文字超出屏幕宽度才需要滚动
                ObjectAnimator anim = ObjectAnimator.ofFloat(tickerText, "translationX",
                        (float) w, (float) -tw);
                anim.setDuration(tw * 20L); // 每像素20ms
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setRepeatMode(ValueAnimator.RESTART);
                anim.start();
                tickerAnim = anim;
            }
        });
    }

    private void showConnectionInfo(String text, boolean persistent) {
        if (statusText != null) {
            statusText.setText(text);
            // 非持久提示：收到布局后自动隐藏
            if (!persistent) {
                statusText.postDelayed(() -> {
                    if (zoneManager.getZoneCount() > 0) {
                        statusText.setVisibility(View.GONE);
                    } else {
                        // 5秒后还没布局，再等一会
                        statusText.postDelayed(() -> {
                            if (zoneManager.getZoneCount() > 0) {
                                statusText.setVisibility(View.GONE);
                            }
                        }, 5000);
                    }
                }, 5000);
            }
        }
    }

    private void startSplitScreenService() {
        Intent serviceIntent = new Intent(this, SplitScreenService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void startFileServer() {
        try {
            fileServer = new FileServer(this);
            fileServer.start();
            Log.d(TAG, "文件服务器已启动: http://0.0.0.0:" + HTTP_PORT);
        } catch (Exception e) {
            Log.e(TAG, "文件服务器启动失败", e);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    2000);
        }
    }

    private String getLocalIpAddress() {
        // 遍历所有网络接口，找到第一个非 loopback 的 IPv4 地址
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface intf = interfaces.nextElement();
                if (intf.isLoopback() || !intf.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = intf.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取IP失败", e);
        }
        return "0.0.0.0";
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 保持沉浸式
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 遥控器菜单键显示/隐藏状态
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showingInfo = !showingInfo;
            if (statusText != null) {
                statusText.setVisibility(showingInfo ? View.VISIBLE : View.GONE);
                if (showingInfo) {
                    statusText.setText("布局: " + zoneManager.getCurrentLayout()
                            + " 分区: " + zoneManager.getZoneCount());
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tickerAnim != null) {
            tickerAnim.cancel();
            tickerAnim = null;
        }
        if (wsServer != null) {
            try {
                wsServer.stop(1000);
            } catch (Exception e) {
                // ignore
            }
        }
        if (fileServer != null) {
            fileServer.stop();
        }
    }
}
