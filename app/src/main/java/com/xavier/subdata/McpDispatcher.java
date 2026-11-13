package com.xavier.subdata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 2.0 + MCP 方法分发。
 *
 * <p>支持的 MCP 方法：{@code initialize}、{@code tools/list}、{@code tools/call}、
 * {@code ping}，以及通知 {@code notifications/initialized}（无响应）。不做 SSE / 资源 /
 * 提示词，够 DSH、小爱这类客户端接上来查状态和切槽。
 *
 * <p>纯逻辑、不碰网络与 Android，单测直接喂 Map 断言返回的 Map。
 */
public final class McpDispatcher {

    /** MCP 协议版本（2025-06-18 是当前稳定版；客户端报旧版也照样工作）。 */
    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final String SERVER_NAME = "subdata";

    private final List<McpTool> tools;
    private final String version;

    public McpDispatcher(List<McpTool> tools, String version) {
        this.tools = tools == null ? new ArrayList<McpTool>() : tools;
        this.version = version == null ? "0.0.0" : version;
    }

    public List<McpTool> tools() {
        return tools;
    }

    /**
     * 处理一条已解析的 JSON-RPC 消息。
     *
     * @return 响应对象；通知（没有 id）返回 {@code null} —— 传输层据此回 202 空体。
     */
    public Map<String, Object> handle(Object body) {
        if (!(body instanceof Map)) {
            return error(-32600, "请求必须是 JSON 对象", null);
        }
        Map<String, Object> req = Json.obj(body);
        boolean hasId = req.containsKey("id");
        Object id = req.get("id");

        String jsonrpc = Json.str(req.get("jsonrpc"), null);
        if (jsonrpc != null && !"2.0".equals(jsonrpc)) {
            return error(-32600, "jsonrpc 必须是 \"2.0\"", id);
        }
        String method = Json.str(req.get("method"), null);
        if (method == null || method.isEmpty()) {
            return error(-32600, "缺少 method", id);
        }
        Map<String, Object> params = Json.obj(req.get("params"));

        // 通知：按 JSON-RPC 2.0 一律不回包
        if (!hasId) {
            return null;
        }

        switch (method) {
            case "initialize":
                return ok(id, initializeResult());
            case "ping":
                return ok(id, new LinkedHashMap<String, Object>());
            case "tools/list":
                return ok(id, toolsListResult());
            case "tools/call":
                return toolsCall(id, params);
            default:
                return error(-32601, "不支持的方法：" + method, id);
        }
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("protocolVersion", PROTOCOL_VERSION);
        Map<String, Object> caps = new LinkedHashMap<>();
        Map<String, Object> toolsCap = new LinkedHashMap<>();
        toolsCap.put("listChanged", false);
        caps.put("tools", toolsCap);
        res.put("capabilities", caps);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", SERVER_NAME);
        info.put("title", "SubData MCP");
        info.put("version", version);
        res.put("serverInfo", info);
        res.put("instructions", "SubData 是 Xposed 模块，把目标 App 的数据目录按 slot 隔离。"
                + "用 subdata.status / subdata.slots.list 查看，subdata.slots.switch 切槽"
                + "（会强停目标 App）。危险操作前先问用户。");
        return res;
    }

    private Map<String, Object> toolsListResult() {
        List<Object> list = new ArrayList<>();
        for (McpTool t : tools) {
            list.add(t.descriptor());
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("tools", list);
        return res;
    }

    private Map<String, Object> toolsCall(Object id, Map<String, Object> params) {
        String name = Json.str(params.get("name"), null);
        if (name == null || name.isEmpty()) {
            return error(-32602, "tools/call 缺少 params.name", id);
        }
        Map<String, Object> args = Json.obj(params.get("arguments"));
        McpResult r = McpTools.invoke(tools, name, args);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", r.text);
        List<Object> arr = new ArrayList<>();
        arr.add(content);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("content", arr);
        res.put("isError", r.isError);
        return ok(id, res);
    }

    // ------------------------------------------------------------------ 组装

    static Map<String, Object> ok(Object id, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    public static Map<String, Object> error(int code, String message, Object id) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", (long) code);
        err.put("message", message == null ? "error" : message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", err);
        return m;
    }
}
