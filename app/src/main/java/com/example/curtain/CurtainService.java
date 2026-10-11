package com.example.curtain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 降级后端：普通前台服务 + [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]。
 *
 * 不需要无障碍权限，只要「显示在其他应用上层」就能用。
 *
 * 状态栏：这种窗口的**层级**确实永远低于状态栏，我们盖不住它；
 * 但可以让系统把它**藏起来** —— io.japp.blackscreen 只靠悬浮窗权限就做到了，
 * 关键是窗口要有"代表屏幕"的资格（全屏 / 不透明 / 能拿焦点）。
 *
 * 本服务同时承担「抢焦点」和「不抢焦点」两种模式，区别只在窗口带不带
 * FLAG_NOT_FOCUSABLE，详见 CurtainOverlay 的类注释和 wantFocus()。
 */
public class CurtainService extends Service {

    private static final String TAG = "CurtainService";
    private static final String CHANNEL_ID = "curtain";
    private static final int NOTIFICATION_ID = 1;

    @Nullable
    private static CurtainService instance;

    private CurtainOverlay overlay;

    /** 这条后端当前是否正在显示幕布（供 CurtainRouter 判断要不要发刷新）。 */
    public static boolean isShowing(@NonNull Context context) {
        CurtainService service = instance;
        return service != null && service.overlay != null && service.overlay.isShowing();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        // 在 onCreate 里就 startForeground，避开 startForegroundService 的 5 秒限制
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (CurtainRouter.ACTION_STOP.equals(action)) {
            hideOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (CurtainRouter.ACTION_REFRESH.equals(action)) {
            if (overlay != null) {
                overlay.refresh();
                overlay.rebuildIfNeeded();
            }
            return START_STICKY;
        }

        if (intent == null && !CurtainPrefs.isRunning(this)) {
            // 服务被系统重启（START_STICKY），但用户其实是关着的
            stopSelf();
            return START_NOT_STICKY;
        }

        showOverlay();
        return START_STICKY;
    }

    private void showOverlay() {
        if (overlay != null && overlay.isShowing()) {
            // 模式可能在主界面被改过（抢焦点 <-> 不抢焦点），窗口参数对不上就重建
            overlay.rebuildIfNeeded();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "没有悬浮窗权限，放弃显示");
            stopSelf();
            return;
        }
        if (overlay == null) {
            overlay = new CurtainOverlay(this,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    () -> {
                        hideOverlay();
                        stopSelf();
                    });
        }
        if (!overlay.show()) {
            stopSelf();
        }
    }

    private void hideOverlay() {
        if (overlay != null) {
            overlay.hide();
        }
    }

    @Override
    public void onDestroy() {
        if (overlay != null) {
            overlay.hide();
            overlay = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_curtain)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
