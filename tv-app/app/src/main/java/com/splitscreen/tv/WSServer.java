package com.splitscreen.tv;

import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * WebSocket 服务端 - 使用 Java-WebSocket 库
 * 电视端默认监听，等待手机/电脑控制端连接
 */
public class WSServer extends WebSocketServer {

    private static final String TAG = "WSServer";

    private final WsListener listener;

    public interface WsListener {
        void onConnected();
        void onDisconnected();
        void onMessage(String message, WebSocket conn);
        void onError(String error);
    }

    public WSServer(int port, WsListener listener) {
        super(new InetSocketAddress(port));
        this.listener = listener;
        setReuseAddr(true);
        setConnectionLostTimeout(10);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.d(TAG, "控制端已连接: " + conn.getRemoteSocketAddress());
        if (listener != null) {
            listener.onConnected();
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.d(TAG, "控制端断开: " + reason);
        if (listener != null) {
            listener.onDisconnected();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Log.d(TAG, "收到消息: " + message);
        if (listener != null) {
            listener.onMessage(message, conn);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "错误: " + (ex != null ? ex.getMessage() : "unknown"));
        if (listener != null) {
            listener.onError(ex != null ? ex.getMessage() : "unknown");
        }
    }

    @Override
    public void onStart() {
        Log.d(TAG, "WebSocket 服务已启动, 端口: " + getPort());
    }

    /** 向所有已连接的控制端广播消息 */
    public void broadcastMessage(String message) {
        Collection<WebSocket> connections = getConnections();
        for (WebSocket conn : connections) {
            if (conn.isOpen()) {
                conn.send(message);
            }
        }
    }

    /** 是否有控制端连接 */
    public boolean hasConnections() {
        return !getConnections().isEmpty();
    }
}
