package com.xavier.subdata;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * 首次切到某个 slot 时，从默认数据目录（CE）克隆一份冷数据。
 *
 * <p>v0.3.1 起接入 {@link SlotState}：克隆前写 {@code .cloning-<slot>}，成功才写
 * {@code .cloned-<slot>}；下次启动看到 {@code .cloning} 残留就知道上次没做完，
 * 直接清掉重做，绝不把半成品当成品。
 *
 * <p>只做一次性 best-effort 拷贝：单个文件失败不影响其它文件。
 */
public final class DataCloner {

    /** 这些目录不克隆：控制目录自身、native 库、缓存。 */
    private static final Set<String> SKIP = new HashSet<>();

    static {
        SKIP.add(PathMap.CONTROL_DIR);
        SKIP.add("lib");
        SKIP.add("cache");
        SKIP.add("code_cache");
        SKIP.add("no_backup");
    }

    private DataCloner() {
    }

    /**
     * 确保 slot 目录存在且已完整克隆。
     *
     * @return true 表示这次真的执行了克隆
     */
    public static boolean ensureSlot(File dataDir, String slot, StringBuilder log) {
        final String s = SlotConfig.sanitize(slot);
        if (dataDir == null || s == null) {
            return false;
        }
        final File controlDir = SlotState.controlDir(dataDir);
        final File dst = new File(controlDir, s);

        final SlotState.Phase phase = SlotState.phase(dataDir, s);
        if (phase == SlotState.Phase.CLONED) {
            return false;
        }
        if (phase == SlotState.Phase.SKIPPED) {
            append(log, "slot " + s + " was skipped (" + SlotState.skipReason(dataDir, s) + "), keep");
            return false;
        }
        if (phase == SlotState.Phase.CLONING) {
            // 上次克隆没做完：这个槽从来没有「完成」过，清掉重做
            SlotState.recoverHalfCloned(dataDir, s);
            append(log, "recover half-cloned slot " + s + " (wiped, recloning)");
        } else if (dst.isDirectory()) {
            // 目录已经在但不带 .cloning：0.3.0 及更早建的槽（可能是骨架槽），
            // 不能盲目重拷，补标记后当成品。
            SlotState.complete(dataDir, s);
            append(log, "slot " + s + " exists without marker, adopt as cloned");
            return false;
        }

        if (!dst.mkdirs() && !dst.isDirectory()) {
            append(log, "mkdirs failed: " + dst);
            return false;
        }
        SlotState.begin(dataDir, s);
        long start = System.currentTimeMillis();
        int[] counters = new int[2];
        File[] children = dataDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (SKIP.contains(child.getName())) {
                    continue;
                }
                copyTree(child, new File(dst, child.getName()), counters);
            }
        }
        SlotState.complete(dataDir, s);
        append(log, "cloned " + counters[0] + " files, " + counters[1] + " dirs into " + dst
                + " in " + (System.currentTimeMillis() - start) + "ms");
        return true;
    }

    private static void copyTree(File src, File dst, int[] counters) {
        try {
            if (src.isDirectory()) {
                if (!dst.isDirectory() && !dst.mkdirs() && !dst.isDirectory()) {
                    return;
                }
                counters[1]++;
                File[] kids = src.listFiles();
                if (kids != null) {
                    for (File kid : kids) {
                        copyTree(kid, new File(dst, kid.getName()), counters);
                    }
                }
            } else if (src.isFile()) {
                copyFile(src, dst);
                counters[0]++;
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static void copyFile(File src, File dst) {
        if (dst.exists() && dst.length() == src.length()) {
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return;
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void append(StringBuilder sb, String s) {
        if (sb == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(s);
    }
}
