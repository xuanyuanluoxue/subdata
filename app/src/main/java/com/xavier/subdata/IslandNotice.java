package com.xavier.subdata;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 非主槽时在小米超级岛（HyperOS 焦点通知）挂一条「当前处于副本数据」。
 *
 * <p>做法和真机上已经进岛的通知完全一致：
 * <ol>
 *   <li>反射调 {@code android.app.Notification#promoteOngoing(boolean)}（隐藏 API，
 *       找不到就跳过，绝不抛出去）；</li>
 *   <li>extras 里放 {@code android.requestPromotedOngoing=true} 和
 *       {@code miui.focus.param=<JSON>}（JSON 由 {@link IslandParam#json(String)} 生成）；</li>
 *   <li>能拿到 App 图标就再塞 {@code miui.focus.pics} Bundle（拿不到就不塞）。</li>
 * </ol>
 *
 * <p>只有 <b>主进程</b> 会发（多进程 App 的推送 / 服务进程一律跳过），
 * 并且只在 {@code slot != main_slot} 且 {@code island_notice=true} 时发；
 * 其它情况会顺手把残留通知取消掉。
 *
 * <p>学习通自己没有 {@code POST_NOTIFICATIONS} 权限时 {@code notify()} 会静默失败，
 * 所以发之前先看 {@link NotificationManager#areNotificationsEnabled()}，
 * 关了只写日志——不申请权限、不弹窗、不崩。
 */
public final class IslandNotice {

    /** 固定 id，重复发只是更新同一条。 */
    public static final int NOTIFY_ID = 0x5BDA7A;

    /** 等 App 自己的界面起来再发，太快发有的 ROM 会把通知吞掉。 */
    private static final int DELAY_MS = 2000;

    /** 塞进 {@code miui.focus.pics} 的图标边长上限。 */
    private static final int MAX_PIC_PX = 288;

    /** 图标 Bundle 还没在真机上验证过格式，先开着；出问题把它改成 false 即可。 */
    private static final boolean BRING_PIC = true;

    private static final String TAG = "SubData.island";

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
     * 在 App 进程起来之后决定发还是撤。
     *
     * @param ctx         目标 App 的 Context（在 {@code Application#attach} 里拿到的）
     * @param cfg         当前 slot 配置
     * @param mainProcess 是否主进程
     */
    public static void schedule(final Context ctx, final SlotConfig cfg, final boolean mainProcess) {
        if (ctx == null || cfg == null) {
            return;
        }
        final boolean want = cfg.islandNotice && !cfg.isMainSlot() && mainProcess;
        if (!want) {
            cancel(ctx, cfg);
            return;
        }
        final String slot = cfg.slot;
        final Handler h = mainHandler();
        if (h != null) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    post(ctx, slot);
                }
            }, DELAY_MS);
        } else {
            post(ctx, slot);
        }
    }

    /** 主槽 / 关掉开关 / 非主进程：把可能留下的通知撤掉。 */
    private static void cancel(Context ctx, SlotConfig cfg) {
        try {
            NotificationManager nm = manager(ctx);
            if (nm != null) {
                nm.cancel(NOTIFY_ID);
            }
            log("island: skip slot=" + cfg.slot + " main=" + cfg.mainSlot
                    + " notice=" + cfg.islandNotice + (nm == null ? " (no NotificationManager)" : ""));
        } catch (Throwable t) {
            log("island: cancel failed: " + t);
        }
    }

    private static void post(Context ctx, String slot) {
        try {
            NotificationManager nm = manager(ctx);
            if (nm == null) {
                log("island: no NotificationManager, skip");
                return;
            }
            if (!nm.areNotificationsEnabled()) {
                // 目标 App 没开通知权限：静默失败是预期行为，只记日志
                log("island: notifications disabled for app, skip slot=" + slot);
                return;
            }
            ensureChannel(nm);
            Notification n = build(ctx, slot);
            nm.notify(NOTIFY_ID, n);
            log("island: posted slot=" + slot);
        } catch (Throwable t) {
            log("island: post failed: " + t);
        }
    }

    private static NotificationManager manager(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static Handler mainHandler() {
        try {
            Looper l = Looper.getMainLooper();
            return l == null ? null : new Handler(l);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 无声、高重要度、不上角标的独立渠道。 */
    private static void ensureChannel(NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(
                IslandParam.CHANNEL_ID, IslandParam.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(IslandParam.CHANNEL_DESC);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private static Notification build(Context ctx, String slot) {
        Notification.Builder b = new Notification.Builder(ctx, IslandParam.CHANNEL_ID);
        b.setSmallIcon(smallIcon(ctx));
        b.setContentTitle(IslandParam.TITLE);
        b.setContentText(IslandParam.content(slot));
        b.setOngoing(true);
        b.setAutoCancel(false);
        b.setShowWhen(false);
        b.setOnlyAlertOnce(true);
        // 框架的 Builder 没有 setSilent（那是 NotificationCompat），这里手动静音：
        // 渠道本身已经是无声的，再把这条的响铃/振动默认值清掉
        b.setSound(null);
        b.setVibrate(null);
        b.setDefaults(0);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);

        Notification n = b.build();
        Bundle extras = n.extras;
        if (extras == null) {
            extras = new Bundle();
            n.extras = extras;
        }
        extras.putBoolean(IslandParam.EXTRA_PROMOTED, true);
        extras.putString(IslandParam.EXTRA_FOCUS_PARAM, IslandParam.json(slot));

        if (BRING_PIC) {
            Bundle pics = picsBundle(ctx);
            if (pics != null) {
                extras.putBundle(IslandParam.EXTRA_FOCUS_PICS, pics);
            }
        }

        // 这一位同时塞进 flags，避免个别 ROM 只看 flags
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        promoteOngoing(n);
        return n;
    }

    /** 小图标用 App 自己的图标；拿不到退回系统图标，绝不能抛。 */
    private static int smallIcon(Context ctx) {
        try {
            int icon = ctx.getApplicationInfo().icon;
            if (icon != 0) {
                return icon;
            }
        } catch (Throwable ignored) {
            // 继续走兜底
        }
        return android.R.drawable.ic_dialog_info;
    }

    /**
     * {@code miui.focus.pics} Bundle：key 必须和 JSON 里的 {@code pic} 同名。
     * 任何一步失败都返回 null（等于不带图标）。
     */
    private static Bundle picsBundle(Context ctx) {
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(ctx.getPackageName());
            Bitmap bmp = toBitmap(d, MAX_PIC_PX);
            if (bmp == null) {
                return null;
            }
            Bundle b = new Bundle();
            b.putParcelable(IslandParam.PIC_NAME, bmp);
            return b;
        } catch (Throwable t) {
            log("island: pic skipped: " + t);
            return null;
        }
    }

    private static Bitmap toBitmap(Drawable d, int max) {
        if (d == null) {
            return null;
        }
        if (d instanceof BitmapDrawable) {
            Bitmap raw = ((BitmapDrawable) d).getBitmap();
            if (raw != null && raw.getWidth() <= max && raw.getHeight() <= max) {
                return raw;
            }
        }
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            w = max / 2;
            h = max / 2;
        }
        float scale = Math.min(1f, (float) max / Math.max(w, h));
        w = Math.max(1, (int) (w * scale));
        h = Math.max(1, (int) (h * scale));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return out;
    }

    /** 隐藏 API：让 MIUI 把这条通知当成「可上岛」的常驻通知。找不到就跳过。 */
    private static void promoteOngoing(Notification n) {
        try {
            Method m;
            try {
                m = Notification.class.getDeclaredMethod("promoteOngoing", boolean.class);
                m.setAccessible(true);
            } catch (NoSuchMethodException e) {
                m = Notification.class.getMethod("promoteOngoing", boolean.class);
            }
            m.invoke(n, Boolean.TRUE);
        } catch (NoSuchMethodException e) {
            log("island: promoteOngoing not found (non-MIUI?), skip");
        } catch (Throwable t) {
            log("island: promoteOngoing failed: " + t);
        }
    }
}
