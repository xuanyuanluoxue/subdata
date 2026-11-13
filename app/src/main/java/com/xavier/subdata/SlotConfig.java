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
 *      main_slot=a        —— 主槽，非主槽时会在超级岛提示
 *      island_notice=true —— 非主槽时发超级岛提示
 * subdata/subdata.log   —— 运行日志（本模块写入）
 * </pre>
 *
 * 放在 App 自己的数据目录里，好处是「App 自己的 uid 一定能读写」，
 * 不依赖模块的 SharedPreferences 能否被跨进程读取。
 */
public final class SlotConfig {

    public static final String DEFAULT_SLOT = "a";
    /** 主槽：与它相同的 slot 视为「原始数据」，不发超级岛提示。 */
    public static final String DEFAULT_MAIN_SLOT = "a";
    public static final String SLOT_FILE = "slot";
    public static final String CONF_FILE = "subdata.conf";
    public static final String LOG_FILE = "subdata.log";

    /** 写回文件时的稳定顺序，方便人看和 diff。 */
    private static final String[] CONF_ORDER = {
            "enabled", "clone_on_first_run", "redirect_external",
            "hook_file_paths", "hook_data_dir", "slot", "main_slot", "island_notice",
    };

    /** 只允许安全字符，且不能与控制目录同名。 */
    private static final Pattern SLOT_RE = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public final File controlDir;
    public final String slot;
    public final boolean enabled;
    public final boolean cloneOnFirstRun;
    public final boolean redirectExternal;
    public final boolean hookFilePaths;
    public final boolean hookDataDir;
    /** 主槽名，与 {@link #slot} 相同表示没在用副本。 */
    public final String mainSlot;
    /** 非主槽时是否在小米超级岛提示。 */
    public final boolean islandNotice;

    private SlotConfig(File controlDir, String slot, boolean enabled, boolean cloneOnFirstRun,
                       boolean redirectExternal, boolean hookFilePaths, boolean hookDataDir,
                       String mainSlot, boolean islandNotice) {
        this.controlDir = controlDir;
        this.slot = slot;
        this.enabled = enabled;
        this.cloneOnFirstRun = cloneOnFirstRun;
        this.redirectExternal = redirectExternal;
        this.hookFilePaths = hookFilePaths;
        this.hookDataDir = hookDataDir;
        this.mainSlot = mainSlot;
        this.islandNotice = islandNotice;
    }

    /** 当前是否就是主槽（主槽不发超级岛提示）。 */
    public boolean isMainSlot() {
        return mainSlot.equals(slot);
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

        return from(controlDir, slot, conf);
    }

    /**
     * 从「已经解析好的 conf」构造配置，供 {@link #load(File)} 与单元测试共用，
     * 保证界面里看到的默认值和模块里跑的默认值是同一套。
     *
     * @param slot 已经确定下来的 slot；传 null 则从 conf 里取
     */
    static SlotConfig from(File controlDir, String slot, Map<String, String> conf) {
        final Map<String, String> c = conf == null ? new LinkedHashMap<String, String>() : conf;
        String s = slot;
        if (s == null) {
            s = sanitize(c.get("slot"));
        }
        if (s == null) {
            s = DEFAULT_SLOT;
        }
        String mainSlot = sanitize(c.get("main_slot"));
        if (mainSlot == null) {
            mainSlot = DEFAULT_MAIN_SLOT;
        }
        return new SlotConfig(
                controlDir,
                s,
                truthy(c.get("enabled"), true),
                truthy(c.get("clone_on_first_run"), true),
                truthy(c.get("redirect_external"), true),
                truthy(c.get("hook_file_paths"), true),
                truthy(c.get("hook_data_dir"), true),
                mainSlot,
                truthy(c.get("island_notice"), true));
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
        if (!f.isFile()) {
            return new LinkedHashMap<>();
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return parseConf(sb.toString());
        } catch (Throwable ignored) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析 key=value 文本。界面侧拿到的内容是 root 读出来的字符串，
     * 与文件读取共用同一套解析，避免两处规则漂移。
     */
    public static Map<String, String> parseConf(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String raw : text.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(s.substring(0, eq).trim().toLowerCase(), s.substring(eq + 1).trim());
        }
        return out;
    }

    /** 序列化成稳定顺序的 key=value，供界面写回。 */
    public static String formatConf(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        if (kv != null) {
            for (String key : CONF_ORDER) {
                String v = kv.get(key);
                if (v != null) {
                    sb.append(key).append('=').append(v.trim()).append('\n');
                }
            }
            for (Map.Entry<String, String> e : kv.entrySet()) {
                if (contains(CONF_ORDER, e.getKey()) || e.getValue() == null) {
                    continue;
                }
                sb.append(e.getKey()).append('=').append(e.getValue().trim()).append('\n');
            }
        }
        return sb.toString();
    }

    /** "true/1/yes/on" 视为开；无法识别时用默认值。 */
    public static boolean truthy(String v, boolean def) {
        return bool(v, def);
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

    private static boolean contains(String[] arr, String key) {
        for (String s : arr) {
            if (s.equals(key)) {
                return true;
            }
        }
        return false;
    }

    static void ensureDir(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            // 交给调用方在读写时兜底
        }
    }
}
