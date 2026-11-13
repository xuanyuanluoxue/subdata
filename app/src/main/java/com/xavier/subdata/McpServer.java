package com.xavier.subdata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 的 HTTP 传输层：{@code POST /mcp} + Bearer 鉴权 + JSON-RPC 分发。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>默认只听 127.0.0.1</b>，只有用户显式打开「允许局域网访问」才绑 0.0.0.0；</li>
 *   <li>每个连接一个短任务（4 个线程的上限），所有 JSON-RPC 分发丢进<b>单线程</b>执行器
 *       —— 切槽和 force-stop 不是并发安全的，串行化比吞吐重要；</li>
 *   <li>任何异常都只影响当前请求，绝不外抛把服务带崩；</li>
 *   <li>日志写 {@code files/mcp.log}，超过 512 KiB 轮转一次。</li>
 * </ul>
 *
 * <p>HTTP 层自己写的（见 {@link McpHttp}）：Android 没有 com.sun.net.httpserver，
 * 而这个端点只需要「一个 POST + 一个响应」，没必要为此引入 NanoHTTPD/Ktor。
 */
public final class McpServer {

    public static final int DEFAULT_PORT = 8773;
    private static final int SOCKET_TIMEOUT_MS = 15000;
    private static final long TOOL_TIMEOUT_MS = 60000L;
    private static final long LOG_MAX_BYTES = 512L * 1024L;

    private final int port;
    private final boolean lan;
    private final String token;
    private final McpDispatcher dispatcher;
    private final File logFile;

    private final ExecutorService connections;
    private final ExecutorService serial;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong authFailures = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptor;
    private volatile boolean running;
    private volatile String lastError;
    private volatile long startedAt;

    public McpServer(int port, boolean lan, String token, McpDispatcher dispatcher, File logFile) {
        this.port = port;
        this.lan = lan;
        this.token = token;
        this.dispatcher = dispatcher;
        this.logFile = logFile;
        this.connections = Executors.newFixedThreadPool(4, daemon("subdata-mcp-conn"));
        this.serial = Executors.newSingleThreadExecutor(daemon("subdata-mcp-tool"));
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动监听。返回 false 表示端口被占/绑定失败 —— 调用方只需提示，不影响 App 其它功能。 */
    public synchronized boolean start() {
        if (running) {
            return true;
        }
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            InetAddress addr = InetAddress.getByName(lan ? "0.0.0.0" : "127.0.0.1");
            ss.bind(new InetSocketAddress(addr, port), 16);
            serverSocket = ss;
            lastError = null;
            startedAt = System.currentTimeMillis();
            running = true;
            Thread t = new Thread(this::acceptLoop, "subdata-mcp-accept");
            t.setDaemon(true);
            acceptor = t;
            t.start();
            log("start listen=" + (lan ? "0.0.0.0" : "127.0.0.1") + ":" + port);
            return true;
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
            running = false;
            log("start failed: " + lastError);
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (Throwable ignored) {
                // 关闭失败无所谓
            }
        }
        Thread t = acceptor;
        if (t != null) {
            t.interrupt();
        }
        acceptor = null;
        log("stop");
    }

    public boolean running() {
        return running;
    }

    public int port() {
        return port;
    }

    public long requests() {
        return requests.get();
    }

    public long authFailures() {
        return authFailures.get();
    }

    public String lastError() {
        return lastError;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("host", lan ? "0.0.0.0" : "127.0.0.1");
        m.put("port", (long) port);
        m.put("lan", lan);
        m.put("requests", requests.get());
        m.put("auth_failures", authFailures.get());
        m.put("since", startedAt);
        if (lastError != null) {
            m.put("error", lastError);
        }
        return m;
    }

    // ------------------------------------------------------------------ 接收循环

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                connections.submit(() -> handle(s));
            } catch (Throwable e) {
                if (!running) {
                    return;
                }
                // 单次 accept 失败（EMFILE 等）不致命，歇一下继续
                lastError = String.valueOf(e);
                sleep(200);
            }
        }
    }

    private void handle(Socket socket) {
        long t0 = System.currentTimeMillis();
        try (Socket s = socket) {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            McpHttp.Request req;
            try {
                req = McpHttp.parse(in);
            } catch (McpHttp.BadRequest bad) {
                write(out, McpHttp.response(400, "Bad Request", "application/json",
                        Json.write(McpDispatcher.error(-32700, bad.getMessage(), null))));
                log("bad-request " + bad.getMessage());
                return;
            }

            String route = req.route();
            if (!"/mcp".equals(route) && !"/mcp/".equals(route)) {
                write(out, McpHttp.response(404, "Not Found", "application/json",
                        Json.write(McpDispatcher.error(-32600, "unknown endpoint, use POST /mcp",
                                null))));
                log("404 " + route);
                return;
            }
            if (!"POST".equals(req.method)) {
                write(out, McpHttp.response(405, "Method Not Allowed", "application/json",
                        Json.write(McpDispatcher.error(-32600,
                                "only POST /mcp (SSE not implemented)", null))));
                log("405 " + req.method);
                return;
            }

            String provided = McpToken.fromAuthorization(req.header("authorization"));
            if (!McpToken.matches(provided, token)) {
                authFailures.incrementAndGet();
                write(out, McpHttp.response(401, "Unauthorized", "application/json",
                        Json.write(McpDispatcher.error(-32001, "invalid bearer token", null))));
                log("401 auth-fail");
                return;
            }

            Object parsed;
            try {
                parsed = Json.parse(req.body);
            } catch (Json.ParseException pe) {
                write(out, McpHttp.response(400, "Bad Request", "application/json",
                        Json.write(McpDispatcher.error(-32700, "parse error: " + pe.getMessage(),
                                null))));
                log("parse-error " + pe.getMessage());
                return;
            }
            if (!(parsed instanceof Map)) {
                write(out, McpHttp.response(400, "Bad Request", "application/json",
                        Json.write(McpDispatcher.error(-32600, "batch not supported", null))));
                log("batch-rejected");
                return;
            }

            requests.incrementAndGet();
            Map<String, Object> response;
            try {
                Future<Map<String, Object>> f = serial.submit(() -> dispatcher.handle(parsed));
                response = f.get(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                response = McpDispatcher.error(-32603, "internal error: "
                        + e.getClass().getSimpleName(), null);
            }

            if (response == null) {
                write(out, McpHttp.response(202, "Accepted", null, ""));
                log("202 notification " + elapsed(t0));
                return;
            }
            write(out, McpHttp.response(200, "OK", "application/json; charset=utf-8",
                    Json.write(response)));
            log("200 " + toolName(parsed) + " " + elapsed(t0));
        } catch (Throwable e) {
            // 连接级异常（客户端提前断开等）只记一行，不影响服务
            log("conn-error " + e.getClass().getSimpleName());
        }
    }

    private static void write(OutputStream out, byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (Throwable ignored) {
            // 客户端断开就算了
        }
    }

    private static String toolName(Object parsed) {
        Map<String, Object> m = Json.obj(parsed);
        String method = Json.str(m.get("method"), "?");
        if ("tools/call".equals(method)) {
            String name = Json.str(Json.obj(m.get("params")).get("name"), "?");
            return method + ":" + name;
        }
        return method;
    }

    private static String elapsed(long t0) {
        return (System.currentTimeMillis() - t0) + "ms";
    }

    // ------------------------------------------------------------------ 日志

    private void log(String line) {
        if (logFile == null) {
            return;
        }
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String text = stamp + " [mcp] " + line + "\n";
        synchronized (McpServer.class) {
            try {
                File parent = logFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    return;
                }
                if (logFile.length() > LOG_MAX_BYTES) {
                    File old = new File(logFile.getParentFile(), logFile.getName() + ".1");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    logFile.renameTo(old);
                }
                try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // 日志写不进去不能影响协议
            }
        }
    }

    private static ThreadFactory daemon(final String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
