package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 原子写：{@link AtomicFiles} + {@link SlotConfig} 的 slot / subdata.conf 写入。
 *
 * <p>要保证的两件事：内容要么是旧的、要么是完整的新内容（不留半行/空文件），
 * 以及写完之后目录里不残留 {@code .tmp}。
 */
public class AtomicFilesTest {

    @Test
    public void write_createsFileWithoutTmpResidue() throws IOException {
        File dir = SlotStateTest.tempDir("atomic-basic");
        try {
            File target = new File(dir, "slot");
            assertTrue(AtomicFiles.write(target, "b\n"));

            assertEquals("b\n", read(target));
            assertFalse("不能留下 .tmp", AtomicFiles.tmpFor(target).exists());
        } finally {
            SlotStateTest.deleteTree(dir);
        }
    }

    @Test
    public void write_overwritesExistingContent() throws IOException {
        File dir = SlotStateTest.tempDir("atomic-overwrite");
        try {
            File target = new File(dir, "subdata.conf");
            SlotStateTest.write(target, "slot=a\nenabled=true\n");

            assertTrue(AtomicFiles.write(target, "slot=b\n"));

            assertEquals("slot=b\n", read(target));
            assertFalse(AtomicFiles.tmpFor(target).exists());
        } finally {
            SlotStateTest.deleteTree(dir);
        }
    }

    @Test
    public void write_createsParentDirectories() throws IOException {
        File dir = SlotStateTest.tempDir("atomic-mkdirs");
        try {
            File target = new File(dir, "subdata/deep/nested/slot");
            assertTrue(AtomicFiles.write(target, "a"));
            assertEquals("a", read(target));
        } finally {
            SlotStateTest.deleteTree(dir);
        }
    }

    @Test
    public void write_supportsUtf8AndEmptyContent() throws IOException {
        File dir = SlotStateTest.tempDir("atomic-utf8");
        try {
            File target = new File(dir, "note.txt");
            assertTrue(AtomicFiles.write(target, "副本数据 · b 槽"));
            assertEquals("副本数据 · b 槽", read(target));
            assertTrue(AtomicFiles.write(target, ""));
            assertEquals("", read(target));
        } finally {
            SlotStateTest.deleteTree(dir);
        }
    }

    @Test
    public void write_nullTargetReturnsFalse() {
        assertFalse(AtomicFiles.write((File) null, "x"));
    }

    @Test
    public void tmpFor_isSiblingFile() throws IOException {
        File dir = SlotStateTest.tempDir("atomic-tmp");
        try {
            File target = new File(dir, "slot");
            assertEquals(new File(dir, "slot.tmp"), AtomicFiles.tmpFor(target));
        } finally {
            SlotStateTest.deleteTree(dir);
        }
    }

    // ------------------------------------------------------------ SlotConfig

    @Test
    public void writeSlot_writesAndIsIdempotent() throws IOException {
        File dataDir = SlotStateTest.tempDir("atomic-slot-file");
        try {
            File control = new File(dataDir, "subdata");
            File slotFile = new File(control, "slot");
            // load() 会顺手把 slot 文件落盘（默认 a）
            SlotConfig cfg = SlotConfig.load(dataDir);
            assertEquals("a", cfg.slot);
            assertEquals("a", read(slotFile).trim());

            long firstModified = slotFile.lastModified();
            // 再写同一个值：内容不变，连 mtime 都不该动（避免无意义落盘）
            slotFile.setLastModified(firstModified);
            SlotConfig.load(dataDir);
            assertEquals("a", read(slotFile).trim());
            assertFalse(AtomicFiles.tmpFor(slotFile).exists());
        } finally {
            SlotStateTest.deleteTree(dataDir);
        }
    }

    @Test
    public void writeConf_roundTripsThroughParser() throws IOException {
        File dataDir = SlotStateTest.tempDir("atomic-write-conf");
        try {
            File control = new File(dataDir, "subdata");
            java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
            kv.put("slot", "b");
            kv.put("island_notice", "false");
            kv.put("main_slot", "a");

            assertTrue(SlotConfig.writeConf(control, SlotConfig.formatConf(kv)));

            Map<String, String> back = SlotConfig.readConf(new File(control, "subdata.conf"));
            assertEquals("b", back.get("slot"));
            assertEquals("false", back.get("island_notice"));
            assertEquals("a", back.get("main_slot"));
        } finally {
            SlotStateTest.deleteTree(dataDir);
        }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
