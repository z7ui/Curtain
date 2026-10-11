package com.example.curtain;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Process;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * 取"幕布下面那个应用"的名字和图标，显示在中间圆钮上。
 *
 * 需要「使用情况访问」权限；没授权或查询失败都返回 null，调用方回退到内置锁图标。
 * queryUsageStats 是跨进程调用，必须在后台线程执行。
 */
public final class ForegroundApp {

    private ForegroundApp() {
    }

    public static final class Info {
        public final String packageName;
        public final String label;
        public final Drawable icon;

        Info(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    public static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager aom =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) {
                return false;
            }
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只能在后台线程调用。 */
    @Nullable
    public static Info current(Context context) {
        if (!hasUsageAccess(context)) {
            return null;
        }
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            List<UsageStats> stats =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 6 * 3600_000L, now);
            if (stats == null || stats.isEmpty()) {
                return null;
            }

            UsageStats best = null;
            String self = context.getPackageName();
            for (UsageStats stat : stats) {
                String pkg = stat.getPackageName();
                // 跳过自己和系统界面，否则永远显示的是自己
                if (self.equals(pkg) || "android".equals(pkg)
                        || "com.android.systemui".equals(pkg)) {
                    continue;
                }
                if (best == null || stat.getLastTimeUsed() > best.getLastTimeUsed()) {
                    best = stat;
                }
            }
            if (best == null) {
                return null;
            }

            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(best.getPackageName(), 0);
            String label = pm.getApplicationLabel(ai).toString();
            Drawable icon = pm.getApplicationIcon(ai);
            return new Info(best.getPackageName(), label, icon);
        } catch (Throwable t) {
            return null;
        }
    }
}
