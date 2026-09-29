package com.earpopupx;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {

    private static final int REQ_BT = 10;
    private static final int REQ_NOTIFY = 11;
    private static final int REQ_MEDIA = 12;

    private TextView status;
    private Switch swEnable;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        bind();
        updateStatus();
        requestMissing();
        startMonitorIfPossible();
    }

    private void bind() {
        status = findViewById(R.id.status);
        swEnable = findViewById(R.id.enable);
        swEnable.setChecked(AppPrefs.enabled(this));
        swEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AppPrefs.setEnabled(MainActivity.this, isChecked);
                if (!isChecked) {
                    stopService(new Intent(MainActivity.this, BluetoothMonitorService.class));
                } else {
                    startMonitorIfPossible();
                }
            }
        });

        findViewById(R.id.overlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlay();
            }
        });
        findViewById(R.id.notifications).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotifications();
            }
        });
        findViewById(R.id.bluetooth).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestBluetooth();
            }
        });
        findViewById(R.id.battery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ignoreBatteryOptimization();
            }
        });
        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startMonitorIfPossible();
                Toast.makeText(MainActivity.this, "耳机监听已启动", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    openOverlay();
                    return;
                }
                new EarPopupWindow(MainActivity.this).show("Redmi Buds 5 Pro Gaming",
                        BatteryState.aggregateOnly(90, "测试：真实读数示例"));
            }
        });
        findViewById(R.id.pick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickMedia();
            }
        });
        findViewById(R.id.reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setMedia(MainActivity.this, null);
                Toast.makeText(MainActivity.this, "已恢复默认", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开悬浮窗设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN}, REQ_BT);
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        } else {
            Toast.makeText(this, "此系统版本无需通知权限", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestMissing() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBluetooth();
        }
    }

    private void ignoreBatteryOptimization() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_MEDIA);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_MEDIA && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(
                        data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            AppPrefs.setMedia(this, data.getData());
            Toast.makeText(this, "图片 / GIF 已保存", Toast.LENGTH_SHORT).show();
        }
    }

    private void startMonitorIfPossible() {
        if (!AppPrefs.enabled(this)) return;
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) return;
        Intent i = new Intent(this, BluetoothMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败：请在系统设置里允许后台弹出界面", Toast.LENGTH_LONG).show();
        }
    }

    private void updateStatus() {
        StringBuilder s = new StringBuilder();
        s.append("当前状态\n");
        s.append(Settings.canDrawOverlays(this) ? "✓ 悬浮窗：已授权\n" : "✗ 悬浮窗：未授权\n");
        if (Build.VERSION.SDK_INT >= 31) {
            s.append(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    ? "✓ 蓝牙：已授权\n" : "✗ 蓝牙：未授权\n");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            s.append(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    ? "✓ 通知：已授权\n" : "✗ 通知：未授权\n");
        }
        if (status != null) status.setText(s.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }
}
