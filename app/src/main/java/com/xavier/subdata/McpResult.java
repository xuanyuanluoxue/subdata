package com.xavier.subdata;

/**
 * 一次工具调用的结果：MCP 规范里就是 {@code content} + {@code isError}。
 *
 * <p>content 只有一条 text —— 内容是 JSON 字符串。这样既能被模型直接读，也能被脚本
 * {@code jq} 二次处理；没有图片/资源类型的需求，不引入多段 content 的复杂度。
 */
public final class McpResult {

    public final boolean isError;
    public final String text;

    private McpResult(boolean isError, String text) {
        this.isError = isError;
        this.text = text == null ? "" : text;
    }

    public static McpResult ok(String text) {
        return new McpResult(false, text);
    }

    public static McpResult ok(Object jsonValue) {
        return new McpResult(false, Json.write(jsonValue));
    }

    public static McpResult error(String message) {
        return new McpResult(true, Json.write(errorBody(message)));
    }

    private static java.util.Map<String, Object> errorBody(String message) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message == null ? "unknown error" : message);
        return m;
    }
}
