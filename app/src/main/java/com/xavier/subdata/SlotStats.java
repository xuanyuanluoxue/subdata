package com.xavier.subdata;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 每个 slot 的数据占用统计（纯 Java，不依赖 Android / root，可直接单测）。
 *
 * <p>数据来自 root 执行 {@code du -sk ...} 的原始输出，形如：</p>
 * <pre>
 * 12480\t/data/data/com.chaoxing.mobile/subdata/a
 * 96\t/data/data/com.chaoxing.mobile/subdata/b
 * </pre>
 *
 * <p>这个类只做「解析 + 人类可读格式化」两件事，实际跑 du 由 {@link TargetRepo} 负责，
 * 这样解析逻辑可以在 JVM 里跑测试，不需要真机。</p>
 */
public final class SlotStats {

    private SlotStats() {
    }

    /**
     * 把 {@code du -sk} 的输出解析成 {@code slot -> 字节数}。
     *
     * <ul>
     *   <li>非法行（数字不是数字、路径取不出合法 slot）直接跳过，不抛异常；</li>
     *   <li>同一个 slot 出现多次时后者覆盖前者（du 偶尔会重复列同一目录）；</li>
     *   <li>返回类型保留插入顺序，界面按 root 的原始顺序渲染更稳定。</li>
     * </ul>
     */
    public static Map<String, Long> parseDu(String duOutput) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (duOutput == null || duOutput.isEmpty()) {
            return out;
        }
        for (String raw : duOutput.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            // du 用制表符分隔；某些 toybox 版本用空格，两种都接受
            int sep = line.indexOf('\t');
            if (sep < 0) {
                sep = line.indexOf(' ');
            }
            if (sep <= 0) {
                continue;
            }
            String num = line.substring(0, sep).trim();
            String path = line.substring(sep + 1).trim();
            long kb = parseLong(num, -1L);
            if (kb < 0 || path.isEmpty()) {
                continue;
            }
            path = stripTrailingSlash(path);
            String name = baseName(path);
            // 只认合法的 slot 名，挡住 subdata.conf / subdata.log 这类文件
            if (SlotConfig.sanitize(name) == null) {
                continue;
            }
            out.put(name, kb * 1024L);
        }
        return out;
    }

    /** 单个 slot 的体积；没统计到返回 -1（界面据此显示「未知」而不是 0）。 */
    public static long sizeOf(Map<String, Long> sizes, String slot) {
        if (sizes == null || slot == null) {
            return -1L;
        }
        Long v = sizes.get(slot);
        return v == null ? -1L : v;
    }

    /**
     * 人类可读体积。刻意不用 {@code String.format("%.1f")} 的本地化行为，
     * 统一用 {@link Locale#US}，避免某些 Locale 下小数点是逗号、界面变味。
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** {@code "a · 12.3 MB"}；体积未知时只显示 slot 名，不留丑陋的「—」。 */
    public static String label(String slot, long bytes) {
        String name = slot == null ? "" : slot;
        return bytes < 0 ? name : name + " · " + formatSize(bytes);
    }

    // ---------------------------------------------------------------- 内部

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stripTrailingSlash(String p) {
        int end = p.length();
        while (end > 1 && p.charAt(end - 1) == '/') {
            end--;
        }
        return p.substring(0, end);
    }

    private static String baseName(String p) {
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }
}
