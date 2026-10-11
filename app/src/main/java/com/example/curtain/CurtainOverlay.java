package com.example.curtain;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * 「把幕布盖到屏幕上」这件事本身，两条后端共用。
 *
 * 关键区别就在 windowType：
 *
 *  - [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] (2038)
 *      普通前台服务就能加。SDK 文档写得很明确：它在所有 Activity 之上，
 *      但**在状态栏、输入法这些关键系统窗口之下**，而且系统会忽略它请求的
 *      系统 UI 可见性变更。所以状态栏那排图标一定浮在幕布上面。
 *
 *  - [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] (2032)
 *      只有无障碍服务能加，属于「受信任覆盖层」，可以真正改变系统 UI 可见性，
 *      状态栏和导航栏能一起藏掉。
 *
 * 另外两点是从 FakeStandby 抄的细节：
 *  - flags 里同时给上 FLAG_FULLSCREEN / FLAG_TRANSLUCENT_STATUS / FLAG_TRANSLUCENT_NAVIGATION
 *  - 视图比屏幕多画一圈再往左上偏移，把圆角和挖孔一起盖住
 */
public final class CurtainOverlay {

    private static final String TAG = "CurtainOverlay";
    private static final int OVERSIZE_DP = 48;

    public interface OnExitListener {
        void onExit();
    }

    private final Context context;
    private final int windowType;
    private final WindowManager windowManager;
    private final OnExitListener exitListener;

    private CurtainOverlayView view;

    public CurtainOverlay(@NonNull Context context, int windowType,
                          @NonNull OnExitListener exitListener) {
        this.context = context.getApplicationContext();
        this.windowType = windowType;
        this.windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.exitListener = exitListener;
    }

    public boolean isShowing() {
        return view != null;
    }

    /** 返回是否成功显示。失败不抛异常，调用方自己决定要不要收尾。 */
    public boolean show() {
        if (view != null) {
            return true;
        }
        if (windowManager == null) {
            return false;
        }

        CurtainOverlayView overlayView = new CurtainOverlayView(context);
        // 视图比屏幕大一圈，padding 得补回来，否则内容会贴边
        overlayView.setExtraEdgeInset(oversizePx());
        overlayView.setOnExitListener(() -> exitListener.onExit());
        try {
            windowManager.addView(overlayView, buildParams());
        } catch (Throwable t) {
            Log.e(TAG, "addView 失败（windowType=" + windowType + "）", t);
            return false;
        }

        view = overlayView;
        applySystemUi(overlayView);
        CurtainPrefs.setRunning(context, true);
        return true;
    }

    public void hide() {
        if (view != null) {
            try {
                windowManager.removeView(view);
            } catch (Throwable ignored) {
                // 可能已经被移除了
            }
            view = null;
        }
        CurtainPrefs.setRunning(context, false);
    }

    /** 偏好变了之后让幕布重新读一遍。 */
    public void refresh() {
        if (view != null) {
            view.refreshFromPrefs();
        }
    }

    private int oversizePx() {
        return (int) (OVERSIZE_DP * context.getResources().getDisplayMetrics().density);
    }

    private WindowManager.LayoutParams buildParams() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int oversize = oversizePx();

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dm.widthPixels + 2 * oversize,
                dm.heightPixels + 2 * oversize,
                windowType,
                flags,
                // 幕布是渐变半透明的，必须用带 alpha 的格式
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = -oversize;
        params.y = -oversize;
        params.alpha = 1f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
        }
        return params;
    }

    /**
     * 请求隐藏系统栏。
     * 对普通悬浮窗系统会直接忽略，对无障碍覆盖层才真正生效 —— 这就是双轨制的意义。
     */
    @SuppressWarnings("deprecation")
    private void applySystemUi(View target) {
        target.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = target.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }
}