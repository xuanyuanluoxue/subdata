package com.xavier.subdata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 界面对「目标 App 的 subdata 目录」的全部读写，走 {@link RootShell}。
 *
 * <p>这些操作只碰 {@code /data/data/<pkg>/subdata}，绝不触碰 App 的原始数据目录，
 * 因此任何一步失败都不会损坏用户数据。
 */
public final class TargetRepo {

    private TargetRepo() {
    }

    public static String dataDir(String pkg) {
        return "/data/data/" + pkg;
    }

    public static String controlDir(String pkg) {
        return dataDir(pkg) + "/" + PathMap.CONTROL_DIR;
    }

    public static String slotFile(String pkg) {
        return controlDir(pkg) + "/" + SlotConfig.SLOT_FILE;
    }

    public static String confFile(String pkg) {
        return controlDir(pkg) + "/" + SlotConfig.CONF_FILE;
    }

    public static String logFile(String pkg) {
        return controlDir(pkg) + "/" + SlotConfig.LOG_FILE;
    }

    /** 目标 App 是否真的装了。 */
    public static boolean appInstalled(String pkg) {
        return RootShell.run("test -d " + RootShell.shq(dataDir(pkg)) + " && echo yes").out
                .contains("yes");
    }

    /** 当前 slot；读不到返回 null（还没被注入过）。 */
    public static String readSlot(String pkg) {
        String s = RootShell.run("cat " + RootShell.shq(slotFile(pkg))).trim();
        return s.isEmpty() ? null : SlotConfig.sanitize(s);
    }

    public static Map<String, String> readConf(String pkg) {
        String text = RootShell.run("cat " + RootShell.shq(confFile(pkg))).out;
        return SlotConfig.parseConf(text);
    }

    public static boolean writeConf(String pkg, Map<String, String> kv) {
        mkdirs(pkg);
        if (!RootShell.writeFile(confFile(pkg), SlotConfig.formatConf(kv))) {
            return false;
        }
        RootShell.handOverToApp(pkg, confFile(pkg));
        return true;
    }

    public static boolean writeSlot(String pkg, String slot) {
        if (SlotConfig.sanitize(slot) == null) {
            return false;
        }
        mkdirs(pkg);
        if (!RootShell.writeFile(slotFile(pkg), slot + "\n")) {
            return false;
        }
        RootShell.handOverToApp(pkg, slotFile(pkg));
        return true;
    }

    /** subdata/ 下的 slot 目录列表。 */
    public static List<String> listSlots(String pkg) {
        List<String> out = new ArrayList<>();
        String text = RootShell.run("ls -1 " + RootShell.shq(controlDir(pkg)) + " 2>/dev/null").out;
        for (String line : text.split("\n")) {
            String name = line.trim();
            if (name.isEmpty()) {
                continue;
            }
            String ok = SlotConfig.sanitize(name);
            if (ok == null) {
                continue;
            }
            boolean isDir = RootShell.run("test -d " + RootShell.shq(controlDir(pkg) + "/" + ok)
                    + " && echo yes").out.contains("yes");
            if (isDir) {
                out.add(ok);
            }
        }
        return out;
    }

    public static boolean createSlot(String pkg, String slot) {
        String s = SlotConfig.sanitize(slot);
        if (s == null) {
            return false;
        }
        String dir = controlDir(pkg) + "/" + s;
        if (!RootShell.run("mkdir -p " + RootShell.shq(dir)).ok()) {
            return false;
        }
        RootShell.handOverToApp(pkg, dir, true);
        return true;
    }

    public static boolean renameSlot(String pkg, String from, String to) {
        String a = SlotConfig.sanitize(from);
        String b = SlotConfig.sanitize(to);
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        String src = controlDir(pkg) + "/" + a;
        String dst = controlDir(pkg) + "/" + b;
        if (RootShell.run("test -e " + RootShell.shq(dst) + " && echo yes").out.contains("yes")) {
            return false;
        }
        if (!RootShell.run("mv " + RootShell.shq(src) + " " + RootShell.shq(dst)).ok()) {
            return false;
        }
        if (a.equals(readSlot(pkg))) {
            writeSlot(pkg, b);
        }
        return true;
    }

    /** 日志尾部 n 行；没有文件返回空串。 */
    public static String readLogTail(String pkg, int lines) {
        return RootShell.run("tail -n " + lines + " " + RootShell.shq(logFile(pkg)) + " 2>/dev/null",
                10).out;
    }

    public static String readLog(String pkg) {
        return RootShell.run("cat " + RootShell.shq(logFile(pkg)) + " 2>/dev/null", 15).out;
    }

    public static boolean clearLog(String pkg) {
        return RootShell.run("rm -f " + RootShell.shq(logFile(pkg))).ok();
    }

    /** 危险操作：只删 control 目录（副本 + 日志 + 配置），不碰原始数据。 */
    public static boolean deleteControlDir(String pkg) {
        String dir = controlDir(pkg);
        // 双重校验：路径必须以 /data/data/<pkg>/subdata 结尾，防止拼错删到别处
        if (!dir.equals("/data/data/" + pkg + "/" + PathMap.CONTROL_DIR)) {
            return false;
        }
        return RootShell.run("rm -rf " + RootShell.shq(dir), 30).ok();
    }

    public static boolean forceStop(String pkg) {
        return RootShell.run("am force-stop " + RootShell.shq(pkg), 15).ok();
    }

    /**
     * 从日志里挑出最后一条 "installed slot=… hooks=…"，即最近一次真正装上的记录。
     */
    public static String lastInstallLine(String log) {
        String found = null;
        for (String line : log.split("\n")) {
            if (line.contains("installed slot=") || line.contains("disabled by")) {
                found = line.trim();
            }
        }
        return found;
    }

    /** 默认配置：与模块内 {@link SlotConfig} 的缺省值保持一致。 */
    public static Map<String, String> defaultConf() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("enabled", "true");
        m.put("clone_on_first_run", "true");
        m.put("redirect_external", "true");
        m.put("hook_file_paths", "true");
        m.put("hook_data_dir", "true");
        m.put("main_slot", SlotConfig.DEFAULT_MAIN_SLOT);
        m.put("island_notice", "true");
        return m;
    }

    private static void mkdirs(String pkg) {
        if (RootShell.run("mkdir -p " + RootShell.shq(controlDir(pkg))).ok()) {
            RootShell.handOverToApp(pkg, controlDir(pkg), true);
        }
    }
}
