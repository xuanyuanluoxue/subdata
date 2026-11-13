package com.xavier.subdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 解析/序列化：MCP 的唯一外部协议层，必须严格且可预测。 */
public class JsonTest {

    @Test
    public void parseObjectBasic() throws Exception {
        Map<String, Object> m = Json.obj(Json.parse("{\"a\":1,\"b\":\"x\"}"));
        assertEquals(Long.valueOf(1), m.get("a"));
        assertEquals("x", m.get("b"));
    }

    @Test
    public void parseNestedArray() throws Exception {
        Object o = Json.parse("{\"tools\":[{\"name\":\"a\"},{\"name\":\"b\"}]}");
        List<Object> tools = Json.arr(Json.obj(o).get("tools"));
        assertEquals(2, tools.size());
        assertEquals("b", Json.str(Json.obj(tools.get(1)).get("name"), null));
    }

    @Test
    public void parseUnicodeEscape() throws Exception {
        assertEquals("中", Json.parse("\"\\u4e2d\""));
    }

    @Test
    public void parseDoubleAndLong() throws Exception {
        assertEquals(Double.valueOf(1.5), Json.parse("1.5"));
        assertEquals(Long.valueOf(42), Json.parse("42"));
        assertEquals(Long.valueOf(-7), Json.parse("-7"));
    }

    @Test
    public void parseBooleansAndNull() throws Exception {
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
    }

    @Test
    public void parseEmptyContainers() throws Exception {
        assertTrue(Json.obj(Json.parse("{}")).isEmpty());
        assertTrue(Json.arr(Json.parse("[]")).isEmpty());
    }

    @Test
    public void parseRejectsTrailingData() {
        try {
            Json.parse("{\"a\":1} garbage");
            fail("expected ParseException");
        } catch (Json.ParseException expected) {
            // ok
        }
    }

    @Test
    public void parseRejectsUnterminated() {
        try {
            Json.parse("{\"a\":\"x");
            fail("expected ParseException");
        } catch (Json.ParseException expected) {
            // ok
        }
    }

    @Test
    public void parseRejectsTooDeep() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append('[');
        }
        for (int i = 0; i < 40; i++) {
            sb.append(']');
        }
        try {
            Json.parse(sb.toString());
            fail("expected ParseException");
        } catch (Json.ParseException expected) {
            // ok
        }
    }

    @Test
    public void writeRoundTrip() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "subdata.status");
        m.put("ok", true);
        m.put("n", 3L);
        String text = Json.write(m);
        Map<String, Object> back = Json.obj(Json.parse(text));
        assertEquals("subdata.status", back.get("name"));
        assertEquals(Boolean.TRUE, back.get("ok"));
        assertEquals(Long.valueOf(3), back.get("n"));
    }

    @Test
    public void writeEscapesControlChars() {
        String text = Json.write("a\"b\nc\\d");
        assertEquals("\"a\\\"b\\nc\\\\d\"", text);
        assertTrue(Json.quote("x").startsWith("\""));
    }

    @Test
    public void helpersUseDefaults() {
        assertEquals("d", Json.str(null, "d"));
        assertEquals(5L, Json.num("5", 1));
        assertEquals(1L, Json.num("abc", 1));
        assertTrue(Json.bool("true", false));
        assertTrue(Json.bool("YES", false));
        assertFalse(Json.bool(Boolean.FALSE, true));
        assertEquals(0, Json.obj(null).size());
        assertEquals(0, Json.arr(null).size());
    }

    @Test
    public void parseReturnsEmptyMapForBadShape() {
        // 非对象交给 obj() 时返回空表而不是抛异常 —— 工具层据此报「缺少参数」
        assertEquals(0, Json.obj("string").size());
        assertEquals(0, Json.arr(123L).size());
    }
}
