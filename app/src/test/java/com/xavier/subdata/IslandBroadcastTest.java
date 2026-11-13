package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link IslandBroadcast}：广播协议里所有「不需要 Android 也能测」的判定。
 *
 * <p>真正的安全边界在这里：接收器是 {@code exported=true} 的，任何 App 都能发广播，
 * 所以「发送方 uid 的包名是否在白名单里」必须由纯逻辑保证，并且要测到没有漏洞。
 */
public class IslandBroadcastTest {

    private static final List<String> WHITELIST =
            Arrays.asList("com.chaoxing.mobile", "mark.via");

    @Test
    public void want_onlyForNonMainSlotWithNoticeOn() {
        assertTrue(IslandBroadcast.want(true, false));
        assertFalse("主槽不上岛", IslandBroadcast.want(true, true));
        assertFalse("关掉提示不上岛", IslandBroadcast.want(false, false));
        assertFalse(IslandBroadcast.want(false, true));
    }

    @Test
    public void key_distinguishesSlotAndWant() {
        String show = IslandBroadcast.key("mark.via", "b", true);
        String hide = IslandBroadcast.key("mark.via", "b", false);
        assertNotEquals("切换状态必须能立刻发出去", show, hide);
        assertNotEquals(IslandBroadcast.key("mark.via", "b", true),
                IslandBroadcast.key("mark.via", "c", true));
        assertEquals(show, IslandBroadcast.key("mark.via", "b", true));
    }

    @Test
    public void key_isNullSafe() {
        assertTrue(IslandBroadcast.key(null, null, false).length() > 0);
    }

    @Test
    public void senderAllowed_requiresIntersection() {
        assertTrue(IslandBroadcast.senderAllowed(
                Collections.singletonList("mark.via"), WHITELIST));
        assertFalse(IslandBroadcast.senderAllowed(
                Collections.singletonList("com.evil.app"), WHITELIST));
        // 共享 uid 的多个包，只要有一个在白名单就放行（uid 校验的固有粒度）
        assertTrue(IslandBroadcast.senderAllowed(
                Arrays.asList("com.evil.app", "com.chaoxing.mobile"), WHITELIST));
    }

    @Test
    public void senderAllowed_rejectsEmptyAndNull() {
        assertFalse(IslandBroadcast.senderAllowed(Collections.<String>emptyList(), WHITELIST));
        assertFalse(IslandBroadcast.senderAllowed(null, WHITELIST));
        assertFalse(IslandBroadcast.senderAllowed(Collections.singletonList("mark.via"), null));
        assertFalse(IslandBroadcast.senderAllowed(Collections.singletonList("mark.via"),
                Collections.<String>emptyList()));
    }

    @Test
    public void claimedPkg_mustBeWhitelistedAndBelongToSender() {
        List<String> sender = Collections.singletonList("mark.via");
        assertTrue(IslandBroadcast.claimedPkgAllowed("mark.via", sender, WHITELIST));
        // 自称白名单里的另一个 App，但 uid 对不上 → 拒
        assertFalse(IslandBroadcast.claimedPkgAllowed("com.chaoxing.mobile", sender, WHITELIST));
        // 自称不在白名单 → 拒
        assertFalse(IslandBroadcast.claimedPkgAllowed("com.evil.app", sender, WHITELIST));
        assertFalse(IslandBroadcast.claimedPkgAllowed(null, sender, WHITELIST));
        assertFalse(IslandBroadcast.claimedPkgAllowed("", sender, WHITELIST));
        assertFalse(IslandBroadcast.claimedPkgAllowed("mark.via", null, WHITELIST));
    }

    @Test
    public void sanitizeSlot_matchesConfigRules() {
        assertEquals("b", IslandBroadcast.sanitizeSlot("b"));
        assertEquals("work_2", IslandBroadcast.sanitizeSlot(" work_2 "));
        assertNull(IslandBroadcast.sanitizeSlot(".."));
        assertNull(IslandBroadcast.sanitizeSlot("../etc"));
        assertNull("控制目录名不能用", IslandBroadcast.sanitizeSlot("subdata"));
        assertNull(IslandBroadcast.sanitizeSlot(""));
        assertNull(IslandBroadcast.sanitizeSlot(null));
    }

    @Test
    public void summary_isLogFriendly() {
        assertEquals("show pkg=mark.via slot=b",
                IslandBroadcast.summary("mark.via", "b", true));
        assertEquals("hide pkg=mark.via slot=a",
                IslandBroadcast.summary("mark.via", "a", false));
    }

    @Test
    public void protocolConstantsAreStable() {
        // 协议是跨进程契约，改名等于不兼容，写死断言防手滑
        assertEquals("com.xavier.subdata.action.ISLAND_STATE", IslandBroadcast.ACTION);
        assertEquals("com.xavier.subdata.IslandReceiver", IslandBroadcast.RECEIVER_CLASS);
        assertEquals(1, IslandBroadcast.VERSION);
        assertEquals(10_000L, IslandBroadcast.MIN_INTERVAL_MS);
    }
}
