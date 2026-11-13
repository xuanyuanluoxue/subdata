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
 * 外部存储（EXT）树克隆：把 {@code /storage/emulated/0/Android/data/<pkg>/} 克隆到
 * {@code <ext>/subdata/<slot>/}，与 CE 的 {@link DataCloner} 对齐。
 *
 * <p>为什么非做不可（真机实测）：Via 浏览器的壁纸放在
 * {@code /storage/emulated/0/Android/data/mark.via/files/content/}。CE 克隆只覆盖
 * {@code /data/data/<pkg>}，新槽的 EXT 只有空骨架 → 壁纸直接丢，
 * 上一轮是 Lead 手工拷了 83MB 才恢复。
 *
 * <p>为什么不能无脑全拷：EXT 树经常几百 MB 到几 GB（缓存、下载、WebView 数据）。
 * 在目标 App 的启动路径上同步拷几个 GB，等于首启卡死 / 被系统杀掉。
 * 所以有三道闸：
 * <ol>
 *   <li>体积闸：源树超过 {@value #MAX_BYTES_MB}MB 只建骨架，写跳过标记（不再重试），
 *       日志与 UI 都能看到原因；</li>
 *   <li>时间闸：一次克隆最多 {@value #MAX_MS}ms，超时就停下并记跳过；</li>
 *   <li>条目闸：最多 {@value #MAX_ENTRIES} 个条目，防海量小文件。</li>
 * </ol>
 *
 * <p>跳过是「有意的、可解释的」，用 {@link SlotState#skip(File, String, String)} 落盘，
 * 这样下次启动不会再卡一遍，也不会把半成品当成品。
 *
 * <p>代码跑在<b>目标 App 进程</b>里，EXT 目录的属主（{@code uid:ext_data_rw}）、
 * 权限（{@code 2770}）、SELinux 标签（{@code media_rw_data_file}）由系统在 mkdir 时自动给对，
 * 模块不需要、也不应该手工 chmod/chcon。
 *
 * <p>纯 {@code java.io}，可单测。
 */
public final class ExtCloner {

    /** 源树超过这个体积就只建骨架。 */
    public static final long MAX_BYTES_MB = 512L;
    public static final long MAX_BYTES = MAX_BYTES_MB * 1024L * 1024L;

    /** 单次克隆的时间上限（毫秒）。 */
    public static final long MAX_MS = 25_000L;

    /** 条目上限。 */
    public static final int MAX_ENTRIES = 20_000;

    /** EXT 骨架里必须有的目录（{@code getExternalFilesDir(null)} 就是 {@code files}）。 */
    public static final String[] EXT_DIRS = {"files", "cache", "no_backup"};

    /** 不克隆的顶层名字。 */
    private static final Set<String> SKIP = new HashSet<>();

    static {
        SKIP.add(PathMap.CONTROL_DIR); // 自身，防递归
        SKIP.add("cache");
        SKIP.add("code_cache");
        SKIP.add("no_backup");
        SKIP.add("lib");
    }

    /** 一次 ensure 的结果。 */
    public static final class Report {
        /** EXT 根存在且可用。 */
        public boolean applicable;
        /** none / already / skeleton / clone / skip。 */
        public String mode = "none";
        /** 进入时的状态名。 */
        public String phase = "NONE";
        /** 拷贝的文件数 / 目录数 / 字节数。 */
        public int files;
        public int dirs;
        public long bytes;
        public long ms;
        /** 跳过原因（mode=skip 时非空）。 */
        public String reason;
        /** 镜像出来的空目录数（骨架模式）。 */
        public int mirrored;

        public boolean cloned() {
            return "clone".equals(mode);
        }

        public boolean skipped() {
            return "skip".equals(mode);
        }

        /** 是否需要写日志。 */
        public boolean noteworthy() {
            return !"none".equals(mode) && !"already".equals(mode);
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("ext:").append(mode).append(" from=").append(phase);
            if ("clone".equals(mode)) {
                sb.append(" files=").append(files).append(" dirs=").append(dirs)
                        .append(" ").append(bytes / 1024 / 1024).append("MB")
                        .append(" in ").append(ms).append("ms");
            } else if ("skeleton".equals(mode)) {
                sb.append(" mirrored=").append(mirrored);
            } else if ("skip".equals(mode)) {
                sb.append(" reason=").append(reason);
            }
            return sb.toString();
        }
    }

    private ExtCloner() {
    }

    public static Report ensure(File extRoot, String slot, boolean clone) {
        return ensure(extRoot, slot, clone, MAX_BYTES, MAX_MS);
    }

    /**
     * @param extRoot 外部存储上的 App 根目录，{@code .../Android/data/<pkg>}
     * @param slot    槽名
     * @param clone   是否要做全量克隆（{@code clone_on_first_run}）
     * @param maxBytes 体积上限
     * @param maxMs    时间上限
     */
    public static Report ensure(File extRoot, String slot, boolean clone,
                                long maxBytes, long maxMs) {
        Report r = new Report();
        final String s = SlotConfig.sanitize(slot);
        if (extRoot == null || s == null || !extRoot.isDirectory()) {
            return r;
        }
        r.applicable = true;

        final File slotDir = SlotState.slotDir(extRoot, s);
        final SlotState.Phase phase = SlotState.phase(extRoot, s);
        r.phase = phase.name();

        if (phase == SlotState.Phase.CLONED) {
            r.mode = "already";
            return r;
        }
        if (phase == SlotState.Phase.SKIPPED) {
            r.mode = "skip";
            r.reason = SlotState.skipReason(extRoot, s);
            return r;
        }
        if (phase == SlotState.Phase.CLONING) {
            // 半成品（真机上就是 83MB 拷到一半被 kill）：清掉重做
            SlotState.recoverHalfCloned(extRoot, s);
            r.phase = "CLONING(recovered)";
        }

        if (!clone) {
            r.mirrored = ensureSkeleton(extRoot, slotDir);
            r.mode = "skeleton";
            return r;
        }

        long size = measure(extRoot, maxBytes);
        if (size > maxBytes) {
            r.mirrored = ensureSkeleton(extRoot, slotDir);
            r.reason = "source " + (size / 1024 / 1024) + "MB > limit "
                    + (maxBytes / 1024 / 1024) + "MB, skeleton only";
            SlotState.skip(extRoot, s, r.reason);
            r.mode = "skip";
            r.bytes = size;
            return r;
        }

        if (!slotDir.mkdirs() && !slotDir.isDirectory()) {
            r.reason = "mkdirs failed: " + slotDir;
            SlotState.skip(extRoot, s, r.reason);
            r.mode = "skip";
            return r;
        }

        SlotState.begin(extRoot, s);
        final long start = System.currentTimeMillis();
        final long deadline = start + Math.max(0L, maxMs);
        final int[] counters = {0, 0, 0}; // files, dirs, entries
        final boolean[] aborted = {false};
        File[] children = extRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (aborted[0]) {
                    break;
                }
                if (SKIP.contains(child.getName())) {
                    continue;
                }
                copyTree(child, new File(slotDir, child.getName()), counters, deadline, aborted);
            }
        }
        r.files = counters[0];
        r.dirs = counters[1];
        r.ms = System.currentTimeMillis() - start;

        if (aborted[0]) {
            // 超时 / 条目超限：不写 .cloned，落一个可解释的跳过标记，下次不再重复卡
            r.reason = (counters[2] >= MAX_ENTRIES
                    ? "too many entries (>=" + MAX_ENTRIES + ")"
                    : "timeout > " + maxMs + "ms")
                    + ", partial " + r.files + " files";
            SlotState.skip(extRoot, s, r.reason);
            r.mode = "skip";
            return r;
        }

        SlotState.complete(extRoot, s);
        r.mode = "clone";
        return r;
    }

    /** 只建骨架：标准目录 + 顶层目录结构镜像（每个槽一次，打 .skeleton 标记）。 */
    private static int ensureSkeleton(File extRoot, File slotDir) {
        if (!slotDir.isDirectory() && !slotDir.mkdirs() && !slotDir.isDirectory()) {
            return 0;
        }
        for (String d : EXT_DIRS) {
            File f = new File(slotDir, d);
            if (!f.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                f.mkdirs();
            }
        }
        File marker = new File(slotDir, SlotSkeleton.MARKER);
        if (marker.isFile()) {
            return 0;
        }
        int made = 0;
        File[] kids = extRoot.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                if (!kid.isDirectory() || SKIP.contains(kid.getName())) {
                    continue;
                }
                File target = new File(slotDir, kid.getName());
                if (!target.isDirectory() && target.mkdirs()) {
                    made++;
                }
            }
        }
        AtomicFiles.write(marker, "ext skeleton\n");
        return made;
    }

    /** 估算源树体积：超上限就提前返回（只关心「是否超」）。 */
    static long measure(File root, long cap) {
        long[] total = {0};
        measureWalk(root, total, cap, 0, new int[]{0});
        return total[0];
    }

    private static void measureWalk(File f, long[] total, long cap, int depth, int[] entries) {
        if (depth > 24 || total[0] > cap || entries[0] > MAX_ENTRIES) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            if (total[0] > cap || entries[0] > MAX_ENTRIES) {
                return;
            }
            entries[0]++;
            if (kid.isDirectory()) {
                // 顶层被排除的目录本来就不会拷，也不计入体积
                if (depth > 0 || !SKIP.contains(kid.getName())) {
                    measureWalk(kid, total, cap, depth + 1, entries);
                }
            } else if (kid.isFile()) {
                total[0] += kid.length();
            }
        }
    }

    private static void copyTree(File src, File dst, int[] counters, long deadline,
                                 boolean[] aborted) {
        if (counters[2] >= MAX_ENTRIES) {
            aborted[0] = true;
            return;
        }
        if (System.currentTimeMillis() >= deadline) {
            // maxMs=0 时这里恒成立，单测用它在不 sleep 的情况下造「超时」
            aborted[0] = true;
            return;
        }
        counters[2]++;
        try {
            if (src.isDirectory()) {
                if (!dst.isDirectory() && !dst.mkdirs() && !dst.isDirectory()) {
                    return;
                }
                counters[1]++;
                File[] kids = src.listFiles();
                if (kids != null) {
                    for (File kid : kids) {
                        if (aborted[0]) {
                            return;
                        }
                        copyTree(kid, new File(dst, kid.getName()), counters, deadline, aborted);
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
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException ignored) {
        }
    }
}
