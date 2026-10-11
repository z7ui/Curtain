package com.example.curtain;

import android.annotation.SuppressLint;
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
        updateTile();
    }

    // 34 以下只有 Intent 版本可用，这里是有意的兼容分支
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Override
    public void onClick() {
        super.onClick();

        if (CurtainRouter.isRunning(this)) {
            CurtainRouter.stop(this);
            updateTile();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            Intent perm = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            perm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(perm);
            return;
        }

        // 用透明 Activity 中转，控制中心才能在这之前收起来
        Intent launcher = new Intent(this, CurtainLaunchActivity.class);
        launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, launcher,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(launcher);
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean active = CurtainRouter.isRunning(this);
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getString(active ? R.string.tile_active : R.string.tile_inactive));
        }
        tile.updateTile();
    }
}
