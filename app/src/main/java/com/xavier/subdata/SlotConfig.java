package com.xavier.subdata;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 每个目标 App 自己的配置，放在 {@code /data/data/<pkg>/subdata/} 下。
 *
 * <pre>
 * subdata/slot          —— 一行，当前 slot 名（a / b / work ...）
 * subdata/subdata.conf  —— key=value，可选
 *      enabled=true
 *      clone_on_first_run=true
 *      redirect_external=true
 *      hook_file_paths=true
 *      hook_data_dir=true
 *      slot=a
 * subdata/subdata.log   —— 运行日志（本模块写入）
 * </pre>
 *
 * 放在 App 自己的数据目录里，好处是「App 自己的 uid 一定能读写」，
 * 不依赖模块的 SharedPreferences 能否被跨进程读取。
 */
public final class SlotConfig {

    public static final String DEFAULT_SLOT = "a";
    public static final String SLOT_FILE = "slot";
    public static final String CONF_FILE = "subdata.conf";
    public static final String LOG_FILE = "subdata.log";

    /** 只允许安全字符，且不能与控制目录同名。 */
    private static final Pattern SLOT_RE = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public final File controlDir;
    public final String slot;
    public final boolean enabled;
    public final boolean cloneOnFirstRun;
    public final boolean redirectExternal;
    public final boolean hookFilePaths;
    public final boolean hookDataDir;

    private SlotConfig(File controlDir, String slot, boolean enabled, boolean cloneOnFirstRun,
                       boolean redirectExternal, boolean hookFilePaths, boolean hookDataDir) {
        this.controlDir = controlDir;
        this.slot = slot;
        this.enabled = enabled;
        this.cloneOnFirstRun = cloneOnFirstRun;
        this.redirectExternal = redirectExternal;
        this.hookFilePaths = hookFilePaths;
        this.hookDataDir = hookDataDir;
    }

    public static SlotConfig load(String dataDir) {
        return load(new File(dataDir));
    }

    public static SlotConfig load(File dataDir) {
        final File controlDir = new File(dataDir, PathMap.CONTROL_DIR);
        ensureDir(controlDir);

        Map<String, String> conf = readConf(new File(controlDir, CONF_FILE));

        String slot = readSlot(new File(controlDir, SLOT_FILE));
        if (slot == null) {
            slot = sanitize(conf.get("slot"));
        }
        if (slot == null) {
            slot = DEFAULT_SLOT;
        }
        writeSlot(new File(controlDir, SLOT_FILE), slot);

        return new SlotConfig(
                controlDir,
                slot,
                bool(conf.get("enabled"), true),
                bool(conf.get("clone_on_first_run"), true),
                bool(conf.get("redirect_external"), true),
                bool(conf.get("hook_file_paths"), true),
                bool(conf.get("hook_data_dir"), true));
    }

    /** 非法 slot 名返回 null（绝不接受 "."、".."、"/" 之类）。 */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (!SLOT_RE.matcher(s).matches()) {
            return null;
        }
        if (PathMap.CONTROL_DIR.equals(s)) {
            return null;
        }
        return s;
    }

    static String readSlot(File f) {
        if (!f.isFile()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return sanitize(line);
        } catch (Throwable t) {
            return null;
        }
    }

    static void writeSlot(File f, String slot) {
        try {
            if (f.isFile()) {
                String cur = readSlot(f);
                if (slot.equals(cur)) {
                    return;
                }
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false),
                    StandardCharsets.UTF_8)) {
                w.write(slot);
                w.write('\n');
            }
        } catch (Throwable ignored) {
            // 只读环境或首次创建失败都不致命，退回默认 slot
        }
    }

    static Map<String, String> readConf(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!f.isFile()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                out.put(s.substring(0, eq).trim().toLowerCase(), s.substring(eq + 1).trim());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static boolean bool(String v, boolean def) {
        if (v == null) {
            return def;
        }
        v = v.trim().toLowerCase();
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "off".equals(v)) {
            return false;
        }
        return def;
    }

    static void ensureDir(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            // 交给调用方在读写时兜底
        }
    }
}
