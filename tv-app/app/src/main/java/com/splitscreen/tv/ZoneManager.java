package com.splitscreen.tv;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.splitscreen.tv.BaseZone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分区管理器 - 管理电视屏幕上的所有分区
 * 负责创建、布局、更新各分区
 */
public class ZoneManager {

    private static final String TAG = "ZoneManager";

    private final Context context;
    private final FrameLayout container;  // 屏幕容器
    private final Map<Integer, BaseZone> zones = new HashMap<>();
    private String currentLayout = "full";

    // 分区布局模板定义
    private static final Map<String, List<ZoneRect>> LAYOUT_TEMPLATES = new HashMap<>();

    static {
        // 全屏 (1分区)
        List<ZoneRect> full = new ArrayList<>();
        full.add(new ZoneRect(0, 0, 100, 100, "full"));
        LAYOUT_TEMPLATES.put("full", full);

        // 水平二等分 (2分区)
        List<ZoneRect> h2 = new ArrayList<>();
        h2.add(new ZoneRect(0, 0, 50, 100, "left"));
        h2.add(new ZoneRect(50, 0, 50, 100, "right"));
        LAYOUT_TEMPLATES.put("2h", h2);

        // 垂直二等分 (2分区)
        List<ZoneRect> v2 = new ArrayList<>();
        v2.add(new ZoneRect(0, 0, 100, 50, "top"));
        v2.add(new ZoneRect(0, 50, 100, 50, "bottom"));
        LAYOUT_TEMPLATES.put("2v", v2);

        // 主+副 (2分区, 2:1)
        List<ZoneRect> p2 = new ArrayList<>();
        p2.add(new ZoneRect(0, 0, 67, 100, "main"));
        p2.add(new ZoneRect(67, 0, 33, 100, "side"));
        LAYOUT_TEMPLATES.put("2+1", p2);

        // 田字格 (4分区)
        List<ZoneRect> g4 = new ArrayList<>();
        g4.add(new ZoneRect(0, 0, 50, 50, "tl"));
        g4.add(new ZoneRect(50, 0, 50, 50, "tr"));
        g4.add(new ZoneRect(0, 50, 50, 50, "bl"));
        g4.add(new ZoneRect(50, 50, 50, 50, "br"));
        LAYOUT_TEMPLATES.put("2x2", g4);

        // 左列3行+右侧大区 (4分区)
        List<ZoneRect> l4 = new ArrayList<>();
        l4.add(new ZoneRect(0, 0, 33, 33, "lt"));
        l4.add(new ZoneRect(0, 33, 33, 33, "lc"));
        l4.add(new ZoneRect(0, 66, 33, 34, "lb"));
        l4.add(new ZoneRect(33, 0, 67, 100, "rmain"));
        LAYOUT_TEMPLATES.put("3+1", l4);

        // 2行3列 (6分区)
        List<ZoneRect> s6 = new ArrayList<>();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                s6.add(new ZoneRect(col * 33, row * 50, 33, 50, "r" + row + "c" + col));
            }
        }
        LAYOUT_TEMPLATES.put("2x3", s6);

        // 九宫格 (9分区)
        List<ZoneRect> n9 = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                n9.add(new ZoneRect(col * 33, row * 33, 33, 33, "r" + row + "c" + col));
            }
        }
        LAYOUT_TEMPLATES.put("3x3", n9);

        // 田字格+中心大区 (5分区)
        List<ZoneRect> f5 = new ArrayList<>();
        f5.add(new ZoneRect(0, 0, 50, 50, "tl"));
        f5.add(new ZoneRect(50, 0, 50, 50, "tr"));
        f5.add(new ZoneRect(0, 50, 50, 50, "bl"));
        f5.add(new ZoneRect(50, 50, 50, 50, "br"));
        f5.add(new ZoneRect(15, 15, 70, 70, "center"));
        LAYOUT_TEMPLATES.put("4+1", f5);
    }

    public ZoneManager(Context context, FrameLayout container) {
        this.context = context;
        this.container = container;
    }

    /**
     * 设置布局模板
     */
    public void setLayout(String layoutName) {
        List<ZoneRect> rects = LAYOUT_TEMPLATES.get(layoutName);
        if (rects == null) {
            Log.w(TAG, "未知布局: " + layoutName + "，使用全屏");
            rects = LAYOUT_TEMPLATES.get("full");
            layoutName = "full";
        }
        currentLayout = layoutName;
        applyLayout(rects);
    }

    /**
     * 设置自定义布局 (百分比坐标)
     */
    public void setCustomLayout(JSONArray zonesArray) {
        List<ZoneRect> rects = new ArrayList<>();
        try {
            for (int i = 0; i < zonesArray.length(); i++) {
                JSONObject z = zonesArray.getJSONObject(i);
                rects.add(new ZoneRect(
                        z.getInt("x"),
                        z.getInt("y"),
                        z.getInt("w"),
                        z.getInt("h"),
                        z.optString("id", "z" + i)
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "自定义布局解析失败", e);
            return;
        }
        currentLayout = "custom";
        applyLayout(rects);
    }

    private void applyLayout(List<ZoneRect> rects) {
        // 清除旧分区
        container.removeAllViews();
        zones.clear();

        // 创建新分区
        for (int i = 0; i < rects.size(); i++) {
            ZoneRect r = rects.get(i);

            BaseZone zone = new BaseZone(context);
            zone.setZoneId(i);
            zone.setZoneName(r.name);
            zone.setTag("zone_" + i);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, 0);
            // 百分比转换为权重或具体值
            // 使用 FrameLayout 手动布局
            container.addView(zone);

            // 存储分区信息，在 onLayout 时设置位置
            zone.setLayoutParamsData(r.x, r.y, r.w, r.h);

            zones.put(i, zone);
        }

        // 请求重新布局
        container.requestLayout();
    }

    /**
     * 在容器布局完成后，更新各分区实际位置
     */
    public void updateZonePositions(int containerWidth, int containerHeight) {
        for (BaseZone zone : zones.values()) {
            ZoneRect r = zone.getLayoutParamsData();
            if (r != null) {
                int left = containerWidth * r.x / 100;
                int top = containerHeight * r.y / 100;
                int right = containerWidth * (r.x + r.w) / 100;
                int bottom = containerHeight * (r.y + r.h) / 100;

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) zone.getLayoutParams();
                lp.leftMargin = left;
                lp.topMargin = top;
                lp.width = right - left;
                lp.height = bottom - top;

                // 给分区添加边框/分割线效果
                zone.setPadding(2, 2, 2, 2);

                zone.setLayoutParams(lp);
            }
        }
    }

    /**
     * 设置分区内容
     */
    public void setZoneContent(int zoneId, String contentType, JSONObject params) {
        BaseZone zone = zones.get(zoneId);
        if (zone == null) {
            Log.w(TAG, "分区 " + zoneId + " 不存在");
            return;
        }

        switch (contentType) {
            case "image":
                String imageUrl = params.optString("url", "");
                String fit = params.optString("fit", "cover");
                zone.showImage(imageUrl, fit);
                break;

            case "video":
                String videoUrl = params.optString("url", "");
                boolean loop = params.optBoolean("loop", false);
                boolean mute = params.optBoolean("mute", false);
                zone.showVideo(videoUrl, loop, mute);
                break;

            case "web":
                String webUrl = params.optString("url", "");
                String html = params.optString("html", null);
                if (html != null) {
                    zone.showHtml(html);
                } else {
                    zone.showWebUrl(webUrl);
                }
                break;

            case "text":
                String text = params.optString("text", "");
                String textColor = params.optString("color", "#FFFFFF");
                int fontSize = params.optInt("fontSize", 24);
                String align = params.optString("align", "center");
                zone.showText(text, textColor, fontSize, align);
                break;

            case "slideshow":
                JSONArray urls = params.optJSONArray("urls");
                int interval = params.optInt("interval", 3000);
                if (urls != null) {
                    List<String> slideUrls = new ArrayList<>();
                    for (int i = 0; i < urls.length(); i++) {
                        slideUrls.add(urls.optString(i));
                    }
                    zone.showSlideshow(slideUrls, interval);
                }
                break;

            case "scroll":
                String scrollText = params.optString("text", "");
                int speed = params.optInt("speed", 50);
                String direction = params.optString("direction", "up");
                zone.showScroll(scrollText, speed, direction);
                break;

            case "clock":
                zone.showClock(params.optString("format", "HH:mm:ss"),
                        params.optString("color", "#FFFFFF"),
                        params.optInt("fontSize", 48));
                break;

            default:
                Log.w(TAG, "未知内容类型: " + contentType);
                break;
        }
    }

    /**
     * 清空分区
     */
    public void clearZone(int zoneId) {
        BaseZone zone = zones.get(zoneId);
        if (zone != null) {
            zone.clear();
        }
    }

    /**
     * 设置背景颜色
     */
    public void setBackground(String color) {
        try {
            container.setBackgroundColor(Color.parseColor(color));
        } catch (Exception e) {
            container.setBackgroundColor(Color.BLACK);
        }
    }

    /**
     * 获取当前分区数量
     */
    public int getZoneCount() {
        return zones.size();
    }

    /**
     * 获取当前布局名称
     */
    public String getCurrentLayout() {
        return currentLayout;
    }

    /**
     * 获取状态 JSON
     */
    public JSONObject getStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("layout", currentLayout);
            status.put("zones", zones.size());
            JSONArray zoneList = new JSONArray();
            for (BaseZone z : zones.values()) {
                JSONObject zi = new JSONObject();
                zi.put("id", z.getZoneId());
                zi.put("name", z.getZoneName());
                zi.put("type", z.getContentType());
                zoneList.put(zi);
            }
            status.put("zoneList", zoneList);
        } catch (Exception e) {
            Log.e(TAG, "状态JSON失败", e);
        }
        return status;
    }

    /**
     * 分区矩形（百分比坐标）
     */
    public static class ZoneRect {
        public int x, y, w, h;  // 百分比 0-100
        public String name;

        public ZoneRect(int x, int y, int w, int h, String name) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.name = name;
        }
    }
}
