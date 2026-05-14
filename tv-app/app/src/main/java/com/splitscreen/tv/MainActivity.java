package com.splitscreen.tv;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
    private WSClient wsClient;
    private FileServer fileServer;
    private Handler mainHandler;
    private boolean showingInfo = false;

    // 连接配置（可从 intent extra 读取）
    private String serverHost = null;  // null = 监听模式

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 读取 intent 参数
        if (getIntent().hasExtra("server_host")) {
            serverHost = getIntent().getStringExtra("server_host");
        }
        if (getIntent().hasExtra("server_port")) {
            // 可从 intent 指定端口
        }

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

        // 显示IP信息（5秒后隐藏）
        showConnectionInfo("启动中...", false);
    }

    private void initWebSocket() {
        try {
            java.net.URI uri = new java.net.URI("ws://0.0.0.0:" + WS_PORT);
            wsClient = new WSClient(uri, new WSClient.WsListener() {
            @Override
            public void onConnected() {
                Log.d(TAG, "已连接控制端");
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
            public void onMessage(String message) {
                handleMessage(message);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "WS错误: " + error);
            }
        });   // end WSClient constructor

        wsClient.connect();
    } catch (Exception e) {
        Log.e(TAG, "WebSocket初始化失败: " + e.getMessage());
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
                    pong.put("http_port", HTTP_PORT);  // 文件上传端口
                    wsClient.send(pong.toString());
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
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.send(zoneManager.getStatus().toString());
        }
    }

    private void showConnectionInfo(String text, boolean persistent) {
        if (statusText != null) {
            statusText.setText(text);
            // 隐藏提示文字（分区布局会覆盖它）
            if (!persistent) {
                statusText.postDelayed(() -> {
                    if (zoneManager.getZoneCount() > 0) {
                        statusText.setVisibility(View.GONE);
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
        // 尝试通过 WiFi 获取本机 IP
        try {
            java.net.Socket socket = new java.net.Socket("8.8.8.8", 53);
            String ip = socket.getLocalAddress().getHostAddress();
            socket.close();
            return ip;
        } catch (Exception e) {
            return "0.0.0.0";
        }
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
        if (wsClient != null) {
            wsClient.disconnect();
        }
        if (fileServer != null) {
            fileServer.stop();
        }
    }
}
