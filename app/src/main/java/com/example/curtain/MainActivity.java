package com.example.curtain;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 主界面。
 *
 * 一个关键点：幕布是**悬浮窗**，盖在屏幕上时本 Activity 仍然处于 resumed 状态，
 * 用幕布上的手势关掉它之后系统不会回调 onResume。所以「启动 / 停止」按钮必须靠
 * SharedPreferences 监听器同步，否则按钮文字会和真实状态脱节
 * —— 表现就是「关了还显示停止，点它反而又启动了」。
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvOverlayState;
    private TextView tvAccessibilityState;
    private TextView tvUsageState;
    private TextView tvNotifState;
    private TextView tvLevelValue;
    private TextView tvTimeoutValue;
    private TextView tvMode;
    private TextView tvHint;

    private Button btnToggle;
    private SeekBar sbLevel;
    private SeekBar sbTimeout;

    private SharedPreferences prefs;

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> refreshState());

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (CurtainPrefs.KEY_RUNNING.equals(key)) {
                    refreshToggleButton();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvOverlayState = findViewById(R.id.tv_overlay_state);
        tvAccessibilityState = findViewById(R.id.tv_accessibility_state);
        tvUsageState = findViewById(R.id.tv_usage_state);
        tvNotifState = findViewById(R.id.tv_notif_state);
        tvLevelValue = findViewById(R.id.tv_level_value);
        tvTimeoutValue = findViewById(R.id.tv_timeout_value);
        tvMode = findViewById(R.id.tv_mode);
        tvHint = findViewById(R.id.tv_hint);
        btnToggle = findViewById(R.id.btn_toggle);
        sbLevel = findViewById(R.id.sb_level);
        sbTimeout = findViewById(R.id.sb_timeout);

        findViewById(R.id.btn_overlay_perm).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.btn_accessibility_perm).setOnClickListener(v ->
                startActivitySafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_usage_perm).setOnClickListener(v ->
                startActivitySafely(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        findViewById(R.id.btn_notif_perm).setOnClickListener(v -> requestNotification());

        applyStatusBarAppearance();
        setupSliders();
        setupToggle();

        prefs = CurtainPrefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    @Override
    protected void onDestroy() {
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        super.onDestroy();
    }

    /** 边到边显示时，状态栏图标要根据深浅色切换明暗，否则会看不见。 */
    private void applyStatusBarAppearance() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
    }

    private void setupSliders() {
        sbLevel.setMax(CurtainPrefs.LEVEL_MAX - CurtainPrefs.LEVEL_MIN);
        sbLevel.setProgress(CurtainPrefs.level(this) - CurtainPrefs.LEVEL_MIN);
        tvLevelValue.setText(getString(R.string.level_format, CurtainPrefs.level(this)));
        sbLevel.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int level = progress + CurtainPrefs.LEVEL_MIN;
                tvLevelValue.setText(getString(R.string.level_format, level));
                if (fromUser) {
                    // 写进偏好即可，幕布那边有监听器会实时刷新
                    CurtainPrefs.setLevel(MainActivity.this, level);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 兜底：万一监听器没生效，主动让幕布重读一次
                CurtainRouter.refresh(MainActivity.this);
            }
        });

        sbTimeout.setMax(CurtainPrefs.TIMEOUT_MS.length - 1);
        sbTimeout.setProgress(CurtainPrefs.timeoutIndex(this));
        tvTimeoutValue.setText(CurtainPrefs.TIMEOUT_LABEL_RES[CurtainPrefs.timeoutIndex(this)]);
        sbTimeout.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTimeoutValue.setText(CurtainPrefs.TIMEOUT_LABEL_RES[progress]);
                if (fromUser) {
                    CurtainPrefs.setTimeoutIndex(MainActivity.this, progress);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                CurtainRouter.refresh(MainActivity.this);
            }
        });
    }

    private void setupToggle() {
        btnToggle.setOnClickListener(v -> {
            // 用和按钮文字同一个来源判断，避免"显示停止、实际执行启动"
            if (CurtainRouter.isRunning(this)) {
                CurtainRouter.stop(this);
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_SHORT).show();
                    openOverlaySettings();
                    return;
                }
                CurtainRouter.start(this);
            }
            refreshToggleButton();
            // 服务是异步的，稍后再对一次真实状态
            btnToggle.postDelayed(this::refreshState, 300);
        });
    }

    private void refreshToggleButton() {
        boolean running = CurtainRouter.isRunning(this);
        btnToggle.setText(running ? R.string.btn_stop : R.string.btn_start);
        tvHint.setText(running ? R.string.hint_running : R.string.hint_idle);
    }

    private void refreshState() {
        boolean overlay = Settings.canDrawOverlays(this);
        tvOverlayState.setText(overlay ? R.string.state_granted : R.string.state_missing);
        tvOverlayState.setTextColor(color(overlay ? R.color.state_ok : R.color.state_warn));

        boolean accessibility = CurtainRouter.accessibilityEnabled(this);
        tvAccessibilityState.setText(accessibility ? R.string.state_enabled : R.string.state_disabled);
        tvAccessibilityState.setTextColor(
                color(accessibility ? R.color.state_ok : R.color.state_warn));

        boolean usage = ForegroundApp.hasUsageAccess(this);
        tvUsageState.setText(usage ? R.string.state_granted : R.string.state_missing);
        tvUsageState.setTextColor(color(usage ? R.color.state_ok : R.color.state_muted));

        boolean notif = notifGranted();
        tvNotifState.setText(notif ? R.string.state_granted : R.string.state_missing);
        tvNotifState.setTextColor(color(notif ? R.color.state_ok : R.color.state_muted));

        tvMode.setText(accessibility ? R.string.mode_accessibility : R.string.mode_overlay);
        refreshToggleButton();
    }

    private boolean notifGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            Toast.makeText(this, R.string.notif_not_needed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openOverlaySettings() {
        startActivitySafely(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void startActivitySafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.cannot_open_settings), Toast.LENGTH_SHORT).show();
        }
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}