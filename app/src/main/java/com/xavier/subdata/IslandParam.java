package com.xavier.subdata;

/**
 * 小米超级岛（HyperOS 焦点通知）的参数拼装。
 *
 * <p>只做字符串/JSON 拼装，不碰 Android API，可以直接单元测试。真正的发通知在
 * {@link IslandNotice}。
 *
 * <p>JSON 形状是照真机上已经进岛的一条通知 dump 出来的
 * （{@code dumpsys notification --noredact}），字段名一个都不能改：
 * <pre>
 * miui.focus.param={"param_v2":{"protocol":1,"business":"dsh_live_running",
 *   "updatable":true,"enableFloat":false,"ticker":"…","aodTitle":"…",
 *   "param_island":{"islandProperty":1,
 *     "bigIslandArea":{"imageTextInfoLeft":{"type":1,
 *         "picInfo":{"type":1,"pic":"miui.focus.pic_icon"},
 *         "textInfo":{"title":"…"}}},
 *     "smallIslandArea":{"picInfo":{"type":1,"pic":"miui.focus.pic_icon"}}},
 *   "baseInfo":{"type":2,"title":"…","content":"…"}}}
 * </pre>
 *
 * <p>注意：文案故意没有走 {@code strings.xml}。模块代码是注入进目标 App 进程运行的，
 * 那里只有目标 App 的资源，模块自己的 {@code R.string} 根本不存在（硬用会变成
 * Resources$NotFoundException），所以这几个字符串只能写在常量里。
 */
public final class IslandParam {

    /** 焦点通知的开关位，必须放进 Notification extras。 */
    public static final String EXTRA_PROMOTED = "android.requestPromotedOngoing";
    /** 超级岛参数 JSON。 */
    public static final String EXTRA_FOCUS_PARAM = "miui.focus.param";
    /** 图标 Bundle（可选）。 */
    public static final String EXTRA_FOCUS_PICS = "miui.focus.pics";

    /** JSON 里引用的图标名，需要和 {@link #EXTRA_FOCUS_PICS} 里的 key 一致。 */
    public static final String PIC_NAME = "miui.focus.pic_icon";

    /** 超级岛业务标识。 */
    public static final String BUSINESS = "subdata_slot";

    /** 大岛 / 小岛 / ticker 都用这一句。 */
    public static final String TITLE = "副本数据";

    /** 通知渠道（无声、高重要度）。 */
    public static final String CHANNEL_ID = "subdata_island";
    public static final String CHANNEL_NAME = "副本数据提示";
    public static final String CHANNEL_DESC = "非主槽运行时提示当前处于副本数据";

    private IslandParam() {
    }

    /** 大岛正文，例如「当前使用 b 槽 · 与原数据隔离」。 */
    public static String content(String slot) {
        return "当前使用 " + (slot == null ? "?" : slot) + " 槽 · 与原数据隔离";
    }

    /**
     * 生成 {@code miui.focus.param} 的完整 JSON。
     *
     * @param slot 当前 slot 名，只会出现在 {@code baseInfo.content} 里
     */
    public static String json(String slot) {
        final String title = quote(TITLE);
        final String pic = quote(PIC_NAME);
        final String biz = quote(BUSINESS);
        final String text = quote(content(slot));
        StringBuilder sb = new StringBuilder(640);
        sb.append("{\"param_v2\":{");
        sb.append("\"protocol\":1,");
        sb.append("\"business\":").append(biz).append(',');
        sb.append("\"updatable\":true,");
        sb.append("\"enableFloat\":false,");
        sb.append("\"ticker\":").append(title).append(',');
        sb.append("\"aodTitle\":").append(title).append(',');
        sb.append("\"param_island\":{");
        sb.append("\"islandProperty\":1,");
        sb.append("\"bigIslandArea\":{\"imageTextInfoLeft\":{");
        sb.append("\"type\":1,");
        sb.append("\"picInfo\":{\"type\":1,\"pic\":").append(pic).append("},");
        sb.append("\"textInfo\":{\"title\":").append(title).append('}');
        sb.append("}},");
        sb.append("\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":").append(pic)
                .append("}}");
        sb.append("},");
        sb.append("\"baseInfo\":{\"type\":2,");
        sb.append("\"title\":").append(title).append(',');
        sb.append("\"content\":").append(text);
        sb.append("}}");
        sb.append('}');
        return sb.toString();
    }

    /**
     * 最小 JSON 字符串转义（够用：这些文案都是我们自己写的，不需要处理代理对）。
     */
    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = hex.length(); k < 4; k++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
