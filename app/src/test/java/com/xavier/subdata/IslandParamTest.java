package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 超级岛 JSON 生成的纯逻辑测试（不依赖 Android）。
 *
 * <p>格式来自真机 {@code dumpsys notification --noredact} 里已经进岛的那条通知，
 * 字段名 / 结构一个都不能漂。
 */
public class IslandParamTest {

    private static final String SLOT = "b";

    @Test
    public void extrasKeysMatchTheRealDump() {
        assertEquals("android.requestPromotedOngoing", IslandParam.EXTRA_PROMOTED);
        assertEquals("miui.focus.param", IslandParam.EXTRA_FOCUS_PARAM);
        assertEquals("miui.focus.pics", IslandParam.EXTRA_FOCUS_PICS);
        assertEquals("miui.focus.pic_icon", IslandParam.PIC_NAME);
        assertEquals("subdata_slot", IslandParam.BUSINESS);
        assertEquals("副本数据", IslandParam.TITLE);
        assertEquals("subdata_island", IslandParam.CHANNEL_ID);
    }

    @Test
    public void content_mentionsSlotAndIsolation() {
        assertEquals("当前使用 b 槽 · 与原数据隔离", IslandParam.content(SLOT));
        assertEquals("当前使用 工作2 槽 · 与原数据隔离", IslandParam.content("工作2"));
        assertTrue(IslandParam.content(null).contains("?"));
    }

    @Test
    public void json_hasEveryFieldFromTheDump() {
        String j = IslandParam.json(SLOT);
        String[] must = {
                "\"param_v2\":{",
                "\"protocol\":1",
                "\"business\":\"subdata_slot\"",
                "\"updatable\":true",
                "\"enableFloat\":false",
                "\"ticker\":\"副本数据\"",
                "\"aodTitle\":\"副本数据\"",
                "\"param_island\":{",
                "\"islandProperty\":1",
                "\"bigIslandArea\":{\"imageTextInfoLeft\":{",
                "\"type\":1",
                "\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_icon\"}",
                "\"textInfo\":{\"title\":\"副本数据\"}",
                "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_icon\"}}",
                "\"baseInfo\":{\"type\":2",
                "\"title\":\"副本数据\"",
                "\"content\":\"当前使用 b 槽 · 与原数据隔离\"",
        };
        for (String s : must) {
            assertTrue("missing " + s + " in " + j, j.contains(s));
        }
    }

    @Test
    public void json_startsAtParamV2AndIsSingleLine() {
        String j = IslandParam.json(SLOT);
        assertTrue(j, j.startsWith("{\"param_v2\":{"));
        assertTrue(j, j.endsWith("}}"));
        assertFalse(j, j.contains("\n"));
        assertFalse(j, j.contains("\r"));
    }

    @Test
    public void json_bracesAreBalanced() {
        String j = IslandParam.json(SLOT);
        int depth = 0;
        int objectDepth = 0;
        int arrayDepth = 0;
        boolean inString = false;
        boolean esc = false;
        for (int i = 0; i < j.length(); i++) {
            char c = j.charAt(i);
            if (inString) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '{':
                    objectDepth++;
                    break;
                case '}':
                    objectDepth--;
                    break;
                case '[':
                    arrayDepth++;
                    break;
                case ']':
                    arrayDepth--;
                    break;
                default:
                    break;
            }
            depth = objectDepth + arrayDepth;
            assertTrue("negative depth at " + i, depth >= 0);
        }
        assertFalse("unterminated string", inString);
        assertEquals(0, objectDepth);
        assertEquals(0, arrayDepth);
    }

    @Test
    public void quote_escapesWhatJsonCaresAbout() {
        assertEquals("\"a\"", IslandParam.quote("a"));
        assertEquals("\"a\\\"b\"", IslandParam.quote("a\"b"));
        assertEquals("\"a\\\\b\"", IslandParam.quote("a\\b"));
        assertEquals("\"a\\nb\"", IslandParam.quote("a\nb"));
        assertEquals("\"a\\tb\"", IslandParam.quote("a\tb"));
        assertEquals("\"\\u0001\"", IslandParam.quote("\u0001"));
        assertEquals("null", IslandParam.quote(null));
        // 中文和 · 不能转义
        assertEquals("\"副本数据·\"", IslandParam.quote("副本数据·"));
    }

    @Test
    public void json_survivesQuotesInsideSlotName() {
        // slot 名本来就被 sanitize 限死了，这里只保证拼 JSON 不炸
        String j = IslandParam.json("a\"b");
        assertEquals(1, count(j, "\\\""));
        assertTrue(j.contains("当前使用 a\\\"b 槽"));
    }

    @Test
    public void json_isStableForSameSlot() {
        assertEquals(IslandParam.json("b"), IslandParam.json("b"));
        assertFalse(IslandParam.json("b").equals(IslandParam.json("c")));
    }

    @Test
    public void json_fitsInAnIntentExtra() {
        // 真机那条是 500 字节上下；超过 4KB 就说明哪里拼重复了
        assertTrue(IslandParam.json("work").length() < 4096);
    }

    private static int count(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }
}
