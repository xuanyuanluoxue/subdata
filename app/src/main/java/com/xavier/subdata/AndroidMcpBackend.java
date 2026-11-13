package com.xavier.subdata;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用真机能力实现 {@link McpBackend}：白名单走 {@link TargetStore}，
 * 目标 App 的目录全走 {@link TargetRepo}（内部是 {@code su}）。
 *
 * <p>刻意做成一个薄适配层：所有判断都在 {@link McpTools} 里，这里只负责「怎么读到/写到」。
 */
public final class AndroidMcpBackend implements McpBackend {

    private final Context ctx;
    private final TargetStore store;

    public AndroidMcpBackend(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.store = new TargetStore(this.ctx);
    }

    @Override
    public String versionName() {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    @Override
    public int versionCode() {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return (int) pi.getLongVersionCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String androidRelease() {
        try {
            return android.os.Build.VERSION.RELEASE;
        } catch (Throwable t) {
            return "?";
        }
    }

    @Override
    public boolean rootOk() {
        return RootShell.available();
    }

    @Override
    public List<String> targets() {
        return store.targets();
    }

    @Override
    public boolean isWhitelisted(String pkg) {
        return store.isWhitelisted(pkg);
    }

    @Override
    public void addTarget(String pkg) {
        List<String> all = new ArrayList<>(store.targets());
        if (!all.contains(pkg)) {
            all.add(pkg);
            store.setTargets(all);
        }
    }

    @Override
    public void removeTarget(String pkg) {
        List<String> all = new ArrayList<>(store.targets());
        all.remove(pkg);
        store.setTargets(all);
    }

    @Override
    public String activeTarget() {
        return store.activeTarget();
    }

    @Override
    public boolean appInstalled(String pkg) {
        return TargetRepo.appInstalled(pkg);
    }

    @Override
    public String currentSlot(String pkg) {
        return TargetRepo.readSlot(pkg);
    }

    @Override
    public Map<String, String> conf(String pkg) {
        return TargetRepo.readConf(pkg);
    }

    @Override
    public List<String> slots(String pkg) {
        return TargetRepo.listSlots(pkg);
    }

    @Override
    public Map<String, Long> slotSizes(String pkg) {
        return TargetRepo.slotSizes(pkg);
    }

    @Override
    public boolean createSlot(String pkg, String slot) {
        return TargetRepo.createSlot(pkg, slot);
    }

    @Override
    public boolean switchSlot(String pkg, String slot) {
        return TargetRepo.switchAndStop(pkg, slot);
    }

    @Override
    public boolean deleteSlot(String pkg, String slot) {
        return TargetRepo.deleteSlot(pkg, slot);
    }

    @Override
    public String logTail(String pkg, int lines) {
        return TargetRepo.readLogTail(pkg, lines);
    }

    @Override
    public String diagnosticsReport(String pkg) {
        Diagnostics.Facts f = new Diagnostics.Facts();
        f.versionName = versionName();
        f.versionCode = versionCode();
        f.androidRelease = androidRelease();
        f.pkg = pkg;
        f.rootOk = rootOk();
        f.slot = currentSlot(pkg);
        f.conf = conf(pkg);
        f.slots = slots(pkg);
        f.sizes = slotSizes(pkg);
        f.issues = HealthCheck.inspect(f.conf, f.slot, f.slots);
        f.log = TargetRepo.readLog(pkg);
        return Diagnostics.buildReport(f);
    }

    @Override
    public String saveDiagnostics(String pkg, String content) {
        if (content == null) {
            return null;
        }
        try {
            File dir = ctx.getExternalFilesDir("diagnostics");
            if (dir == null) {
                dir = new File(ctx.getFilesDir(), "diagnostics");
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            File out = new File(dir, Diagnostics.fileName(System.currentTimeMillis()));
            try (Writer w = new OutputStreamWriter(new FileOutputStream(out, false),
                    StandardCharsets.UTF_8)) {
                w.write(content);
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Map<String, Object> mcpStatus() {
        return McpManager.statusMap(ctx);
    }

    /** 给界面用：诊断文件目录（不存在时创建）。 */
    public static File diagnosticsDir(Context ctx) {
        File dir = ctx.getExternalFilesDir("diagnostics");
        if (dir == null) {
            dir = new File(ctx.getFilesDir(), "diagnostics");
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** 未使用的占位，避免误删导入；保持 Map 导入有用。 */
    static Map<String, Object> emptyMap() {
        return new LinkedHashMap<>();
    }
}
