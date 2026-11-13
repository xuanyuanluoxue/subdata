package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** du 解析与体积格式化（纯 Java，不需要 root / 真机）。 */
public class SlotStatsTest {

    @Test
    public void parseDu_readsTabSeparatedDuOutput() {
        String out = "12480\t/data/data/com.chaoxing.mobile/subdata/a\n"
                + "96\t/data/data/com.chaoxing.mobile/subdata/b\n";
        Map<String, Long> m = SlotStats.parseDu(out);
        assertEquals(2, m.size());
        assertEquals(Long.valueOf(12480L * 1024L), m.get("a"));
        assertEquals(Long.valueOf(96L * 1024L), m.get("b"));
    }

    @Test
    public void parseDu_acceptsSpaceSeparatorAndTrailingSlash() {
        String out = "2048 /data/data/com.x/subdata/work/\n";
        Map<String, Long> m = SlotStats.parseDu(out);
        assertEquals(Long.valueOf(2048L * 1024L), m.get("work"));
    }

    @Test
    public void parseDu_skipsJunkAndControlFiles() {
        String out = "\n"
                + "not-a-number\t/data/data/com.x/subdata/a\n"
                + "12\t/data/data/com.x/subdata/subdata.conf\n"
                + "12\t/data/data/com.x/subdata/subdata.log\n"
                + "12\t/data/data/com.x/subdata/..\n"
                + "34\t/data/data/com.x/subdata/ok\n";
        Map<String, Long> m = SlotStats.parseDu(out);
        // 只剩合法的目录名 ok
        assertEquals(1, m.size());
        assertTrue(m.containsKey("ok"));
    }

    @Test
    public void parseDu_lastEntryWinsAndEmptyInputIsSafe() {
        String out = "100\t/data/data/com.x/subdata/a\n200\t/data/data/com.x/subdata/a\n";
        assertEquals(Long.valueOf(200L * 1024L), SlotStats.parseDu(out).get("a"));
        assertTrue(SlotStats.parseDu(null).isEmpty());
        assertTrue(SlotStats.parseDu("").isEmpty());
    }

    @Test
    public void formatSize_coversEveryUnitBoundary() {
        assertEquals("0 B", SlotStats.formatSize(0));
        assertEquals("1023 B", SlotStats.formatSize(1023));
        assertEquals("1 KB", SlotStats.formatSize(1024));
        assertEquals("1.0 MB", SlotStats.formatSize(1024L * 1024L));
        assertEquals("1.00 GB", SlotStats.formatSize(1024L * 1024L * 1024L));
        // 未知体积统一显示破折号，不用 0 冒充
        assertEquals("—", SlotStats.formatSize(-1));
    }

    @Test
    public void sizeOf_missingSlotIsMinusOne() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("a", 4096L);
        assertEquals(4096L, SlotStats.sizeOf(m, "a"));
        assertEquals(-1L, SlotStats.sizeOf(m, "zzz"));
        assertEquals(-1L, SlotStats.sizeOf(null, "a"));
        assertEquals(-1L, SlotStats.sizeOf(m, null));
    }

    @Test
    public void label_hidesPlaceholderWhenSizeUnknown() {
        assertEquals("a · 4 KB", SlotStats.label("a", 4096));
        assertEquals("b", SlotStats.label("b", -1));
    }
}
