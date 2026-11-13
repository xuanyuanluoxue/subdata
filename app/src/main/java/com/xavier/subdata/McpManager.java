package com.xavier.subdata;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 服务的开关、端口、局域网设置与单例生命周期。
 *
 * <p>设置存在独立的 {@code SharedPreferences("mcp")}，<b>不</b>混进目标 App 白名单那份
 * prefs —— 那份会被 {@code xposedsharedprefs} 开放给目标进程读取，token 和端口没理由让
 * 它们看见。
 *
 * <p>启动失败绝不抛异常：端口被占、没网权限之类只写进状态，界面照常可用。
 */
public final class McpManager {

    private static final String PREFS = "mcp";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PORT = "port";
    private static final String KEY_LAN = "lan";

    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private static McpServer server;
    private static String cachedToken;
    /** 前台服务是否已经拉起来（避免 sync 反复 startService 造成 onStartCommand 递归）。 */
    private static boolean serviceWanted;

    private McpManager() {
    }

    // ------------------------------------------------------------------ 设置

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static int port(Context ctx) {
        int p = prefs(ctx).getInt(KEY_PORT, McpServer.DEFAULT_PORT);
        return p >= MIN_PORT && p <= MAX_PORT ? p : McpServer.DEFAULT_PORT;
    }

    /** 端口非法返回 false，不写库。 */
    public static boolean setPort(Context ctx, int p) {
        if (p < MIN_PORT || p > MAX_PORT) {
            return false;
        }
        prefs(ctx).edit().putInt(KEY_PORT, p).apply();
        return true;
    }

    public static boolean lan(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LAN, false);
    }

    public static void setLan(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_LAN, on).apply();
    }

    // ------------------------------------------------------------------ token

    public static String token(Context ctx) {
        String t = cachedToken;
        if (t == null) {
            t = McpToken.loadOrCreate(ctx.getFilesDir());
            cachedToken = t;
        }
        return t;
    }

    public static String resetToken(Context ctx) {
        cachedToken = McpToken.reset(ctx.getFilesDir());
        return cachedToken;
    }

    public static File tokenFile(Context ctx) {
        return McpToken.file(ctx.getFilesDir());
    }

    // ------------------------------------------------------------------ 生命周期

    /** 按当前设置对齐服务状态（幂等）。界面打开、开关切换、开机后都可以调。 */
    public static synchronized void sync(Context ctx) {
        boolean want = enabled(ctx);
        if (!want) {
            serviceWanted = false;
            try {
                ctx.stopService(new Intent(ctx, McpService.class));
            } catch (Throwable ignored) {
                // 服务没起过就无所谓
            }
            stop();
            return;
        }
        ensureService(ctx);
        if (server != null && server.running()
                && server.port() == port(ctx)) {
            // 端口没变就复用；局域网开关变了要重建（绑定地址不同）
            Object lanFlag = server.status().get("lan");
            if (Boolean.valueOf(lan(ctx)).equals(lanFlag)) {
                return;
            }
        }
        stop();
        start(ctx);
    }

    private static void start(Context ctx) {
        int port = port(ctx);
        boolean lan = lan(ctx);
        String token = token(ctx);
        McpDispatcher dispatcher = new McpDispatcher(
                McpTools.build(new AndroidMcpBackend(ctx)), appVersion(ctx));
        File log = new File(ctx.getFilesDir(), "mcp.log");
        McpServer s = new McpServer(port, lan, token, dispatcher, log);
        server = s;
        s.start();
    }

    /** 打开 MCP 时拉起前台服务保活；失败（ROM 限制）不影响监听本身。 */
    private static void ensureService(Context ctx) {
        if (serviceWanted) {
            return;
        }
        serviceWanted = true;
        try {
            Intent i = new Intent(ctx, McpService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            serviceWanted = false;
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public static boolean running() {
        McpServer s = server;
        return s != null && s.running();
    }

    public static McpServer current() {
        return server;
    }

    public static String lastError() {
        McpServer s = server;
        return s == null ? null : s.lastError();
    }

    /** 给 {@code subdata.status} 与界面共用的一份状态快照。 */
    public static Map<String, Object> statusMap(Context ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        McpServer s = server;
        m.put("enabled", enabled(ctx));
        m.put("running", s != null && s.running());
        m.put("host", lan(ctx) ? "0.0.0.0" : "127.0.0.1");
        m.put("port", (long) port(ctx));
        m.put("lan", lan(ctx));
        m.put("requests", s == null ? 0L : s.requests());
        m.put("auth_failures", s == null ? 0L : s.authFailures());
        if (s != null && s.lastError() != null) {
            m.put("error", s.lastError());
        }
        return m;
    }

    static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 只给单测/诊断用：把缓存清掉，下次重新读文件。 */
    static void clearTokenCache() {
        cachedToken = null;
    }
}
