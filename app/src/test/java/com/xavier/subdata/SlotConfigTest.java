package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 界面写回配置 + CLI 扫描用的纯逻辑测试（不依赖 Android）。 */
public class SlotConfigTest {

    @Test
    public void parseConf_ignoresCommentsAndJunk() {
        String text = "# comment\n\nenabled=false\n  clone_on_first_run = false \r\n"
                + "badline\n=novalue\nHook_Data_Dir=no\n";
        Map<String, String> kv = SlotConfig.parseConf(text);
        assertEquals("false", kv.get("enabled"));
        assertEquals("false", kv.get("clone_on_first_run"));
        assertEquals("no", kv.get("hook_data_dir"));
        // 非法行不产生条目
        assertEquals(3, kv.size());
    }

    @Test
    public void parseConf_nullOrEmpty() {
        assertTrue(SlotConfig.parseConf(null).isEmpty());
        assertTrue(SlotConfig.parseConf("").isEmpty());
    }

    @Test
    public void formatConf_keepsStableOrderAndExtras() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("extra_key", "42");
        kv.put("hook_data_dir", "false");
        kv.put("enabled", "true");
        kv.put("slot", "work");
        String out = SlotConfig.formatConf(kv);
        assertEquals("enabled=true\nhook_data_dir=false\nslot=work\nextra_key=42\n", out);
    }

    @Test
    public void formatThenParse_isLossless() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("enabled", "false");
        kv.put("clone_on_first_run", "true");
        kv.put("redirect_external", "off");
        Map<String, String> back = SlotConfig.parseConf(SlotConfig.formatConf(kv));
        assertEquals(kv, back);
    }

    @Test
    public void truthy_handlesAllSpellings() {
        assertTrue(SlotConfig.truthy("true", false));
        assertTrue(SlotConfig.truthy("1", false));
        assertTrue(SlotConfig.truthy("YES", false));
        assertTrue(SlotConfig.truthy("on", false));
        assertFalse(SlotConfig.truthy("false", true));
        assertFalse(SlotConfig.truthy("0", true));
        // 无法识别 -> 用默认值
        assertTrue(SlotConfig.truthy("maybe", true));
        assertFalse(SlotConfig.truthy("maybe", false));
        assertTrue(SlotConfig.truthy(null, true));
    }

    @Test
    public void sanitize_rejectsTraversalAndBadNames() {
        assertNull(SlotConfig.sanitize(".."));
        assertNull(SlotConfig.sanitize("a/b"));
        assertNull(SlotConfig.sanitize("subdata"));
        assertNull(SlotConfig.sanitize(""));
        assertNull(SlotConfig.sanitize("中文slot"));
        assertNull(SlotConfig.sanitize("a".repeat(33)));
        assertEquals("work-1", SlotConfig.sanitize(" work-1 "));
    }

    @Test
    public void scan_realTree_mapsEveryEntryAndStaysIdempotent() throws IOException {
        File root = new File("build/tmp/scan-app");
        deleteTree(root);
        File files = new File(root, "files");
        File cache = new File(root, "cache");
        assertTrue(files.mkdirs());
        assertTrue(cache.mkdirs());
        write(new File(files, "prefs.xml"), "x");
        write(new File(cache, "a.tmp"), "y");

        PathMap map = new PathMap("b", java.util.Collections.singletonList(root.getPath()));
        PathMap.Scan s = map.scan(root, 1000);

        assertTrue("should be clean: " + s.missed + s.escaped + s.notIdempotent, s.clean());
        assertTrue("files should be seen", s.files >= 2);
        assertTrue("dirs should be seen", s.dirs >= 3);
        // 根目录自己 + 子项都应该被重定向
        assertTrue(s.redirected >= 5);
        assertEquals(0, s.missed.size());
        // 映射结果必须落在 <root>/subdata/b 之下
        String mapped = map.map(new File(files, "prefs.xml").getPath());
        assertTrue(mapped, mapped.startsWith(root.getPath() + "/subdata/b/"));
    }

    @Test
    public void scan_controlDirIsPassthroughNotMissed() throws IOException {
        File root = new File("build/tmp/scan-control");
        deleteTree(root);
        File slot = new File(root, "subdata/b/files");
        assertTrue(slot.mkdirs());
        write(new File(slot, "x"), "1");

        PathMap map = new PathMap("b", java.util.Collections.singletonList(root.getPath()));
        PathMap.Scan s = map.scan(root, 1000);
        assertTrue("control dir must not be reported as missed: " + s.missed, s.clean());
    }

    @Test
    public void cli_argsAreParseable() {
        // main() 会 System.exit，这里只验证参数解析用的基础方法
        PathMap map = new PathMap("a", java.util.Arrays.asList("/data/data/com.x"));
        assertNotNull(map.slotRoot());
        assertEquals("/data/data/com.x/subdata/a", map.slotRoot());
    }

    private static void write(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
