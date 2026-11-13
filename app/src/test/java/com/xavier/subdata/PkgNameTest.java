package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 包名校验（越界防护）。
 *
 * <p>{@code TargetRepo} 里所有路径都是 "/data/data/" + pkg 拼出来的，
 * 其中 {@code deleteSlot} / {@code deleteControlDir} 走的是 {@code rm -rf}。
 * 这些用例就是钉死「pkg 里出现 ".." 或 "/" 时必须被拒绝」。</p>
 */
public class PkgNameTest {

    @Test
    public void acceptsRealPackageNames() {
        assertEquals("com.chaoxing.mobile",
                SlotConfig.sanitizePkg("com.chaoxing.mobile"));
        assertEquals("com.xavier.subdata", SlotConfig.sanitizePkg(" com.xavier.subdata "));
        assertEquals("a.b", SlotConfig.sanitizePkg("a.b"));
        // 数字与下划线可以出现在非首字符
        assertEquals("com.a1_b.c2", SlotConfig.sanitizePkg("com.a1_b.c2"));
    }

    @Test
    public void rejectsPathTraversalAndSeparators() {
        assertNull(SlotConfig.sanitizePkg(".."));
        assertNull(SlotConfig.sanitizePkg("../subdata"));
        assertNull(SlotConfig.sanitizePkg("com.x/../y"));
        assertNull(SlotConfig.sanitizePkg("com.x\\y"));
        assertNull(SlotConfig.sanitizePkg("com.x..y"));
        assertNull(SlotConfig.sanitizePkg("/data/data/com.x"));
    }

    @Test
    public void rejectsEmptyAndSillyValues() {
        assertNull(SlotConfig.sanitizePkg(null));
        assertNull(SlotConfig.sanitizePkg(""));
        assertNull(SlotConfig.sanitizePkg("   "));
        assertNull(SlotConfig.sanitizePkg("nodots"));
        assertNull(SlotConfig.sanitizePkg("1com.xavier"));
        assertNull(SlotConfig.sanitizePkg("com.1xavier"));
        assertNull(SlotConfig.sanitizePkg("com."));
        assertNull(SlotConfig.sanitizePkg("a".repeat(256)));
    }

    @Test
    public void targetRepoGuardMatchesConfigGuard() {
        assertTrue(TargetRepo.validPkg("com.chaoxing.mobile"));
        assertFalse(TargetRepo.validPkg(".."));
        assertFalse(TargetRepo.validPkg(null));
        // 危险操作在 pkg 不可信时必须直接失败，而不是拼出一个奇怪路径
        assertFalse(TargetRepo.deleteSlot("..", "a"));
        assertFalse(TargetRepo.deleteControlDir(".."));
    }
}
