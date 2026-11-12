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
 * 首次切到某个 slot 时，从默认数据目录克隆一份冷数据。
 *
 * <p>只做一次性 best-effort 拷贝：单个文件失败不影响其它文件，
 * 拷贝完成会写 {@code .cloned} 标记，避免每次冷启动重复扫描。</p>
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

    private static final String MARKER = ".cloned";

    private DataCloner() {
    }

    /**
     * @return true 表示这次真的执行了克隆
     */
    public static boolean ensureSlot(File dataDir, String slot, StringBuilder log) {
        if (dataDir == null || slot == null || slot.isEmpty()) {
            return false;
        }
        File controlDir = new File(dataDir, PathMap.CONTROL_DIR);
        File dst = new File(controlDir, slot);
        if (dst.isDirectory()) {
            if (!new File(dst, MARKER).isFile()) {
                touch(new File(dst, MARKER));
            }
            return false;
        }
        if (!dst.mkdirs() && !dst.isDirectory()) {
            append(log, "mkdirs failed: " + dst);
            return false;
        }
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
        touch(new File(dst, MARKER));
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

    private static void touch(File f) {
        try {
            if (!f.isFile() && !f.createNewFile()) {
                return;
            }
        } catch (IOException ignored) {
            return;
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
