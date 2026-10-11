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
 * ## 状态栏：重新拆过 io.japp.blackscreen 之后，结论改了
 *
 * Black Screen v2.0.7 **没有无障碍服务**（manifest 里只有通知监听、磁贴、JobService），
 * 权限也只有 SYSTEM_ALERT_WINDOW，但它的全屏黑层同样是普通悬浮窗，
 * 却能把状态栏一起弄黑。它唯一的动作是视图上的一句：
 *
 *     o21.setSystemUiVisibility(4102);
 *     // 4102 = IMMERSIVE_STICKY(4096) | HIDE_NAVIGATION(2) | FULLSCREEN(4)
 *
 * 所以 SDK 那句"悬浮窗在状态栏之下"说的是**层级**（这点没错，我们确实画不到它上面），
 * 但"系统会忽略悬浮窗的系统 UI 可见性变更"并不成立 —— 能不能藏掉系统栏，
 * 看的是这个窗口有没有资格**代表屏幕**。对照它的窗口参数：
 *
 *     width/height = MATCH_PARENT            → 全屏
 *     format       = PixelFormat.RGBA_8888   → 不透明
 *     flags        = LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS
 *                    （注意 **没有** FLAG_NOT_FOCUSABLE）→ 它就是焦点窗口
 *
 * 三条都满足，系统才采纳它的 FULLSCREEN 去藏系统栏；而它自己是不透明全屏的，
 * 藏掉之后状态栏那一条照样是黑的，于是看起来"整屏全黑"。
 *
 * 我们原来照抄 FakeStandby 的参数，里面带着 FLAG_NOT_FOCUSABLE，
 * 又因为幕布是渐变半透明而必须用 PixelFormat.TRANSLUCENT ——
 * 这两条正好让它失去"代表屏幕"的资格，沉浸请求就被无视了。
 * 于是普通悬浮窗默认**不带 FLAG_NOT_FOCUSABLE**（主界面里的「普通悬浮窗 · 抢焦点」）；
 * 想不打扰后台应用就切「不抢焦点」，代价是状态栏又会浮在幕布上面。
 * 无障碍覆盖层天生被信任，继续沿用 FakeStandby 的原参数（见 [buildParams]）。
 *
 * 代价：幕布期间后面的 app 会失去窗口焦点，个别视频/游戏会因此自动暂停。
 * 还没验证：如果只去掉 NOT_FOCUSABLE 仍然藏不住状态栏，说明"不透明"也是硬条件，
 * 那就只能在"渐变半透明幕布"和"藏掉状态栏"之间二选一了。
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

    /** 当前这个窗口建的时候到底抢没抢焦点，用来判断模式变了要不要重建。 */
    private boolean focusNeeded;

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
        focusNeeded = wantFocus();
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
        detach();
        CurtainPrefs.setRunning(context, false);
    }

    /**
     * 模式变了（比如从「抢焦点」切到「不抢焦点」）就按新参数把窗口重建一次。
     * 只在正在显示时有意义；没显示的话下次 show() 自然会用新参数。
     */
    public void rebuildIfNeeded() {
        if (view == null || focusNeeded == wantFocus()) {
            return;
        }
        detach();
        show();
    }

    private void detach() {
        CurtainOverlayView old = view;
        view = null;
        if (old == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(old);
        } catch (Throwable ignored) {
            // 可能已经被移除了
        }
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

    /**
     * 这个窗口要不要抢焦点。
     *
     *  - 无障碍覆盖层：不要。它天生被信任，不抢焦点也会被采纳（FakeStandby 原参数）。
     *  - 普通悬浮窗 + 「抢焦点」模式：要。系统才会理会它请求的沉浸模式。
     *  - 普通悬浮窗 + 「不抢焦点」模式：不要。放弃状态栏，换回不打扰后台应用。
     */
    private boolean wantFocus() {
        return windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                && CurtainRouter.effectiveMode(context) == CurtainPrefs.MODE_OVERLAY_FOCUS;
    }

    private WindowManager.LayoutParams buildParams() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int oversize = oversizePx();

        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        // 无障碍覆盖层天生受信任，不抢焦点系统也会采纳它的沉浸请求（FakeStandby 就这么用）；
        // 普通悬浮窗必须反过来 —— 不带 NOT_FOCUSABLE 才能成为焦点窗口，详见类注释。
        // 所以只有「要抢焦点」这一种情况才不加这个 flag。
        if (!wantFocus()) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }

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
     *
     * 系统认不认这一句，取决于窗口有没有"代表屏幕"的资格（全屏 / 不透明 / 能拿焦点），
     * 而不是 windowType 本身 —— 没资格时设了也是白设，详见类注释。
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