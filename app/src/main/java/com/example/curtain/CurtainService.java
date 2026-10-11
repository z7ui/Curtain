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
 * 不需要无障碍权限，只要「显示在其他应用上层」就能用。代价是**盖不住状态栏** ——
 * 这是系统限制（这种窗口的层级天生低于状态栏，而且系统会忽略它请求的系统 UI 可见性变更）。
 * 想要状态栏一起变黑，请去开无障碍服务，那时走的是 CurtainAccessibilityService。
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
