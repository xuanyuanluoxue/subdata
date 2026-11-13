package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置体检的判定逻辑。
 *
 * <p>这些用例本质上是在替真机挡枪：每一条 DANGER 都对应一种「装上去会崩」的组合，
 * 先在 JVM 里钉死，比让用户在手机上踩一遍便宜得多。</p>
 */
public class HealthCheckTest {

    private static Map<String, String> conf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, String> healthyConf() {
        return conf("enabled", "true", "clone_on_first_run", "true",
                "redirect_external", "false", "hook_file_paths", "true", "hook_data_dir", "true");
    }

    private static boolean has(HealthCheck check, List<HealthCheck.Issue> list, HealthCheck.Code c) {
        for (HealthCheck.Issue i : list) {
            if (i.code == c) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void healthySetupHasNoIssues() {
        List<HealthCheck.Issue> issues = HealthCheck.inspect(
                healthyConf(), "a", Arrays.asList("a", "b"));
        assertTrue("健康配置不应报问题: " + issues, issues.isEmpty());
        assertNull(HealthCheck.worst(issues));
        assertFalse(HealthCheck.hasDanger(issues));
    }

    @Test
    public void missingSlotDirWithCloneOffIsDanger() {
        List<HealthCheck.Issue> issues = HealthCheck.inspect(
                conf("enabled", "true", "clone_on_first_run", "false",
                        "redirect_external", "false", "hook_file_paths", "true",
                        "hook_data_dir", "true"),
                "b", Collections.singletonList("a"));
        assertTrue(issues.toString(), has(null, issues, HealthCheck.Code.SLOT_DIR_MISSING_NO_CLONE));
        assertTrue(HealthCheck.hasDanger(issues));
        assertEquals(HealthCheck.Level.DANGER, issues.get(0).level);
        assertEquals("b", issues.get(0).slot);
    }

    @Test
    public void missingSlotDirWithCloneOnIsOnlyInfo() {
        List<HealthCheck.Issue> issues = HealthCheck.inspect(
                healthyConf(), "b", Collections.singletonList("a"));
        assertTrue(has(null, issues, HealthCheck.Code.SLOT_DIR_MISSING_WILL_CLONE));
        assertFalse(HealthCheck.hasDanger(issues));
    }

    @Test
    public void disabledModuleIsReportedButNotDanger() {
        Map<String, String> c = healthyConf();
        c.put("enabled", "false");
        List<HealthCheck.Issue> issues = HealthCheck.inspect(c, "a", Collections.singletonList("a"));
        assertTrue(has(null, issues, HealthCheck.Code.MODULE_DISABLED));
        assertFalse(HealthCheck.hasDanger(issues));
    }

    @Test
    public void bothFallbackHooksOffIsWarn() {
        Map<String, String> c = healthyConf();
        c.put("hook_file_paths", "false");
        c.put("hook_data_dir", "false");
        List<HealthCheck.Issue> issues = HealthCheck.inspect(c, "a", Collections.singletonList("a"));
        assertTrue(has(null, issues, HealthCheck.Code.HOOKS_TOO_NARROW));
        assertEquals(HealthCheck.Level.WARN, HealthCheck.worst(issues));
    }

    @Test
    public void confSlotDiffersFromActualSlotIsReported() {
        Map<String, String> c = healthyConf();
        c.put("slot", "b");
        List<HealthCheck.Issue> issues = HealthCheck.inspect(c, "a", Arrays.asList("a", "b"));
        assertTrue(has(null, issues, HealthCheck.Code.SLOT_CONFLICT));
    }

    @Test
    public void emptySlotListBecomesMissingDirInsteadOfDoubleWarning() {
        List<HealthCheck.Issue> issues = HealthCheck.inspect(healthyConf(), "a",
                new ArrayList<String>());
        assertFalse("不该同时报 NO_SLOTS 和缺失",
                has(null, issues, HealthCheck.Code.NO_SLOTS));
        assertTrue(has(null, issues, HealthCheck.Code.SLOT_DIR_MISSING_WILL_CLONE));
    }

    @Test
    public void nullInputsNeverThrow() {
        for (Map<String, String> c : Arrays.asList(null, new LinkedHashMap<String, String>())) {
            List<HealthCheck.Issue> issues = HealthCheck.inspect(c, null, null);
            assertTrue(issues.toString(), has(null, issues, HealthCheck.Code.CONF_EMPTY));
        }
        assertNull(HealthCheck.worst(null));
        assertFalse(HealthCheck.hasDanger(null));
    }

    @Test
    public void dangerousIssueSortsToTheFront() {
        Map<String, String> c = conf("enabled", "true", "clone_on_first_run", "false",
                "redirect_external", "true", "hook_file_paths", "false", "hook_data_dir", "false");
        List<HealthCheck.Issue> issues = HealthCheck.inspect(c, "z", Collections.singletonList("a"));
        assertEquals(HealthCheck.Level.DANGER, issues.get(0).level);
        // 严重度单调不增
        for (int i = 1; i < issues.size(); i++) {
            assertTrue(issues.get(i).level.ordinal() <= issues.get(i - 1).level.ordinal());
        }
    }

    @Test
    public void illegalSlotNamesInDirListAreIgnored() {
        List<HealthCheck.Issue> issues = HealthCheck.inspect(healthyConf(), "a",
                Arrays.asList("a", "..", "bad/name", "subdata"));
        assertTrue("非法目录名应被忽略: " + issues, issues.isEmpty());
    }
}
