package com.example.curtain;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 首选后端：无障碍服务。
 *
 * 只有无障碍服务能创建 TYPE_ACCESSIBILITY_OVERLAY 窗口，也只有它能把状态栏 /
 * 导航栏一起藏掉。代价是用户必须去「设置 → 无障碍」里手动打开。
 *
 * 这个服务由系统绑定，不能也不该 startForeground，所以它没有常驻通知。
 * 权限上只申请「能画悬浮窗」，不读取屏幕内容
 * （见 res/xml/accessibility_service_config.xml 的 canRetrieveWindowContent="false"）。
 */
public class CurtainAccessibilityService extends AccessibilityService {

    private static final String TAG = "CurtainA11y";

    private CurtainOverlay overlay;

    /** 无障碍服务不需要事件，保持空实现。 */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        overlay = new CurtainOverlay(this,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                () -> {
                    if (overlay != null) {
                        overlay.hide();
                    }
                });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (overlay == null) {
            // onServiceConnected 还没跑完，下次再说
            return START_NOT_STICKY;
        }

        String action = intent == null ? null : intent.getAction();
        if (CurtainRouter.ACTION_STOP.equals(action)) {
            overlay.hide();
        } else if (CurtainRouter.ACTION_REFRESH.equals(action)) {
            overlay.refresh();
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "没有悬浮窗权限，放弃显示");
                return START_NOT_STICKY;
            }
            if (!overlay.show()) {
                Log.w(TAG, "幕布显示失败");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlay != null) {
            overlay.hide();
            overlay = null;
        }
    }
}
