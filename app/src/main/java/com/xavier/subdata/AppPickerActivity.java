package com.xavier.subdata;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 目标应用白名单：从已安装应用里勾选，支持搜索。
 *
 * <p>只列出「用户应用 + 已经在白名单里的应用」，避免一屏系统应用。
 */
public class AppPickerActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<AppEntry> all = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();

    private AppAdapter adapter;
    private TextView tvEmpty;
    private String query = "";

    static final class AppEntry {
        final String pkg;
        final String label;
        final Drawable icon;

        AppEntry(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new AppAdapter();
        RecyclerView rv = findViewById(R.id.rv_apps);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        tvEmpty = findViewById(R.id.tv_empty);

        TargetStore store = new TargetStore(this);
        selected.addAll(store.targets());

        TextInputEditText search = findViewById(R.id.et_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s == null ? "" : s.toString().toLowerCase(Locale.ROOT).trim();
                adapter.notifyDataSetChanged();
                updateEmpty();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            List<String> pkgs = new ArrayList<>(selected);
            Collections.sort(pkgs);
            store.setTargets(pkgs);
            Snackbar.make(findViewById(R.id.rv_apps),
                    getString(R.string.picker_saved, pkgs.size()), Snackbar.LENGTH_SHORT).show();
            ui.postDelayed(this::finish, 350);
        });

        loadApps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void loadApps() {
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            List<AppEntry> out = new ArrayList<>();
            // getInstalledApplications(int) 在 33+ 已废弃，为兼容 minSdk 26 继续用
            @SuppressWarnings("deprecation")
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) {
                    continue;
                }
                if (getPackageName().equals(ai.packageName)) {
                    continue;
                }
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !selected.contains(ai.packageName)) {
                    continue;
                }
                String label;
                Drawable icon;
                try {
                    label = String.valueOf(pm.getApplicationLabel(ai));
                    icon = pm.getApplicationIcon(ai);
                } catch (Throwable t) {
                    label = ai.packageName;
                    icon = null;
                }
                out.add(new AppEntry(ai.packageName, label, icon));
            }
            Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
            final List<AppEntry> finalOut = out;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                all.clear();
                all.addAll(finalOut);
                adapter.notifyDataSetChanged();
                updateEmpty();
            });
        });
    }

    private void updateEmpty() {
        boolean empty = visible().isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private List<AppEntry> visible() {
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : all) {
            if (query.isEmpty()
                    || e.label.toLowerCase(Locale.ROOT).contains(query)
                    || e.pkg.toLowerCase(Locale.ROOT).contains(query)) {
                out.add(e);
            }
        }
        return out;
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView pkg;
            final MaterialCheckBox cb;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.iv_icon);
                name = v.findViewById(R.id.tv_app_name);
                pkg = v.findViewById(R.id.tv_app_pkg);
                cb = v.findViewById(R.id.cb_selected);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final AppEntry e = visible().get(position);
            h.name.setText(e.label);
            h.pkg.setText(e.pkg);
            if (e.icon != null) {
                h.icon.setImageDrawable(e.icon);
            } else {
                h.icon.setImageResource(R.drawable.ic_launcher_foreground);
            }
            h.cb.setChecked(selected.contains(e.pkg));
            View.OnClickListener toggle = v -> {
                if (selected.contains(e.pkg)) {
                    selected.remove(e.pkg);
                    h.cb.setChecked(false);
                } else {
                    selected.add(e.pkg);
                    h.cb.setChecked(true);
                }
            };
            h.itemView.setOnClickListener(toggle);
            h.cb.setOnClickListener(toggle);
        }

        @Override
        public int getItemCount() {
            return visible().size();
        }
    }

    @SuppressWarnings("unused")
    private void warnNoTarget() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.status_no_target)
                .setMessage(R.string.picker_hint)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
