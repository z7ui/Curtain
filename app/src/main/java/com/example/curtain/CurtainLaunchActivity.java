package com.example.curtain;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * 磁贴 -> 透明 Activity -> 后端服务。
 *
 * 绕这一圈的目的：控制中心必须先把面板收起来，否则幕布会盖在控制中心上面。
 */
public class CurtainLaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            CurtainRouter.start(this);
        }

        finish();
        overridePendingTransition(0, 0);
    }
}
