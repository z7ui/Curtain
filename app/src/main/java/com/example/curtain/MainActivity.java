package com.example.curtain;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String[] TIMEOUT_LABELS = {"15 秒", "30 秒", "1 分钟", "2 分钟",
            "5 分钟", "10 分钟", "30 分钟", "从不"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 悬浮窗权限
        findViewById(R.id.tv_perm).setOnClickListener(v -> {
            Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(it);
        });

        // 使用情况访问权限（用于获取前台应用图标）
        findViewById(R.id.tv_usage_perm).setOnClickListener(v -> {
            Intent it = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(it);
        });

        findViewById(R.id.btn_check).setOnClickListener(v -> {
            boolean overlay = Settings.canDrawOverlays(this);
            boolean usage = hasUsageAccess();
            Toast.makeText(this,
                    "悬浮窗：" + (overlay ? "已授予" : "未授予")
                            + "\n使用情况访问：" + (usage ? "已授予" : "未授予"),
                    Toast.LENGTH_LONG).show();
        });

        SeekBar sbLevel = findViewById(R.id.sb_level);
        TextView tvLevel = findViewById(R.id.tv_level_value);
        int savedLevel = getSharedPreferences("curtain", MODE_PRIVATE).getInt("level", 4);
        sbLevel.setMax(4);
        sbLevel.setProgress(savedLevel);
        tvLevel.setText("级别 " + savedLevel);
        sbLevel.setOnSeekBarChangeListener(new SimpleListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvLevel.setText("级别 " + p);
                getSharedPreferences("curtain", MODE_PRIVATE)
                        .edit().putInt("level", p).apply();
            }
        });

        SeekBar sbTimeout = findViewById(R.id.sb_timeout);
        TextView tvTimeout = findViewById(R.id.tv_timeout_value);
        int savedTimeout = getSharedPreferences("curtain", MODE_PRIVATE).getInt("timeout", 0);
        sbTimeout.setMax(7);
        sbTimeout.setProgress(savedTimeout);
        tvTimeout.setText(TIMEOUT_LABELS[savedTimeout]);
        sbTimeout.setOnSeekBarChangeListener(new SimpleListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvTimeout.setText(TIMEOUT_LABELS[p]);
                getSharedPreferences("curtain", MODE_PRIVATE)
                        .edit().putInt("timeout", p).apply();
            }
        });
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static abstract class SimpleListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}