package com.xavier.subdata;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：把 MCP 服务钉在系统里，别让 Doze / 后台清理一调就超时。
 *
 * <p>产品经理评审里被三次点名的问题是「MCP 看起来能用、实际连不上」——模块 App 一退到后台
 * 就被冻结。前台服务 + 常驻通知是 Android 上唯一合规的保活手段，这里只做最低限度：
 * 一个 LOW 级通知（不响不亮），点了回到主界面。
 *
 * <p>服务本身<b>不</b>启动 MCP：它只是让进程活着，真正的监听仍在 {@link McpManager}。
 * 这样即使厂商 ROM 拒绝启动前台服务（抛异常），MCP 也照样能用，只是不保活。
 */
public final class McpService extends Service {

    private static final String CHANNEL_ID = "mcp_service";
    private static final int NOTIF_ID = 0x5D01;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.mcp_notif_channel),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {
            // 通知不可用不影响 MCP
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {
            // Android 12+ 后台启动限制 / 权限被拒：退化成普通服务，MCP 不受影响
        }
        if (!McpManager.enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                McpManager.sync(getApplicationContext());
            } catch (Throwable ignored) {
                // sync 自己兜底
            }
        }, "mcp-boot").start();
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.mcp_notif_title))
                .setContentText(getString(R.string.mcp_notif_text,
                        McpManager.lan(this) ? "0.0.0.0" : "127.0.0.1", McpManager.port(this)))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public void onDestroy() {
        // 服务被停 = 用户关掉了 MCP（或系统回收），把监听一起收掉，别留下裸端口
        McpManager.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
