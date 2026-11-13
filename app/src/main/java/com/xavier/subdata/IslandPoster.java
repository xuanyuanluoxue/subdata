package com.xavier.subdata;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import java.lang.reflect.Method;

/**
 * 在<b>模块 App 自己的进程</b>里 post / cancel 超级岛通知。
 *
 * <p>v0.3.1 的核心变化：通知必须由模块 App（{@code com.xavier.subdata}）发，
 * 不能用被注入的目标 App 的身份发。原因是真机实测出来的权限问题：
 * 通知归属 {@code ctx.getPackageName()}，而目标 App（Via / 学习通）没有
 * {@code android.permission.POST_PROMOTED_NOTIFICATIONS}，于是
 * {@code PROMOTED_ONGOING} 永远标记不上；模块 App 自己声明了这个权限、系统自动授予，
 * 由它发才有上岛资格（对照 App {@code io.github.ccch1mneyyy.dsh_app} 就是这样上岛的）。
 *
 * <p>三道退化，任何一道失败都不抛、不崩、只记日志：
 * <ol>
 *   <li>{@code POST_NOTIFICATIONS} 没开 → 撤掉通知并返回；</li>
 *   <li>{@code POST_PROMOTED_NOTIFICATIONS} 没拿到 → 照发，只靠 extras 尽力（记日志）；</li>
 *   <li>反射 {@code promoteOngoing(boolean)} 失败 → 同上（记日志）。</li>
 * </ol>
 *
 * <p>{@link IslandParam} 里的 JSON 形状是照真机 dump 抄的，字段名一个都不能改。
 */
public final class IslandPoster {

    /** 固定 id：重复 post 只是更新同一条，残留也不会堆一片。 */
    public static final int NOTIFY_ID = 0x5BDA7A;

    /** 图标 Bundle 的边长上限。 */
    private static final int MAX_PIC_PX = 288;

    /** 图标 Bundle 在真机上还没最终确认；出问题把它改成 false 即可，不影响上岛。 */
    private static final boolean BRING_PIC = true;

    private static final String TAG = "SubData.poster";

    private IslandPoster() {
    }

    /**
     * 按期望状态应用一次。
     *
     * @param ctx  模块 App 的 Context
     * @param pkg  发起方目标 App 的包名（只用于日志）
     * @param slot 槽名
     * @param want true=发/更新；false=撤掉
     */
    public static void apply(Context ctx, String pkg, String slot, boolean want) {
        if (ctx == null) {
            return;
        }
        NotificationManager nm = manager(ctx);
        if (nm == null) {
            log("no NotificationManager, skip " + IslandBroadcast.summary(pkg, slot, want));
            return;
        }
        if (!want) {
            cancel(ctx, nm, pkg, slot);
            return;
        }
        if (!notificationsEnabled(ctx, nm)) {
            // 模块 App 自己没通知权限：撤掉旧岛并跳过（静默退化）
            cancel(ctx, nm, pkg, slot);
            log("POST_NOTIFICATIONS not granted, cancel instead of post");
            return;
        }
        try {
            ensureChannel(nm);
            Notification n = build(ctx, slot);
            nm.notify(NOTIFY_ID, n);
            log("posted slot=" + slot + " from pkg=" + pkg
                    + " promotedPerm=" + promotedGranted(ctx));
        } catch (Throwable t) {
            log("post failed slot=" + slot + ": " + t);
        }
    }

    private static void cancel(Context ctx, NotificationManager nm, String pkg, String slot) {
        try {
            nm.cancel(NOTIFY_ID);
            log("cancel slot=" + slot + " from pkg=" + pkg);
        } catch (Throwable t) {
            log("cancel failed: " + t);
        }
    }

    /**
     * 通知能不能发出去。
     *
     * <p>{@code areNotificationsEnabled()} 在 API 33+ 会带上 POST_NOTIFICATIONS 的运行时状态，
     * 但个别 ROM 返回不准，所以再显式查一次权限。
     */
    static boolean notificationsEnabled(Context ctx, NotificationManager nm) {
        try {
            if (nm != null && !nm.areNotificationsEnabled()) {
                return false;
            }
        } catch (Throwable ignored) {
            // 查不了就当能用，交给 notify 自己失败
        }
        try {
            return ctx.checkSelfPermission(ModuleMeta.PERM_POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            // API 33 以下没有这个权限概念
            return true;
        }
    }

    /** {@code POST_PROMOTED_NOTIFICATIONS} 有没有拿到（拿不到只影响「必定上岛」，不影响其它）。 */
    static boolean promotedGranted(Context ctx) {
        try {
            return ctx.checkSelfPermission(ModuleMeta.PERM_PROMOTED)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static NotificationManager manager(Context ctx) {
        try {
            return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
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

        n.flags |= Notification.FLAG_ONGOING_EVENT;
        promoteOngoing(n);
        return n;
    }

    /** 小图标用模块 App 自己的图标；拿不到退回系统图标，绝不能抛。 */
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

    /** {@code miui.focus.pics} Bundle：key 必须和 JSON 里的 {@code pic} 同名。 */
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
            log("pic skipped: " + t);
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
            log("promoteOngoing not found (non-MIUI?), rely on extras only");
        } catch (Throwable t) {
            log("promoteOngoing failed: " + t);
        }
    }

    private static void log(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
            // logcat 也失败就真的没辙了
        }
    }
}
