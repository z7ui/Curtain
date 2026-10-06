package com.example.curtain;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class CurtainTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile(CurtainService.isRunning);
    }

    @Override
    public void onClick() {
        super.onClick();

        if (CurtainService.isRunning) {
            // 关闭
            Intent it = new Intent(this, CurtainService.class);
            it.setAction(CurtainService.ACTION_HIDE);
            startService(it);
            updateTile(false);
            return;
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Intent perm = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            perm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(perm);
            return;
        }

        // 用透明 Activity 中转，从而自动收起控制中心
        Intent launcher = new Intent(this, CurtainLaunchActivity.class);
        launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, launcher,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(launcher);
        }
        updateTile(true);
    }

    private void updateTile(boolean active) {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.updateTile();
        }
    }
}