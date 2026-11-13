package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具层的安全规则：白名单、参数 sanitize、危险操作确认、当前槽/主槽保护。
 * 全部在纯 JVM 里跑，不需要 root 或真机。
 */
public class McpToolsTest {

    private Fake backend;
    private List<McpTool> tools;

    @Before
    public void setUp() {
        backend = new Fake();
        tools = McpTools.build(backend);
    }

    // ------------------------------------------------------------------ 基础

    @Test
    public void exposesElevenToolsWithUniqueNames() {
        assertEquals(11, tools.size());
        List<String> names = new ArrayList<>();
        for (McpTool t : tools) {
            assertFalse("重复工具名 " + t.name, names.contains(t.name));
            names.add(t.name);
            assertEquals("object", t.inputSchema.get("type"));
            assertTrue(t.inputSchema.containsKey("properties"));
            assertNotNull(t.description);
            assertTrue(t.description.length() > 4);
        }
        assertEquals(Arrays.asList(McpTool.LOW, McpTool.MEDIUM, McpTool.HIGH),
                Arrays.asList("low", "medium", "high"));
    }

    @Test
    public void statusAggregatesTargets() throws Exception {
        Map<String, Object> out = ok("subdata.status");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("0.4.0", Json.obj(out.get("module")).get("version"));
        List<Object> targets = Json.arr(out.get("targets"));
        assertEquals(1, targets.size());
        Map<String, Object> t = Json.obj(targets.get(0));
        assertEquals("mark.via", t.get("pkg"));
        assertEquals("a", t.get("slot"));
        assertNotNull(out.get("mcp"));
    }

    @Test
    public void targetsListReturnsWhitelist() throws Exception {
        Map<String, Object> out = ok("subdata.targets.list");
        assertEquals(Arrays.asList("mark.via"), needsList(out.get("targets")));
        assertEquals("mark.via", out.get("active"));
    }

    @Test
    public void targetsAddAcceptsNewInstalledPackage() throws Exception {
        backend.installed.add("com.new.app");
        Map<String, Object> out = ok("subdata.targets.add", "pkg", "com.new.app");
        assertTrue(backend.whitelist.contains("com.new.app"));
        assertEquals(Boolean.TRUE, out.get("ok"));
    }

    @Test
    public void targetsAddRejectsUninstalled() {
        Map<String, Object> out = err("subdata.targets.add", "pkg", "com.ghost.app");
        assertTrue(String.valueOf(out.get("error")).contains("未安装"));
    }

    @Test
    public void targetsAddRejectsBadPackageName() {
        assertTrue(String.valueOf(err("subdata.targets.add", "pkg", "../etc").get("error"))
                .contains("非法包名"));
    }

    @Test
    public void targetsRemoveOnlyTouchesWhitelist() throws Exception {
        Map<String, Object> out = ok("subdata.targets.remove", "pkg", "mark.via");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertFalse(backend.whitelist.contains("mark.via"));
        assertFalse(backend.deleted);
    }

    // ------------------------------------------------------------------ 隔离规则

    @Test
    public void rejectsPackageOutsideWhitelist() {
        Map<String, Object> out = err("subdata.slots.list", "pkg", "com.other.app");
        assertTrue(String.valueOf(out.get("error")).contains("白名单"));
    }

    @Test
    public void rejectsDangerousPackagePath() {
        Map<String, Object> out = err("subdata.slots.list", "pkg", "..");
        assertTrue(String.valueOf(out.get("error")).contains("非法包名"));
    }

    @Test
    public void rejectsBadSlotNames() {
        for (String bad : new String[]{"../x", "a/b", "a b", "", "x".repeat(33), "subdata"}) {
            Map<String, Object> out = err("subdata.slots.switch", "pkg", "mark.via", "slot", bad);
            assertTrue(bad, String.valueOf(out.get("error")).contains("槽名")
                    || String.valueOf(out.get("error")).contains("缺少参数"));
        }
    }

    @Test
    public void missingPkgIsReported() {
        assertTrue(String.valueOf(err("subdata.slots.list").get("error")).contains("缺少参数 pkg"));
    }

    @Test
    public void notInstalledTargetRejected() {
        backend.installed.remove("mark.via");
        assertTrue(String.valueOf(err("subdata.slots.list", "pkg", "mark.via").get("error"))
                .contains("不可用"));
    }

    // ------------------------------------------------------------------ 切槽

    @Test
    public void slotsListReportsSizes() throws Exception {
        Map<String, Object> out = ok("subdata.slots.list", "pkg", "mark.via");
        assertEquals("a", out.get("current"));
        List<Object> slots = Json.arr(out.get("slots"));
        assertEquals(2, slots.size());
        Map<String, Object> a = Json.obj(slots.get(0));
        assertEquals("a", a.get("name"));
        assertEquals(100L, a.get("bytes"));
        assertEquals(Boolean.TRUE, a.get("current"));
    }

    @Test
    public void switchWritesSlotAndForceStops() throws Exception {
        Map<String, Object> out = ok("subdata.slots.switch", "pkg", "mark.via", "slot", "b");
        assertEquals(Boolean.TRUE, out.get("changed"));
        assertEquals(Boolean.TRUE, out.get("force_stopped"));
        assertEquals("a", out.get("from"));
        assertEquals("b", out.get("slot"));
        assertTrue(backend.switched);
        assertEquals("b", backend.currentByPkg.get("mark.via"));
    }

    @Test
    public void switchToCurrentIsNoop() throws Exception {
        Map<String, Object> out = ok("subdata.slots.switch", "pkg", "mark.via", "slot", "a");
        assertEquals(Boolean.FALSE, out.get("changed"));
        assertFalse(backend.switched);
    }

    @Test
    public void switchToMissingSlotRejected() {
        Map<String, Object> out = err("subdata.slots.switch", "pkg", "mark.via", "slot", "zz");
        assertTrue(String.valueOf(out.get("error")).contains("槽不存在"));
    }

    // ------------------------------------------------------------------ 删除

    @Test
    public void deleteRequiresExplicitConfirm() {
        Map<String, Object> out = err("subdata.slots.delete", "pkg", "mark.via", "slot", "b");
        assertTrue(String.valueOf(out.get("error")).contains("confirm=true"));
        assertFalse(backend.deleted);
    }

    @Test
    public void deleteRejectsConfirmStringFalse() {
        Map<String, Object> out = err("subdata.slots.delete", "pkg", "mark.via",
                "slot", "b", "confirm", "false");
        assertTrue(String.valueOf(out.get("error")).contains("confirm=true"));
        assertFalse(backend.deleted);
    }

    @Test
    public void deleteCurrentSlotRefused() {
        Map<String, Object> out = err("subdata.slots.delete", "pkg", "mark.via",
                "slot", "a", "confirm", Boolean.TRUE);
        assertTrue(String.valueOf(out.get("error")).contains("正在使用"));
        assertFalse(backend.deleted);
    }

    @Test
    public void deleteMainSlotRefused() {
        backend.currentByPkg.put("mark.via", "b");
        backend.conf.put("main_slot", "a");
        Map<String, Object> out = err("subdata.slots.delete", "pkg", "mark.via",
                "slot", "a", "confirm", Boolean.TRUE);
        assertTrue(String.valueOf(out.get("error")).contains("主槽"));
        assertFalse(backend.deleted);
    }

    @Test
    public void deleteNonCurrentSlotWorks() throws Exception {
        Map<String, Object> out = ok("subdata.slots.delete", "pkg", "mark.via",
                "slot", "b", "confirm", Boolean.TRUE);
        assertEquals(Boolean.TRUE, out.get("deleted"));
        assertTrue(backend.deleted);
        assertFalse(backend.slotsOf("mark.via").contains("b"));
    }

    // ------------------------------------------------------------------ 建槽 / 日志 / 体检

    @Test
    public void createDuplicateRejected() {
        assertTrue(String.valueOf(err("subdata.slots.create", "pkg", "mark.via", "slot", "a")
                .get("error")).contains("已存在"));
    }

    @Test
    public void createNewSlotWorks() throws Exception {
        Map<String, Object> out = ok("subdata.slots.create", "pkg", "mark.via", "slot", "c");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertTrue(backend.created);
        assertTrue(backend.slotsOf("mark.via").contains("c"));
    }

    @Test
    public void logTailClampsLines() throws Exception {
        Map<String, Object> high = ok("subdata.log.tail", "pkg", "mark.via", "lines", 9999L);
        assertEquals(200L, high.get("lines"));
        Map<String, Object> low = ok("subdata.log.tail", "pkg", "mark.via", "lines", 0L);
        assertEquals(1L, low.get("lines"));
        assertEquals(50L, ok("subdata.log.tail", "pkg", "mark.via").get("lines"));
    }

    @Test
    public void healthCheckReturnsWorst() throws Exception {
        Map<String, Object> out = ok("subdata.health.check", "pkg", "mark.via");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertNotNull(out.get("worst"));
        assertNotNull(out.get("issues"));
    }

    @Test
    public void diagnosticsDefaultsToActiveTarget() throws Exception {
        Map<String, Object> out = ok("subdata.diagnostics.export");
        assertEquals("mark.via", out.get("pkg"));
        assertNotNull(out.get("report"));
        assertEquals("/tmp/fake-report.md", out.get("path"));
    }

    @Test
    public void unknownToolIsError() {
        McpResult r = McpTools.invoke(tools, "subdata.nope", new LinkedHashMap<>());
        assertTrue(r.isError);
        assertTrue(r.text.contains("未知工具"));
    }

    @Test
    public void nullArgumentsAreTolerated() {
        McpResult r = McpTools.invoke(tools, "subdata.status", null);
        assertFalse(r.isError);
    }

    // ------------------------------------------------------------------ 助手

    private Map<String, Object> ok(String tool, Object... kv) throws Exception {
        McpResult r = McpTools.invoke(tools, tool, args(kv));
        assertFalse(tool + " → " + r.text, r.isError);
        return Json.obj(Json.parse(r.text));
    }

    private Map<String, Object> err(String tool, Object... kv) {
        McpResult r = McpTools.invoke(tools, tool, args(kv));
        assertTrue(tool + " 应当失败", r.isError);
        try {
            return Json.obj(Json.parse(r.text));
        } catch (Json.ParseException e) {
            throw new AssertionError("错误结果不是 JSON：" + r.text);
        }
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static List<String> needsList(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : Json.arr(o)) {
            out.add(String.valueOf(x));
        }
        return out;
    }

    // ------------------------------------------------------------------ 假后端

    private static final class Fake implements McpBackend {
        final List<String> whitelist = new ArrayList<>();
        final List<String> installed = new ArrayList<>();
        final Map<String, List<String>> slotsByPkg = new HashMap<>();
        final Map<String, String> currentByPkg = new HashMap<>();
        final Map<String, Long> sizes = new LinkedHashMap<>();
        final Map<String, String> conf = new LinkedHashMap<>();
        boolean switched;
        boolean created;
        boolean deleted;

        Fake() {
            whitelist.add("mark.via");
            installed.add("mark.via");
            slotsByPkg.put("mark.via", new ArrayList<>(Arrays.asList("a", "b")));
            currentByPkg.put("mark.via", "a");
            sizes.put("a", 100L);
            sizes.put("b", 200L);
            conf.put("main_slot", "a");
        }

        List<String> slotsOf(String pkg) {
            List<String> l = slotsByPkg.get(pkg);
            if (l == null) {
                l = new ArrayList<>();
                slotsByPkg.put(pkg, l);
            }
            return l;
        }

        @Override
        public String versionName() {
            return "0.4.0";
        }

        @Override
        public int versionCode() {
            return 7;
        }

        @Override
        public String androidRelease() {
            return "17";
        }

        @Override
        public boolean rootOk() {
            return true;
        }

        @Override
        public List<String> targets() {
            return new ArrayList<>(whitelist);
        }

        @Override
        public boolean isWhitelisted(String pkg) {
            return whitelist.contains(pkg);
        }

        @Override
        public void addTarget(String pkg) {
            whitelist.add(pkg);
        }

        @Override
        public void removeTarget(String pkg) {
            whitelist.remove(pkg);
        }

        @Override
        public String activeTarget() {
            return whitelist.isEmpty() ? null : whitelist.get(0);
        }

        @Override
        public boolean appInstalled(String pkg) {
            return installed.contains(pkg);
        }

        @Override
        public String currentSlot(String pkg) {
            return currentByPkg.get(pkg);
        }

        @Override
        public Map<String, String> conf(String pkg) {
            return new LinkedHashMap<>(conf);
        }

        @Override
        public List<String> slots(String pkg) {
            return new ArrayList<>(slotsOf(pkg));
        }

        @Override
        public Map<String, Long> slotSizes(String pkg) {
            return new LinkedHashMap<>(sizes);
        }

        @Override
        public boolean createSlot(String pkg, String slot) {
            created = true;
            slotsOf(pkg).add(slot);
            return true;
        }

        @Override
        public boolean switchSlot(String pkg, String slot) {
            switched = true;
            currentByPkg.put(pkg, slot);
            return true;
        }

        @Override
        public boolean deleteSlot(String pkg, String slot) {
            deleted = true;
            slotsOf(pkg).remove(slot);
            return true;
        }

        @Override
        public String logTail(String pkg, int lines) {
            return "installed slot=a hooks=20";
        }

        @Override
        public String diagnosticsReport(String pkg) {
            return "# report for " + pkg + "\n";
        }

        @Override
        public String saveDiagnostics(String pkg, String content) {
            return "/tmp/fake-report.md";
        }

        @Override
        public Map<String, Object> mcpStatus() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("running", false);
            m.put("port", 8773L);
            return m;
        }
    }
}
