package com.xavier.subdata;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Slot 管理：列出 subdata/ 下的 slot，支持新建 / 重命名 / 切换当前 slot。
 *
 * <p>「切换」只写 {@code subdata/slot} 一行，模块在下次注入时读取；
 * 正在运行的进程不会自动切换，所以界面会提示强制停止目标 App。
 */
public class SlotActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<String> slots = new ArrayList<>();
    /** slot -> 占用字节；没统计到的槽不在表里。 */
    private final Map<String, Long> sizes = new LinkedHashMap<>();
    private String current;
    private String pkg;

    private SlotAdapter adapter;
    private TextView tvEmpty;
    private MaterialButton btnNew;
    private MaterialButton btnForceStop;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_slot);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tv_empty);
        btnNew = findViewById(R.id.btn_new_slot);
        btnForceStop = findViewById(R.id.btn_force_stop);

        adapter = new SlotAdapter();
        RecyclerView rv = findViewById(R.id.rv_slots);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        pkg = new TargetStore(this).activeTarget();

        btnNew.setOnClickListener(v -> showNewSlotDialog());
        btnForceStop.setOnClickListener(v -> forceStop());

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
            boolean root = RootShell.available();
            List<String> list = new ArrayList<>();
            Map<String, Long> sizeMap = new LinkedHashMap<>();
            String cur = null;
            if (root && target != null) {
                list.addAll(TargetRepo.listSlots(target));
                cur = TargetRepo.readSlot(target);
                sizeMap = TargetRepo.slotSizes(target);
            }
            final List<String> fList = list;
            final Map<String, Long> fSizes = sizeMap;
            final String fCur = cur;
            final boolean fRoot = root;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                slots.clear();
                slots.addAll(fList);
                sizes.clear();
                sizes.putAll(fSizes);
                current = fCur;
                adapter.notifyDataSetChanged();
                boolean empty = slots.isEmpty();
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (!fRoot) {
                    tvEmpty.setText(R.string.slot_no_root);
                    tvEmpty.setVisibility(View.VISIBLE);
                }
                btnNew.setEnabled(fRoot && target != null);
                btnForceStop.setEnabled(fRoot && target != null);
            });
        });
    }

    private void showNewSlotDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint(R.string.slot_name_hint);
        FrameLayout wrap = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.slot_new)
                .setView(wrap)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> createSlot(et.getText().toString()))
                .show();
    }

    private void createSlot(String raw) {
        final String slot = SlotConfig.sanitize(raw);
        if (slot == null) {
            toast(getString(R.string.slot_name_invalid));
            return;
        }
        final String target = pkg;
        io.execute(() -> {
            boolean ok = TargetRepo.createSlot(target, slot);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(ok ? getString(R.string.slot_created, slot)
                        : getString(R.string.slot_create_fail, getString(R.string.root_denied)));
                reload();
            });
        });
    }

    private void showActions(String slot) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.slot_switch));
        items.add(getString(R.string.slot_rename));
        items.add(getString(R.string.slot_delete));
        new MaterialAlertDialogBuilder(this)
                .setTitle(slot)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        switchTo(slot);
                    } else if (which == 1) {
                        showRenameDialog(slot);
                    } else {
                        confirmDelete(slot);
                    }
                })
                .show();
    }

    private void showRenameDialog(final String from) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(from);
        et.setHint(R.string.slot_name_hint);
        FrameLayout wrap = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.slot_rename_title)
                .setView(wrap)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> renameSlot(from, et.getText().toString()))
                .show();
    }

    private void renameSlot(String from, String raw) {
        final String to = SlotConfig.sanitize(raw);
        if (to == null) {
            toast(getString(R.string.slot_name_invalid));
            return;
        }
        final String target = pkg;
        io.execute(() -> {
            boolean ok = TargetRepo.renameSlot(target, from, to);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(ok ? getString(R.string.slot_renamed, to)
                        : getString(R.string.slot_create_fail, getString(R.string.root_denied)));
                reload();
            });
        });
    }

    private void switchTo(final String slot) {
        if (slot.equals(current)) {
            toast(getString(R.string.slot_switched, slot));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.section_slot)
                .setMessage(getString(R.string.slot_switch_confirm, slot))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.slot_switch, (d, w) -> {
                    final String target = pkg;
                    io.execute(() -> {
                        // 切完直接强停：只写 slot 文件不会立即生效，用户极易漏做这一步
                        final boolean ok = TargetRepo.switchAndStop(target, slot);
                        ui.post(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            toast(ok ? getString(R.string.slot_switched, slot)
                                    : getString(R.string.switch_write_fail,
                                    getString(R.string.root_denied)));
                            reload();
                        });
                    });
                })
                .show();
    }

    /** 删除单槽：两次确认，且不允许删当前正在用的槽（先切走再说）。 */
    private void confirmDelete(final String slot) {
        if (slot.equals(current)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.slot_delete)
                    .setMessage(getString(R.string.slot_delete_current, slot))
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        long size = SlotStats.sizeOf(sizes, slot);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.slot_delete)
                .setMessage(getString(R.string.slot_delete_confirm,
                        slot, SlotStats.formatSize(size)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.slot_delete_yes, (d, w) -> confirmDelete2(slot))
                .show();
    }

    private void confirmDelete2(final String slot) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.slot_delete)
                .setMessage(getString(R.string.slot_delete_confirm2, slot))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.slot_delete_yes, (d, w) -> doDelete(slot))
                .show();
    }

    private void doDelete(final String slot) {
        final String target = pkg;
        if (target == null) {
            return;
        }
        io.execute(() -> {
            final boolean ok = TargetRepo.deleteSlot(target, slot);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(ok ? getString(R.string.slot_deleted, slot)
                        : getString(R.string.slot_delete_fail, getString(R.string.root_denied)));
                reload();
            });
        });
    }

    private void forceStop() {
        final String target = pkg;
        if (target == null) {
            return;
        }
        io.execute(() -> {
            boolean ok = TargetRepo.forceStop(target);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(ok ? getString(R.string.slot_force_stopped)
                        : getString(R.string.switch_write_fail, getString(R.string.root_denied)));
            });
        });
    }

    private void toast(String msg) {
        Snackbar.make(findViewById(R.id.rv_slots), msg, Snackbar.LENGTH_SHORT).show();
    }

    private final class SlotAdapter extends RecyclerView.Adapter<SlotAdapter.VH> {

        final class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView tag;
            final TextView meta;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tv_slot_name);
                tag = v.findViewById(R.id.tv_current_tag);
                meta = v.findViewById(R.id.tv_slot_meta);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_slot, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final String slot = slots.get(position);
            h.name.setText(slot);
            boolean isCurrent = slot.equals(current);
            h.tag.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            long size = SlotStats.sizeOf(sizes, slot);
            if (size < 0) {
                h.meta.setVisibility(View.GONE);
            } else {
                h.meta.setVisibility(View.VISIBLE);
                h.meta.setText(SlotStats.formatSize(size));
            }
            h.itemView.setOnClickListener(v -> showActions(slot));
        }

        @Override
        public int getItemCount() {
            return slots.size();
        }
    }
}
