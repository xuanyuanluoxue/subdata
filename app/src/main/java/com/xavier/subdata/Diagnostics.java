package com.xavier.subdata;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 诊断报告拼装：把「环境 + 配置 + 槽 + 体检结论 + 日志」汇成一份纯文本。
 *
 * <p>这里只做拼字符串（纯 Java、可单测）；真正落盘和分享由界面侧完成，
 * 这样报告格式的每一次改动都能在 JVM 里断言，不用装到真机上看。</p>
 *
 * <p>隐私：报告里只有包名、slot 名、配置和模块自己的日志，不读取任何账号内容。
 * 日志本身可能含有路径，发给别人前应自行过一眼。</p>
 */
public final class Diagnostics {

    /** 报告输入。字段都是可空的，缺什么就少显示一行，绝不抛异常。 */
    public static final class Facts {
        public String versionName;
        public int versionCode;
        public String androidRelease;
        public String pkg;
        public boolean rootOk;
        public String slot;
        public Map<String, String> conf;
        public List<String> slots;
        public Map<String, Long> sizes;
        public List<HealthCheck.Issue> issues;
        public String log;
    }

    private Diagnostics() {
    }

    /** 生成报告全文。保证以换行结尾，且任何时候都不抛异常。 */
    public static String buildReport(Facts f) {
        StringBuilder sb = new StringBuilder(1024);
        if (f == null) {
            return "# SubData 诊断报告\n(没有可用信息)\n";
        }
        line(sb, "# SubData 诊断报告");
        line(sb, "模块版本: " + nz(f.versionName) + " (" + (f.versionCode > 0
                ? String.valueOf(f.versionCode) : "?") + ")");
        line(sb, "Android: " + nz(f.androidRelease));
        line(sb, "目标包名: " + nz(f.pkg));
        line(sb, "root 可用: " + (f.rootOk ? "是" : "否"));
        line(sb, "当前 slot: " + nz(f.slot));

        line(sb, "");
        line(sb, "## 配置 subdata.conf");
        if (f.conf == null || f.conf.isEmpty()) {
            line(sb, "(空 / 未读取)");
        } else {
            String text = SlotConfig.formatConf(f.conf);
            if (text.isEmpty()) {
                line(sb, "(空)");
            } else {
                sb.append(text);
            }
        }

        line(sb, "");
        line(sb, "## 槽目录与占用");
        if (f.slots == null || f.slots.isEmpty()) {
            line(sb, "(没有槽目录)");
        } else {
            for (String s : f.slots) {
                long size = SlotStats.sizeOf(f.sizes, s);
                line(sb, "- " + SlotStats.label(s, size));
            }
        }

        line(sb, "");
        line(sb, "## 体检结论");
        if (f.issues == null || f.issues.isEmpty()) {
            line(sb, "(没有问题)");
        } else {
            for (HealthCheck.Issue i : f.issues) {
                line(sb, "- [" + i.level + "] " + i.code + (i.slot == null ? "" : " @ " + i.slot));
            }
        }

        line(sb, "");
        line(sb, "## subdata.log");
        if (f.log == null || f.log.trim().isEmpty()) {
            line(sb, "(空)");
        } else {
            sb.append(f.log);
            if (!f.log.endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** {@code subdata-diag-20261113-1120.txt}，用 UTC+本地时区避免跨时区看不懂。 */
    public static String fileName(long epochMillis) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);
        return "subdata-diag-" + fmt.format(new Date(epochMillis)) + ".txt";
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "未知" : s;
    }
}
