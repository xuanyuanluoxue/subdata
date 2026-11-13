package com.xavier.subdata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 配置体检：把「配置 + 现有槽目录」比对一遍，提前指出会崩或会静默不生效的组合。
 *
 * <p>纯 Java，不依赖 Android / root，界面侧把 {@link Issue#code} 翻成文案即可。
 * 之所以单独抽出来，是因为这类判断最容易写错又最难在真机上复现——
 * 放进单测里跑，比让用户在手机上试错便宜得多。</p>
 *
 * <p>典型场景：{@code clone_on_first_run=false} 又切到一个还没有目录的槽，
 * 模块启动时不会建目录，App 一读自己的数据目录就可能崩。体检能在切换之前就警告。</p>
 */
public final class HealthCheck {

    private HealthCheck() {
    }

    /** 问题严重度。 */
    public enum Level {
        /** 正常信息，不影响使用。 */
        INFO,
        /** 会导致功能不符合预期，建议处理。 */
        WARN,
        /** 很可能让目标 App 崩溃或数据错乱，切之前必须处理。 */
        DANGER,
    }

    /** 稳定的问题码，界面按它取文案；新增码只追加不改名。 */
    public enum Code {
        /** 当前槽还没有数据目录，且没开自动克隆 —— 目标 App 可能起不来。 */
        SLOT_DIR_MISSING_NO_CLONE,
        /** 当前槽还没有数据目录，但开了自动克隆 —— 首次启动会复制一份。 */
        SLOT_DIR_MISSING_WILL_CLONE,
        /** 模块对该 App 完全停用。 */
        MODULE_DISABLED,
        /** 两个兜底 hook 都关了，只剩 ContextImpl 的几个方法。 */
        HOOKS_TOO_NARROW,
        /** 一个槽目录都没有。 */
        NO_SLOTS,
        /** 开了外部存储重定向，外部缓存会翻倍。 */
        EXTERNAL_DOUBLED,
        /** 配置里没有任何可识别的键（首次使用）。 */
        CONF_EMPTY,
        /** slot 与 subdata.conf 里的 slot= 不一致，以 slot 文件为准。 */
        SLOT_CONFLICT,
    }

    /** 一条体检结论。 */
    public static final class Issue {
        public final Code code;
        public final Level level;
        /** 关联的 slot 名，没有则为 null。 */
        public final String slot;

        Issue(Code code, Level level, String slot) {
            this.code = code;
            this.level = level;
            this.slot = slot;
        }

        @Override
        public String toString() {
            return level + "/" + code + (slot == null ? "" : "(" + slot + ")");
        }
    }

    /**
     * 跑一遍体检。
     *
     * @param conf      {@code subdata.conf} 解析后的键值，可为 null
     * @param slot      当前生效的 slot 名，可为 null（未知）
     * @param slotDirs  {@code subdata/} 下已有的槽目录名，可为 null
     * @return 按严重度从高到低排好的问题列表，没有问题返回空列表
     */
    public static List<Issue> inspect(Map<String, String> conf, String slot,
                                      Collection<String> slotDirs) {
        List<Issue> out = new ArrayList<>();
        final boolean hasConf = conf != null && !conf.isEmpty();
        final boolean enabled = SlotConfig.truthy(get(conf, "enabled"), true);
        final boolean clone = SlotConfig.truthy(get(conf, "clone_on_first_run"), true);
        final boolean hookFiles = SlotConfig.truthy(get(conf, "hook_file_paths"), true);
        final boolean hookDataDir = SlotConfig.truthy(get(conf, "hook_data_dir"), true);
        final boolean external = SlotConfig.truthy(get(conf, "redirect_external"), true);

        final List<String> dirs = new ArrayList<>();
        if (slotDirs != null) {
            for (String d : slotDirs) {
                String ok = SlotConfig.sanitize(d);
                if (ok != null) {
                    dirs.add(ok);
                }
            }
        }

        if (!hasConf) {
            out.add(new Issue(Code.CONF_EMPTY, Level.INFO, null));
        }
        if (!enabled) {
            out.add(new Issue(Code.MODULE_DISABLED, Level.INFO, null));
        }
        if (!hookDataDir && !hookFiles) {
            out.add(new Issue(Code.HOOKS_TOO_NARROW, Level.WARN, null));
        }
        if (external) {
            out.add(new Issue(Code.EXTERNAL_DOUBLED, Level.INFO, null));
        }
        if (dirs.isEmpty()) {
            out.add(new Issue(Code.NO_SLOTS, Level.WARN, null));
        }

        final String cur = SlotConfig.sanitize(slot);
        if (cur != null && !dirs.isEmpty() && !dirs.contains(cur)) {
            // 有别的槽、偏偏当前这个没有目录：最危险的一种
            out.add(clone
                    ? new Issue(Code.SLOT_DIR_MISSING_WILL_CLONE, Level.INFO, cur)
                    : new Issue(Code.SLOT_DIR_MISSING_NO_CLONE, Level.DANGER, cur));
        } else if (cur != null && dirs.isEmpty()) {
            // 一个槽都没有：等同上一种，但等级按 clone 开关走
            out.removeIf(i -> i.code == Code.NO_SLOTS);
            out.add(clone
                    ? new Issue(Code.SLOT_DIR_MISSING_WILL_CLONE, Level.INFO, cur)
                    : new Issue(Code.SLOT_DIR_MISSING_NO_CLONE, Level.DANGER, cur));
        }

        // conf 里的 slot= 与 slot 文件不一致：以 slot 文件为准，但提示一句
        String confSlot = SlotConfig.sanitize(get(conf, "slot"));
        if (cur != null && confSlot != null && !confSlot.equals(cur)) {
            out.add(new Issue(Code.SLOT_CONFLICT, Level.INFO, cur));
        }

        sort(out);
        return out;
    }

    /** 最高严重度；没有任何问题返回 null。 */
    public static Level worst(List<Issue> issues) {
        Level w = null;
        if (issues != null) {
            for (Issue i : issues) {
                if (w == null || i.level.ordinal() > w.ordinal()) {
                    w = i.level;
                }
            }
        }
        return w;
    }

    /** 是否存在 DANGER 级问题。 */
    public static boolean hasDanger(List<Issue> issues) {
        return worst(issues) == Level.DANGER;
    }

    /** DANGER 排最前，其次 WARN，最后 INFO；同级保持插入顺序（稳定排序）。 */
    private static void sort(List<Issue> list) {
        list.sort((a, b) -> Integer.compare(b.level.ordinal(), a.level.ordinal()));
    }

    private static String get(Map<String, String> m, String key) {
        return m == null ? null : m.get(key);
    }
}
