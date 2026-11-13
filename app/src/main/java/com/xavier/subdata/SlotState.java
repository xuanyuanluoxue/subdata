package com.xavier.subdata;

import java.io.File;

/**
 * 槽的「生命周期标记」状态机 —— 半成品槽自愈的基础。
 *
 * <p>上一轮的事故：克隆一个 83MB 的 EXT 树时被系统杀掉，结果 {@code subdata/b/}
 * 里躺着一半文件，却没有任何标记说明它没弄完；下次启动直接把它当成品用，
 * 于是壁纸丢了、目录结构缺了，用户只能手工拷。
 *
 * <p>所以把「正在做 / 做完了 / 主动跳过」显式写进标记文件，状态全在磁盘上：
 * <pre>
 * &lt;base&gt;/subdata/.cloning-&lt;slot&gt;        克隆开始前写，完成后删
 * &lt;base&gt;/subdata/.cloned-&lt;slot&gt;         克隆完整成功
 * &lt;base&gt;/subdata/.clone-skipped-&lt;slot&gt;  主动跳过（超过体积上限 / 超时），文件里是原因
 * &lt;base&gt;/subdata/&lt;slot&gt;/.cloned         0.3.0 及以前的旧标记，仍然认
 * </pre>
 *
 * <p>判定优先级：{@code CLONING} 残留 &gt; {@code CLONED} &gt; {@code SKIPPED}。
 * 有 {@code .cloning} 残留就说明上次没做完 —— 由调用方决定重做（见
 * {@link #recoverHalfCloned(File, String)}），绝不把它当成品。
 *
 * <p>{@code base} 对 CE 是 {@code /data/data/<pkg>}，对 EXT 是
 * {@code /storage/emulated/0/Android/data/<pkg>}；两棵树共用同一套状态机。
 *
 * <p>纯 {@code java.io}，可单测。
 */
public final class SlotState {

    /** 克隆中。 */
    public static final String PREFIX_CLONING = ".cloning-";
    /** 克隆完成。 */
    public static final String PREFIX_CLONED = ".cloned-";
    /** 主动跳过。 */
    public static final String PREFIX_SKIPPED = ".clone-skipped-";

    /** 状态。 */
    public enum Phase {
        /** 没动过。 */
        NONE,
        /** 有 .cloning 残留：上次克隆没做完。 */
        CLONING,
        /** 已完成。 */
        CLONED,
        /** 主动跳过（超限），不再重试。 */
        SKIPPED,
    }

    private SlotState() {
    }

    public static File controlDir(File base) {
        return new File(base, PathMap.CONTROL_DIR);
    }

    public static File slotDir(File base, String slot) {
        return new File(controlDir(base), slot);
    }

    public static File marker(File base, String prefix, String slot) {
        return new File(controlDir(base), prefix + slot);
    }

    public static File cloningMarker(File base, String slot) {
        return marker(base, PREFIX_CLONING, slot);
    }

    public static File clonedMarker(File base, String slot) {
        return marker(base, PREFIX_CLONED, slot);
    }

    public static File skippedMarker(File base, String slot) {
        return marker(base, PREFIX_SKIPPED, slot);
    }

    /** 读状态；参数非法时返回 {@link Phase#NONE}。 */
    public static Phase phase(File base, String slot) {
        final String s = SlotConfig.sanitize(slot);
        if (base == null || s == null) {
            return Phase.NONE;
        }
        if (cloningMarker(base, s).isFile()) {
            return Phase.CLONING;
        }
        if (clonedMarker(base, s).isFile() || new File(slotDir(base, s), ".cloned").isFile()) {
            return Phase.CLONED;
        }
        if (skippedMarker(base, s).isFile()) {
            return Phase.SKIPPED;
        }
        return Phase.NONE;
    }

    /** 跳过原因（没有则返回 null）。 */
    public static String skipReason(File base, String slot) {
        final String s = SlotConfig.sanitize(slot);
        if (base == null || s == null) {
            return null;
        }
        File f = skippedMarker(base, s);
        if (!f.isFile()) {
            return null;
        }
        try {
            return readTrimmed(f);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 开始克隆：建控制目录，写 {@code .cloning-<slot>}，并清掉旧的跳过标记。 */
    public static void begin(File base, String slot) {
        final String s = SlotConfig.sanitize(slot);
        if (base == null || s == null) {
            return;
        }
        File control = controlDir(base);
        if (!control.isDirectory() && !control.mkdirs() && !control.isDirectory()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        skippedMarker(base, s).delete();
        AtomicFiles.write(cloningMarker(base, s), "cloning " + s + "\n");
    }

    /** 克隆成功：删 .cloning，写 .cloned（同时补 0.3.0 的旧标记，方便回退）。 */
    public static void complete(File base, String slot) {
        final String s = SlotConfig.sanitize(slot);
        if (base == null || s == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        cloningMarker(base, s).delete();
        //noinspection ResultOfMethodCallIgnored
        skippedMarker(base, s).delete();
        AtomicFiles.write(clonedMarker(base, s), "cloned " + s + "\n");
        File dir = slotDir(base, s);
        if (dir.isDirectory()) {
            AtomicFiles.write(new File(dir, ".cloned"), "");
        }
    }

    /** 主动跳过：删 .cloning，写原因。 */
    public static void skip(File base, String slot, String reason) {
        final String s = SlotConfig.sanitize(slot);
        if (base == null || s == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        cloningMarker(base, s).delete();
        AtomicFiles.write(skippedMarker(base, s), (reason == null ? "" : reason) + "\n");
    }

    /**
     * 半成品自愈：有 {@code .cloning} 残留就把该槽目录整个删掉，并重新写 {@code .cloning}。
     *
     * <p>为什么敢删：只有 {@code .cloning} 存在而 {@code .cloned} 不存在时才走这里，
     * 意味着这个槽从来没有「完成」过，里面的内容一定是拷贝的一半，
     * 留着比删掉更危险（会被当成成品，缺文件而且没人知道缺哪些）。
     *
     * @return true = 确实做过清理
     */
    public static boolean recoverHalfCloned(File base, String slot) {
        final String s = SlotConfig.sanitize(slot);
        if (base == null || s == null) {
            return false;
        }
        if (phase(base, s) != Phase.CLONING) {
            return false;
        }
        deleteTree(slotDir(base, s));
        // 标记也要清掉：调用方会重新 begin()，否则 phase 永远停在 CLONING
        //noinspection ResultOfMethodCallIgnored
        cloningMarker(base, s).delete();
        return true;
    }

    /**
     * 递归删除（不跟随软链）。返回删掉的条目数，纯 best-effort。
     *
     * <p>{@code File#delete()} 对目录只能删空目录，所以先序删子项再删自己。
     */
    public static int deleteTree(File f) {
        if (f == null) {
            return 0;
        }
        int[] n = {0};
        deleteWalk(f, n, 0);
        return n[0];
    }

    private static void deleteWalk(File f, int[] n, int depth) {
        if (depth > 32 || f == null) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                if (kid.isDirectory() && !isSymlink(kid)) {
                    deleteWalk(kid, n, depth + 1);
                } else if (kid.delete()) {
                    n[0]++;
                }
            }
        }
        if (f.delete()) {
            n[0]++;
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readTrimmed(File f) {
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 512)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r <= 0) {
                        break;
                    }
                    off += r;
                }
                return new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (Throwable t) {
            return "";
        }
    }
}
