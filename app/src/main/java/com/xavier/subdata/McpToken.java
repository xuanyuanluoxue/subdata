package com.xavier.subdata;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * MCP 的 Bearer token：生成、存放、校验。
 *
 * <p>存放位置是模块 App 的私有目录 {@code files/mcp.token}（{@code /data/data/com.xavier.subdata/files/}）。
 * 这个目录别的 App 读不到；同类 App 想读要有 root，而 root 本来就能读一切 —— 所以边界设在
 * 「不额外泄露」：token 不进日志、不进诊断报告、不落 SharedPreferences（后者会被 Xposed 的
 * world-readable 模式开放给目标进程）。
 *
 * <p>权限收成 600：只有模块自己的 uid 能读写。
 */
public final class McpToken {

    public static final String FILE_NAME = "mcp.token";

    /** URL-safe Base64 的 32 字节 = 43 个字符，够 256 bit 熵。 */
    private static final int BYTES = 32;

    private McpToken() {
    }

    public static File file(File filesDir) {
        return new File(filesDir, FILE_NAME);
    }

    /** 生成一个新 token（不落盘）。 */
    public static String generate() {
        byte[] raw = new byte[BYTES];
        new SecureRandom().nextBytes(raw);
        return base64Url(raw);
    }

    /**
     * 读 token；不存在或内容非法就生成一个并写入。任何 IO 失败都返回新生成的值（内存态），
     * 让 MCP 服务仍可用 —— 只是重启后会换 token。
     */
    public static String loadOrCreate(File filesDir) {
        try {
            File f = file(filesDir);
            String cur = read(filesDir);
            if (cur != null) {
                return cur;
            }
            String fresh = generate();
            write(f, fresh);
            return fresh;
        } catch (Throwable t) {
            return generate();
        }
    }

    /** 读现有 token；没有/为空/含空白则返回 null。 */
    public static String read(File filesDir) {
        File f = file(filesDir);
        if (!f.isFile()) {
            return null;
        }
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f),
                StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[256];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 1024) {
                    break;
                }
            }
            String s = sb.toString().trim();
            return valid(s) ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 重新生成并覆盖；返回新 token。 */
    public static String reset(File filesDir) {
        String fresh = generate();
        write(file(filesDir), fresh);
        return fresh;
    }

    public static boolean write(File f, String token) {
        if (!valid(token)) {
            return false;
        }
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false),
                    StandardCharsets.UTF_8)) {
                w.write(token);
                w.write('\n');
                w.flush();
            }
            // 先全关再只给属主：顺序不能反，否则 chmod 前有一段 world-readable 窗口
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setExecutable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 只接受 URL-safe Base64、长度合理，挡住空串/带空格/带换行的脏数据。 */
    public static boolean valid(String token) {
        if (token == null) {
            return false;
        }
        int n = token.length();
        if (n < 16 || n > 128) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * 常量时间比较，避免通过响应时间猜 token。
     *
     * <p>长度不同也走完整比较（用 {@link MessageDigest#isEqual}），提前 return 会泄露长度。
     */
    public static boolean matches(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        byte[] a = provided.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /** 从 {@code Authorization: Bearer xxx} 里取 token；格式不对返回 null。 */
    public static String fromAuthorization(String header) {
        if (header == null) {
            return null;
        }
        String h = header.trim();
        int sp = h.indexOf(' ');
        if (sp <= 0) {
            return null;
        }
        String scheme = h.substring(0, sp);
        String value = h.substring(sp + 1).trim();
        if (!"bearer".equalsIgnoreCase(scheme) || value.isEmpty()) {
            return null;
        }
        return value;
    }

    static String base64Url(byte[] raw) {
        StringBuilder sb = new StringBuilder((raw.length * 4 + 2) / 3);
        final String alpha =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int i = 0;
        while (i + 3 <= raw.length) {
            int n = ((raw[i] & 0xff) << 16) | ((raw[i + 1] & 0xff) << 8) | (raw[i + 2] & 0xff);
            sb.append(alpha.charAt((n >> 18) & 63));
            sb.append(alpha.charAt((n >> 12) & 63));
            sb.append(alpha.charAt((n >> 6) & 63));
            sb.append(alpha.charAt(n & 63));
            i += 3;
        }
        int rem = raw.length - i;
        if (rem == 1) {
            int n = (raw[i] & 0xff) << 16;
            sb.append(alpha.charAt((n >> 18) & 63));
            sb.append(alpha.charAt((n >> 12) & 63));
        } else if (rem == 2) {
            int n = ((raw[i] & 0xff) << 16) | ((raw[i + 1] & 0xff) << 8);
            sb.append(alpha.charAt((n >> 18) & 63));
            sb.append(alpha.charAt((n >> 12) & 63));
            sb.append(alpha.charAt((n >> 6) & 63));
        }
        return sb.toString();
    }
}
