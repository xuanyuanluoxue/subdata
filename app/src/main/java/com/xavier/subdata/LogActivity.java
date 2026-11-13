package com.xavier.subdata;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 日志页：显示 subdata.log 全文（滚动），支持一键复制 / 清空。 */
public class LogActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvLog;
    private String text = "";
    private String pkg;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvLog = findViewById(R.id.tv_log);
        pkg = new TargetStore(this).activeTarget();

        findViewById(R.id.btn_export).setOnClickListener(v -> exportDiag());
        findViewById(R.id.btn_copy).setOnClickListener(v -> copy());
        findViewById(R.id.btn_clear).setOnClickListener(v -> confirmClear());

        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void reload() {
        final String target = pkg;
        io.execute(() -> {
            final boolean root = RootShell.available();
            String body = "";
            if (target != null && root) {
                body = TargetRepo.readLog(target);
            }
            final String fBody = body;
            final boolean fRoot = root;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!fRoot) {
                    text = "";
                    tvLog.setText(R.string.log_no_root);
                    return;
                }
                text = fBody;
                tvLog.setText(fBody.isEmpty() ? getString(R.string.log_empty) : fBody);
            });
        });
    }

    /**
     * 导出诊断包：把环境、配置、槽体积、体检结论和完整日志拼成一份 txt，
     * 写到 App 自己的外部目录，再交给系统分享。
     *
     * <p>任何一步失败都只弹提示，绝不崩溃——这个按钮的意义就是在出问题时救场，
     * 它自己不能成为新的故障点。</p>
     */
    private void exportDiag() {
        final String target = pkg;
        io.execute(() -> {
            String path = null;
            String err = "";
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) {
                    err = getString(R.string.diag_no_dir);
                } else if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                    err = getString(R.string.diag_no_dir);
                } else {
                    Diagnostics.Facts f = new Diagnostics.Facts();
                    f.versionName = versionName();
                    f.versionCode = versionCode();
                    f.androidRelease = Build.VERSION.RELEASE;
                    f.pkg = target;
                    f.rootOk = RootShell.available();
                    f.conf = TargetRepo.readConf(target);
                    f.slot = TargetRepo.readSlot(target);
                    f.slots = TargetRepo.listSlots(target);
                    f.sizes = TargetRepo.slotSizes(target);
                    f.issues = HealthCheck.inspect(f.conf, f.slot, f.slots);
                    f.log = TargetRepo.readLog(target);
                    File out = new File(dir, Diagnostics.fileName(System.currentTimeMillis()));
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(out),
                            StandardCharsets.UTF_8)) {
                        w.write(Diagnostics.buildReport(f));
                    }
                    path = out.getAbsolutePath();
                }
            } catch (Throwable t) {
                err = String.valueOf(t);
            }
            final String fPath = path;
            final String fErr = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (fPath == null) {
                    toast(getString(R.string.diag_failed, fErr));
                    return;
                }
                toast(getString(R.string.diag_saved, fPath));
                share(fPath);
            });
        });
    }

    private void share(String path) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider",
                    new File(path));
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_subject));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.diag_share)));
        } catch (Throwable ignored) {
            // 没有分享面板 / provider 没装好都不影响文件已经落盘
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int versionCode() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    private void copy() {
        if (text.isEmpty()) {
            toast(getString(R.string.log_empty));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("subdata.log", text));
            toast(getString(R.string.log_copied));
        }
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_clear)
                .setMessage(R.string.log_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.log_clear, (d, w) -> clear())
                .show();
    }

    private void clear() {
        final String target = pkg;
        if (target == null) {
            return;
        }
        io.execute(() -> {
            boolean ok = TargetRepo.clearLog(target);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(ok ? getString(R.string.log_cleared) : getString(R.string.root_denied));
                reload();
            });
        });
    }

    private void toast(String msg) {
        Snackbar.make(findViewById(R.id.tv_log), msg, Snackbar.LENGTH_SHORT).show();
    }
}
