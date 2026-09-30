package com.yuanbao.earbuds;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一键设置：让 App 自己通过 Stellar / Shizuku 跑完所有命令，
 * 并把采集到的系统信息生成诊断报告发给开发者，用于定制硬编码。
 */
public class SetupActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final StringBuilder log = new StringBuilder();

    private TextView tvLog, tvStatus, tvManager;
    private Button btnGrant, btnBlock, btnReport, btnRetry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        tvLog = findViewById(R.id.tvLog);
        tvStatus = findViewById(R.id.tvStatus);
        tvManager = findViewById(R.id.tvManager);
        btnGrant = findViewById(R.id.btnGrant);
        btnBlock = findViewById(R.id.btnBlock);
        btnReport = findViewById(R.id.btnReport);
        btnRetry = findViewById(R.id.btnRetry);

        btnGrant.setOnClickListener(v -> doSetup(true));
        btnBlock.setOnClickListener(v -> doSetup(false));
        btnReport.setOnClickListener(v -> doReport());
        btnRetry.setOnClickListener(v -> refreshStatus());

        refreshStatus();
    }

    private void refreshStatus() {
        tvManager.setText(ShizukuHelper.detectManager(this));
        boolean running = ShizukuHelper.isServiceRunning();
        boolean perm = ShizukuHelper.hasPermission();
        if (!running) {
            tvStatus.setText("服务状态：未运行 ✗\n请打开 Stellar → 启动 → 无线调试 → 启动，"
                    + "再回来点「重新检测服务」");
            tvStatus.setTextColor(0xFFB00020);
        } else if (!perm) {
            tvStatus.setText("服务状态：运行中 ✓\n但还没授权本 App，点下面的按钮会自动弹出授权请求");
            tvStatus.setTextColor(0xFFE68A00);
        } else {
            tvStatus.setText("服务状态：运行中 ✓　已授权 ✓\n可以直接点按钮执行");
            tvStatus.setTextColor(0xFF00A060);
        }
    }

    private void append(String s) {
        log.append(s).append('\n');
        main.post(() -> tvLog.setText(log.toString()));
    }

    private void busy(boolean b) {
        main.post(() -> {
            btnGrant.setEnabled(!b);
            btnBlock.setEnabled(!b);
            btnReport.setEnabled(!b);
        });
    }

    /** grant=true 时执行完整设置；false 时只执行屏蔽原生弹窗 */
    private void doSetup(boolean grant) {
        if (!ShizukuHelper.isServiceRunning()) {
            Toast.makeText(this, "Stellar 服务未运行，请先启动", Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        ShizukuHelper.requestPermission(ok -> {
            if (!ok) {
                append("✗ 授权被拒绝或超时。请在 Stellar 的「授权应用」里允许本 App。");
                Toast.makeText(this, "未获得授权", Toast.LENGTH_SHORT).show();
                return;
            }
            busy(true);
            pool.execute(() -> {
                if (grant) {
                    append("=== ① 授予基础权限 ===");
                    runCmds(ShizukuHelper.grantSelfCommands());

                    append("\n=== ② 探测 MIUI 扩展权限编号 ===");
                    List<String[]> ops = ShizukuHelper.probeOps();
                    for (String[] r : ops) {
                        append("opcode " + r[0] + " : " + r[1]);
                    }

                    append("\n=== ③ 验证实际生效状态 ===");
                    for (String v : ShizukuHelper.verifyKeyPerms()) {
                        append(v);
                        if (v.contains("ignore") || v.contains("deny")
                                || v.contains("default")) {
                            append("   ⚠ 这项没生效，弹窗可能因此无法显示");
                        }
                    }
                }

                // 只点「屏蔽」时也必须先把自身权限补上。
                // 之前只跑屏蔽命令、不跑授权命令，结果本 App 自己的
                // SYSTEM_ALERT_WINDOW 一直是 ignore —— 悬浮窗引擎根本弹不出来，
                // 用户会以为「装了 App 反而没弹窗」。
                if (!grant) {
                    append("=== ① 先补上本 App 自身权限 ===");
                    runCmds(ShizukuHelper.grantSelfCommands());
                    for (String v : ShizukuHelper.verifyKeyPerms()) {
                        append(v);
                        if (v.contains("ignore") || v.contains("deny")
                                || v.contains("default")) {
                            append("   ⚠ 这项没生效，弹窗可能因此无法显示");
                        }
                    }
                }

                append("\n=== ④ 屏蔽小米原生弹窗（温和） ===");
                runCmds(ShizukuHelper.blockMiuiCommands());
                append("\n--- 回读验证 ---");
                for (String v : ShizukuHelper.verifyBlockState()) {
                    append(v);
                }

                append("\n=== 完成 ===");
                append("建议：强制停止本 App 后重新打开，或直接重启手机，让权限生效。");
                append("然后回到主界面点「立即测试弹窗」验证。\n");
                main.post(() -> {
                    busy(false);
                    refreshStatus();
                    askStrongBlock();
                });
            });
        });
    }

    /**
     * 温和屏蔽通常压不住，因为小米快连弹窗是系统 Activity 而非悬浮窗。
     * 这里把唯一可能真正生效的手段（冻结组件）摆出来，让用户自己决定。
     */
    private void askStrongBlock() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("温和手段可能无效，要不要用强力屏蔽？")
                .setMessage("小米快连弹窗是系统进程启动的 Activity，不是悬浮窗，"
                        + "所以禁权限往往压不住它。\n\n"
                        + "唯一可能真正生效的办法是直接冻结「小米蓝牙扩展」组件。\n\n"
                        + "代价：开盖快连弹窗失效（普通蓝牙连接与音频不受影响）。\n"
                        + "随时可以再执行一次恢复。\n\n"
                        + "要用吗？")
                .setNegativeButton("先不用", (d, w) ->
                        Toast.makeText(this, "执行完成", Toast.LENGTH_SHORT).show())
                .setPositiveButton("冻结组件", (d, w) -> {
                    if (!ShizukuHelper.hasPermission()) {
                        Toast.makeText(this, "未获得授权", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    busy(true);
                    pool.execute(() -> {
                        append("\n=== ⑤ 强力屏蔽：冻结小米蓝牙扩展 ===");
                        runCmds(ShizukuHelper.blockMiuiStrongCommands());
                        append("\n--- 回读验证 ---");
                        for (String v : ShizukuHelper.verifyBlockState()) {
                            append(v);
                        }
                        main.post(() -> {
                            busy(false);
                            Toast.makeText(this, "已执行，建议重启手机后验证",
                                    Toast.LENGTH_LONG).show();
                        });
                    });
                })
                .setNeutralButton("恢复组件", (d, w) -> {
                    if (!ShizukuHelper.hasPermission()) {
                        Toast.makeText(this, "未获得授权", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    busy(true);
                    pool.execute(() -> {
                        append("\n=== ⑤ 恢复小米蓝牙扩展 ===");
                        runCmds(ShizukuHelper.restoreMiuiCommands());
                        for (String v : ShizukuHelper.verifyBlockState()) {
                            append(v);
                        }
                        main.post(() -> {
                            busy(false);
                            Toast.makeText(this, "已恢复", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .show();
    }

    private void runCmds(String[] cmds) {
        for (String c : cmds) {
            String out = ShizukuHelper.exec(c);
            append("$ " + c);
            append("  → " + (out.isEmpty() ? "(无输出，通常表示成功)" : out));
        }
    }

    private void doReport() {
        if (!ShizukuHelper.hasPermission()) {
            Toast.makeText(this, "需要先获得授权，请先点上面第 ① 个按钮", Toast.LENGTH_LONG).show();
            return;
        }
        busy(true);
        pool.execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("========== 耳机弹窗 · 诊断报告 ==========\n\n");
            sb.append("【设备】\n");
            sb.append(ShizukuHelper.detectManager(this)).append('\n');

            sb.append("【系统信息】\n");
            for (String c : ShizukuHelper.infoCommands()) {
                sb.append("$ ").append(c).append('\n');
                sb.append("  → ").append(ShizukuHelper.exec(c)).append('\n');
            }

            sb.append("\n【MIUI 扩展 AppOps 探测】\n");
            for (String[] r : ShizukuHelper.probeOps()) {
                sb.append("opcode ").append(r[0]).append(" : ").append(r[1]).append('\n');
                sb.append("   set → ").append(r[2]).append('\n');
                sb.append("   get → ").append(r[3]).append('\n');
            }

            // 系统蓝牙栈里到底有没有这副耳机的电量记录 ——
            // 决定「真电量」能否拿到，比反复试 GATT 更能说明问题
            sb.append("\n【系统蓝牙栈电量诊断】\n");
            for (String c : ShizukuHelper.batteryDiagCommands()) {
                sb.append("$ ").append(c).append('\n');
                sb.append(ShizukuHelper.exec(c)).append('\n');
            }

            // 屏蔽到底生效没有，必须回读，不能只看命令有没有报错
            sb.append("\n【小米弹窗屏蔽状态】\n");
            for (String v : ShizukuHelper.verifyBlockState()) {
                sb.append(v).append('\n');
            }

            // 列出小米蓝牙扩展的全部组件，用于定位弹窗到底是哪个 Activity。
            // 冻整个包会连快连一起干掉；找到具体组件就能只禁弹窗、保留蓝牙。
            sb.append("\n【小米蓝牙扩展 · 组件清单】\n");
            sb.append("$ dumpsys package com.xiaomi.bluetooth\n");
            sb.append(ShizukuHelper.dumpMiuiComponents()).append('\n');

            sb.append("\n========== 报告结束 ==========");
            final String report = sb.toString();
            main.post(() -> {
                busy(false);
                // 报告越来越大，直接塞进 Intent 有大小上限（Binder 事务约 1MB，
                // 不少 App 限制更小）。超阈值就落盘成文件再分享，更稳。
                if (report.length() > 60000) {
                    try {
                        java.io.File dir = new java.io.File(
                                getExternalFilesDir(null), "report");
                        if (!dir.exists()) dir.mkdirs();
                        java.io.File f = new java.io.File(dir, "诊断报告.txt");
                        java.io.FileOutputStream fos =
                                new java.io.FileOutputStream(f, false);
                        fos.write(report.getBytes("UTF-8"));
                        fos.close();
                        android.net.Uri uri = androidx.core.content.FileProvider
                                .getUriForFile(this, getPackageName() + ".files", f);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(share,
                                "报告较长，已存成文件，发送这个"));
                        Toast.makeText(this,
                                "报告约 " + (report.length() / 1024) + " KB，已存成文件分享",
                                Toast.LENGTH_LONG).show();
                        return;
                    } catch (Throwable e) {
                        // 落盘失败就退回文本分享
                    }
                }
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("report", report));
                    Toast.makeText(this, "报告已复制，粘贴发给我即可", Toast.LENGTH_LONG).show();
                }
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(share, "发送诊断报告"));
            });
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
