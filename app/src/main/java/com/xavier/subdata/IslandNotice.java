package com.xavier.subdata;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 非主槽时在小米超级岛（HyperOS 焦点通知）挂一条「当前处于副本数据」——<b>发送端</b>。
 *
 * <p>v0.3.0 及以前的做法是在目标 App 进程里直接 {@code notify()}。真机实测证明这条路走不通：
 * <pre>
 * SubData.island: promoteOngoing not found (non-MIUI?), skip
 * </pre>
 * 通知归属目标 App，而 Via / 学习通没有 {@code POST_PROMOTED_NOTIFICATIONS}，
 * 永远拿不到 {@code PROMOTED_ONGOING}。
 *
 * <p>v0.3.1 改成：这里只判断「要不要上岛」，然后用<b>显式广播</b>把
 * {@code {pkg, slot, isMainSlot, want, v}} 发给模块 App，由
 * {@link IslandReceiver} + {@link IslandPoster} 用模块自己的身份发通知。
 *
 * <p>三条纪律：
 * <ul>
 *   <li>只有主进程发（多进程 App 的推送 / 服务进程一律跳过）；</li>
 *   <li>同一 slot 的同一状态 10s 内只发一次（{@link IslandDebounce}），防广播风暴；</li>
 *   <li>全程 try/catch：广播发不出去（模块被卸载 / 被冻结）绝不影响数据重定向。</li>
 * </ul>
 */
public final class IslandNotice {

    /** 兼容旧引用：通知 id 现在由 {@link IslandPoster} 持有。 */
    public static final int NOTIFY_ID = IslandPoster.NOTIFY_ID;

    /** 等 App 自己的界面起来再发，太快发有的 ROM 会把通知吞掉。 */
    private static final int DELAY_MS = 2000;

    private static final String TAG = "SubData.island";

    /** 发送端节流：每个进程各有一份，够用。 */
    private static final IslandDebounce DEBOUNCE =
            new IslandDebounce(IslandBroadcast.MIN_INTERVAL_MS);

    /**
     * 日志文件由 {@code SubDataModule} 装配进来。
     *
     * <p>这里<b>故意不引用 {@code SubDataModule}</b>：那个类 implements 了 Xposed 的接口，
     * 在没有 Xposed 类的进程里连类都加载不了（NoClassDefFoundError: IXposedHookLoadPackage），
     * 一次日志调用就能把通知流程带崩。通知逻辑自己写文件最稳。
     */
    private static volatile File sLogFile;

    private IslandNotice() {
    }

    /** 由模块在注入时装配日志文件；不装配就只打 logcat。 */
    public static void setLogFile(File f) {
        sLogFile = f;
    }

    private static void log(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
            // logcat 也会失败的话就真的没辙了
        }
        final File f = sLogFile;
        if (f == null) {
            return;
        }
        try (FileWriter w = new FileWriter(f, true)) {
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            w.write(ts + " " + msg + "\n");
        } catch (Throwable ignored) {
            // 日志失败绝不影响主流程
        }
    }

    /**
     * 在 App 进程起来之后决定要不要通知模块 App 上岛。
     *
     * @param ctx         目标 App 的 Context（在 {@code Application#attach} 里拿到的）
     * @param cfg         当前 slot 配置
     * @param mainProcess 是否主进程
     */
    public static void schedule(final Context ctx, final SlotConfig cfg, final boolean mainProcess) {
        if (ctx == null || cfg == null) {
            return;
        }
        if (!mainProcess) {
            // 非主进程不参与：主进程一定会跑，多个进程发只是重复广播
            return;
        }
        final boolean want = IslandBroadcast.want(cfg.islandNotice, cfg.isMainSlot());
        final String pkg = ctx.getPackageName();
        final String key = IslandBroadcast.key(pkg, cfg.slot, want);
        if (!DEBOUNCE.allow(key, SystemClock.elapsedRealtime())) {
            log("island: dedupe slot=" + cfg.slot + " want=" + want);
            return;
        }
        final SlotConfig c = cfg;
        final Handler h = mainHandler();
        if (h != null) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    send(ctx, c, want);
                }
            }, DELAY_MS);
        } else {
            send(ctx, c, want);
        }
    }

    /** 发一条显式广播给模块 App；不区分「发/撤」，统一同步一次状态。 */
    static void send(Context ctx, SlotConfig cfg, boolean want) {
        try {
            Intent i = new Intent(IslandBroadcast.ACTION);
            i.setComponent(new ComponentName(ModuleMeta.MODULE_PKG, IslandBroadcast.RECEIVER_CLASS));
            // setPackage 让系统按包名做一次额外约束（显式组件时作用有限，但没坏处）
            i.setPackage(ModuleMeta.MODULE_PKG);
            i.putExtra(IslandBroadcast.EXTRA_PKG, ctx.getPackageName());
            i.putExtra(IslandBroadcast.EXTRA_SLOT, cfg.slot);
            i.putExtra(IslandBroadcast.EXTRA_MAIN_SLOT, cfg.isMainSlot());
            i.putExtra(IslandBroadcast.EXTRA_WANT, want);
            i.putExtra(IslandBroadcast.EXTRA_VERSION, IslandBroadcast.VERSION);
            ctx.sendBroadcast(i);
            log("island: broadcast want=" + want + " slot=" + cfg.slot
                    + " main=" + cfg.isMainSlot() + " notice=" + cfg.islandNotice);
        } catch (Throwable t) {
            log("island: broadcast failed: " + t);
        }
    }

    private static Handler mainHandler() {
        try {
            Looper l = Looper.getMainLooper();
            return l == null ? null : new Handler(l);
        } catch (Throwable t) {
            return null;
        }
    }
}
