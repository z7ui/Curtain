package com.example.curtain;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * 所有可调项集中在这里，避免散落的硬编码 SharedPreferences 键。
 *
 * 键名对外公开，是因为幕布视图和主界面都要监听偏好变化来实时刷新
 * （幕布是悬浮窗，主界面不会走 onResume，只能靠监听器同步）。
 */
public final class CurtainPrefs {

    /** 亮度级别：1 = 最亮，5 = 最暗。 */
    public static final int LEVEL_MIN = 1;
    public static final int LEVEL_MAX = 5;

    /** 每档对应的边缘不透明度，中间区取 60%。 */
    public static final int[] EDGE_ALPHA = {60, 110, 160, 200, 235};
    public static final int CENTER_ALPHA_RATIO_PERCENT = 60;

    /** 自动进入纯黑（隐藏所有界面元素）的档位。 */
    public static final long[] TIMEOUT_MS = {
            15_000L, 30_000L, 60_000L, 120_000L,
            300_000L, 600_000L, 1_800_000L, Long.MAX_VALUE
    };
    public static final int[] TIMEOUT_LABEL_RES = {
            R.string.timeout_15s, R.string.timeout_30s, R.string.timeout_1m, R.string.timeout_2m,
            R.string.timeout_5m, R.string.timeout_10m, R.string.timeout_30m, R.string.timeout_never
    };

    public static final String FILE = "curtain";
    public static final String KEY_LEVEL = "level";
    public static final String KEY_TIMEOUT = "timeout";
    public static final String KEY_RUNNING = "running";

    private CurtainPrefs() {
    }

    public static SharedPreferences get(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int level(@NonNull Context c) {
        return clamp(get(c).getInt(KEY_LEVEL, LEVEL_MAX), LEVEL_MIN, LEVEL_MAX);
    }

    public static void setLevel(@NonNull Context c, int value) {
        get(c).edit().putInt(KEY_LEVEL, clamp(value, LEVEL_MIN, LEVEL_MAX)).apply();
    }

    public static int timeoutIndex(@NonNull Context c) {
        return clamp(get(c).getInt(KEY_TIMEOUT, 1), 0, TIMEOUT_MS.length - 1);
    }

    public static void setTimeoutIndex(@NonNull Context c, int value) {
        get(c).edit().putInt(KEY_TIMEOUT, clamp(value, 0, TIMEOUT_MS.length - 1)).apply();
    }

    /**
     * 幕布是否正在显示。由 CurtainOverlay 统一读写，
     * 两条后端（无障碍 / 普通悬浮窗）共用同一个状态。
     */
    public static boolean isRunning(@NonNull Context c) {
        return get(c).getBoolean(KEY_RUNNING, false);
    }

    public static void setRunning(@NonNull Context c, boolean value) {
        get(c).edit().putBoolean(KEY_RUNNING, value).apply();
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
