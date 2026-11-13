package com.xavier.subdata;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块自己的 SharedPreferences：目标应用白名单 + 界面当前选中的目标。
 *
 * <p>与给 hook 读的 {@code XSharedPreferences} 共用同一个 prefs 文件（{@link ModuleMeta#PREFS_NAME}），
 * {@code targets} 的格式保持「换行分隔」，兼容老版本写入的内容。
 *
 * <p>v0.3.1 起不再引用 {@link SubDataModule} 的静态成员：那个类 implements 了 Xposed 接口，
 * 而 Xposed API 是 compileOnly，模块 App 的 APK 里没有这些类 —— 界面/广播接收器一旦
 * 引用它就有 {@code NoClassDefFoundError} 的风险。常量统一走 {@link ModuleMeta}。
 *
 * <p>注意：本类跑在<b>模块 App 进程</b>；目标进程读同一份文件要用
 * {@code XSharedPreferences} + manifest 里的 {@code xposedsharedprefs} meta-data。
 */
public final class TargetStore {

    private static final String KEY_TARGETS = "targets";
    private static final String KEY_ACTIVE = "active_target";

    private final SharedPreferences prefs;

    public TargetStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(ModuleMeta.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<String> targets() {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString(KEY_TARGETS, ModuleMeta.DEFAULT_TARGETS);
        if (raw == null) {
            return out;
        }
        for (String s : raw.split("[,;\\s]+")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public void setTargets(List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String s : pkgs) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        prefs.edit().putString(KEY_TARGETS, sb.toString()).apply();
    }

    /** 界面操作对象：优先用显式选中的那个，否则取白名单第一个。 */
    public String activeTarget() {
        List<String> all = targets();
        if (all.isEmpty()) {
            return null;
        }
        String act = prefs.getString(KEY_ACTIVE, null);
        if (act != null && all.contains(act)) {
            return act;
        }
        return all.get(0);
    }

    public void setActiveTarget(String pkg) {
        prefs.edit().putString(KEY_ACTIVE, pkg).apply();
    }

    /**
     * 是否在白名单里。
     *
     * <p>给广播接收器做发送方校验用；也顺带兼容「prefs 里写了但界面没刷新」的情况——
     * 每次都重新读 prefs，不做进程内缓存。
     */
    public boolean isWhitelisted(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        return targets().contains(pkg);
    }
}
