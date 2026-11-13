package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * {@link SlotSkeleton} 的纯逻辑测试：不依赖 Android，直接跑 JVM。
 *
 * <p>覆盖 bug 1（clone 关掉时不建槽目录）和 bug 2（DE 树没建）。
 * 临时目录用系统 tmpdir（真 POSIX 文件系统），避免在 DrvFs 上测权限。
 */
public class SlotSkeletonTest {

    // ---------------------------------------------------------------- ce / de

    @Test
    public void ensure_createsStandardDirsWhenNotCloning() throws IOException {
        File ce = tempDir("skel-ce");
        try {
            // 模拟 clone_on_first_run=false：原目录里没有任何东西
            SlotSkeleton.Report r = SlotSkeleton.ensure(ce, null, "b");

            assertTrue("should be clean: " + r.failed, r.clean());
            assertTrue("first time", r.firstTime);
            File slotRoot = new File(ce, "subdata/b");
            assertTrue(slotRoot.isDirectory());
            for (String d : SlotSkeleton.CE_DIRS) {
                assertTrue("missing " + d, new File(slotRoot, d).isDirectory());
            }
            // databases 是这次崩溃的直接原因，单独再断言一次
            assertTrue(new File(slotRoot, "databases").isDirectory());
            assertTrue(new File(slotRoot, SlotSkeleton.MARKER).isFile());
            assertEquals(SlotSkeleton.CE_DIRS.length, r.ceCreated.size());
        } finally {
            deleteTree(ce);
        }
    }

    @Test
    public void ensure_createsDeSkeleton() throws IOException {
        File ce = tempDir("skel-de-ce");
        File de = tempDir("skel-de");
        try {
            SlotSkeleton.Report r = SlotSkeleton.ensure(ce, de, "a");
            assertTrue(r.clean());
            File deSlot = new File(de, "subdata/a");
            assertTrue(deSlot.isDirectory());
            // 需求点名要的三个
            assertTrue(new File(deSlot, "cache").isDirectory());
            assertTrue(new File(deSlot, "code_cache").isDirectory());
            assertTrue(new File(deSlot, "shared_prefs").isDirectory());
            for (String d : SlotSkeleton.DE_DIRS) {
                assertTrue("missing de/" + d, new File(deSlot, d).isDirectory());
            }
            assertEquals(SlotSkeleton.DE_DIRS.length, r.deCreated.size());
        } finally {
            deleteTree(ce);
            deleteTree(de);
        }
    }

    @Test
    public void ensure_toleratesMissingDeDir() throws IOException {
        File ce = tempDir("skel-node");
        try {
            SlotSkeleton.Report r = SlotSkeleton.ensure(ce, null, "work");
            assertTrue(r.clean());
            assertTrue(r.deCreated.isEmpty());
            assertTrue(new File(ce, "subdata/work/files").isDirectory());
        } finally {
            deleteTree(ce);
        }
    }

    // ---------------------------------------------------------------- 镜像

    @Test
    public void ensure_mirrorsDirectoryTreeButCopiesNoFile() throws IOException {
        File ce = tempDir("skel-mirror");
        try {
            // 原数据目录：深一点的目录 + 文件，模拟真机上那 113 个目录
            File deep = new File(ce, "files/audio/cache/sub");
            assertTrue(deep.mkdirs());
            assertTrue(new File(ce, "shared_prefs").mkdirs());
            assertTrue(new File(ce, "databases").mkdirs());
            assertTrue(new File(ce, "lib").mkdirs());
            write(new File(deep, "secret.dat"), "不该被复制");
            write(new File(ce, "shared_prefs/p.xml"), "x");

            SlotSkeleton.Report r = SlotSkeleton.ensure(ce, null, "b");
            assertTrue(r.clean());

            File slotRoot = new File(ce, "subdata/b");
            assertTrue("nested dir should be mirrored",
                    new File(slotRoot, "files/audio/cache/sub").isDirectory());
            assertTrue(new File(slotRoot, "shared_prefs").isDirectory());
            // 文件一个都不能带过来（不克隆就该是干净槽）
            assertFalse(new File(slotRoot, "files/audio/cache/sub/secret.dat").exists());
            assertFalse(new File(slotRoot, "shared_prefs/p.xml").exists());
            // lib 不镜像；控制目录自己也不能被镜像进去
            assertFalse(new File(slotRoot, "lib").exists());
            assertFalse(new File(slotRoot, "subdata").exists());
            assertTrue("mirrored=" + r.ceMirrored, r.ceMirrored >= 3);
        } finally {
            deleteTree(ce);
        }
    }

    @Test
    public void ensure_isIdempotentAndSecondRunIsCheap() throws IOException {
        File ce = tempDir("skel-idem");
        try {
            assertTrue(new File(ce, "files/x").mkdirs());
            SlotSkeleton.Report first = SlotSkeleton.ensure(ce, null, "a");
            assertTrue(first.clean());
            assertTrue(first.changed());

            SlotSkeleton.Report second = SlotSkeleton.ensure(ce, null, "a");
            assertTrue(second.clean());
            assertFalse("second run is not first time", second.firstTime);
            assertTrue("second run should create nothing", second.ceCreated.isEmpty());
            // 有 .skeleton 标记，第二次不再遍历原目录
            assertEquals(0, second.ceMirrored);
            assertFalse("nothing changed on second run", second.changed());
        } finally {
            deleteTree(ce);
        }
    }

    @Test
    public void ensure_restoresDeletedStandardDir() throws IOException {
        File ce = tempDir("skel-restore");
        try {
            SlotSkeleton.ensure(ce, null, "a");
            File db = new File(ce, "subdata/a/databases");
            assertTrue(db.isDirectory());
            deleteTree(db);
            assertFalse(db.exists());

            SlotSkeleton.Report r = SlotSkeleton.ensure(ce, null, "a");
            assertTrue(r.clean());
            assertTrue("databases must come back", db.isDirectory());
            assertTrue(r.ceCreated.contains("ce:databases"));
        } finally {
            deleteTree(ce);
        }
    }

    // ---------------------------------------------------------------- 权限

    @Test
    public void ensure_hardensDirPermissionsTo700() throws IOException {
        assumeTrue("需要 POSIX 权限支持",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        File ce = tempDir("skel-perm");
        try {
            SlotSkeleton.ensure(ce, null, "a");
            assertOwnerOnly(new File(ce, "subdata"));
            assertOwnerOnly(new File(ce, "subdata/a"));
            assertOwnerOnly(new File(ce, "subdata/a/databases"));
        } finally {
            deleteTree(ce);
        }
    }

    private static void assertOwnerOnly(File dir) throws IOException {
        Set<PosixFilePermission> p = Files.getPosixFilePermissions(dir.toPath());
        assertTrue(dir.getPath(), p.contains(PosixFilePermission.OWNER_READ));
        assertTrue(dir.getPath(), p.contains(PosixFilePermission.OWNER_WRITE));
        assertTrue(dir.getPath(), p.contains(PosixFilePermission.OWNER_EXECUTE));
        assertFalse(dir.getPath(), p.contains(PosixFilePermission.GROUP_READ));
        assertFalse(dir.getPath(), p.contains(PosixFilePermission.GROUP_WRITE));
        assertFalse(dir.getPath(), p.contains(PosixFilePermission.OTHERS_READ));
        assertFalse(dir.getPath(), p.contains(PosixFilePermission.OTHERS_EXECUTE));
    }

    // ---------------------------------------------------------------- 边界

    @Test
    public void ensure_rejectsBadArgumentsWithoutCreatingAnything() throws IOException {
        File ce = tempDir("skel-args");
        try {
            assertFalse(SlotSkeleton.ensure(null, null, "a").clean());
            assertFalse(SlotSkeleton.ensure(ce, null, "../etc").clean());
            SlotSkeleton.Report r = SlotSkeleton.ensure(ce, null, "");
            assertFalse(r.clean());
            assertEquals(0, r.createdCount());
            assertFalse("nothing should be created for a bad slot",
                    new File(ce, "subdata").exists());
        } finally {
            deleteTree(ce);
        }
    }

    @Test
    public void ceDirsCoverEveryDirMentionedByTheBugReport() {
        String[] required = {"databases", "shared_prefs", "files", "cache", "code_cache",
                "no_backup", "app_webview", "app_UApm", "app_accs", "app_textures",
                "crashsdk", "db", ".cache"};
        assertEquals(required.length, SlotSkeleton.CE_DIRS.length);
        for (String d : required) {
            assertTrue("CE_DIRS missing " + d, contains(SlotSkeleton.CE_DIRS, d));
        }
        for (String d : new String[]{"cache", "code_cache", "shared_prefs"}) {
            assertTrue("DE_DIRS missing " + d, contains(SlotSkeleton.DE_DIRS, d));
        }
    }

    @Test
    public void mirrorDirs_respectsLimit() throws IOException {
        File ce = tempDir("skel-limit");
        try {
            for (int i = 0; i < 5; i++) {
                assertTrue(new File(ce, "d" + i).mkdirs());
            }
            assertEquals(3, SlotSkeleton.mirrorDirs(ce, new File(ce, "subdata/x"), 3));
        } finally {
            deleteTree(ce);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** 系统 tmpdir 下的一次性目录（真 POSIX 文件系统，权限断言才有意义）。 */
    private static File tempDir(String name) throws IOException {
        File base = new File(System.getProperty("java.io.tmpdir"), "subdata-skel");
        deleteTree(new File(base, name));
        File d = new File(base, name);
        assertTrue("mkdirs " + d, d.mkdirs());
        return d;
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
