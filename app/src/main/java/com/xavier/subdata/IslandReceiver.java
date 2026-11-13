package com.xavier.subdata;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 模块 App 侧的超级岛广播接收器（{@code exported=true}）。
 *
 * <p>被注入的目标进程只发一条<b>显式广播</b>过来，由这里换成模块 App 自己的身份去 post。
 * 因为导出，任何人都能发，所以做三道校验：
 * <ol>
 *   <li><b>认人</b>：{@code getSendingUid()} → {@code getPackagesForUid} → 必须至少有一个包名
 *       在模块白名单里（{@link IslandBroadcast#senderAllowed}）；</li>
 *   <li><b>认自称</b>：extras 里的 {@code pkg} 必须既在白名单里、又真的属于该 uid
 *       （{@link IslandBroadcast#claimedPkgAllowed}）；</li>
 *   <li><b>认参</b>：{@code slot} 过 {@link SlotConfig#sanitize}，非法直接丢。</li>
 * </ol>
 *
 * <p>另外用 {@link IslandDebounce} 做接收端节流：同一个 App 的同一个槽状态 10s 内只处理一次，
 * 避免被反复触发时模块进程反复起来跑通知逻辑。
 *
 * <p>取发送方 uid 用反射（{@code getSendingUid} / {@code getSentFromUid} 的可用性随 API 版本变化），
 * 拿不到就<b>拒绝</b>——安全默认值宁严不宽。
 */
public class IslandReceiver extends BroadcastReceiver {

    private static final String TAG = "SubData.receiver";

    /** 接收端节流；跟发送端同样的 10s 窗口。 */
    private static final IslandDebounce DEBOUNCE = new IslandDebounce(IslandBroadcast.MIN_INTERVAL_MS);

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            handle(context, intent);
        } catch (Throwable t) {
            log("onReceive failed: " + t);
        }
    }

    private void handle(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!IslandBroadcast.ACTION.equals(intent.getAction())) {
            log("ignore action=" + intent.getAction());
            return;
        }
        final Context app = context.getApplicationContext();
        final long now = SystemClock.elapsedRealtime();

        final int uid = senderUid(this);
        final List<String> senderPkgs = senderPkgs(app, this, uid);
        final List<String> whitelist = new TargetStore(app).targets();

        if (!IslandBroadcast.senderAllowed(senderPkgs, whitelist)) {
            log("reject uid=" + uid + " pkgs=" + senderPkgs + " (not in whitelist)");
            return;
        }
        final String claimed = intent.getStringExtra(IslandBroadcast.EXTRA_PKG);
        if (!IslandBroadcast.claimedPkgAllowed(claimed, senderPkgs, whitelist)) {
            log("reject claimed pkg=" + claimed + " uid=" + uid + " pkgs=" + senderPkgs);
            return;
        }
        final String slot = IslandBroadcast.sanitizeSlot(
                intent.getStringExtra(IslandBroadcast.EXTRA_SLOT));
        if (slot == null) {
            log("reject bad slot=" + intent.getStringExtra(IslandBroadcast.EXTRA_SLOT));
            return;
        }
        final boolean isMain = intent.getBooleanExtra(IslandBroadcast.EXTRA_MAIN_SLOT, true);
        // 主槽一律不上岛；want 缺省时按「非主槽」推断
        final boolean want = !isMain
                && intent.getBooleanExtra(IslandBroadcast.EXTRA_WANT, !isMain);
        final int version = intent.getIntExtra(IslandBroadcast.EXTRA_VERSION, 0);
        if (version > IslandBroadcast.VERSION) {
            log("unknown protocol v=" + version + ", best effort");
        }

        final String key = IslandBroadcast.key(claimed, slot, want) + "#rx";
        if (!DEBOUNCE.allow(key, now)) {
            log("dedupe " + IslandBroadcast.summary(claimed, slot, want));
            return;
        }
        IslandPoster.apply(app, claimed, slot, want);
        log("accepted " + IslandBroadcast.summary(claimed, slot, want) + " uid=" + uid
                + " v=" + version);
    }

    /**
     * 发送方包名：优先用 uid 反查（最可信），uid 拿不到时退回
     * {@code BroadcastReceiver#getSentFromPackage()}（也是系统给的，不是 intent 里的自称）。
     *
     * <p>为什么要这条兜底：不同 API / ROM 对「发起方 uid」的记录不一致
     * （实测 emulator API 37 上 {@code am broadcast} 发起时 {@code getSentFromUid()} 返回 -1），
     * 只认 uid 会让超级岛在某些设备上静默失效。
     */
    static List<String> senderPkgs(Context ctx, BroadcastReceiver r, int uid) {
        if (uid >= 0) {
            return pkgsForUid(ctx, uid);
        }
        String p = sentFromPackage(r);
        if (p != null && !p.isEmpty()) {
            return new ArrayList<>(Collections.singletonList(p));
        }
        return Collections.emptyList();
    }

    /** {@code getSentFromPackage()}（API 34+），拿不到返回 null。 */
    static String sentFromPackage(BroadcastReceiver r) {
        try {
            Method m = BroadcastReceiver.class.getMethod("getSentFromPackage");
            Object v = m.invoke(r);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 发送方 uid。
     *
     * <p>{@code BroadcastReceiver#getSendingUid()} / {@code getSentFromUid()} 都是较新的 API
     * （API 34 起是 {@code getSentFromUid}），用反射取，取不到返回 -1（调用方按「不认识」处理）。
     */
    static int senderUid(BroadcastReceiver r) {
        for (String name : new String[]{"getSendingUid", "getSentFromUid"}) {
            try {
                Method m = BroadcastReceiver.class.getMethod(name);
                Object v = m.invoke(r);
                if (v instanceof Integer) {
                    int uid = (Integer) v;
                    if (uid >= 0) {
                        return uid;
                    }
                }
            } catch (Throwable ignored) {
                // 换下一个
            }
        }
        return -1;
    }

    private static List<String> pkgsForUid(Context ctx, int uid) {
        if (uid < 0) {
            return Collections.emptyList();
        }
        try {
            String[] arr = ctx.getPackageManager().getPackagesForUid(uid);
            if (arr == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(Arrays.asList(arr));
        } catch (Throwable t) {
            log("getPackagesForUid failed: " + t);
            return Collections.emptyList();
        }
    }

    private static void log(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
    }
}
