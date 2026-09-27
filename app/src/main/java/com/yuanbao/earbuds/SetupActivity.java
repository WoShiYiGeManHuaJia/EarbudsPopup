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

                append("\n=== ③ 屏蔽小米原生弹窗 ===");
                runCmds(ShizukuHelper.blockMiuiCommands());

                append("\n=== 完成 ===");
                append("建议：强制停止本 App 后重新打开，或直接重启手机，让权限生效。");
                append("然后回到主界面点「立即测试弹窗」验证。\n");
                main.post(() -> {
                    busy(false);
                    refreshStatus();
                    Toast.makeText(this, "执行完成", Toast.LENGTH_SHORT).show();
                });
            });
        });
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

            sb.append("\n========== 报告结束 ==========");
            String report = sb.toString();
            main.post(() -> {
                busy(false);
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
