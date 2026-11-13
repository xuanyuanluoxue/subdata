package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 0.2.1 新增配置项：main_slot / island_notice。 */
public class SlotConfigExtTest {

    @Test
    public void load_defaultsIslandOnAndMainSlotA() throws IOException {
        File dir = tempDir("conf-default");
        try {
            SlotConfig cfg = SlotConfig.load(dir);
            assertEquals("a", cfg.slot);
            assertEquals("a", cfg.mainSlot);
            assertTrue("island_notice 默认开", cfg.islandNotice);
            assertTrue("默认就在主槽", cfg.isMainSlot());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void load_readsMainSlotAndIslandSwitch() throws IOException {
        File dir = tempDir("conf-read");
        try {
            writeConf(dir, "enabled=true\nslot=b\nmain_slot=a\nisland_notice=false\n");
            SlotConfig cfg = SlotConfig.load(dir);
            assertEquals("b", cfg.slot);
            assertEquals("a", cfg.mainSlot);
            assertFalse(cfg.islandNotice);
            assertFalse(cfg.isMainSlot());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void load_fallsBackWhenMainSlotIsJunk() throws IOException {
        File dir = tempDir("conf-junk");
        try {
            writeConf(dir, "slot=b\nmain_slot=../etc\n");
            SlotConfig cfg = SlotConfig.load(dir);
            assertEquals("a", cfg.mainSlot);
            assertTrue(cfg.islandNotice);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void isMainSlot_whenSlotFileWins() throws IOException {
        File dir = tempDir("conf-slotfile");
        try {
            writeConf(dir, "slot=b\nmain_slot=b\n");
            SlotConfig cfg = SlotConfig.load(dir);
            assertEquals("b", cfg.slot);
            assertTrue(cfg.isMainSlot());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void formatConf_writesNewKeysInStableOrder() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("island_notice", "false");
        kv.put("main_slot", "a");
        kv.put("slot", "b");
        kv.put("enabled", "true");
        assertEquals("enabled=true\nslot=b\nmain_slot=a\nisland_notice=false\n",
                SlotConfig.formatConf(kv));
    }

    @Test
    public void defaultConfFromUiMatchesModuleDefaults() throws IOException {
        Map<String, String> def = TargetRepo.defaultConf();
        assertEquals("a", def.get("main_slot"));
        assertEquals("true", def.get("island_notice"));
        SlotConfig cfg = SlotConfig.from(new File(System.getProperty("java.io.tmpdir"), "subdata-conf"), "a", def);
        assertTrue(cfg.islandNotice);
        assertTrue(cfg.isMainSlot());
    }

    // ---------------------------------------------------------------- 工具

    private static File tempDir(String name) throws IOException {
        File base = new File(System.getProperty("java.io.tmpdir"), "subdata-conf");
        deleteTree(new File(base, name));
        File d = new File(base, name);
        assertTrue("mkdirs " + d, d.mkdirs());
        return d;
    }

    private static void writeConf(File dataDir, String text) throws IOException {
        File control = new File(dataDir, PathMap.CONTROL_DIR);
        assertTrue(control.isDirectory() || control.mkdirs());
        try (FileOutputStream out = new FileOutputStream(
                new File(control, SlotConfig.CONF_FILE))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
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
