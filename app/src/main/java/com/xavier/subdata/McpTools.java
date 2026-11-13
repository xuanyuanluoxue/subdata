package com.xavier.subdata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具集：参数校验 + 危险操作守卫 + 结果组装。
 *
 * <p>这一层刻意<b>完全不碰 Android API</b>（后端通过 {@link McpBackend} 注入），
 * 所以「白名单外 pkg 必须被拒」「delete 没有 confirm=true 必须被拒」「slot 名非法必须被拒」
 * 这些最要紧的安全规则都能在纯 JVM 单测里跑，不依赖真机。
 *
 * <p>危险级别：
 * <ul>
 *   <li>{@link McpTool#LOW} 只读，随时可调；</li>
 *   <li>{@link McpTool#MEDIUM} 会改配置/建目录，可回退；</li>
 *   <li>{@link McpTool#HIGH} 会强停 App 或删数据，必须显式确认。</li>
 * </ul>
 */
public final class McpTools {

    /** 工具内部的可预期失败（参数/状态不满足），转成 {@code isError} 而不是协议错误。 */
    static final class ToolError extends RuntimeException {
        ToolError(String msg) {
            super(msg);
        }
    }

    private McpTools() {
    }

    // ------------------------------------------------------------------ 工具表

    public static List<McpTool> build(final McpBackend b) {
        List<McpTool> tools = new ArrayList<>();

        tools.add(new McpTool("subdata.status",
                "模块总览：版本、root、白名单目标各自的当前 slot 与槽目录、MCP 服务状态。只读。",
                McpTool.LOW, schema(props(), new String[0]),
                args -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    Map<String, Object> mod = new LinkedHashMap<>();
                    mod.put("name", ModuleMeta.MODULE_PKG);
                    mod.put("version", b.versionName());
                    mod.put("versionCode", (long) b.versionCode());
                    mod.put("android", b.androidRelease());
                    out.put("module", mod);
                    out.put("root", b.rootOk());
                    List<Object> targets = new ArrayList<>();
                    for (String pkg : b.targets()) {
                        targets.add(targetBrief(b, pkg));
                    }
                    out.put("targets", targets);
                    out.put("mcp", b.mcpStatus());
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.targets.list",
                "列出模块白名单里的目标 App，以及界面当前选中的那一个。只读。",
                McpTool.LOW, schema(props(), new String[0]),
                args -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("targets", new ArrayList<Object>(b.targets()));
                    out.put("active", b.activeTarget());
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.targets.add",
                "把已安装的 App 加入白名单（只写白名单，不改它的任何数据）。",
                McpTool.MEDIUM, schema(props(
                        "pkg", str("目标应用包名，例如 mark.via")), "pkg"),
                args -> {
                    String pkg = parsePkg(args);
                    if (b.targets().size() >= 20) {
                        throw new ToolError("白名单已满（最多 20 个）");
                    }
                    if (!b.appInstalled(pkg)) {
                        throw new ToolError("未安装该应用：" + pkg);
                    }
                    b.addTarget(pkg);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    out.put("targets", new ArrayList<Object>(b.targets()));
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.targets.remove",
                "把 App 移出白名单。只改白名单，不删它的数据目录。",
                McpTool.MEDIUM, schema(props(
                        "pkg", str("目标应用包名")), "pkg"),
                args -> {
                    String pkg = requirePkg(b, args);
                    if (!b.isWhitelisted(pkg)) {
                        throw new ToolError("不在白名单：" + pkg);
                    }
                    b.removeTarget(pkg);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    out.put("targets", new ArrayList<Object>(b.targets()));
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.slots.list",
                "列出某个目标 App 的槽（含每个槽的占用体积）和当前生效的槽。只读。",
                McpTool.LOW, schema(props(
                        "pkg", str("目标应用包名")), "pkg"),
                args -> {
                    String pkg = requirePkg(b, args);
                    requireInstalled(b, pkg);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    out.put("current", b.currentSlot(pkg));
                    List<Object> slots = new ArrayList<>();
                    Map<String, Long> sizes = b.slotSizes(pkg);
                    for (String name : b.slots(pkg)) {
                        Map<String, Object> s = new LinkedHashMap<>();
                        long bytes = SlotStats.sizeOf(sizes, name);
                        s.put("name", name);
                        s.put("bytes", bytes);
                        s.put("size", SlotStats.formatSize(bytes));
                        s.put("current", name.equals(b.currentSlot(pkg)));
                        slots.add(s);
                    }
                    out.put("slots", slots);
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.slots.create",
                "新建一个空槽目录（不克隆数据；首次进入该槽时由模块按 clone_on_first_run 决定是否克隆）。",
                McpTool.MEDIUM, schema(props(
                        "pkg", str("目标应用包名"),
                        "slot", str("槽名，[A-Za-z0-9_-]{1,32}，例如 b")), "pkg", "slot"),
                args -> {
                    String pkg = requirePkg(b, args);
                    String slot = requireSlot(args);
                    requireInstalled(b, pkg);
                    if (b.slots(pkg).contains(slot)) {
                        throw new ToolError("槽已存在：" + slot);
                    }
                    if (!b.createSlot(pkg, slot)) {
                        throw new ToolError("建目录失败（root 被拒？）：" + slot);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    out.put("slot", slot);
                    out.put("slots", new ArrayList<Object>(b.slots(pkg)));
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.slots.switch",
                "切到指定槽：写 slot 文件并【立即 force-stop 目标 App】，下次启动即生效。"
                        + "危险级别高：会杀掉目标 App 的进程。",
                McpTool.HIGH, schema(props(
                        "pkg", str("目标应用包名"),
                        "slot", str("要切到的槽名（必须已存在）")), "pkg", "slot"),
                args -> {
                    String pkg = requirePkg(b, args);
                    String slot = requireSlot(args);
                    requireInstalled(b, pkg);
                    List<String> slots = b.slots(pkg);
                    if (!slots.contains(slot)) {
                        throw new ToolError("槽不存在：" + slot + "（已有：" + join(slots) + "）");
                    }
                    String from = b.currentSlot(pkg);
                    if (slot.equals(from)) {
                        Map<String, Object> same = new LinkedHashMap<>();
                        same.put("ok", true);
                        same.put("pkg", pkg);
                        same.put("slot", slot);
                        same.put("changed", false);
                        same.put("note", "already current");
                        return McpResult.ok(same);
                    }
                    if (!b.switchSlot(pkg, slot)) {
                        throw new ToolError("写 slot 文件失败（root 被拒？）");
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    out.put("from", from);
                    out.put("slot", slot);
                    out.put("changed", true);
                    out.put("force_stopped", true);
                    out.put("note", "目标 App 已被强停，重新打开即使用该槽的数据");
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.slots.delete",
                "删除某个槽的目录（数据不可恢复）。必须 confirm=true；不能删当前槽，也不能删主槽。",
                McpTool.HIGH, schema(props(
                        "pkg", str("目标应用包名"),
                        "slot", str("要删除的槽名"),
                        "confirm", bool("必须显式传 true，否则拒绝执行")),
                        "pkg", "slot", "confirm"),
                args -> {
                    String pkg = requirePkg(b, args);
                    String slot = requireSlot(args);
                    if (!Json.bool(args.get("confirm"), false)) {
                        throw new ToolError("拒绝执行：删除槽必须显式传 confirm=true");
                    }
                    requireInstalled(b, pkg);
                    if (!b.slots(pkg).contains(slot)) {
                        throw new ToolError("槽不存在：" + slot);
                    }
                    String current = b.currentSlot(pkg);
                    if (slot.equals(current)) {
                        throw new ToolError("拒绝删除当前正在使用的槽 " + slot + "，请先切到别的槽");
                    }
                    String main = SlotConfig.sanitize(b.conf(pkg).get("main_slot"));
                    if (main == null) {
                        main = SlotConfig.DEFAULT_MAIN_SLOT;
                    }
                    if (slot.equals(main)) {
                        throw new ToolError("拒绝删除主槽 " + slot + "（那是原始数据的槽）");
                    }
                    if (!b.deleteSlot(pkg, slot)) {
                        throw new ToolError("删除失败（root 被拒？）");
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    out.put("slot", slot);
                    out.put("deleted", true);
                    out.put("slots", new ArrayList<Object>(b.slots(pkg)));
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.log.tail",
                "读目标 App 的 subdata.log 尾部（模块注入日志）。只读。",
                McpTool.LOW, schema(props(
                        "pkg", str("目标应用包名"),
                        "lines", integer("要读多少行，1..200，默认 50")), "pkg"),
                args -> {
                    String pkg = requirePkg(b, args);
                    int lines = (int) Math.max(1, Math.min(200, Json.num(args.get("lines"), 50)));
                    String tail = b.logTail(pkg, lines);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    out.put("lines", lines);
                    out.put("text", tail == null ? "" : tail);
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.health.check",
                "配置体检：槽目录缺失、模块被关、hook 过窄、外部存储重复等。只读。",
                McpTool.LOW, schema(props(
                        "pkg", str("目标应用包名")), "pkg"),
                args -> {
                    String pkg = requirePkg(b, args);
                    requireInstalled(b, pkg);
                    Map<String, String> conf = b.conf(pkg);
                    String slot = b.currentSlot(pkg);
                    List<String> slots = b.slots(pkg);
                    List<HealthCheck.Issue> issues = HealthCheck.inspect(conf, slot, slots);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    HealthCheck.Level worst = HealthCheck.worst(issues);
                    out.put("worst", worst == null ? "none" : worst.name().toLowerCase());
                    List<Object> list = new ArrayList<>();
                    for (HealthCheck.Issue i : issues) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", i.code.name().toLowerCase());
                        m.put("level", i.level.name().toLowerCase());
                        if (i.slot != null) {
                            m.put("slot", i.slot);
                        }
                        list.add(m);
                    }
                    out.put("issues", list);
                    return McpResult.ok(out);
                }));

        tools.add(new McpTool("subdata.diagnostics.export",
                "生成诊断报告（包名/slot/配置/日志，不含账号内容），返回全文与落盘路径。",
                McpTool.LOW, schema(props(
                        "pkg", str("目标应用包名；省略则用当前选中的目标")), new String[0]),
                args -> {
                    String raw = Json.str(args.get("pkg"), null);
                    String pkg = raw == null || raw.trim().isEmpty()
                            ? b.activeTarget() : requirePkg(b, args);
                    if (pkg == null) {
                        throw new ToolError("没有可用的目标 App，请先 add 一个");
                    }
                    String report = b.diagnosticsReport(pkg);
                    String path = b.saveDiagnostics(pkg, report);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("pkg", pkg);
                    out.put("path", path);
                    out.put("bytes", (long) (report == null ? 0 : report.length()));
                    out.put("report", report);
                    return McpResult.ok(out);
                }));

        return tools;
    }

    // ------------------------------------------------------------------ 校验

    private static void requireInstalled(McpBackend b, String pkg) {
        if (!b.appInstalled(pkg)) {
            throw new ToolError("目标 App 不可用（未安装或数据目录不可读）：" + pkg);
        }
    }

    /** 只校验包名格式（`subdata.targets.add` 用：它要能加一个还没进白名单的包）。 */
    static String parsePkg(Map<String, Object> args) {
        String raw = Json.str(args.get("pkg"), null);
        if (raw == null || raw.trim().isEmpty()) {
            throw new ToolError("缺少参数 pkg");
        }
        String pkg = SlotConfig.sanitizePkg(raw);
        if (pkg == null) {
            throw new ToolError("非法包名：" + raw);
        }
        return pkg;
    }

    /** pkg 必须在白名单内且通过 {@link SlotConfig#sanitizePkg}。 */
    static String requirePkg(McpBackend b, Map<String, Object> args) {
        String pkg = parsePkg(args);
        if (!b.isWhitelisted(pkg)) {
            throw new ToolError("包名不在白名单内，拒绝操作：" + pkg
                    + "（先用 subdata.targets.add 加入）");
        }
        return pkg;
    }

    /** slot 名必须匹配 {@code [A-Za-z0-9_-]{1,32}}。 */
    static String requireSlot(Map<String, Object> args) {
        String raw = Json.str(args.get("slot"), null);
        if (raw == null || raw.trim().isEmpty()) {
            throw new ToolError("缺少参数 slot");
        }
        String slot = SlotConfig.sanitize(raw);
        if (slot == null) {
            throw new ToolError("非法槽名：" + raw + "（只允许 [A-Za-z0-9_-]{1,32}）");
        }
        return slot;
    }

    // ------------------------------------------------------------------ 调度

    /** 按名字找工具并执行；所有可预期失败都转成 {@code isError} 文本。 */
    public static McpResult invoke(List<McpTool> tools, String name,
                                   Map<String, Object> args) {
        if (name == null || name.isEmpty()) {
            return McpResult.error("缺少工具名");
        }
        for (McpTool t : tools) {
            if (t.name.equals(name)) {
                try {
                    return t.handler.call(args == null
                            ? new LinkedHashMap<String, Object>() : args);
                } catch (ToolError e) {
                    return McpResult.error(e.getMessage());
                } catch (Throwable t2) {
                    return McpResult.error("内部错误：" + t2.getClass().getSimpleName()
                            + (t2.getMessage() == null ? "" : " " + t2.getMessage()));
                }
            }
        }
        return McpResult.error("未知工具：" + name);
    }

    // ------------------------------------------------------------------ 组装助手

    private static Map<String, Object> targetBrief(McpBackend b, String pkg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pkg", pkg);
        boolean installed = b.appInstalled(pkg);
        m.put("installed", installed);
        if (installed) {
            m.put("slot", b.currentSlot(pkg));
            List<Object> slots = new ArrayList<>();
            Map<String, Long> sizes = b.slotSizes(pkg);
            for (String s : b.slots(pkg)) {
                slots.add(s);
            }
            m.put("slots", slots);
            m.put("sizes", sizes);
        }
        return m;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    // ---- JSON Schema 小工具（够用就好） ----

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> schema(Map<String, Object> properties,
                                              String... required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required == null
                ? new ArrayList<>() : java.util.Arrays.asList(required));
        m.put("additionalProperties", false);
        return m;
    }

    private static Map<String, Object> str(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> bool(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "boolean");
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> integer(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "integer");
        m.put("description", desc);
        return m;
    }
}
