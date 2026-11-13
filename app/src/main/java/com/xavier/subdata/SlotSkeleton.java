package com.xavier.subdata;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * slot 目录骨架：让「不克隆」的 slot 也能被 App 正常打开。
 *
 * <p>历史问题（0.2.0 及以前）：只有 {@code clone_on_first_run=true} 的路径会建目录。
 * 关掉克隆后 {@code subdata/<slot>} 根本没被创建，App 一启动就炸：
 * <pre>
 * RuntimeException: Unable to get provider com.chaoxing.bookshelf.dao.BooksProviderMgr
 *   Caused by: SQLiteCantOpenDatabaseException: Cannot open database …
 *     '/data/user/0/com.chaoxing.mobile/subdata/b/databases/ssreader_db_sqlite.sqlite'
 *     Directory …/subdata/b/databases doesn't exist
 * W ContextImpl: Failed to ensure /data/user_de/0/com.chaoxing.mobile/subdata/a/cache
 * E SharedPreferencesImpl: Couldn't create directory for SharedPreferences file
 *     …/subdata/a/shared_prefs/move_to_de_records.xml
 * </pre>
 *
 * <p>这个类做三件事，全部幂等、全部 best-effort：
 * <ol>
 *   <li>在 CE 数据根下建一套标准目录（{@link #CE_DIRS}）；</li>
 *   <li>把原数据目录的<b>目录结构</b>镜像一份到 slot 里——只建目录、<b>不拷任何文件</b>，
 *       等价于人工 {@code mkdir} 复刻那 113 个目录；每个 slot 只做一次（打 {@code .skeleton} 标记）；</li>
 *   <li>在 DE 数据根（{@code /data/user_de/0/<pkg>}）下建同样的骨架（{@link #DE_DIRS}）。</li>
 * </ol>
 *
 * <p>权限按 CE 常规：目录 {@code 700}，属主就是目标 App 自己——模块注入后运行在
 * App 进程里，新建的目录天然属于 App uid，这里只把 group/other 的权限显式清掉。
 *
 * <p>纯 {@code java.io} / {@code java.nio.file}，不依赖 Android，可直接跑单元测试。
 */
public final class SlotSkeleton {

    /** CE（凭据加密）数据根下必须存在的子目录；缺一个 App 就可能崩在启动阶段。 */
    public static final String[] CE_DIRS = {
            "databases",
            "shared_prefs",
            "files",
            "cache",
            "code_cache",
            "no_backup",
            "app_webview",
            "app_UApm",
            "app_accs",
            "app_textures",
            "crashsdk",
            "db",
            ".cache",
    };

    /**
     * DE（设备加密）数据根下的骨架。规范只要求 cache / code_cache / shared_prefs，
     * 这里多带几个「App 真会用」的目录，成本几乎为零。
     */
    public static final String[] DE_DIRS = {
            "cache",
            "code_cache",
            "shared_prefs",
            "files",
            "databases",
            "no_backup",
    };

    /** 骨架做完的标记（放在 slot 根里，不参与映射）。 */
    static final String MARKER = ".skeleton";

    /** 镜像目录结构的上限，防止某些 App 有几十万个小目录时卡住启动。 */
    static final int MIRROR_LIMIT = 4096;

    /** 镜像的最大深度，防御软链环。 */
    static final int MIRROR_MAX_DEPTH = 8;

    /** 不镜像的名字：控制目录自己、native 库。 */
    private static final Set<String> SKIP_NAMES = new LinkedHashSet<>();

    static {
        SKIP_NAMES.add(PathMap.CONTROL_DIR);
        SKIP_NAMES.add("lib");
    }

    private SlotSkeleton() {
    }

    /** 一次 ensure 的结果，供日志与单测使用。 */
    public static final class Report {
        /** 新建的标准 CE 目录名。 */
        public final List<String> ceCreated = new ArrayList<>();
        /** 新建的标准 DE 目录名。 */
        public final List<String> deCreated = new ArrayList<>();
        /** 失败项（目录不可写、参数非法等）。 */
        public final List<String> failed = new ArrayList<>();
        /** 镜像出来的 CE 子目录数量（含嵌套）。 */
        public int ceMirrored;
        /** 镜像出来的 DE 子目录数量。 */
        public int deMirrored;
        /** 该 slot 之前没有任何骨架（首次初始化）。 */
        public boolean firstTime;

        public boolean clean() {
            return failed.isEmpty();
        }

        public int createdCount() {
            return ceCreated.size() + deCreated.size();
        }

        /** 有变化才值得写日志，避免每次冷启动刷一行。 */
        public boolean changed() {
            return firstTime || !ceCreated.isEmpty() || !deCreated.isEmpty()
                    || ceMirrored > 0 || deMirrored > 0 || !clean();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(firstTime ? "init" : "sync")
                    .append(" ce=+").append(ceCreated.size())
                    .append("/").append(ceMirrored)
                    .append(" de=+").append(deCreated.size())
                    .append("/").append(deMirrored);
            if (!failed.isEmpty()) {
                sb.append(" failed=").append(failed.size());
                int n = 0;
                for (String f : failed) {
                    sb.append(n++ == 0 ? " [" : ", ").append(f);
                    if (n >= 3) {
                        sb.append("…");
                        break;
                    }
                }
                sb.append(']');
            }
            return sb.toString();
        }
    }

    /**
     * 建好（或补齐）一个 slot 的目录骨架。
     *
     * @param ceDataDir CE 数据根，通常 {@code /data/data/<pkg>}，必填
     * @param deDataDir DE 数据根，通常 {@code /data/user_de/0/<pkg>}，可为 null
     * @param slot      slot 名，会过一遍 {@link SlotConfig#sanitize}
     */
    public static Report ensure(File ceDataDir, File deDataDir, String slot) {
        Report r = new Report();
        final String s = SlotConfig.sanitize(slot);
        if (ceDataDir == null || s == null) {
            r.failed.add("invalid args: dir=" + ceDataDir + " slot=" + slot);
            return r;
        }

        // ---- CE
        final File control = new File(ceDataDir, PathMap.CONTROL_DIR);
        mkdir(control, r, "control", null);
        final File ceSlot = new File(control, s);
        r.firstTime = !ceSlot.isDirectory();
        mkdir(ceSlot, r, "ce:" + s, null);
        ensureDirs(ceSlot, CE_DIRS, r.ceCreated, r, "ce:");

        // 只镜像目录结构：clone=false 的 slot 也要有 App 预期的那些目录。
        // 每个 slot 只扫一次（有 .skeleton 标记就跳过），避免每次冷启动都遍历整棵树。
        if (!new File(ceSlot, MARKER).isFile()) {
            r.ceMirrored = mirrorDirs(ceDataDir, ceSlot, MIRROR_LIMIT);
        }
        touch(new File(ceSlot, MARKER));

        // ---- DE
        if (deDataDir != null) {
            final File deControl = new File(deDataDir, PathMap.CONTROL_DIR);
            mkdir(deControl, r, "de-control", null);
            final File deSlot = new File(deControl, s);
            mkdir(deSlot, r, "de:" + s, null);
            ensureDirs(deSlot, DE_DIRS, r.deCreated, r, "de:");
            final File deMarker = new File(deSlot, MARKER);
            if (!deMarker.isFile()) {
                r.deMirrored = mirrorDirs(deDataDir, deSlot, MIRROR_LIMIT);
                touch(deMarker);
            }
        }
        return r;
    }

    /** 把一棵树的目录结构照着建成空目录（只建目录，不拷文件）。 */
    public static int mirrorDirs(File srcRoot, File dstRoot, int limit) {
        if (srcRoot == null || dstRoot == null || !srcRoot.isDirectory()) {
            return 0;
        }
        int[] budget = {limit};
        int[] made = {0};
        mirrorWalk(srcRoot, dstRoot, budget, made, 0);
        return made[0];
    }

    private static void mirrorWalk(File src, File dst, int[] budget, int[] made, int depth) {
        if (budget[0] <= 0 || depth > MIRROR_MAX_DEPTH) {
            return;
        }
        final File[] kids = src.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            if (budget[0] <= 0) {
                return;
            }
            if (!kid.isDirectory() || isSymlink(kid)) {
                continue;
            }
            final String name = kid.getName();
            if (name.isEmpty() || name.startsWith("..") || SKIP_NAMES.contains(name)) {
                continue;
            }
            final File target = new File(dst, name);
            if (!target.isDirectory()) {
                if (!target.mkdirs() && !target.isDirectory()) {
                    continue;
                }
                harden(target);
                budget[0]--;
                made[0]++;
            }
            mirrorWalk(kid, target, budget, made, depth + 1);
        }
    }

    private static void ensureDirs(File root, String[] names, List<String> created, Report r,
                                   String prefix) {
        for (String n : names) {
            mkdir(new File(root, n), r, prefix + n, created);
        }
    }

    /**
     * 建目录 + 收权限。已经存在就什么都不做（幂等，冷启动只花几次 stat）。
     */
    private static void mkdir(File dir, Report r, String label, List<String> createdSink) {
        if (dir.isDirectory()) {
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            r.failed.add(label);
            return;
        }
        harden(dir);
        if (createdSink != null) {
            createdSink.add(label);
        }
    }

    /**
     * 目录降到 700：owner 可读写执行，group / other 一律清空。
     * 属主不用动——模块跑在目标 App 进程里，uid 本来就是对的。
     */
    static void harden(File dir) {
        try {
            dir.setReadable(false, false);
            dir.setWritable(false, false);
            dir.setExecutable(false, false);
            dir.setReadable(true, true);
            dir.setWritable(true, true);
            dir.setExecutable(true, true);
        } catch (Throwable ignored) {
            // 某些 FUSE / 只读挂载上会抛，权限降不下来也不该影响启动
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void touch(File f) {
        try {
            if (!f.isFile() && !f.createNewFile()) {
                return;
            }
            // 标记文件不是数据，放宽到 600 即可
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (Throwable ignored) {
            // 打个标记而已，失败不影响功能
        }
    }
}
