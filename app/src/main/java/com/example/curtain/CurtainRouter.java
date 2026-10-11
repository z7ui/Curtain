package com.example.curtain;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;

/**
 * 统一入口：决定这次由哪条后端来画幕布。
 *
 * 主界面里可以选三种模式（见 CurtainPrefs.MODE_*）：
 *
 *  1. 普通悬浮窗 · 抢焦点
 *     CurtainService + TYPE_APPLICATION_OVERLAY，窗口**不带** FLAG_NOT_FOCUSABLE。
 *     不需要无障碍；窗口能拿到焦点，系统才采纳它请求的沉浸模式 —— 状态栏能一起变黑。
 *     代价是后面的应用会失去窗口焦点，个别视频 / 游戏会自动暂停。
 *
 *  2. 普通悬浮窗 · 不抢焦点
 *     同上，但带 FLAG_NOT_FOCUSABLE。不打扰后台应用，代价是状态栏图标会浮在幕布上面。
 *
 *  3. 无障碍覆盖层
 *     CurtainAccessibilityService + TYPE_ACCESSIBILITY_OVERLAY。又藏状态栏又不抢焦点，
 *     三种里最干净；系统里没开无障碍时会**自动降级**成第 1 种（主界面会提示）。
 *
 * 主界面 / 磁贴 / 快捷方式 / 通知 全部走这里，不要再各自 startService。
 */
public final class CurtainRouter {

    public static final String ACTION_START = "com.example.curtain.action.START";
    public static final String ACTION_STOP = "com.example.curtain.action.STOP";
    public static final String ACTION_REFRESH = "com.example.curtain.action.REFRESH";

    /** 换了后端之后等这么久再起新的，给旧服务一点时间收干净。 */
    private static final long RESTART_DELAY_MS = 350L;

    private CurtainRouter() {
    }

    public static boolean isRunning(@NonNull Context c) {
        return CurtainPrefs.isRunning(c);
    }

    /** 无障碍服务是否已在系统设置里被打开。 */
    public static boolean accessibilityEnabled(@NonNull Context c) {
        AccessibilityManager manager =
                (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        String wanted = CurtainAccessibilityService.class.getName();
        for (AccessibilityServiceInfo info
                : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            String pkg = info.getResolveInfo().serviceInfo.packageName;
            String name = info.getResolveInfo().serviceInfo.name;
            if (c.getPackageName().equals(pkg) && wanted.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 无障碍这次到底用不用得上：既要在设置里选了它，也要系统里真的开着。 */
    public static boolean useAccessibility(@NonNull Context c) {
        return CurtainPrefs.mode(c) == CurtainPrefs.MODE_ACCESSIBILITY && accessibilityEnabled(c);
    }

    /** 实际生效的模式 —— 选了无障碍但没开，就降级成「抢焦点」。 */
    public static int effectiveMode(@NonNull Context c) {
        int mode = CurtainPrefs.mode(c);
        if (mode == CurtainPrefs.MODE_ACCESSIBILITY && !accessibilityEnabled(c)) {
            return CurtainPrefs.MODE_OVERLAY_FOCUS;
        }
        return mode;
    }

    /** 是否处于「选了无障碍但没开、已被降级」的状态，界面要提示。 */
    public static boolean isFallback(@NonNull Context c) {
        return CurtainPrefs.mode(c) == CurtainPrefs.MODE_ACCESSIBILITY && !accessibilityEnabled(c);
    }

    public static void start(@NonNull Context c) {
        if (useAccessibility(c)) {
            c.startService(new Intent(c, CurtainAccessibilityService.class)
                    .setAction(ACTION_START));
        } else {
            c.startForegroundService(new Intent(c, CurtainService.class)
                    .setAction(ACTION_START));
        }
    }

    public static void stop(@NonNull Context c) {
        if (accessibilityEnabled(c)) {
            // 无障碍服务是系统绑定的，不能停服务本身，只能让它把幕布收掉
            c.startService(new Intent(c, CurtainAccessibilityService.class)
                    .setAction(ACTION_STOP));
        }
        // 这条路用 stopService，避免用 startForegroundService 又把它拉起来
        try {
            c.stopService(new Intent(c, CurtainService.class));
        } catch (Throwable ignored) {
            // 本来就没在跑
        }
        // 立即反映到界面上，别等 onDestroy
        CurtainPrefs.setRunning(c, false);
    }

    public static void toggle(@NonNull Context c) {
        if (isRunning(c)) {
            stop(c);
        } else {
            start(c);
        }
    }

    /**
     * 模式切换：正在显示的话，先收掉旧后端，隔一小会儿再按新模式起。
     *
     * 为什么要等：stopService 是异步的。立刻 start 有可能落到同一个还没死掉的
     * 服务实例上，于是新参数被旧的窗口顶掉 —— 表现就是"切了模式没反应"。
     */
    public static void restartForMode(@NonNull Context c) {
        if (!isRunning(c)) {
            return;
        }
        final Context app = c.getApplicationContext();
        stop(app);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // 期间用户自己按了启动的话就别再插手
            if (!isRunning(app)) {
                start(app);
            }
        }, RESTART_DELAY_MS);
    }

    /** 等级 / 自动纯黑 / 模式 等设置变了之后，让正在显示的那条后端重新读一遍。 */
    public static void refresh(@NonNull Context c) {
        if (useAccessibility(c)) {
            c.startService(new Intent(c, CurtainAccessibilityService.class)
                    .setAction(ACTION_REFRESH));
        }
        if (CurtainService.isShowing(c)) {
            c.startForegroundService(new Intent(c, CurtainService.class)
                    .setAction(ACTION_REFRESH));
        }
    }
}
