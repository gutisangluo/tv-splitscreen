package com.splitscreen.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
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

    // 时钟
    private Handler clockHandler;

    public BaseZone(@NonNull Context context) {
        super(context);
        init();
    }

    private void init() {
        // 分区默认黑色背景 + 分割边框
        setBackgroundColor(Color.parseColor("#1a1a1a"));
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
                .load(url)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(imageView);

        addView(imageView);
    }

    // ========== 视频 ==========

    @SuppressLint("UnsafeOptInUsageError")
    public void showVideo(String url, boolean loop, boolean mute) {
        clearContent();
        contentType = "video";

        playerView = new PlayerView(getContext());
        playerView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        playerView.setUseController(false);  // TV端默认无控制栏
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);

        exoPlayer = new ExoPlayer.Builder(getContext())
                .setHandleAudioBecomingNoisy(true)
                .build();
        exoPlayer.setRepeatMode(loop ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        exoPlayer.setVolume(mute ? 0f : 1f);

        MediaItem mediaItem = MediaItem.fromUri(url);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();

        playerView.setPlayer(exoPlayer);
        addView(playerView);
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
            Glide.with(getContext()).load(urls.get(0)).into(imageView);
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
                        Glide.with(getContext())
                                .load(urls.get(slideIndex))
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

        textView = new TextView(getContext());
        textView.setLayoutParams(new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        textView.setText(text);
        textView.setTextSize(18);
        textView.setTextColor(Color.WHITE);
        textView.setSingleLine(false);
        textView.setGravity(Gravity.CENTER);

        final int frameDelay = 30;  // ms
        scrollHandler = new Handler(Looper.getMainLooper());

        switch (direction) {
            case "up": {
                textView.setLayoutParams(new LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                addView(textView);
                final int totalHeight = getResources().getDisplayMetrics().heightPixels;
                final float[] translateY = {totalHeight};

                scrollHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        translateY[0] -= speed * 30f / 1000f;
                        textView.setTranslationY(translateY[0]);
                        if (translateY[0] + textView.getHeight() > 0) {
                            scrollHandler.postDelayed(this, frameDelay);
                        } else {
                            translateY[0] = totalHeight;
                            scrollHandler.postDelayed(this, frameDelay);
                        }
                    }
                });
                break;
            }
            case "left": {
                addView(textView);
                final float[] translateX = {getWidth()};
                scrollHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        textView.setSingleLine(true);
                        translateX[0] -= speed * 30f / 1000f;
                        textView.setTranslationX(translateX[0]);
                        if (translateX[0] + textView.getWidth() > 0) {
                            scrollHandler.postDelayed(this, frameDelay);
                        } else {
                            translateX[0] = getWidth();
                            scrollHandler.postDelayed(this, frameDelay);
                        }
                    }
                });
                break;
            }
            default: {
                // 垂直滚动
                showText(text, "#FFFFFF", 18, "center");
                break;
            }
        }
    }

    // ========== 时钟 ==========

    public void showClock(String format, String color, int fontSize) {
        clearContent();
        contentType = "clock";

        textView = new TextView(getContext());
        textView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        textView.setGravity(Gravity.CENTER);
        textView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));

        try {
            textView.setTextColor(Color.parseColor(color));
        } catch (Exception e) {
            textView.setTextColor(Color.WHITE);
        }
        textView.setTextSize(fontSize);

        clockHandler = new Handler(Looper.getMainLooper());
        clockHandler.post(new Runnable() {
            @Override
            public void run() {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(format, java.util.Locale.getDefault());
                textView.setText(sdf.format(new java.util.Date()));
                clockHandler.postDelayed(this, 1000);
            }
        });

        addView(textView);
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
