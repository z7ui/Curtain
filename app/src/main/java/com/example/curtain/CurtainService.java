package com.example.curtain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.WindowManager;

import androidx.annotation.Nullable;

public class CurtainService extends Service {

    public static final String ACTION_SHOW = "com.example.curtain.SHOW";
    public static final String ACTION_HIDE = "com.example.curtain.HIDE";
    public static boolean isRunning = false;

    // 与 LSPosed 模块通信的广播
    private static final String ACTION_SYSTEM_BAR = "com.example.curtain.STATUS_BAR_CONTROL";
    private static final String EXTRA_HIDE = "hide";
    private static final String TARGET_SYSTEMUI = "com.android.systemui";

    private WindowManager wm;
    private CurtainOverlayView overlayView;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(1, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_SHOW.equals(action)) {
                showOverlay();
            } else if (ACTION_HIDE.equals(action)) {
                hideOverlay();
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void showOverlay() {
        if (overlayView != null) return;

        overlayView = new CurtainOverlayView(this);
        overlayView.setOnExitListener(() -> {
            hideOverlay();
            stopSelf();
        });

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
        }

        wm.addView(overlayView, params);
        isRunning = true;

        // 通知 LSPosed 模块隐藏状态栏和导航栏
        notifySystemBar(true);
    }

    private void hideOverlay() {
        if (overlayView != null) {
            try { wm.removeView(overlayView); } catch (Throwable ignored) {}
            overlayView = null;
        }
        isRunning = false;

        // 通知 LSPosed 模块恢复状态栏和导航栏
        notifySystemBar(false);
    }

    /**
     * 给 LSPosed 模块发广播，控制状态栏/导航栏显隐
     */
    private void notifySystemBar(boolean hide) {
        try {
            Intent intent = new Intent(ACTION_SYSTEM_BAR);
            intent.putExtra(EXTRA_HIDE, hide);
            intent.setPackage(TARGET_SYSTEMUI);
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        // 保险：Service 被销毁时也恢复状态栏
        notifySystemBar(false);
        hideOverlay();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        String channelId = "curtain_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, "幕布", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, channelId)
                .setContentTitle("幕布运行中")
                .setContentText("点击磁贴可关闭")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }
}