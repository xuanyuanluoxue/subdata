package com.xavier.subdata;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
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

    // ---- MCP 服务（v0.4.0）
    private TextView tvMcpStatus;
    private TextView tvMcpDetail;
    private TextView tvMcpToken;
    private MaterialSwitch swMcpEnabled;
    private MaterialSwitch swMcpLan;
    private EditText etMcpPort;
    /** 程序化回填 MCP 开关时不要触发保存与重启服务。 */
    private boolean mcpBinding;

    /** 最近一次读到的槽目录与体积，供 chip 点按时使用。 */
    private List<String> slotNames = new ArrayList<>();
    private Map<String, Long> slotSizes = new LinkedHashMap<>();

    /** conf key -> 开关控件（顺序即界面顺序）。 */
    private final Map<String, MaterialSwitch> switches = new LinkedHashMap<>();

    /** 程序化回填时不要把改动当成用户操作写回文件。 */
    private boolean binding;

    /** 通知权限申请的请求码。 */
    private static final int REQ_NOTIFY = 0x5B01;

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

        bindMcp();
        showVersion();
        ensureNotificationPermission();
        reload();
    }

    /**
     * 申请通知权限（API 33+ 的运行时权限）。
     *
     * <p>v0.3.1 起超级岛通知由<b>模块 App 自己</b>发（目标 App 没有
     * {@code POST_PROMOTED_NOTIFICATIONS}，发出去也上不了岛），所以模块 App 必须有
     * {@code POST_NOTIFICATIONS}。这里只在第一次进首页时问一次，被拒也不影响其它功能——
     * 通知相关的地方都会自己判断权限并静默跳过。
     */
    private void ensureNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return;
            }
            if (checkSelfPermission(ModuleMeta.PERM_POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[]{ModuleMeta.PERM_POST_NOTIFICATIONS}, REQ_NOTIFY);
        } catch (Throwable ignored) {
            // 个别 ROM 上 requestPermissions 会抛，忽略即可
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从白名单 / slot 页回来要重新读
        reload();
        renderMcp();
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

    // ------------------------------------------------------------------ MCP 服务

    /**
     * 绑定 MCP 区块：开关、端口、令牌、状态。
     *
     * <p>服务本身跑在模块进程里（{@link McpManager} 单例），界面只是它的遥控器：
     * 每次改动都先落 SharedPreferences，再 {@link McpManager#sync} 对齐，最后重画状态。
     * 这样即使绑定失败（端口被占），界面也不会崩，只会显示「启动失败：…」。
     */
    private void bindMcp() {
        tvMcpStatus = findViewById(R.id.tv_mcp_status);
        tvMcpDetail = findViewById(R.id.tv_mcp_detail);
        tvMcpToken = findViewById(R.id.tv_mcp_token);
        etMcpPort = findViewById(R.id.et_mcp_port);

        View enabledRow = findViewById(R.id.row_mcp_enabled);
        ((TextView) enabledRow.findViewById(R.id.sw_title)).setText(R.string.mcp_switch_title);
        ((TextView) enabledRow.findViewById(R.id.sw_desc)).setText(R.string.mcp_switch_desc);
        swMcpEnabled = enabledRow.findViewById(R.id.sw_toggle);
        swMcpEnabled.setOnCheckedChangeListener((b, checked) -> {
            if (mcpBinding) {
                return;
            }
            McpManager.setEnabled(this, checked);
            applyMcp();
        });

        View lanRow = findViewById(R.id.row_mcp_lan);
        ((TextView) lanRow.findViewById(R.id.sw_title)).setText(R.string.mcp_lan_title);
        ((TextView) lanRow.findViewById(R.id.sw_desc)).setText(R.string.mcp_lan_desc);
        swMcpLan = lanRow.findViewById(R.id.sw_toggle);
        swMcpLan.setOnCheckedChangeListener((b, checked) -> {
            if (mcpBinding) {
                return;
            }
            if (!checked) {
                McpManager.setLan(this, false);
                applyMcp();
                return;
            }
            // 打开局域网必须先过一道风险确认
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.mcp_lan_title_dialog)
                    .setMessage(R.string.mcp_lan_msg)
                    .setNegativeButton(R.string.cancel, (d, w) -> {
                        mcpBinding = true;
                        b.setChecked(false);
                        mcpBinding = false;
                    })
                    .setPositiveButton(R.string.mcp_lan_title, (d, w) -> {
                        McpManager.setLan(this, true);
                        applyMcp();
                    })
                    .show();
        });

        findViewById(R.id.btn_mcp_refresh).setOnClickListener(v -> applyMcp());

        findViewById(R.id.btn_mcp_apply).setOnClickListener(v -> {
            int p;
            try {
                p = Integer.parseInt(etMcpPort.getText().toString().trim());
            } catch (Throwable t) {
                toast(getString(R.string.mcp_port_bad));
                return;
            }
            if (!McpManager.setPort(this, p)) {
                toast(getString(R.string.mcp_port_bad));
                return;
            }
            toast(getString(R.string.mcp_port_saved));
            applyMcp();
        });

        findViewById(R.id.btn_mcp_copy).setOnClickListener(v -> {
            String token = McpManager.token(this);
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("subdata mcp token", token));
                    toast(getString(R.string.mcp_copied));
                }
            } catch (Throwable t) {
                toast(getString(R.string.mcp_port_bad));
            }
        });

        findViewById(R.id.btn_mcp_reset).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.mcp_reset_title)
                        .setMessage(R.string.mcp_reset_msg)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.mcp_reset, (d, w) -> {
                            McpManager.resetToken(this);
                            toast(getString(R.string.mcp_reset_ok));
                            applyMcp();
                        })
                        .show());

        renderMcp();
        applyMcp();
    }

    /** 把设置对齐到运行中的服务（后台线程，避免绑定端口时卡界面）。 */
    private void applyMcp() {
        io.execute(() -> {
            try {
                McpManager.sync(getApplicationContext());
            } catch (Throwable t) {
                android.util.Log.e("SubDataMcp", "applyMcp failed", t);
            }
            ui.post(this::renderMcp);
        });
    }

    private void renderMcp() {
        if (isFinishing() || isDestroyed() || tvMcpStatus == null) {
            return;
        }
        Map<String, Object> st = McpManager.statusMap(this);
        boolean running = Boolean.TRUE.equals(st.get("running"));
        boolean enabled = Boolean.TRUE.equals(st.get("enabled"));
        Object err = st.get("error");
        Object host = st.get("host");
        long port = st.get("port") instanceof Number ? ((Number) st.get("port")).longValue() : 0;
        long req = st.get("requests") instanceof Number ? ((Number) st.get("requests")).longValue() : 0;
        long auth = st.get("auth_failures") instanceof Number
                ? ((Number) st.get("auth_failures")).longValue() : 0;

        mcpBinding = true;
        swMcpEnabled.setChecked(enabled);
        swMcpLan.setChecked(McpManager.lan(this));
        if (!etMcpPort.hasFocus()) {
            etMcpPort.setText(String.valueOf(McpManager.port(this)));
        }
        mcpBinding = false;

        if (running) {
            tvMcpStatus.setText(getString(R.string.mcp_status_running,
                    String.valueOf(host), port));
        } else if (enabled && err != null) {
            tvMcpStatus.setText(getString(R.string.mcp_status_error, String.valueOf(err)));
        } else {
            tvMcpStatus.setText(R.string.mcp_status_stopped);
        }
        tvMcpDetail.setText(getString(R.string.mcp_detail, req, auth));
        tvMcpToken.setText(McpManager.token(this));
        swMcpLan.setEnabled(enabled);
        etMcpPort.setEnabled(enabled);
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
