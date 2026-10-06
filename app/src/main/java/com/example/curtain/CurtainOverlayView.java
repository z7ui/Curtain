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
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CurtainOverlayView extends FrameLayout {

    public interface OnExitListener { void onExit(); }

    // 级别 0-4 对应边缘的 alpha，差别拉大
    private static final int[] EDGE_ALPHA = {110, 150, 190, 230, 255};

    private static final long[] TIMEOUTS_MS = {15_000, 30_000, 60_000, 120_000,
            300_000, 600_000, 1800_000, Long.MAX_VALUE};
    private static final String[] TIMEOUT_LABELS = {"15 秒", "30 秒", "1 分钟", "2 分钟",
            "5 分钟", "10 分钟", "30 分钟", "从不"};

    private View topBar, quickStatus, lockContainer;
    private TextView tvTime, tvDate, tvBattery, tvLevel, tvTimeout, tvHint;
    private ImageView ivLock;

    private int level;
    private int timeoutIndex;
    private long lastTouchTime;
    private boolean dimmed;
    private boolean dragging;
    private float lockStartY;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OnExitListener exitListener;
    private SharedPreferences prefs;

    public CurtainOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        init(context);
    }

    public void setOnExitListener(OnExitListener l) { this.exitListener = l; }

    public void setAppIcon(Drawable icon) {
        if (icon == null || ivLock == null) return;
        ivLock.setImageDrawable(icon);
        ivLock.setBackground(null);
        ivLock.setPadding(0, 0, 0, 0);
        ivLock.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.curtain_overlay, this, true);

        topBar        = findViewById(R.id.top_bar);
        quickStatus   = findViewById(R.id.quick_status);
        lockContainer = findViewById(R.id.lock_container);
        tvTime        = findViewById(R.id.tv_time);
        tvDate        = findViewById(R.id.tv_date);
        tvBattery     = findViewById(R.id.tv_battery);
        tvLevel       = findViewById(R.id.tv_level);
        tvTimeout     = findViewById(R.id.tv_timeout);
        tvHint        = findViewById(R.id.tv_hint);
        ivLock        = findViewById(R.id.iv_lock);

        prefs = context.getSharedPreferences("curtain", Context.MODE_PRIVATE);
        level = prefs.getInt("level", 4);
        timeoutIndex = prefs.getInt("timeout", 0);

        applyLevel();
        applyTimeoutLabel();
        updateTime();
        updateBattery(context);

        lastTouchTime = System.currentTimeMillis();
        handler.post(tickRunnable);

        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(batteryReceiver, f);

        // 监听 SharedPreferences 变化，从 MainActivity 改也即时生效
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        tvLevel.setOnClickListener(v -> {
            int next = (level + 1) % EDGE_ALPHA.length;
            // 写进 prefs，监听器会自动同步 level 并刷新
            prefs.edit().putInt("level", next).apply();
            lastTouchTime = System.currentTimeMillis();
        });
        tvTimeout.setOnClickListener(v -> {
            int next = (timeoutIndex + 1) % TIMEOUTS_MS.length;
            prefs.edit().putInt("timeout", next).apply();
            lastTouchTime = System.currentTimeMillis();
        });
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> {
                if ("level".equals(key)) {
                    level = sp.getInt("level", 4);
                    applyLevel();
                } else if ("timeout".equals(key)) {
                    timeoutIndex = sp.getInt("timeout", 0);
                    applyTimeoutLabel();
                }
            };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateBattery(c); }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            updateTime();
            checkDim();
            handler.postDelayed(this, 1000);
        }
    };

    private void updateTime() {
        Date now = new Date();
        tvTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        tvDate.setText(new SimpleDateFormat("M月d日，EEE", Locale.getDefault()).format(now));
    }

    private void updateBattery(Context ctx) {
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent it = ctx.registerReceiver(null, f);
        if (it == null) return;
        int lv = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = lv * 100 / sc;
        tvBattery.setText("电池 | " + pct + "%");
    }

    private void applyLevel() {
        tvLevel.setText("级别 " + (level + 1)); // 显示 1~5
        invalidate();
    }

    private void applyTimeoutLabel() {
        tvTimeout.setText(TIMEOUT_LABELS[timeoutIndex]);
    }

    private void checkDim() {
        if (dimmed) return;
        long timeout = TIMEOUTS_MS[timeoutIndex];
        if (timeout == Long.MAX_VALUE) return;
        if (System.currentTimeMillis() - lastTouchTime >= timeout) {
            enterDim();
        }
    }

    private void enterDim() {
        dimmed = true;
        topBar.setVisibility(GONE);
        quickStatus.setVisibility(GONE);
        lockContainer.setVisibility(GONE);
        invalidate();
    }

    private void exitDim() {
        if (!dimmed) return;
        dimmed = false;
        topBar.setVisibility(VISIBLE);
        quickStatus.setVisibility(VISIBLE);
        lockContainer.setVisibility(VISIBLE);
        lastTouchTime = System.currentTimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        if (dimmed) {
            canvas.drawColor(Color.BLACK);
            return;
        }

        int edgeAlpha = EDGE_ALPHA[level];
        int centerAlpha = Math.max(0, (int) (edgeAlpha * 0.60f));

        int edgeColor   = Color.argb(edgeAlpha, 0, 0, 0);
        int centerColor = Color.argb(centerAlpha, 0, 0, 0);

        LinearGradient grad;
        if (w > h) {
            // 横屏：左右边缘黑、中心淡
            grad = new LinearGradient(0, 0, w, 0,
                    new int[]{edgeColor, centerColor, centerColor, edgeColor},
                    new float[]{0f, 0.35f, 0.65f, 1f},
                    Shader.TileMode.CLAMP);
        } else {
            // 竖屏：上下边缘黑、中心淡
            grad = new LinearGradient(0, 0, 0, h,
                    new int[]{edgeColor, centerColor, centerColor, edgeColor},
                    new float[]{0f, 0.35f, 0.65f, 1f},
                    Shader.TileMode.CLAMP);
        }
        paint.setShader(grad);
        canvas.drawRect(0, 0, w, h, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        lastTouchTime = System.currentTimeMillis();

        if (dimmed) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) exitDim();
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (isInside(event.getX(), event.getY(), lockContainer)) {
                    dragging = true;
                    lockStartY = event.getY();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float dy = Math.max(0, event.getY() - lockStartY);
                    ivLock.setTranslationY(dy);
                    tvHint.setAlpha(Math.max(0f, 1f - dy / 400f));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    float dy = event.getY() - lockStartY;
                    if (dy > getHeight() * 0.30f) {
                        if (exitListener != null) exitListener.onExit();
                    } else {
                        ivLock.animate().translationY(0).setDuration(200).start();
                        tvHint.animate().alpha(1f).setDuration(200).start();
                    }
                    dragging = false;
                }
                break;
        }
        return true;
    }

    private boolean isInside(float x, float y, View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int[] self = new int[2];
        getLocationOnScreen(self);
        float lx = loc[0] - self[0];
        float ly = loc[1] - self[1];
        return x >= lx && x <= lx + v.getWidth()
                && y >= ly && y <= ly + v.getHeight();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        try { getContext().unregisterReceiver(batteryReceiver); } catch (Throwable ignored) {}
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener); } catch (Throwable ignored) {}
        super.onDetachedFromWindow();
    }
}