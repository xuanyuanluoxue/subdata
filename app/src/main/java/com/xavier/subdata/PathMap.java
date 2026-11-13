package com.xavier.subdata;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯路径映射逻辑，不依赖 Android / Xposed，可直接跑单元测试，也能用 app_process 当 CLI 跑。
 *
 * <p>规则：凡是落在某个 base 之下的路径，都映射到 {@code <base>/subdata/<slot>/...}。
 * 控制目录 {@code <base>/subdata} 自身保持原样，这让映射天然幂等——
 * 对已经映射过的路径再调用一次 map() 不会叠加（避免 hook 互相踩踏）。</p>
 *
 * <pre>
 * CLI（root 下扫一个真实 App 的目录树，看哪些漏了）：
 *   app_process -Djava.class.path=/data/local/tmp/pathmap.jar /system/bin \
 *       com.xavier.subdata.PathMap a /data/data/com.android.calculator2 --root /data/data/com.android.calculator2
 * </pre>
 */
public final class PathMap {

    /** 控制目录名（放在 App 默认数据目录下）。 */
    public static final String CONTROL_DIR = "subdata";

    private final String[] bases;
    private final String[] targets;

    /**
     * @param slot  数据槽名（a / b / work ...），调用方负责校验
     * @param bases 需要被重定向的根目录，例如 /data/data/com.chaoxing.mobile
     */
    public PathMap(String slot, List<String> bases) {
        if (slot == null || slot.isEmpty()) {
            throw new IllegalArgumentException("slot must not be empty");
        }
        List<String> bs = new ArrayList<>(bases.size());
        List<String> ts = new ArrayList<>(bases.size());
        for (String b : bases) {
            if (b == null || b.isEmpty()) {
                continue;
            }
            String base = trimSlash(b);
            if (bs.contains(base)) {
                continue;
            }
            bs.add(base);
            ts.add(base + "/" + CONTROL_DIR + "/" + slot);
        }
        this.bases = bs.toArray(new String[0]);
        this.targets = ts.toArray(new String[0]);
    }

    public int size() {
        return bases.length;
    }

    public String baseAt(int i) {
        return bases[i];
    }

    public String slotRootAt(int i) {
        return targets[i];
    }

    /** 主数据目录的 slot 根（第一个 base），供日志用。 */
    public String slotRoot() {
        return targets.length > 0 ? targets[0] : "";
    }

    /**
     * 把路径映射到 slot 下。不匹配任何 base 时原样返回。
     */
    public String map(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        for (int i = 0; i < bases.length; i++) {
            final String base = bases[i];
            if (!path.startsWith(base)) {
                continue;
            }
            final int bl = base.length();
            // /data/data/pkg 不能匹配 /data/data/pkg2
            if (path.length() > bl && path.charAt(bl) != '/') {
                continue;
            }
            final String rest = path.substring(bl);
            if (isControl(rest)) {
                return path;
            }
            return targets[i] + rest;
        }
        return path;
    }

    /** 该路径是否已经落在控制目录里（slot 目录、配置文件都不用再映射）。 */
    public boolean isControlPath(String path) {
        if (path == null) {
            return false;
        }
        for (String base : bases) {
            if (!path.startsWith(base)) {
                continue;
            }
            final int bl = base.length();
            if (path.length() > bl && path.charAt(bl) != '/') {
                continue;
            }
            if (isControl(path.substring(bl))) {
                return true;
            }
        }
        return false;
    }

    public File mapFile(File f) {
        if (f == null) {
            return null;
        }
        final String p = f.getPath();
        final String m = map(p);
        return m.equals(p) ? f : new File(m);
    }

    public File[] mapFiles(File[] arr) {
        if (arr == null) {
            return null;
        }
        File[] out = new File[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = mapFile(arr[i]);
        }
        return out;
    }

    private static boolean isControl(String rest) {
        // rest 为 "" 或者以 "/" 开头的剩余部分
        final String c = "/" + CONTROL_DIR;
        return rest.equals(c) || rest.startsWith(c + "/");
    }

    private static String trimSlash(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    // ================================================================== 扫描

    /**
     * 扫一棵真实目录树，统计「会被重定向 / 保持原样 / 漏了」的路径。
     * 纯 java.io，可在模拟器里用 root 直接跑，作为回归工具。
     */
    public static final class Scan {
        public int files;
        public int dirs;
        public int redirected;
        public int passthrough;
        /** 落在 base 里却没有被重定向的路径（应当为空）。 */
        public final List<String> missed = new ArrayList<>();
        /** 映射结果跑出 base/subdata 之外的路径（越界）。 */
        public final List<String> escaped = new ArrayList<>();
        /** map(map(p)) != map(p)，说明幂等性坏了。 */
        public final List<String> notIdempotent = new ArrayList<>();

        public boolean clean() {
            return missed.isEmpty() && escaped.isEmpty() && notIdempotent.isEmpty();
        }
    }

    public Scan scan(File root, int maxEntries) {
        Scan s = new Scan();
        walk(root, s, maxEntries, new int[]{0});
        return s;
    }

    private void walk(File f, Scan s, int maxEntries, int[] seen) {
        if (f == null || seen[0] >= maxEntries) {
            return;
        }
        seen[0]++;
        final String p = f.getPath();
        final String m = map(p);
        if (m.equals(p)) {
            s.passthrough++;
            if (!isControlPath(p)) {
                // 在 base 内却没被重定向
                s.missed.add(p);
            }
        } else {
            s.redirected++;
            String slotRoot = null;
            for (int i = 0; i < bases.length; i++) {
                if (p.startsWith(bases[i])) {
                    slotRoot = targets[i];
                    break;
                }
            }
            if (slotRoot == null || !(m.equals(slotRoot) || m.startsWith(slotRoot + "/"))) {
                s.escaped.add(p + " -> " + m);
            }
            if (!map(m).equals(m)) {
                s.notIdempotent.add(p);
            }
        }
        if (f.isDirectory()) {
            s.dirs++;
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    walk(kid, s, maxEntries, seen);
                }
            }
        } else {
            s.files++;
        }
    }

    // ================================================================== CLI

    /**
     * CLI 入口：{@code <slot> <base1,base2,...> [--root <dir>] [--max <n>] [--quiet]}
     * 退出码 0 = 全部符合预期；1 = 发现漏映射 / 越界 / 幂等性破坏。
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("usage: PathMap <slot> <base1[,base2...]> [--root DIR] [--max N] [--quiet]");
            System.exit(2);
            return;
        }
        final String slot = SlotConfig.sanitize(args[0]);
        if (slot == null) {
            System.out.println("FATAL invalid slot: " + args[0]);
            System.exit(2);
            return;
        }
        List<String> bases = new ArrayList<>();
        for (String b : args[1].split(",")) {
            if (!b.trim().isEmpty()) {
                bases.add(b.trim());
            }
        }
        String rootPath = bases.isEmpty() ? null : bases.get(0);
        int max = 20000;
        boolean quiet = false;
        for (int i = 2; i < args.length; i++) {
            if ("--root".equals(args[i]) && i + 1 < args.length) {
                rootPath = args[++i];
            } else if ("--max".equals(args[i]) && i + 1 < args.length) {
                max = Integer.parseInt(args[++i]);
            } else if ("--quiet".equals(args[i])) {
                quiet = true;
            }
        }

        PathMap map = new PathMap(slot, bases);
        System.out.println("slot=" + slot + " bases=" + map.size() + " root=" + rootPath);
        for (int i = 0; i < map.size(); i++) {
            System.out.println("  base[" + i + "] " + map.baseAt(i) + "  ->  " + map.slotRootAt(i));
        }

        if (rootPath != null) {
            File root = new File(rootPath);
            if (!root.isDirectory()) {
                System.out.println("FATAL root not readable: " + rootPath);
                System.exit(2);
                return;
            }
            Scan s = map.scan(root, max);
            System.out.println("scan: dirs=" + s.dirs + " files=" + s.files
                    + " redirected=" + s.redirected + " passthrough=" + s.passthrough
                    + " missed=" + s.missed.size() + " escaped=" + s.escaped.size()
                    + " notIdempotent=" + s.notIdempotent.size());
            if (!quiet) {
                dump("MISSED (should be redirected but was not)", s.missed);
                dump("ESCAPED (mapped outside slot root)", s.escaped);
                dump("NOT-IDEMPOTENT (map twice != once)", s.notIdempotent);
            }
            System.out.println(s.clean() ? "RESULT OK" : "RESULT FAIL");
            System.exit(s.clean() ? 0 : 1);
        }
        System.out.println("RESULT OK (no scan)");
    }

    private static void dump(String title, List<String> list) {
        if (list.isEmpty()) {
            return;
        }
        System.out.println("-- " + title + " (" + list.size() + ")");
        int n = 0;
        for (String p : list) {
            System.out.println("   " + p);
            if (++n >= 20) {
                System.out.println("   ... (" + (list.size() - n) + " more)");
                break;
            }
        }
    }
}
