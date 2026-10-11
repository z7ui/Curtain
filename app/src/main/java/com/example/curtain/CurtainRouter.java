package com.example.curtain;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;

/**
 * 统一入口：决定这次由哪条后端来画幕布。
 *
 *  1. 无障碍服务已开启 → CurtainAccessibilityService（TYPE_ACCESSIBILITY_OVERLAY）
 *     能把状态栏和导航栏一起藏掉
 *  2. 否则             → CurtainService（前台服务 + TYPE_APPLICATION_OVERLAY）
 *     不需要无障碍，但状态栏图标会浮在幕布上面
 *
 * 主界面 / 磁贴 / 快捷方式 / 通知 全部走这里，不要再各自 startService。
 */
public final class CurtainRouter {

    public static final String ACTION_START = "com.example.curtain.action.START";
    public static final String ACTION_STOP = "com.example.curtain.action.STOP";
    public static final String ACTION_REFRESH = "com.example.curtain.action.REFRESH";

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

    public static void start(@NonNull Context c) {
        if (accessibilityEnabled(c)) {
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

    /** 等级 / 自动纯黑 等设置变了之后，让正在显示的那条后端重新读一遍。 */
    public static void refresh(@NonNull Context c) {
        if (accessibilityEnabled(c)) {
            c.startService(new Intent(c, CurtainAccessibilityService.class)
                    .setAction(ACTION_REFRESH));
        }
        if (CurtainService.isShowing(c)) {
            c.startForegroundService(new Intent(c, CurtainService.class)
                    .setAction(ACTION_REFRESH));
        }
    }
}
