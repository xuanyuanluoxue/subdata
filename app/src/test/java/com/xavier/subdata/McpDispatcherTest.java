package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-RPC 2.0 / MCP 方法分发。 */
public class McpDispatcherTest {

    private static McpDispatcher dispatcher() {
        List<McpTool> tools = new ArrayList<>();
        tools.add(new McpTool("echo", "回显参数", McpTool.LOW,
                schema(), args -> McpResult.ok(args)));
        tools.add(new McpTool("boom", "抛异常", McpTool.MEDIUM,
                schema(), args -> {
                    throw new IllegalStateException("kaboom");
                }));
        return new McpDispatcher(tools, "0.4.0");
    }

    private static Map<String, Object> schema() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", new LinkedHashMap<String, Object>());
        return m;
    }

    private static Map<String, Object> req(Object id, String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        if (id != null) {
            m.put("id", id);
        }
        m.put("method", method);
        if (params != null) {
            m.put("params", params);
        }
        return m;
    }

    @Test
    public void initializeShape() {
        Map<String, Object> res = dispatcher().handle(req(1L, "initialize", null));
        assertEquals("2.0", res.get("jsonrpc"));
        assertEquals(1L, res.get("id"));
        Map<String, Object> result = Json.obj(res.get("result"));
        assertEquals(McpDispatcher.PROTOCOL_VERSION, result.get("protocolVersion"));
        Map<String, Object> info = Json.obj(result.get("serverInfo"));
        assertEquals(McpDispatcher.SERVER_NAME, info.get("name"));
        assertEquals("0.4.0", info.get("version"));
        assertNotNull(Json.obj(result.get("capabilities")).get("tools"));
    }

    @Test
    public void toolsListHasDescriptors() {
        Map<String, Object> res = dispatcher().handle(req("x", "tools/list", null));
        List<Object> tools = Json.arr(Json.obj(res.get("result")).get("tools"));
        assertEquals(2, tools.size());
        Map<String, Object> first = Json.obj(tools.get(0));
        assertEquals("echo", first.get("name"));
        assertNotNull(first.get("inputSchema"));
        assertEquals("low", Json.obj(first.get("annotations")).get("danger"));
    }

    @Test
    public void toolsCallSuccess() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "echo");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("pkg", "mark.via");
        params.put("arguments", args);
        Map<String, Object> res = dispatcher().handle(req(2L, "tools/call", params));
        Map<String, Object> result = Json.obj(res.get("result"));
        assertEquals(Boolean.FALSE, result.get("isError"));
        List<Object> content = Json.arr(result.get("content"));
        assertEquals("text", Json.obj(content.get(0)).get("type"));
        assertTrue(String.valueOf(Json.obj(content.get(0)).get("text")).contains("mark.via"));
    }

    @Test
    public void toolsCallUnknownToolIsIsError() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "nope");
        Map<String, Object> res = dispatcher().handle(req(3L, "tools/call", params));
        Map<String, Object> result = Json.obj(res.get("result"));
        assertEquals(Boolean.TRUE, result.get("isError"));
    }

    @Test
    public void toolsCallHandlerThrowIsIsError() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "boom");
        Map<String, Object> res = dispatcher().handle(req(4L, "tools/call", params));
        assertEquals(Boolean.TRUE, Json.obj(res.get("result")).get("isError"));
    }

    @Test
    public void toolsCallWithoutNameIsInvalidParams() {
        Map<String, Object> res = dispatcher().handle(req(5L, "tools/call", new LinkedHashMap<>()));
        Map<String, Object> err = Json.obj(res.get("error"));
        assertEquals(-32602L, err.get("code"));
    }

    @Test
    public void unknownMethodIsMethodNotFound() {
        Map<String, Object> res = dispatcher().handle(req(6L, "resources/list", null));
        assertEquals(-32601L, Json.obj(res.get("error")).get("code"));
    }

    @Test
    public void notificationGetsNoResponse() {
        assertNull(dispatcher().handle(req(null, "notifications/initialized", null)));
        assertNull(dispatcher().handle(req(null, "tools/call", null)));
    }

    @Test
    public void pingReturnsEmptyResult() {
        Map<String, Object> res = dispatcher().handle(req(7L, "ping", null));
        assertNotNull(res.get("result"));
        assertEquals(0, Json.obj(res.get("result")).size());
    }

    @Test
    public void wrongJsonrpcVersionIsRejected() {
        Map<String, Object> bad = req(8L, "ping", null);
        bad.put("jsonrpc", "1.0");
        Map<String, Object> res = dispatcher().handle(bad);
        assertEquals(-32600L, Json.obj(res.get("error")).get("code"));
    }

    @Test
    public void missingMethodIsRejected() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("jsonrpc", "2.0");
        bad.put("id", 9L);
        Map<String, Object> res = dispatcher().handle(bad);
        assertEquals(-32600L, Json.obj(res.get("error")).get("code"));
    }

    @Test
    public void nonObjectBodyIsInvalidRequest() {
        Map<String, Object> res = dispatcher().handle("[]");
        assertEquals(-32600L, Json.obj(res.get("error")).get("code"));
        assertNull(Json.obj(res.get("error")).get("data"));
        assertFalse(res.containsKey("result"));
    }
}
