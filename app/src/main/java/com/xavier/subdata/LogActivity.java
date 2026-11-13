package com.xavier.subdata;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

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
