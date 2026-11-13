package com.xavier.subdata;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页：模块是否生效 + 目标应用 + slot + 行为开关 + 日志入口 + 危险操作。
 *
 * <p>所有对目标 App 私有目录的读写都在后台线程通过 {@link RootShell} 完成，
 * 主线程只负责渲染，避免 su 弹授权框时界面卡住。
 */
public class MainActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TargetStore store;

    private TextView tvStatusState;
    private TextView tvStatusDetail;
    private TextView tvCurrentSlot;
    private TextView tvTargetPkg;
    private TextView tvVersion;
    private TextView tvTargetSummary;
    private TextView tvSlotSummary;
    private TextView tvLogPreview;
    private TextView tvHealth;
    private ChipGroup chipsSlots;

    private MaterialButton btnRefresh;

    /** 最近一次读到的槽目录与体积，供 chip 点按时使用。 */
    private List<String> slotNames = new ArrayList<>();
    private Map<String, Long> slotSizes = new LinkedHashMap<>();

    /** conf key -> 开关控件（顺序即界面顺序）。 */
    private final Map<String, MaterialSwitch> switches = new LinkedHashMap<>();

    /** 程序化回填时不要把改动当成用户操作写回文件。 */
    private boolean binding;

    private String pkg;
    private Map<String, String> conf = new LinkedHashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.title_main);
        toolbar.setSubtitle(R.string.subtitle_main);

        store = new TargetStore(this);

        tvStatusState = findViewById(R.id.tv_status_state);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        tvCurrentSlot = findViewById(R.id.tv_current_slot);
        tvTargetPkg = findViewById(R.id.tv_target_pkg);
        tvVersion = findViewById(R.id.tv_version);
        tvTargetSummary = findViewById(R.id.tv_target_summary);
        tvSlotSummary = findViewById(R.id.tv_slot_summary);
        tvLogPreview = findViewById(R.id.tv_log_preview);
        tvHealth = findViewById(R.id.tv_health);
        chipsSlots = findViewById(R.id.chips_slots);
        btnRefresh = findViewById(R.id.btn_refresh);

        btnRefresh.setOnClickListener(v -> reload());
        findViewById(R.id.btn_manage_apps).setOnClickListener(v ->
                startActivity(new Intent(this, AppPickerActivity.class)));
        findViewById(R.id.btn_manage_slots).setOnClickListener(v ->
                startActivity(new Intent(this, SlotActivity.class)));
        findViewById(R.id.btn_open_log).setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.btn_delete).setOnClickListener(v -> confirmDelete());

        bindSwitch(R.id.row_enabled, "enabled",
                R.string.switch_enabled_title, R.string.switch_enabled_desc);
        bindSwitch(R.id.row_clone, "clone_on_first_run",
                R.string.switch_clone_title, R.string.switch_clone_desc);
        bindSwitch(R.id.row_external, "redirect_external",
                R.string.switch_external_title, R.string.switch_external_desc);
        bindSwitch(R.id.row_file_paths, "hook_file_paths",
                R.string.switch_file_paths_title, R.string.switch_file_paths_desc);
        bindSwitch(R.id.row_data_dir, "hook_data_dir",
                R.string.switch_data_dir_title, R.string.switch_data_dir_desc);
        bindSwitch(R.id.row_island, "island_notice",
                R.string.switch_island_title, R.string.switch_island_desc);

        showVersion();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从白名单 / slot 页回来要重新读
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void showVersion() {
        String v = "?";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            // 拿不到版本号不影响主流程
        }
        tvVersion.setText(getString(R.string.version_with_beta, v));
    }

    private void bindSwitch(int rowId, String key, int titleRes, int descRes) {
        View row = findViewById(rowId);
        ((TextView) row.findViewById(R.id.sw_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.sw_desc)).setText(descRes);
        MaterialSwitch sw = row.findViewById(R.id.sw_toggle);
        sw.setTag(key);
        switches.put(key, sw);
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (binding) {
                return;
            }
            saveSwitch(key, checked);
        });
    }

    // ------------------------------------------------------------------ 读取

    private void reload() {
        btnRefresh.setEnabled(false);
        io.execute(() -> {
            String target = store.activeTarget();
            final boolean root = RootShell.available();
            boolean installed = false;
            String slot = null;
            Map<String, String> c = TargetRepo.defaultConf();
            String log = "";
            List<String> names = new ArrayList<>();
            Map<String, Long> sizes = new LinkedHashMap<>();
            if (root && target != null) {
                installed = TargetRepo.appInstalled(target);
                if (installed) {
                    slot = TargetRepo.readSlot(target);
                    Map<String, String> onDisk = TargetRepo.readConf(target);
                    if (!onDisk.isEmpty()) {
                        c.putAll(onDisk);
                    }
                    log = TargetRepo.readLog(target);
                    names = TargetRepo.listSlots(target);
                    sizes = TargetRepo.slotSizes(target);
                }
            }
            final boolean fInstalled = installed;
            final String fSlot = slot;
            final Map<String, String> fConf = c;
            final String fLog = log;
            final List<String> fNames = names;
            final Map<String, Long> fSizes = sizes;
            ui.post(() -> render(target, root, fInstalled, fSlot, fConf, fLog, fNames, fSizes));
        });
    }

    private void render(String target, boolean root, boolean installed, String slot,
                        Map<String, String> c, String log,
                        List<String> names, Map<String, Long> sizes) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        btnRefresh.setEnabled(true);
        this.pkg = target;
        this.conf = c;

        List<String> all = store.targets();
        tvTargetSummary.setText(target == null
                ? getString(R.string.status_no_target)
                : getString(R.string.pkg_label, target,
                getString(R.string.target_summary, all.size())));
        tvTargetPkg.setText(target == null ? getString(R.string.value_none) : target);

        String last = TargetRepo.lastInstallLine(log);
        String logTail = tailLines(log, 3);
        tvLogPreview.setText(logTail.isEmpty() ? getString(R.string.log_empty) : logTail);

        if (target == null) {
            tvStatusState.setText(R.string.status_no_target);
            tvStatusDetail.setVisibility(View.GONE);
            tvCurrentSlot.setText(R.string.value_none);
        } else if (!root) {
            tvStatusState.setText(R.string.status_no_root);
            tvStatusDetail.setVisibility(View.GONE);
            tvCurrentSlot.setText(R.string.value_none);
        } else if (!installed) {
            tvStatusState.setText(R.string.status_never_run);
            tvStatusDetail.setText(R.string.status_not_installed);
            tvStatusDetail.setVisibility(View.VISIBLE);
            tvCurrentSlot.setText(R.string.value_none);
        } else if (last == null) {
            tvStatusState.setText(R.string.status_never_run);
            tvStatusDetail.setText(R.string.status_never_run_hint);
            tvStatusDetail.setVisibility(View.VISIBLE);
            tvCurrentSlot.setText(slot == null ? getString(R.string.value_none) : slot);
        } else if (last.contains("disabled by")) {
            tvStatusState.setText(R.string.status_disabled);
            tvStatusDetail.setText(last);
            tvStatusDetail.setVisibility(View.VISIBLE);
            tvCurrentSlot.setText(slot == null ? getString(R.string.value_none) : slot);
        } else {
            tvStatusState.setText(R.string.status_active);
            tvStatusDetail.setText(last);
            tvStatusDetail.setVisibility(View.VISIBLE);
            tvCurrentSlot.setText(slot == null ? getString(R.string.value_none) : slot);
        }

        binding = true;
        for (Map.Entry<String, MaterialSwitch> e : switches.entrySet()) {
            MaterialSwitch sw = e.getValue();
            boolean on = SlotConfig.truthy(c.get(e.getKey()), true);
            sw.setChecked(on);
            sw.setEnabled(root && target != null);
        }
        binding = false;

        slotNames = names == null ? new ArrayList<>() : names;
        slotSizes = sizes == null ? new LinkedHashMap<>() : sizes;

        int slotCount = root && target != null ? slotNames.size() : 0;
        tvSlotSummary.setText(getString(R.string.slot_summary_with_current,
                slot == null ? getString(R.string.value_none) : slot, slotCount));

        renderChips(root && target != null, slot);
        renderHealth(c, slot, slotNames);
    }

    /** 首页直接把槽列成 chip，点一下就能切，省掉「进二级页 → 找 → 确认」。 */
    private void renderChips(boolean usable, String current) {
        chipsSlots.removeAllViews();
        if (!usable || slotNames.isEmpty()) {
            chipsSlots.setVisibility(View.GONE);
            return;
        }
        chipsSlots.setVisibility(View.VISIBLE);
        for (final String name : slotNames) {
            Chip chip = new Chip(this);
            chip.setText(SlotStats.label(name, SlotStats.sizeOf(slotSizes, name)));
            chip.setCheckable(true);
            chip.setChecked(name.equals(current));
            chip.setChipIconVisible(false);
            chip.setOnClickListener(v -> askSwitch(name, current));
            chipsSlots.addView(chip);
        }
    }

    private void askSwitch(final String slot, String current) {
        if (slot.equals(current)) {
            toast(getString(R.string.slot_already_current, slot));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.section_slot)
                .setMessage(getString(R.string.slot_switch_confirm_stop, slot))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.slot_switch, (d, w) -> doSwitch(slot))
                .show();
    }

    private void doSwitch(final String slot) {
        final String target = pkg;
        if (target == null) {
            toast(getString(R.string.no_target_selected));
            return;
        }
        io.execute(() -> {
            final boolean ok = TargetRepo.switchAndStop(target, slot);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(ok ? getString(R.string.slot_switched, slot)
                        : getString(R.string.switch_write_fail, getString(R.string.root_denied)));
                reload();
            });
        });
    }

    /** 体检结论：只显示最严重的那一条，避免首页被塞成日志。 */
    private void renderHealth(Map<String, String> c, String slot, List<String> names) {
        List<HealthCheck.Issue> issues = HealthCheck.inspect(c, slot,
                names == null ? Collections.<String>emptyList() : names);
        HealthCheck.Level worst = HealthCheck.worst(issues);
        if (worst == null) {
            tvHealth.setVisibility(View.GONE);
            return;
        }
        HealthCheck.Issue top = issues.get(0);
        tvHealth.setVisibility(View.VISIBLE);
        tvHealth.setText(healthText(top, issues.size()));
        if (worst == HealthCheck.Level.DANGER) {
            tvHealth.setTextColor(getColor(R.color.subdata_danger));
        } else if (worst == HealthCheck.Level.WARN) {
            tvHealth.setTextColor(getColor(R.color.subdata_secondary));
        } else {
            tvHealth.setTextColor(getColor(R.color.subdata_primary));
        }
    }

    private String healthText(HealthCheck.Issue i, int total) {
        int res;
        switch (i.code) {
            case SLOT_DIR_MISSING_NO_CLONE:
                res = R.string.health_slot_missing_no_clone;
                break;
            case SLOT_DIR_MISSING_WILL_CLONE:
                res = R.string.health_slot_missing_will_clone;
                break;
            case MODULE_DISABLED:
                res = R.string.health_disabled;
                break;
            case HOOKS_TOO_NARROW:
                res = R.string.health_hooks_narrow;
                break;
            case NO_SLOTS:
                res = R.string.health_no_slots;
                break;
            case EXTERNAL_DOUBLED:
                res = R.string.health_external;
                break;
            case SLOT_CONFLICT:
                res = R.string.health_slot_conflict;
                break;
            case CONF_EMPTY:
            default:
                res = R.string.health_conf_empty;
                break;
        }
        String body = i.slot == null ? getString(res) : getString(res, i.slot);
        return total > 1 ? body + getString(R.string.health_more, total - 1) : body;
    }

    // ------------------------------------------------------------------ 写入

    private void saveSwitch(String key, boolean checked) {
        final String target = pkg;
        if (target == null) {
            return;
        }
        io.execute(() -> {
            Map<String, String> cur = TargetRepo.readConf(target);
            if (cur.isEmpty()) {
                cur.putAll(TargetRepo.defaultConf());
            }
            cur.put(key, checked ? "true" : "false");
            final boolean ok = TargetRepo.writeConf(target, cur);
            final String err = ok ? "" : getString(R.string.root_denied);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(ok ? getString(R.string.switch_write_ok)
                        : getString(R.string.switch_write_fail, err));
            });
        });
    }

    private void confirmDelete() {
        final String target = pkg;
        if (target == null) {
            toast(getString(R.string.no_target_selected));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.danger_confirm1_title)
                .setMessage(getString(R.string.danger_confirm1_msg, target))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.danger_confirm1_yes, (d, w) -> confirmDelete2(target))
                .show();
    }

    private void confirmDelete2(String target) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.danger_confirm2_title)
                .setMessage(getString(R.string.danger_confirm2_msg,
                        TargetRepo.controlDir(target)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.danger_confirm2_yes, (d, w) -> doDelete(target))
                .show();
    }

    private void doDelete(String target) {
        io.execute(() -> {
            boolean ok = TargetRepo.deleteControlDir(target);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(ok ? getString(R.string.danger_deleted)
                        : getString(R.string.danger_delete_fail, getString(R.string.root_denied)));
                reload();
            });
        });
    }

    // ------------------------------------------------------------------ 小工具

    private void toast(String msg) {
        View root = findViewById(R.id.root);
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
    }

    private static String tailLines(String text, int n) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.trim().split("\n");
        int from = Math.max(0, lines.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
