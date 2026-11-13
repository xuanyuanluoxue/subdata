package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link IslandDebounce}：10s 节流窗口。
 *
 * <p>发送端靠它防「一次冷启动连发多条广播」（多进程 App 每个进程都会判断一次），
 * 接收端靠它防「被反复触发时反复跑通知逻辑」。用注入时间的方式测，不 sleep。
 */
public class IslandDebounceTest {

    @Test
    public void allowsFirstThenBlocksWithinWindow() {
        IslandDebounce d = new IslandDebounce(10_000L);
        String k = "mark.via#b#1";
        assertTrue(d.allow(k, 1_000L));
        assertFalse(d.allow(k, 1_001L));
        assertFalse(d.allow(k, 10_999L));
        assertTrue("窗口边界之后放行", d.allow(k, 11_000L));
    }

    @Test
    public void differentKeysAreIndependent() {
        IslandDebounce d = new IslandDebounce(10_000L);
        assertTrue(d.allow("mark.via#b#1", 0L));
        assertTrue("另一个槽不受影响", d.allow("mark.via#c#1", 0L));
        assertTrue("发↔撤是不同状态", d.allow("mark.via#b#0", 0L));
        assertTrue(d.allow("com.chaoxing.mobile#b#1", 0L));
    }

    @Test
    public void forgetClearsEntry() {
        IslandDebounce d = new IslandDebounce(10_000L);
        assertTrue(d.allow("k", 0L));
        assertFalse(d.allow("k", 1L));
        d.forget("k");
        assertTrue(d.allow("k", 2L));
    }

    @Test
    public void nullKeyAlwaysAllowed() {
        IslandDebounce d = new IslandDebounce(10_000L);
        assertTrue(d.allow(null, 0L));
        assertTrue(d.allow(null, 0L));
    }

    @Test
    public void zeroIntervalAlwaysAllows() {
        IslandDebounce d = new IslandDebounce(0L);
        assertTrue(d.allow("k", 5L));
        assertTrue(d.allow("k", 5L));
    }

    @Test
    public void clockGoingBackwardsDoesNotLockForever() {
        IslandDebounce d = new IslandDebounce(10_000L);
        assertTrue(d.allow("k", 50_000L));
        // 改系统时间导致 now < 上次记录：不能永久挡住
        assertTrue(d.allow("k", 1_000L));
    }

    @Test
    public void sizeIsBounded() {
        IslandDebounce d = new IslandDebounce(10_000L);
        for (int i = 0; i < 300; i++) {
            assertTrue(d.allow("k" + i, i));
        }
        assertTrue("key 表要有上限，不能无限涨", d.size() <= 256);
        assertEquals(10_000L, d.intervalMs());
    }
}
