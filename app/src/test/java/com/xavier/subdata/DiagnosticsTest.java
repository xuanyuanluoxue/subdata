package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 诊断报告拼装：只关心「内容该有的都在、缺字段不炸」。 */
public class DiagnosticsTest {

    private static Diagnostics.Facts fullFacts() {
        Diagnostics.Facts f = new Diagnostics.Facts();
        f.versionName = "0.3.0-beta";
        f.versionCode = 4;
        f.androidRelease = "17";
        f.pkg = "com.chaoxing.mobile";
        f.rootOk = true;
        f.slot = "a";
        Map<String, String> conf = new LinkedHashMap<>();
        conf.put("enabled", "true");
        conf.put("slot", "a");
        f.conf = conf;
        f.slots = Arrays.asList("a", "b");
        Map<String, Long> sizes = new LinkedHashMap<>();
        sizes.put("a", 12L * 1024L * 1024L);
        sizes.put("b", 0L);
        f.sizes = sizes;
        f.issues = Collections.singletonList(
                new HealthCheck.Issue(HealthCheck.Code.HOOKS_TOO_NARROW,
                        HealthCheck.Level.WARN, null));
        f.log = "11-13 10:00:00 installed slot=a hooks=20\n";
        return f;
    }

    @Test
    public void reportContainsEverySection() {
        String r = Diagnostics.buildReport(fullFacts());
        assertTrue(r, r.contains("# SubData 诊断报告"));
        assertTrue(r, r.contains("0.3.0-beta"));
        assertTrue(r, r.contains("com.chaoxing.mobile"));
        assertTrue(r, r.contains("## 配置 subdata.conf"));
        assertTrue(r, r.contains("enabled=true"));
        assertTrue(r, r.contains("## 槽目录与占用"));
        assertTrue(r, r.contains("12.0 MB"));
        assertTrue(r, r.contains("## 体检结论"));
        assertTrue(r, r.contains("HOOKS_TOO_NARROW"));
        assertTrue(r, r.contains("## subdata.log"));
        assertTrue(r, r.contains("installed slot=a hooks=20"));
        assertTrue("报告应以换行结尾", r.endsWith("\n"));
    }

    @Test
    public void nullFactsProduceReadablePlaceholder() {
        String r = Diagnostics.buildReport(null);
        assertNotNull(r);
        assertTrue(r.contains("没有可用信息"));
    }

    @Test
    public void emptyFieldsDegradeToUnknownInsteadOfCrashing() {
        String r = Diagnostics.buildReport(new Diagnostics.Facts());
        assertTrue(r, r.contains("未知"));
        assertTrue(r, r.contains("(空 / 未读取)"));
        assertTrue(r, r.contains("(没有槽目录)"));
        assertTrue(r, r.contains("(没有问题)"));
        assertTrue(r, r.contains("root 可用: 否"));
    }

    @Test
    public void logWithoutTrailingNewlineStillGetsOne() {
        Diagnostics.Facts f = new Diagnostics.Facts();
        f.log = "last line without newline";
        String r = Diagnostics.buildReport(f);
        assertTrue(r.endsWith("\n"));
        assertTrue(r.contains("last line without newline"));
    }

    @Test
    public void fileNameIsSortableAndHasTxtSuffix() {
        String n = Diagnostics.fileName(0L);
        assertTrue(n, n.matches("subdata-diag-\\d{8}-\\d{4}\\.txt"));
        assertTrue(Diagnostics.fileName(1793000000000L)
                .matches("subdata-diag-\\d{8}-\\d{4}\\.txt"));
    }

    @Test
    public void differentTimestampsYieldDistinctNames() {
        String a = Diagnostics.fileName(1793000000000L);
        String b = Diagnostics.fileName(1793000000000L + 3660_000L);
        assertFalse(a.equals(b));
    }
}
