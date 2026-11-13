package com.xavier.subdata;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块自己的 SharedPreferences：目标应用白名单 + 界面当前选中的目标。
 *
 * <p>与给 hook 读的 {@link SubDataModule} 共用同一个 prefs 文件，
 * {@code targets} 的格式保持「换行分隔」，兼容老版本写入的内容。
 */
public final class TargetStore {

    private static final String KEY_TARGETS = "targets";
    private static final String KEY_ACTIVE = "active_target";

    private final SharedPreferences prefs;

    public TargetStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(SubDataModule.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<String> targets() {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString(KEY_TARGETS, SubDataModule.DEFAULT_TARGETS);
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
}
