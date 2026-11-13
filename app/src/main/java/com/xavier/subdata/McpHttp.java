package com.xavier.subdata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 最小 HTTP/1.1 请求解析 —— 只做 MCP 需要的那一小块。
 *
 * <p>为什么自己写：Android 上没有 {@code com.sun.net.httpserver}，引 NanoHTTPD/Ktor 又会给
 * 一个只有几个类的模块平添依赖与体积。MCP 的传输层只需要「读请求行 + 头 + Content-Length
 * 限定的 JSON body」，150 行足够，而且能脱离 Android 用 {@link java.io.ByteArrayInputStream}
 * 做完整单测。
 *
 * <p>安全边界：请求行/头总长上限、body 上限、header 数上限，避免一个畸形请求把线程拖死。
 */
public final class McpHttp {

    /** body 上限：MCP 请求都很小，256 KiB 已经非常宽松。 */
    public static final int MAX_BODY = 256 * 1024;
    private static final int MAX_HEAD_BYTES = 16 * 1024;
    private static final int MAX_HEADERS = 64;

    public static final class Request {
        public final String method;
        public final String path;
        public final String version;
        public final Map<String, String> headers;
        public final String body;

        Request(String method, String path, String version, Map<String, String> headers,
                String body) {
            this.method = method;
            this.path = path;
            this.version = version;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        }

        /** 去掉 query string 的路径，{@code /mcp?x=1} → {@code /mcp}。 */
        public String route() {
            int q = path.indexOf('?');
            return q < 0 ? path : path.substring(0, q);
        }
    }

    /** 请求不合法（超长、残缺、不是 HTTP）。由服务器回 400。 */
    public static final class BadRequest extends Exception {
        public BadRequest(String msg) {
            super(msg);
        }
    }

    private McpHttp() {
    }

    public static Request parse(InputStream in) throws IOException, BadRequest {
        String requestLine = readLine(in, 4096);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new BadRequest("empty request line");
        }
        String[] parts = requestLine.split(" +");
        if (parts.length < 3) {
            throw new BadRequest("bad request line");
        }
        if (!parts[2].startsWith("HTTP/")) {
            throw new BadRequest("not http");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        int headBytes = requestLine.length();
        for (int n = 0; ; n++) {
            String line = readLine(in, 8192);
            if (line == null) {
                throw new BadRequest("truncated headers");
            }
            if (line.isEmpty()) {
                break;
            }
            if (n >= MAX_HEADERS) {
                throw new BadRequest("too many headers");
            }
            headBytes += line.length();
            if (headBytes > MAX_HEAD_BYTES) {
                throw new BadRequest("headers too large");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(colon + 1).trim();
            headers.put(key, val);
        }
        int len = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new BadRequest("bad content-length");
            }
        }
        if (len < 0 || len > MAX_BODY) {
            throw new BadRequest("body too large");
        }
        String body = readBody(in, len);
        return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1], parts[2], headers, body);
    }

    private static String readBody(InputStream in, int len) throws IOException {
        if (len == 0) {
            return "";
        }
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }

    /** 读一行（不含 CRLF）；到 EOF 且没读到任何字节返回 null。 */
    static String readLine(InputStream in, int max) throws IOException, BadRequest {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (buf.size() == 0) {
                    return null;
                }
                break;
            }
            if (b == '\n') {
                break;
            }
            if (b == '\r') {
                continue;
            }
            if (buf.size() >= max) {
                throw new BadRequest("line too long");
            }
            buf.write(b);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 组装 HTTP 响应字节；{@code body} 为空时也能正确给出 Content-Length: 0。 */
    public static byte[] response(int status, String reason, String contentType, String body) {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(160);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        head.append("Content-Length: ").append(payload.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Cache-Control: no-store\r\n");
        if (status == 401) {
            head.append("WWW-Authenticate: Bearer realm=\"subdata-mcp\"\r\n");
        }
        head.append("\r\n");
        byte[] hb = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[hb.length + payload.length];
        System.arraycopy(hb, 0, out, 0, hb.length);
        System.arraycopy(payload, 0, out, hb.length, payload.length);
        return out;
    }

    public static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 202:
                return "Accepted";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 415:
                return "Unsupported Media Type";
            default:
                return status >= 500 ? "Internal Server Error" : "OK";
        }
    }
}
