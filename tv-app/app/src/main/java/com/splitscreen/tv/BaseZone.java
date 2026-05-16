package com.splitscreen.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * BaseZone - 单个分区的 View
 * 支持：图片、视频、网页、文字、幻灯片、滚动文字、时钟
 */
public class BaseZone extends FrameLayout {

    private static final String TAG = "BaseZone";

    private int zoneId;
    private String zoneName;
    private String contentType = "empty";
    private ZoneManager.ZoneRect layoutData;

    // 内容视图
    private ImageView imageView;
    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private WebView webView;
    private TextView textView;
    private LinearLayout slideContainer;

    // 幻灯片
    private Timer slideTimer;
    private int slideIndex = 0;
    private List<String> slideUrls;

    // 滚动
    private Handler scrollHandler;
    private int scrollOffset = 0;
    private ObjectAnimator scrollAnim;

    // 时钟
    private Handler clockHandler;

    public BaseZone(@NonNull Context context) {
        super(context);
        init();
    }

    private void init() {
        // 分区带分割边框
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.parseColor("#1a1a1a"));  // 底色
        border.setStroke(2, Color.parseColor("#444444"));  // 2px灰色分割线
        setBackground(border);

        setWillNotDraw(false);

        // 硬件加速
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setZoneId(int id) { this.zoneId = id; }
    public int getZoneId() { return zoneId; }
    public void setZoneName(String name) { this.zoneName = name; }
    public String getZoneName() { return zoneName; }
    public void setLayoutParamsData(int x, int y, int w, int h) {
        this.layoutData = new ZoneManager.ZoneRect(x, y, w, h, zoneName);
    }
    public ZoneManager.ZoneRect getLayoutParamsData() { return layoutData; }
    public String getContentType() { return contentType; }

    // ========== 图片 ==========

    @SuppressLint("Glide")
    public void showImage(String url, String fit) {
        clearContent();
        contentType = "image";

        // 相对路径补全为完整 HTTP 地址
        String fullUrl = url;
        if (url != null && url.startsWith("/")) {
            fullUrl = "http://127.0.0.1:9528" + url;
        }

        imageView = new ImageView(getContext());
        imageView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        ImageView.ScaleType scaleType = ImageView.ScaleType.CENTER_CROP;
        switch (fit) {
            case "contain": scaleType = ImageView.ScaleType.FIT_CENTER; break;
            case "fill":    scaleType = ImageView.ScaleType.FIT_XY; break;
            case "fitW":    scaleType = ImageView.ScaleType.FIT_START; break;
        }
        imageView.setScaleType(scaleType);

        Glide.with(getContext())
                .load(fullUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(imageView);

        addView(imageView);
    }

    // ========== 视频 ==========

    @SuppressLint("UnsafeOptInUsageError")
    public void showVideo(String url, boolean loop, boolean mute) {
        clearContent();
        contentType = "video";

        // 相对路径补全
        String fullUrl = url;
        if (url != null && url.startsWith("/")) {
            fullUrl = "http://127.0.0.1:9528" + url;
        }

        try {
            playerView = new PlayerView(getContext());
            playerView.setLayoutParams(new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            playerView.setUseController(false);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);

            exoPlayer = new ExoPlayer.Builder(getContext())
                    .setHandleAudioBecomingNoisy(true)
                    .setRenderersFactory(new androidx.media3.exoplayer.DefaultRenderersFactory(getContext())
                            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                            .setEnableDecoderFallback(true))
                    .build();
            exoPlayer.setRepeatMode(loop ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
            exoPlayer.setVolume(mute ? 0f : 1f);

            MediaItem mediaItem = MediaItem.fromUri(fullUrl);
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            exoPlayer.play();

            playerView.setPlayer(exoPlayer);
            addView(playerView);
            Log.d(TAG, "视频已开始播放: " + fullUrl);
        } catch (Exception e) {
            Log.e(TAG, "视频播放失败: " + fullUrl + " error=" + e.getMessage(), e);
            // 回退：显示文字提示
            showText("视频加载失败", "#FF6666", 16, "center");
        }
    }

    // ========== 网页 ==========

    @SuppressLint("SetJavaScriptEnabled")
    public void showWebUrl(String url) {
        clearContent();
        contentType = "web";

        webView = new WebView(getContext());
        webView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl(url);

        addView(webView);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void showHtml(String html) {
        clearContent();
        contentType = "web";

        webView = new WebView(getContext());
        webView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);

        addView(webView);
    }

    // ========== 文字 ==========

    public void showText(String text, String color, int fontSize, String align) {
        clearContent();
        contentType = "text";

        textView = new TextView(getContext());
        textView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        textView.setText(text);
        textView.setTextSize(fontSize);
        textView.setTypeface(Typeface.DEFAULT_BOLD);

        try {
            textView.setTextColor(Color.parseColor(color));
        } catch (Exception e) {
            textView.setTextColor(Color.WHITE);
        }

        switch (align) {
            case "left":   textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); break;
            case "right":  textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); break;
            default:       textView.setGravity(Gravity.CENTER); break;
        }

        // 添加padding以免文字贴边
        textView.setPadding(16, 8, 16, 8);

        addView(textView);
    }

    // ========== 幻灯片 ==========

    public void showSlideshow(List<String> urls, int intervalMs) {
        clearContent();
        contentType = "slideshow";
        this.slideUrls = urls;
        this.slideIndex = 0;

        imageView = new ImageView(getContext());
        imageView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        if (!urls.isEmpty()) {
            String firstUrl = urls.get(0);
            if (firstUrl.startsWith("/")) firstUrl = "http://127.0.0.1:9528" + firstUrl;
            Glide.with(getContext()).load(firstUrl).into(imageView);
        }

        addView(imageView);

        // 定时切换
        slideTimer = new Timer();
        slideTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                slideIndex = (slideIndex + 1) % urls.size();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (imageView != null && slideIndex < urls.size()) {
                        String slideUrl = urls.get(slideIndex);
                        if (slideUrl.startsWith("/")) slideUrl = "http://127.0.0.1:9528" + slideUrl;
                        Glide.with(getContext())
                                .load(slideUrl)
                                .transition(DrawableTransitionOptions.withCrossFade())
                                .into(imageView);
                    }
                });
            }
        }, intervalMs, intervalMs);
    }

    // ========== 滚动文字 ==========

    public void showScroll(String text, int speed, String direction) {
        clearContent();
        contentType = "scroll";

        // 速度：1-100 → 动画时长 20秒-1秒
        long duration = Math.max(1000, 20000 - speed * 190L);

        switch (direction) {
            case "up": {
                final TextView tv = new TextView(getContext());
                tv.setLayoutParams(new LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                tv.setText(text);
                tv.setTextSize(18);
                tv.setTextColor(Color.WHITE);
                tv.setSingleLine(false);
                tv.setGravity(Gravity.CENTER);
                addView(tv);

                // 等布局完成再启动动画
                post(() -> {
                    final int h = getHeight();
                    tv.setTranslationY(h);
                    // 从 h 滚动到 -tvHeight
                    ObjectAnimator anim = ObjectAnimator.ofFloat(tv, "translationY", h, -tv.getHeight());
                    anim.setDuration(duration);
                    anim.setRepeatCount(ValueAnimator.INFINITE);
                    anim.setRepeatMode(ValueAnimator.RESTART);
                    anim.start();
                    scrollAnim = anim;
                });
                break;
            }
            case "left": {
                final TextView tv = new TextView(getContext());
                tv.setLayoutParams(new LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
                tv.setText(text);
                tv.setTextSize(20);
                tv.setTextColor(Color.WHITE);
                tv.setSingleLine(true);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                addView(tv);

                post(() -> {
                    final int w = getWidth();
                    tv.setTranslationX(w);
                    // 从 w 滚动到 -tvWidth
                    ObjectAnimator anim = ObjectAnimator.ofFloat(tv, "translationX", w, -tv.getWidth());
                    anim.setDuration(duration);
                    anim.setRepeatCount(ValueAnimator.INFINITE);
                    anim.setRepeatMode(ValueAnimator.RESTART);
                    anim.start();
                    scrollAnim = anim;
                });
                break;
            }
            default:
                showText(text, "#FFFFFF", 18, "center");
                break;
        }
    }

    // ========== 时钟 ==========

    public void showClock(String format, String color, int fontSize) {
        clearContent();
        contentType = "clock";

        final TextView tv = new TextView(getContext());
        tv.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.DEFAULT);
        tv.setTextColor(Color.WHITE);
        try { tv.setTextColor(Color.parseColor(color)); } catch (Exception e) { }

        addView(tv);

        clockHandler = new Handler(Looper.getMainLooper());
        clockHandler.post(new Runnable() {
            @Override
            public void run() {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        format, java.util.Locale.getDefault());
                String raw = sdf.format(new java.util.Date());

                // 自动分行
                String[] parts = raw.split(" ");
                StringBuilder display = new StringBuilder();
                int lines = parts.length;
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) display.append("\n");
                    display.append(parts[i]);
                }
                if (lines == 0) { display.append(raw); lines = 1; }

                tv.setText(display.toString());

                // 自动字号：分区高度 × 0.3 ÷ 行数
                int zoneH = getHeight();
                if (zoneH <= 0) zoneH = getResources().getDisplayMetrics().heightPixels;
                int autoSizePx = Math.max(16, (int)(zoneH * 0.3f / lines));
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, autoSizePx);

                clockHandler.postDelayed(this, 1000);
            }
        });
    }

    // ========== 清空 ==========

    public void clear() {
        clearContent();
        contentType = "empty";
    }

    private void clearContent() {
        // 停止定时器
        if (slideTimer != null) {
            slideTimer.cancel();
            slideTimer = null;
        }
        if (scrollHandler != null) {
            scrollHandler.removeCallbacksAndMessages(null);
            scrollHandler = null;
        }
        if (scrollAnim != null) {
            scrollAnim.cancel();
            scrollAnim = null;
        }
        if (clockHandler != null) {
            clockHandler.removeCallbacksAndMessages(null);
            clockHandler = null;
        }

        // 释放 ExoPlayer
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }

        // 清除 WebView
        if (webView != null) {
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }

        // 移除所有子视图
        removeAllViews();
        imageView = null;
        playerView = null;
        textView = null;
        slideContainer = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearContent();
    }
}
