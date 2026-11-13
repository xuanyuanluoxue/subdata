package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * {@link ExtCloner}：EXT 树克隆 / 骨架 / 三道闸 / 半成品自愈。
 *
 * <p>背景：真机上 Via 的壁纸在 {@code Android/data/mark.via/files/content/}，
 * v0.3.0 只克隆 CE，换槽后壁纸丢，上一轮手工拷了 83MB 才恢复。
 */
public class ExtClonerTest {

    @Test
    public void notApplicable_whenExtRootMissing() throws IOException {
        File root = new File("/definitely/not/exists/subdata-test");
        ExtCloner.Report r = ExtCloner.ensure(root, "b", true);
        assertFalse(r.applicable);
        assertEquals("none", r.mode);
        assertFalse(r.noteworthy());
    }

    @Test
    public void notApplicable_whenSlotInvalid() throws IOException {
        File ext = SlotStateTest.tempDir("ext-badslot");
        try {
            ExtCloner.Report r = ExtCloner.ensure(ext, "../x", true);
            assertFalse(r.applicable);
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void clone_copiesTreeAndMarksCloned() throws IOException {
        File ext = SlotStateTest.tempDir("ext-clone");
        try {
            SlotStateTest.write(new File(ext, "files/content/wallpaper.jpg"), "img-bytes");
            SlotStateTest.write(new File(ext, "files/sub/deeper/a.txt"), "a");
            new File(ext, "shared_prefs").mkdirs(); // 顶层目录也要跟着建

            ExtCloner.Report r = ExtCloner.ensure(ext, "b", true);

            assertTrue(r.summary(), r.cloned());
            assertEquals(2, r.files);
            File slot = new File(ext, "subdata/b");
            assertEquals("img-bytes", read(new File(slot, "files/content/wallpaper.jpg")));
            assertEquals("a", read(new File(slot, "files/sub/deeper/a.txt")));
            assertTrue("顶层目录要一起克隆", new File(slot, "shared_prefs").isDirectory());
            assertTrue(SlotState.clonedMarker(ext, "b").isFile());
            assertFalse(SlotState.cloningMarker(ext, "b").isFile());
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void clone_excludesCacheAndControlDir() throws IOException {
        File ext = SlotStateTest.tempDir("ext-exclude");
        try {
            SlotStateTest.write(new File(ext, "files/keep.dat"), "keep");
            SlotStateTest.write(new File(ext, "cache/big.bin"), "xxxx");
            SlotStateTest.write(new File(ext, "code_cache/cc.bin"), "cc");
            // subdata 自身必须排除，否则克隆会把自己拷进自己
            SlotStateTest.write(new File(ext, "subdata/other/old.bin"), "old");

            ExtCloner.Report r = ExtCloner.ensure(ext, "b", true);

            assertTrue(r.cloned());
            File slot = new File(ext, "subdata/b");
            assertTrue(new File(slot, "files/keep.dat").isFile());
            assertFalse(new File(slot, "cache").isDirectory());
            assertFalse(new File(slot, "code_cache").isDirectory());
            assertFalse("不能递归拷贝控制目录", new File(slot, "subdata").isDirectory());
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void skip_whenSourceExceedsLimit() throws IOException {
        File ext = SlotStateTest.tempDir("ext-toobig");
        try {
            // 每个文件 1KB，共 8KB；上限给 2KB
            for (int i = 0; i < 8; i++) {
                SlotStateTest.write(new File(ext, "files/f" + i + ".bin"), repeat('x', 1024));
            }

            ExtCloner.Report r = ExtCloner.ensure(ext, "b", true, 2048L, 10_000L);

            assertTrue(r.summary(), r.skipped());
            assertNotNull(r.reason);
            assertTrue(r.reason, r.reason.contains("skeleton"));
            // 只建骨架，不拷数据
            File slot = new File(ext, "subdata/b");
            assertTrue(new File(slot, "files").isDirectory());
            assertFalse(new File(slot, "files/f0.bin").isFile());
            assertEquals(SlotState.Phase.SKIPPED, SlotState.phase(ext, "b"));
            assertFalse(SlotState.cloningMarker(ext, "b").isFile());
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void skip_isStickyAndNotRetried() throws IOException {
        File ext = SlotStateTest.tempDir("ext-sticky");
        try {
            SlotStateTest.write(new File(ext, "files/a.bin"), repeat('y', 4096));
            ExtCloner.Report first = ExtCloner.ensure(ext, "b", true, 1024L, 10_000L);
            assertTrue(first.skipped());

            ExtCloner.Report second = ExtCloner.ensure(ext, "b", true, 1024L, 10_000L);
            assertTrue(second.skipped());
            assertEquals(first.reason, second.reason);
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void timeout_marksSkippedInsteadOfCloned() throws IOException {
        File ext = SlotStateTest.tempDir("ext-timeout");
        try {
            SlotStateTest.write(new File(ext, "files/a.bin"), "a");
            // maxMs=0：一进拷贝循环就超时，模拟真机被 kill 前的状态
            ExtCloner.Report r = ExtCloner.ensure(ext, "b", true, ExtCloner.MAX_BYTES, 0L);

            assertTrue(r.summary(), r.skipped());
            assertTrue(r.reason, r.reason.contains("timeout"));
            assertEquals(SlotState.Phase.SKIPPED, SlotState.phase(ext, "b"));
            // 跳过 ≠ 完成：绝不能写 .cloned
            assertFalse(SlotState.clonedMarker(ext, "b").isFile());
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void skeleton_whenCloneDisabled() throws IOException {
        File ext = SlotStateTest.tempDir("ext-skeleton");
        try {
            new File(ext, "files").mkdirs();
            new File(ext, "databases").mkdirs();

            ExtCloner.Report r = ExtCloner.ensure(ext, "b", false);

            assertEquals("skeleton", r.mode);
            File slot = new File(ext, "subdata/b");
            for (String d : ExtCloner.EXT_DIRS) {
                assertTrue("缺 " + d, new File(slot, d).isDirectory());
            }
            assertTrue("顶层结构镜像", new File(slot, "databases").isDirectory());
            assertTrue(new File(slot, SlotSkeleton.MARKER).isFile());

            // 第二次不再重复镜像
            ExtCloner.Report again = ExtCloner.ensure(ext, "b", false);
            assertEquals("skeleton", again.mode);
            assertEquals(0, again.mirrored);
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void already_whenClonedBefore() throws IOException {
        File ext = SlotStateTest.tempDir("ext-already");
        try {
            SlotStateTest.write(new File(ext, "files/a.bin"), "a");
            assertTrue(ExtCloner.ensure(ext, "b", true).cloned());

            ExtCloner.Report r = ExtCloner.ensure(ext, "b", true);

            assertEquals("already", r.mode);
            assertFalse(r.noteworthy());
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void halfCloned_residueIsWipedAndRecloned() throws IOException {
        File ext = SlotStateTest.tempDir("ext-recover");
        try {
            SlotStateTest.write(new File(ext, "files/all.bin"), "all");
            // 造一个「拷到一半被 kill」的现场
            SlotState.begin(ext, "b");
            SlotStateTest.write(new File(ext, "subdata/b/files/all.bin"), "al");

            ExtCloner.Report r = ExtCloner.ensure(ext, "b", true);

            assertTrue(r.summary(), r.cloned());
            assertEquals("CLONING(recovered)", r.phase);
            assertEquals("all", read(new File(ext, "subdata/b/files/all.bin")));
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    @Test
    public void measure_ignoresExcludedTopDirs() throws IOException {
        File ext = SlotStateTest.tempDir("ext-measure");
        try {
            SlotStateTest.write(new File(ext, "files/keep.bin"), repeat('k', 100));
            SlotStateTest.write(new File(ext, "cache/huge.bin"), repeat('h', 5000));

            long size = ExtCloner.measure(ext, 10_000L);

            assertTrue("只算要拷的部分, got " + size, size < 2000);
            assertTrue(size >= 100);
        } finally {
            SlotStateTest.deleteTree(ext);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
