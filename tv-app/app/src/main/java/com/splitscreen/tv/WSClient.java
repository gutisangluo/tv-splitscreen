package com.splitscreen.tv;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * WebSocket 客户端 - 使用 Java-WebSocket 库
 * 与控制端通信，接收布局指令、内容更新指令
 */
public class WSClient extends WebSocketClient {

    private static final String TAG = "WSClient";
    private static final int RECONNECT_DELAY_MS = 3000;

    private final WsListener listener;
    private boolean shouldReconnect = true;
    private Thread reconnectThread;

    public interface WsListener {
        void onConnected();
        void onDisconnected();
        void onMessage(String message);
        void onError(String error);
    }

    public WSClient(URI serverUri, WsListener listener) {
        super(serverUri);
        this.listener = listener;
        setConnectionLostTimeout(10); // 10秒无消息判定断开
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        Log.d(TAG, "WebSocket 已连接: " + getURI().toString());
        if (listener != null) {
            listener.onConnected();
        }
    }

    @Override
    public void onMessage(String message) {
        Log.d(TAG, "收到消息: " + message);
        if (listener != null) {
            listener.onMessage(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        Log.d(TAG, "已关闭: " + reason);
        if (listener != null) {
            listener.onDisconnected();
        }
        scheduleReconnect();
    }

    @Override
    public void onError(Exception ex) {
        Log.e(TAG, "错误: " + ex.getMessage());
        if (listener != null) {
            listener.onError(ex.getMessage());
        }
    }

    @Override
    public void connect() {
        shouldReconnect = true;
        super.connect();
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) return;
        if (reconnectThread != null && reconnectThread.isAlive()) return;

        reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
                if (shouldReconnect && !isOpen()) {
                    Log.d(TAG, "尝试重新连接...");
                    reconnect();
                }
            } catch (InterruptedException e) {
                // ignore
            }
        });
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    public void sendStatus(String statusJson) {
        send(statusJson);
    }

    public void disconnect() {
        shouldReconnect = false;
        if (reconnectThread != null) {
            reconnectThread.interrupt();
            reconnectThread = null;
        }
        try {
            closeBlocking();
        } catch (InterruptedException e) {
            close();
        }
    }
}
