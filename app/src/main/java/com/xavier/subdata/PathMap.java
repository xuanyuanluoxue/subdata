package com.xavier.subdata;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯路径映射逻辑，不依赖 Android / Xposed，可直接跑单元测试。
 *
 * <p>规则：凡是落在某个 base 之下的路径，都映射到 {@code <base>/subdata/<slot>/...}。
 * 控制目录 {@code <base>/subdata} 自身保持原样，这让映射天然幂等——
 * 对已经映射过的路径再调用一次 map() 不会叠加（避免 hook 互相踩踏）。</p>
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
}
