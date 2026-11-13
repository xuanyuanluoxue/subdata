package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** HTTP 请求解析与响应组装（MCP 传输层的边界）。 */
public class McpHttpTest {

    private static McpHttp.Request parse(String raw) throws Exception {
        InputStream in = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        return McpHttp.parse(in);
    }

    @Test
    public void parsesPostWithBody() throws Exception {
        McpHttp.Request r = parse("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Content-Length: 17\r\n\r\n{\"jsonrpc\":\"2.0\"}");
        assertEquals("POST", r.method);
        assertEquals("/mcp", r.path);
        assertEquals("HTTP/1.1", r.version);
        assertEquals("{\"jsonrpc\":\"2.0\"}", r.body);
    }

    @Test
    public void headerLookupIsCaseInsensitive() throws Exception {
        McpHttp.Request r = parse("POST /mcp HTTP/1.1\r\n"
                + "Authorization: Bearer abc123\r\nContent-Length: 0\r\n\r\n");
        assertEquals("Bearer abc123", r.header("authorization"));
        assertEquals("Bearer abc123", r.header("AUTHORIZATION"));
        assertNull(r.header("missing"));
    }

    @Test
    public void routeStripsQueryString() throws Exception {
        assertEquals("/mcp", parse("POST /mcp?x=1 HTTP/1.1\r\n\r\n").route());
        assertEquals("/mcp", parse("GET /mcp HTTP/1.1\r\n\r\n").route());
    }

    @Test
    public void rejectsGarbageRequestLine() {
        try {
            parse("hello world\r\n\r\n");
            fail("expected BadRequest");
        } catch (Exception expected) {
            assertTrue(expected instanceof McpHttp.BadRequest);
        }
    }

    @Test
    public void rejectsNonHttpVersion() {
        try {
            parse("POST /mcp FOO/1.1\r\n\r\n");
            fail("expected BadRequest");
        } catch (Exception expected) {
            assertTrue(expected instanceof McpHttp.BadRequest);
        }
    }

    @Test
    public void rejectsTooLargeBody() {
        try {
            parse("POST /mcp HTTP/1.1\r\nContent-Length: " + (McpHttp.MAX_BODY + 1) + "\r\n\r\n");
            fail("expected BadRequest");
        } catch (Exception expected) {
            assertTrue(expected instanceof McpHttp.BadRequest);
        }
    }

    @Test
    public void rejectsBadContentLength() {
        try {
            parse("POST /mcp HTTP/1.1\r\nContent-Length: abc\r\n\r\n");
            fail("expected BadRequest");
        } catch (Exception expected) {
            assertTrue(expected instanceof McpHttp.BadRequest);
        }
    }

    @Test
    public void responseIncludesStatusAndLength() {
        byte[] raw = McpHttp.response(200, "OK", "application/json", "hi");
        String text = new String(raw, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(text.contains("Content-Length: 2\r\n"));
        assertTrue(text.contains("Content-Type: application/json\r\n"));
        assertTrue(text.endsWith("\r\n\r\nhi"));
    }

    @Test
    public void unauthorizedResponseHasChallenge() {
        String text = new String(McpHttp.response(401, "Unauthorized", "application/json", ""),
                StandardCharsets.UTF_8);
        assertTrue(text.contains("WWW-Authenticate: Bearer"));
        assertTrue(text.contains("Content-Length: 0"));
    }

    @Test
    public void emptyBodyIsHandled() throws Exception {
        McpHttp.Request r = parse("POST /mcp HTTP/1.1\r\n\r\n");
        assertEquals("", r.body);
        assertEquals(0, r.headers.size());
    }

    @Test
    public void reasonsAreMapped() {
        assertEquals("Bad Request", McpHttp.reason(400));
        assertEquals("Unauthorized", McpHttp.reason(401));
        assertEquals("Not Found", McpHttp.reason(404));
        assertEquals("Method Not Allowed", McpHttp.reason(405));
        assertFalse(McpHttp.reason(500).isEmpty());
    }

    @Test
    public void malformedHeaderLineIsIgnored() throws Exception {
        McpHttp.Request r = parse("POST /mcp HTTP/1.1\r\nbrokenheader\r\nContent-Length: 0\r\n\r\n");
        // 没有冒号的行被忽略，而合法的 Content-Length 仍然解析出来
        assertEquals(1, r.headers.size());
        assertEquals("0", r.header("content-length"));
    }

    @Test
    public void parsesMultipleHeaders() throws Exception {
        McpHttp.Request r = parse("POST /mcp HTTP/1.1\r\nHost: a\r\nAuthorization: Bearer t\r\n"
                + "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
        assertEquals(4, r.headers.size());
        assertEquals("{}", r.body);
        assertEquals("application/json", r.header("content-type"));
        Map<String, String> headers = r.headers;
        assertTrue(headers.containsKey("host"));
    }
}
