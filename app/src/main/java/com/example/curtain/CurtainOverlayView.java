package com.example.curtain;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 幕布本体：整屏渐变黑 + 时间 / 电量 / 中央关闭钮。
 *
 * 等级和自动纯黑是通过监听 SharedPreferences 生效的 —— 关键点在于幕布是悬浮窗，
 * 主界面不会走 onResume，所以两条路径（幕布上的按钮、主界面的滑杆）都必须靠监听器同步。
 */
public class CurtainOverlayView extends FrameLayout {

    public interface OnExitListener {
        void onExit();
    }

    private static final long TICK_MS = 1000L;

    /**
     * 向下拖多远算关闭。
     * 图标默认在屏幕正中，0.25 就是把它拖到屏幕 3/4 的位置 —— 不用拖到底部。
     */
    private static final float UNLOCK_FRACTION = 0.25f;

    private View topBar;
    private View bottomBar;
    private View centerGroup;
    private TextView tvTime;
    private TextView tvDate;
    private TextView tvBattery;
    private TextView tvHint;
    private TextView btnLevel;
    private TextView btnTimeout;
    private ImageView ivIcon;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private OnExitListener exitListener;
    private SharedPreferences prefs;

    private int level;
    private int timeoutIndex;
    private boolean plainMode;
    private boolean dragging;
    private boolean armedToClose;
    private float dragStartY;
    private int extraEdgeInset;
    private long lastTouchTime;
    private long lastRenderedMinute = -1L;

    private boolean receiverRegistered;
    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;

    // 渐变 shader 只在尺寸或级别变化时重建，避免每次 onDraw 都新分配对象
    private LinearGradient gradientShader;
    private int shaderWidth;
    private int shaderHeight;
    private int shaderLevel;

    public CurtainOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        init(context);
    }

    public void setOnExitListener(OnExitListener listener) {
        this.exitListener = listener;
    }

    /**
     * 幕布窗口比屏幕大一圈（用来盖住圆角和挖孔），布局里的 padding 是相对这个
     * "超画"视图的，要把多出来的量加回去，内容才不会被推到屏幕外面。
     */
    public void setExtraEdgeInset(int px) {
        extraEdgeInset = px;
        applyEdgeInsets();
    }

    private void applyEdgeInsets() {
        // 四个方向都要把超画量补回去：视图左右也各多画了 extraEdgeInset，
        // 漏掉左右的话时间会被推到屏幕左边外面、电量 pill 会跑到右边外面。
        int side = getResources().getDimensionPixelSize(R.dimen.overlay_side_padding)
                + extraEdgeInset;
        int top = getResources().getDimensionPixelSize(R.dimen.overlay_top_padding)
                + extraEdgeInset;
        int bottom = getResources().getDimensionPixelSize(R.dimen.overlay_bottom_padding)
                + extraEdgeInset;
        topBar.setPadding(side, top, side, 0);
        bottomBar.setPadding(side, 0, side, bottom);
    }

    private float unlockDistance() {
        return getHeight() * UNLOCK_FRACTION;
    }

    /** 设置变化时由 CurtainOverlay 调用。 */
    public void refreshFromPrefs() {
        applyLevel();
        applyTimeoutLabel();
    }

    /** 中央圆钮显示的应用图标；传 null 则回退到内置锁图标。 */
    public void setAppIcon(Drawable icon, String label) {
        if (ivIcon == null) {
            return;
        }
        if (icon == null) {
            ivIcon.setImageResource(R.drawable.ic_lock);
            ivIcon.setPadding(dp(18), dp(18), dp(18), dp(18));
        } else {
            ivIcon.setImageDrawable(icon);
            ivIcon.setPadding(0, 0, 0, 0);
        }
        ivIcon.setContentDescription(label);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.curtain_overlay, this, true);

        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        centerGroup = findViewById(R.id.center_group);
        tvTime = findViewById(R.id.tv_time);
        tvDate = findViewById(R.id.tv_date);
        tvBattery = findViewById(R.id.tv_battery);
        tvHint = findViewById(R.id.tv_hint);
        btnLevel = findViewById(R.id.btn_level);
        btnTimeout = findViewById(R.id.btn_timeout);
        ivIcon = findViewById(R.id.iv_icon);

        prefs = CurtainPrefs.get(context);
        level = CurtainPrefs.level(context);
        timeoutIndex = CurtainPrefs.timeoutIndex(context);

        rebuildFormats(context);
        applyLevel();
        applyTimeoutLabel();
        updateTime(true);
        updateBattery(context);

        lastTouchTime = System.currentTimeMillis();
        handler.post(tickRunnable);

        // Android 14 起动态注册必须显式声明导出标志，否则抛 SecurityException
        ContextCompat.registerReceiver(context, batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;

        // 幕布上的按钮和主界面的滑杆都改同一份偏好，靠这个监听器实时同步
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        btnLevel.setOnClickListener(v -> CurtainPrefs.setLevel(context,
                level % CurtainPrefs.LEVEL_MAX + 1));
        btnTimeout.setOnClickListener(v -> CurtainPrefs.setTimeoutIndex(context,
                (timeoutIndex + 1) % CurtainPrefs.TIMEOUT_MS.length));

        applyEdgeInsets();
    }

    private void rebuildFormats(Context context) {
        Locale locale = Locale.getDefault();
        // 尊重系统的 12/24 小时制
        String timeSkeleton = DateFormat.is24HourFormat(context) ? "Hm" : "hm";
        timeFormat = new SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, timeSkeleton), locale);
        dateFormat = new SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, "MMMdEEE"), locale);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (CurtainPrefs.KEY_LEVEL.equals(key)) {
                    applyLevel();
                } else if (CurtainPrefs.KEY_TIMEOUT.equals(key)) {
                    applyTimeoutLabel();
                }
            };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(context);
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            updateTime(false);
            checkAutoPlain();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** force=true 时无条件刷新；否则只在分钟变化时改文本，省掉每秒一次布局。 */
    private void updateTime(boolean force) {
        long now = System.currentTimeMillis();
        long minute = now / 60_000L;
        if (!force && minute == lastRenderedMinute) {
            return;
        }
        lastRenderedMinute = minute;
        Date date = new Date(now);
        tvTime.setText(timeFormat.format(date));
        tvDate.setText(dateFormat.format(date));
    }

    private void updateBattery(Context context) {
        Intent intent = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) {
            return;
        }
        int batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (batteryLevel < 0 || scale <= 0) {
            return;
        }
        tvBattery.setText(getContext().getString(
                R.string.battery_format, Math.round(batteryLevel * 100f / scale)));
    }

    private void applyLevel() {
        level = CurtainPrefs.level(getContext());
        btnLevel.setText(getContext().getString(R.string.level_format, level));
        invalidate();
    }

    private void applyTimeoutLabel() {
        timeoutIndex = CurtainPrefs.timeoutIndex(getContext());
        btnTimeout.setText(CurtainPrefs.TIMEOUT_LABEL_RES[timeoutIndex]);
    }

    /** 到点了就把界面元素藏起来，只留纯黑。 */
    private void checkAutoPlain() {
        if (plainMode) {
            return;
        }
        long timeout = CurtainPrefs.TIMEOUT_MS[timeoutIndex];
        if (timeout == Long.MAX_VALUE) {
            return;
        }
        if (System.currentTimeMillis() - lastTouchTime >= timeout) {
            setPlainMode(true);
        }
    }

    private void setPlainMode(boolean value) {
        if (plainMode == value) {
            return;
        }
        plainMode = value;
        int visibility = value ? GONE : VISIBLE;
        topBar.setVisibility(visibility);
        bottomBar.setVisibility(visibility);
        centerGroup.setVisibility(visibility);
        if (!value) {
            lastTouchTime = System.currentTimeMillis();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        if (plainMode) {
            // 纯黑：OLED 像素完全熄灭
            canvas.drawColor(Color.BLACK);
            return;
        }

        paint.setShader(gradientFor(w, h));
        canvas.drawRect(0, 0, w, h, paint);
    }

    /** 横屏时左右渐变，竖屏时上下渐变，始终让屏幕中间更容易看清。 */
    private LinearGradient gradientFor(int w, int h) {
        if (gradientShader != null && w == shaderWidth && h == shaderHeight
                && level == shaderLevel) {
            return gradientShader;
        }
        int edgeAlpha = CurtainPrefs.EDGE_ALPHA[level - CurtainPrefs.LEVEL_MIN];
        int centerAlpha = edgeAlpha * CurtainPrefs.CENTER_ALPHA_RATIO_PERCENT / 100;
        int edgeColor = Color.argb(edgeAlpha, 0, 0, 0);
        int centerColor = Color.argb(centerAlpha, 0, 0, 0);
        float[] stops = {0f, 0.35f, 0.65f, 1f};
        int[] colors = {edgeColor, centerColor, centerColor, edgeColor};

        gradientShader = w > h
                ? new LinearGradient(0, 0, w, 0, colors, stops, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, 0, h, colors, stops, Shader.TileMode.CLAMP);
        shaderWidth = w;
        shaderHeight = h;
        shaderLevel = level;
        return gradientShader;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyGestureExclusion(w, h);
    }

    /**
     * 只排除左右边缘，挡住误触返回；底部留给系统上滑返回桌面，
     * 这样用户无论如何都能退出去，不会被幕布困住。
     */
    private void applyGestureExclusion(int w, int h) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || w <= 0 || h <= 0) {
            return;
        }
        int maxEdgePx = (int) (200 * getResources().getDisplayMetrics().density);
        int side = Math.min((int) (w * 0.12f), maxEdgePx / 4);
        if (side <= 0) {
            return;
        }
        List<Rect> rects = new ArrayList<>(2);
        rects.add(new Rect(0, 0, side, h));
        rects.add(new Rect(w - side, 0, w, h));
        try {
            setSystemGestureExclusionRects(rects);
        } catch (Throwable ignored) {
            // 某些 ROM 上 overlay 窗口不支持，忽略即可
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        lastTouchTime = System.currentTimeMillis();

        if (plainMode) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                setPlainMode(false);
            }
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 整块屏幕都能起手，不用刚好按在圆钮上
                dragging = true;
                armedToClose = false;
                dragStartY = event.getY();
                centerGroup.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float dy = Math.max(0f, event.getY() - dragStartY);
                    float limit = unlockDistance();
                    // 图标最多只跟到"解锁线"，让用户一眼看到拖这么远就够了
                    centerGroup.setTranslationY(Math.min(dy, limit));
                    setArmed(dy >= limit);
                    if (!armedToClose) {
                        tvHint.setAlpha(Math.max(0.3f, 1f - dy / (limit * 2f)));
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    boolean close = armedToClose;
                    centerGroup.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (close) {
                        if (exitListener != null) {
                            exitListener.onExit();
                        }
                    } else {
                        armedToClose = false;
                        tvHint.setText(R.string.overlay_hint);
                        centerGroup.animate().translationY(0).setDuration(200).start();
                        tvHint.animate().alpha(1f).setDuration(180).start();
                    }
                }
                break;
            default:
                break;
        }
        return true;
    }

    /** 拖过解锁线时给一次明显的反馈，免得用户继续往下拖。 */
    private void setArmed(boolean armed) {
        if (armedToClose == armed) {
            return;
        }
        armedToClose = armed;
        tvHint.setText(armed ? R.string.overlay_release_to_close : R.string.overlay_hint);
        tvHint.setAlpha(1f);
        centerGroup.animate()
                .scaleX(armed ? 1.06f : 0.92f)
                .scaleY(armed ? 1.06f : 0.92f)
                .setDuration(110)
                .start();
    }

    private boolean isInside(float x, float y, View view) {
        if (view == null) {
            return false;
        }
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        int[] self = new int[2];
        getLocationOnScreen(self);
        float lx = loc[0] - self[0];
        float ly = loc[1] - self[1];
        // 判定范围放宽一些，手指没那么准
        float padding = dp(20);
        return x >= lx - padding && x <= lx + view.getWidth() + padding
                && y >= ly - padding && y <= ly + view.getHeight() + padding;
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                getContext().unregisterReceiver(batteryReceiver);
            } catch (Throwable ignored) {
                // 已经注销过了
            }
            receiverRegistered = false;
        }
        if (prefs != null) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            } catch (Throwable ignored) {
                // 已经注销过了
            }
        }
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}