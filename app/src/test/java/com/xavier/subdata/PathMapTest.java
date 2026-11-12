package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class PathMapTest {

    private static final String PKG = "com.chaoxing.mobile";
    private static final String DATA = "/data/data/" + PKG;
    private static final String USER0 = "/data/user/0/" + PKG;
    private static final String EXT = "/storage/emulated/0/Android/data/" + PKG;

    private PathMap map(String slot) {
        List<String> bases = Arrays.asList(DATA, USER0, EXT);
        return new PathMap(slot, bases);
    }

    @Test
    public void mapsRootExactly() {
        assertEquals(DATA + "/subdata/a", map("a").map(DATA));
        assertEquals(USER0 + "/subdata/a", map("a").map(USER0));
    }

    @Test
    public void mapsChildPaths() {
        PathMap m = map("a");
        assertEquals(DATA + "/subdata/a/files/x.db", m.map(DATA + "/files/x.db"));
        assertEquals(DATA + "/subdata/a/shared_prefs/p.xml",
                m.map(DATA + "/shared_prefs/p.xml"));
        assertEquals(EXT + "/subdata/a/files/pic.jpg", m.map(EXT + "/files/pic.jpg"));
    }

    @Test
    public void controlDirIsUntouched() {
        PathMap m = map("a");
        assertEquals(DATA + "/subdata", m.map(DATA + "/subdata"));
        assertEquals(DATA + "/subdata/a/files/x", m.map(DATA + "/subdata/a/files/x"));
        assertEquals(DATA + "/subdata/slot", m.map(DATA + "/subdata/slot"));
        assertTrue(m.isControlPath(DATA + "/subdata/a/files/x"));
        assertTrue(m.isControlPath(DATA + "/subdata"));
        assertFalse(m.isControlPath(DATA + "/files/x"));
    }

    @Test
    public void mappingIsIdempotent() {
        PathMap m = map("b");
        String once = m.map(DATA + "/databases/app.db");
        assertEquals(DATA + "/subdata/b/databases/app.db", once);
        assertEquals(once, m.map(once));
        assertEquals(once, m.map(m.map(m.map(DATA + "/databases/app.db"))));
    }

    @Test
    public void doesNotMatchSiblingPackageWithCommonPrefix() {
        PathMap m = map("a");
        String sibling = DATA + "2/files/x";
        assertEquals(sibling, m.map(sibling));
        assertEquals("/data/data/com.other.app/files/x",
                m.map("/data/data/com.other.app/files/x"));
    }

    @Test
    public void leavesUnrelatedPathsAlone() {
        PathMap m = map("a");
        assertEquals("/sdcard/Download/a.txt", m.map("/sdcard/Download/a.txt"));
        assertEquals("/data/app/~~abc==/com.chaoxing.mobile-1/base.apk",
                m.map("/data/app/~~abc==/com.chaoxing.mobile-1/base.apk"));
        assertEquals("", m.map(""));
        assertNull(m.map(null));
    }

    @Test
    public void doesNotMatchNonSeparatorBoundary() {
        PathMap m = map("a");
        // /data/data/com.chaoxing.mobile.bak 不是同一个 App
        String bak = DATA + ".bak/files/x";
        assertEquals(bak, m.map(bak));
    }

    @Test
    public void mapFileKeepsInstanceWhenUnchanged() {
        PathMap m = map("a");
        File untouched = new File("/sdcard/Download/a.txt");
        assertTrue(untouched == m.mapFile(untouched));

        File mapped = m.mapFile(new File(DATA + "/files/x"));
        assertEquals(DATA + "/subdata/a/files/x", mapped.getPath());
        assertNotNull(mapped);
    }

    @Test
    public void mapsArrays() {
        PathMap m = map("a");
        File[] in = {new File(DATA + "/files/x"), new File("/tmp/y")};
        File[] out = m.mapFiles(in);
        assertEquals(DATA + "/subdata/a/files/x", out[0].getPath());
        assertEquals("/tmp/y", out[1].getPath());
    }

    @Test
    public void slotRootExposed() {
        assertEquals(DATA + "/subdata/work", map("work").slotRoot());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptySlot() {
        new PathMap("", Arrays.asList(DATA));
    }

    @Test
    public void sanitizeSlot() {
        assertEquals("a", SlotConfig.sanitize("a"));
        assertEquals("work_2", SlotConfig.sanitize(" work_2 \n"));
        assertNull(SlotConfig.sanitize(""));
        assertNull(SlotConfig.sanitize(".."));
        assertNull(SlotConfig.sanitize("a/b"));
        assertNull(SlotConfig.sanitize("subdata"));
        assertNull(SlotConfig.sanitize("中文"));
    }

    @Test
    public void slotConfigRoundTrip() throws Exception {
        File tmp = File.createTempFile("subdata", "dir");
        assertTrue(tmp.delete());
        assertTrue(tmp.mkdirs());
        try {
            SlotConfig cfg = SlotConfig.load(tmp);
            assertEquals("a", cfg.slot);
            assertTrue(cfg.enabled);
            assertTrue(cfg.cloneOnFirstRun);
            assertTrue(cfg.redirectExternal);
            assertTrue(cfg.hookFilePaths);
            assertTrue(cfg.hookDataDir);
            assertTrue(new File(cfg.controlDir, "slot").isFile());

            // 手工改成 b 再读
            SlotConfig.writeSlot(new File(new File(tmp, "subdata"), "slot"), "b");
            assertEquals("b", SlotConfig.load(tmp).slot);
        } finally {
            deleteTree(tmp);
        }
    }

    private static void deleteTree(File f) {
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
