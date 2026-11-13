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

/**
 * {@link SlotState} 的状态机与半成品自愈。
 *
 * <p>这些用例对应真机上出过的事故：EXT 树拷到一半被系统杀掉，槽目录里一半文件、
 * 没有任何标记，下次启动直接被当成品用（壁纸丢失）。状态机必须保证
 * 「有 .cloning 残留就永远不算成品」。
 */
public class SlotStateTest {

    @Test
    public void phase_noneWhenNothing() throws IOException {
        File base = tempDir("state-none");
        try {
            assertEquals(SlotState.Phase.NONE, SlotState.phase(base, "a"));
            assertNull(SlotState.skipReason(base, "a"));
        } finally {
            deleteTree(base);
        }
    }

    @Test
    public void begin_thenCloning() throws IOException {
        File base = tempDir("state-begin");
        try {
            SlotState.begin(base, "b");
            assertEquals(SlotState.Phase.CLONING, SlotState.phase(base, "b"));
            assertTrue(SlotState.cloningMarker(base, "b").isFile());
            assertFalse(SlotState.clonedMarker(base, "b").isFile());
        } finally {
            deleteTree(base);
        }
    }

    @Test
    public void complete_clearsCloningAndWritesBothMarkers() throws IOException {
        File base = tempDir("state-complete");
        try {
            SlotState.begin(base, "b");
            new File(base, "subdata/b").mkdirs();
            SlotState.complete(base, "b");

            assertEquals(SlotState.Phase.CLONED, SlotState.phase(base, "b"));
            assertFalse("cloning 标记必须删掉", SlotState.cloningMarker(base, "b").isFile());
            assertTrue(SlotState.clonedMarker(base, "b").isFile());
            // 0.3.0 的旧标记也补上，回退老版本时不会被再克隆一次
            assertTrue(new File(base, "subdata/b/.cloned").isFile());
        } finally {
            deleteTree(base);
        }
    }

    @Test
    public void skip_recordsReasonAndBeatsNothing() throws IOException {
        File base = tempDir("state-skip");
        try {
            SlotState.begin(base, "c");
            SlotState.skip(base, "c", "source 900MB > limit 512MB");
            assertEquals(SlotState.Phase.SKIPPED, SlotState.phase(base, "c"));
            assertEquals("source 900MB > limit 512MB", SlotState.skipReason(base, "c"));
            assertFalse(SlotState.cloningMarker(base, "c").isFile());
        } finally {
            deleteTree(base);
        }
    }

    @Test
    public void legacyClonedMarkerInsideSlotIsHonoured() throws IOException {
        File base = tempDir("state-legacy");
        try {
            File slot = new File(base, "subdata/a");
            assertTrue(slot.mkdirs());
            assertTrue(new File(slot, ".cloned").createNewFile());
            assertEquals(SlotState.Phase.CLONED, SlotState.phase(base, "a"));
        } finally {
            deleteTree(base);
        }
    }

    @Test
    public void recoverHalfCloned_wipesPartialSlot() throws IOException {
        File base = tempDir("state-recover");
        try {
            SlotState.begin(base, "b");
            File slot = new File(base, "subdata/b");
            assertTrue(slot.mkdirs());
            write(new File(slot, "files/half.bin"), "0123456789");
            assertTrue("半成品目录存在", slot.isDirectory());

            assertTrue(SlotState.recoverHalfCloned(base, "b"));

            assertFalse("半成品必须被清掉", slot.isDirectory());
            assertEquals(SlotState.Phase.NONE, SlotState.phase(base, "b"));
        } finally {
            deleteTree(base);
        }
    }

    @Test
    public void recoverHalfCloned_noopWhenCloned() throws IOException {
        File base = tempDir("state-recover-noop");
        try {
            SlotState.begin(base, "a");
            File slot = new File(base, "subdata/a");
            assertTrue(slot.mkdirs());
            write(new File(slot, "keep.txt"), "keep");
            SlotState.complete(base, "a");

            assertFalse(SlotState.recoverHalfCloned(base, "a"));

            assertTrue("成品绝不能删", new File(slot, "keep.txt").isFile());
            assertEquals(SlotState.Phase.CLONED, SlotState.phase(base, "a"));
        } finally {
            deleteTree(base);
        }
    }

    @Test
    public void deleteTree_removesNestedContent() throws IOException {
        File base = tempDir("state-delete");
        try {
            write(new File(base, "subdata/b/files/x/y/z.bin"), "z");
            write(new File(base, "subdata/b/databases/db"), "d");

            int n = SlotState.deleteTree(new File(base, "subdata/b"));

            assertTrue("至少删掉文件+目录", n >= 4);
            assertFalse(new File(base, "subdata/b").exists());
        } finally {
            deleteTree(base);
        }
    }

    @Test
    public void invalidSlotIsIgnored() throws IOException {
        File base = tempDir("state-invalid");
        try {
            assertEquals(SlotState.Phase.NONE, SlotState.phase(base, "../etc"));
            SlotState.begin(base, "../etc");
            assertEquals(SlotState.Phase.NONE, SlotState.phase(base, "../etc"));
            assertNull(SlotState.skipReason(base, "../etc"));
        } finally {
            deleteTree(base);
        }
    }

    // ---------------------------------------------------------------- helpers

    static File tempDir(String tag) throws IOException {
        File f = File.createTempFile("subdata-" + tag + "-", "");
        if (!f.delete() || !f.mkdirs()) {
            throw new IOException("cannot create temp dir: " + f);
        }
        return f;
    }

    static void write(File f, String content) throws IOException {
        File parent = f.getParentFile();
        assertNotNull(parent);
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void deleteTree(File f) {
        SlotState.deleteTree(f);
    }
}
