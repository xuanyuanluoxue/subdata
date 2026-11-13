package com.xavier.subdata;

import java.util.Collection;

/**
 * 超级岛广播协议（v0.3.1）：<b>目标进程不再自己发通知，改为通知模块 App 去发</b>。
 *
 * <p>为什么换人发：真机实测（v0.3.0，Via / 学习通）证明——模块虽然在目标进程里运行，
 * 但 {@code NotificationManager.notify()} 发出来的通知归属<b>目标 App</b>，
 * 而目标 App 没有 {@code android.permission.POST_PROMOTED_NOTIFICATIONS}
 * （只有 {@code io.github.ccch1mneyyy.dsh_app} 这种自己声明了该权限的 App 才有，
 * 实测 {@code granted=true}），于是永远拿不到 {@code PROMOTED_ONGOING} 标记，
 * 反射 {@code promoteOngoing()} 也拿不到隐藏方法：
 * <pre>
 * SubData.island: promoteOngoing not found (non-MIUI?), skip
 * </pre>
 * 所以 v0.3.1 改成：目标进程只发一条显式广播，模块 App 用自己的包名（自己声明了该权限）
 * 去 post，通知归属模块 App，权限与上岛资格都由模块 App 兜住。
 *
 * <p>协议（一条广播 = 一次「状态同步」，不是「发/撤」两条）：
 * <pre>
 * action  com.xavier.subdata.action.ISLAND_STATE
 * target  component = com.xavier.subdata/.IslandReceiver   （显式，不用隐式广播）
 * extras  pkg        String   发起方的包名（= 当前被注入的 App）
 *         slot       String   当前槽名（a / b / work…）
 *         isMainSlot boolean  是否主槽
 *         want       boolean  期望上岛（= island_notice &amp;&amp; !isMainSlot），可选，缺省按 false
 *         v          int      协议版本，当前 {@value #VERSION}
 * </pre>
 *
 * <p>接收端只做两件事：校验发送方 uid 是否属于白名单，然后 {@code want ? post : cancel}。
 * 主槽 / {@code island_notice=false} / 没有通知权限 → 一律 cancel 并清掉残留。
 *
 * <p>这个类只做纯逻辑（常量 + 判定），不碰 Android API，可以直接单测。
 */
public final class IslandBroadcast {

    /** 广播 action。带包名前缀，避免和其它 App 撞车。 */
    public static final String ACTION = "com.xavier.subdata.action.ISLAND_STATE";

    /** 接收端组件（相对模块包名）。 */
    public static final String RECEIVER_CLASS = ModuleMeta.MODULE_PKG + ".IslandReceiver";

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_MAIN_SLOT = "isMainSlot";
    public static final String EXTRA_WANT = "want";
    public static final String EXTRA_VERSION = "v";

    /** 协议版本；以后改字段名时靠它兼容。 */
    public static final int VERSION = 1;

    /**
     * 同一 slot 的同一状态在这个窗口内只同步一次。
     *
     * <p>被注入的 App 通常有好几个进程（主进程 + 推送 + 服务），每个进程都会在启动时
     * 判断一次；没有节流的话一次冷启动就能连发三五条广播，既是广播风暴也白费电。
     */
    public static final long MIN_INTERVAL_MS = 10_000L;

    private IslandBroadcast() {
    }

    /**
     * 期望状态：只有「非主槽 + 开了上岛提示」才应该上岛。
     *
     * @param islandNotice 配置里的 {@code island_notice}
     * @param isMainSlot   当前是否主槽
     */
    public static boolean want(boolean islandNotice, boolean isMainSlot) {
        return islandNotice && !isMainSlot;
    }

    /**
     * 节流用的 key：包名 + 槽 + 期望状态。
     *
     * <p>把 {@code want} 也编进 key，是为了让「主槽 ↔ 非主槽」切换能立刻发出去，
     * 而不是被上一条相同 slot 的记录挡住。
     */
    public static String key(String pkg, String slot, boolean want) {
        return (pkg == null ? "?" : pkg) + '#' + (slot == null ? "?" : slot) + '#' + (want ? '1' : '0');
    }

    /**
     * 发送方校验：{@code uid} 对应的包名里，至少有一个在模块白名单内。
     *
     * <p>这是 v0.3.1 的广播安全底线——接收器 {@code exported=true}，
     * 任何 App 都能发这条广播，所以必须自己认人。
     *
     * @param senderPkgs 发送方 uid 对应的包名（{@code PackageManager#getPackagesForUid}）
     * @param whitelist  模块白名单
     */
    public static boolean senderAllowed(Collection<String> senderPkgs, Collection<String> whitelist) {
        if (senderPkgs == null || whitelist == null || senderPkgs.isEmpty() || whitelist.isEmpty()) {
            return false;
        }
        for (String p : senderPkgs) {
            if (p != null && whitelist.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 广播里自称的包名是否可信：既要在白名单里，也要真的属于发送方 uid。
     *
     * @param claimedPkg 广播 extras 里的 {@code pkg}
     * @param senderPkgs 发送方 uid 对应的包名
     * @param whitelist  模块白名单
     */
    public static boolean claimedPkgAllowed(String claimedPkg, Collection<String> senderPkgs,
                                           Collection<String> whitelist) {
        if (claimedPkg == null || claimedPkg.isEmpty()) {
            return false;
        }
        if (whitelist == null || !whitelist.contains(claimedPkg)) {
            return false;
        }
        return senderPkgs != null && senderPkgs.contains(claimedPkg);
    }

    /** 广播里的 slot 名要过一遍和配置相同的白名单字符集，挡掉 {@code ".."} 之类。 */
    public static String sanitizeSlot(String raw) {
        return SlotConfig.sanitize(raw);
    }

    /** 由三要素拼出通知正文，供接收端日志与 UI 复用（不依赖 Android 资源）。 */
    public static String summary(String pkg, String slot, boolean want) {
        return (want ? "show" : "hide") + " pkg=" + pkg + " slot=" + slot;
    }
}
