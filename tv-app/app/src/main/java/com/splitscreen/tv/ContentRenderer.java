package com.splitscreen.tv;

// 空接口，供以后扩展
public interface ContentRenderer {
    void render(String contentType, org.json.JSONObject params);
}
