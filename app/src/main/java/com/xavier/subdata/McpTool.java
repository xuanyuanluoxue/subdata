package com.xavier.subdata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 MCP tool 的元数据 + 执行体。
 *
 * <p>危险级别（{@link #danger}）是这个项目自定义的标注，会出现在 {@code tools/list} 的结果里，
 * 让 AI 客户端在做危险操作前有机会先问用户。同时映射成 MCP 官方 annotations
 * （{@code readOnlyHint} / {@code destructiveHint}），两边都不落下。
 */
public final class McpTool {

    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";

    public interface Handler {
        McpResult call(Map<String, Object> args);
    }

    public final String name;
    public final String description;
    public final String danger;
    public final Map<String, Object> inputSchema;
    public final Handler handler;

    public McpTool(String name, String description, String danger,
                   Map<String, Object> inputSchema, Handler handler) {
        this.name = name;
        this.description = description;
        this.danger = danger;
        this.inputSchema = inputSchema;
        this.handler = handler;
    }

    public boolean readOnly() {
        return LOW.equals(danger);
    }

    public boolean destructive() {
        return HIGH.equals(danger);
    }

    /** tools/list 里的一条。 */
    public Map<String, Object> descriptor() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", inputSchema);
        Map<String, Object> ann = new LinkedHashMap<>();
        ann.put("title", name);
        ann.put("danger", danger);
        ann.put("readOnlyHint", readOnly());
        ann.put("destructiveHint", destructive());
        ann.put("idempotentHint", readOnly());
        ann.put("openWorldHint", false);
        m.put("annotations", ann);
        return m;
    }
}
