package com.example.curtain;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class CurtainLaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 启动幕布服务
        Intent it = new Intent(this, CurtainService.class);
        it.setAction(CurtainService.ACTION_SHOW);
        startForegroundService(it);

        // 立刻退出自己，控制中心在这之前已经收起
        finish();
        overridePendingTransition(0, 0);
    }
}