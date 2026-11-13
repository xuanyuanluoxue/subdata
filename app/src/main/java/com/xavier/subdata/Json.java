package com.xavier.subdata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小的 JSON 读写实现 —— MCP 层唯一需要的「外部协议」解析器。
 *
 * <p>为什么不引第三方库：模块刻意保持零运行时依赖（现在只有 Material Components），
 * 而 MCP 只需要「解析一个小对象 + 序列化小对象」这两件事，一个 200 行的递归下降解析器
 * 就够，还能被纯 JVM 单测完整覆盖。org.json 在单元测试里是空实现（android.jar stub），
 * 用它会导致 74 个已有单测之外还要跑 Robolectric，得不偿失。
 *
 * <p>约定：
 * <ul>
 *   <li>对象解析成 {@link LinkedHashMap}（保序，方便日志和断言）；</li>
 *   <li>数组解析成 {@link ArrayList}；</li>
 *   <li>整数解析成 {@link Long}，带小数点/指数的解析成 {@link Double}；</li>
 *   <li>解析失败抛 {@link ParseException}，由调用方转成 JSON-RPC 的 -32700。</li>
 * </ul>
 */
public final class Json {

    /** JSON 语法错误。带位置信息，方便排查客户端拼错的地方。 */
    public static final class ParseException extends Exception {
        public ParseException(String msg) {
            super(msg);
        }
    }

    private static final int MAX_DEPTH = 32;

    private Json() {
    }

    // ------------------------------------------------------------------ 解析

    public static Object parse(String text) throws ParseException {
        if (text == null) {
            throw new ParseException("null input");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (!p.eof()) {
            throw new ParseException("trailing data at " + p.i);
        }
        return v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) throws ParseException {
            if (depth > MAX_DEPTH) {
                throw new ParseException("nesting too deep");
            }
            if (eof()) {
                throw new ParseException("unexpected end of input");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    return number();
            }
        }

        Object literal(String word, Object val) throws ParseException {
            if (!s.startsWith(word, i)) {
                throw new ParseException("bad literal at " + i);
            }
            i += word.length();
            return val;
        }

        Map<String, Object> object(int depth) throws ParseException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (!eof() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (eof() || s.charAt(i) != '"') {
                    throw new ParseException("expected key at " + i);
                }
                String key = string();
                skipWs();
                if (eof() || s.charAt(i) != ':') {
                    throw new ParseException("expected ':' at " + i);
                }
                i++;
                skipWs();
                m.put(key, value(depth + 1));
                skipWs();
                if (eof()) {
                    throw new ParseException("unterminated object");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return m;
                }
                throw new ParseException("expected ',' or '}' at " + i);
            }
        }

        List<Object> array(int depth) throws ParseException {
            List<Object> out = new ArrayList<>();
            i++; // [
            skipWs();
            if (!eof() && s.charAt(i) == ']') {
                i++;
                return out;
            }
            while (true) {
                skipWs();
                out.add(value(depth + 1));
                skipWs();
                if (eof()) {
                    throw new ParseException("unterminated array");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return out;
                }
                throw new ParseException("expected ',' or ']' at " + i);
            }
        }

        String string() throws ParseException {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new ParseException("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new ParseException("bad escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw new ParseException("bad \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException nfe) {
                            throw new ParseException("bad \\u escape at " + i);
                        }
                        i += 4;
                        break;
                    default:
                        throw new ParseException("bad escape \\" + e);
                }
            }
        }

        Object number() throws ParseException {
            int start = i;
            if (!eof() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            boolean fp = false;
            while (!eof()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    fp = true;
                    i++;
                } else {
                    break;
                }
            }
            String raw = s.substring(start, i);
            if (raw.isEmpty()) {
                throw new ParseException("unexpected char at " + start);
            }
            try {
                if (!fp) {
                    return Long.valueOf(raw);
                }
                return Double.valueOf(raw);
            } catch (NumberFormatException nfe) {
                throw new ParseException("bad number: " + raw);
            }
        }
    }

    // ------------------------------------------------------------------ 序列化

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quoteInto(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quoteInto(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            Object[] arr = (Object[]) v;
            for (int k = 0; k < arr.length; k++) {
                if (k > 0) {
                    sb.append(',');
                }
                writeValue(sb, arr[k]);
            }
            sb.append(']');
        } else {
            quoteInto(sb, v.toString());
        }
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s == null ? 4 : s.length() + 2);
        quoteInto(sb, s);
        return sb.toString();
    }

    private static void quoteInto(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ 取值助手

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    public static String str(Object o, String def) {
        return o instanceof String ? (String) o : def;
    }

    public static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof String) {
            String s = ((String) o).trim().toLowerCase();
            return "true".equals(s) || "1".equals(s) || "yes".equals(s);
        }
        return def;
    }

    public static long num(Object o, long def) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof String) {
            try {
                return Long.parseLong(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
