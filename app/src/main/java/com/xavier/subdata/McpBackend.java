package com.xavier.subdata;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具实现与真实设备之间的唯一接缝。
 *
 * <p>存在的意义是可测试性：真实实现 {@link AndroidMcpBackend} 要 root、要 Android Context，
 * 在纯 JVM 单测里跑不了；测试用假实现就能把「工具参数校验 / 危险操作确认 / 白名单守卫」
 * 全部覆盖，而不需要真机或 Robolectric。
 */
public interface McpBackend {

    String versionName();

    int versionCode();

    String androidRelease();

    boolean rootOk();

    // ---- 白名单

    List<String> targets();

    boolean isWhitelisted(String pkg);

    void addTarget(String pkg);

    void removeTarget(String pkg);

    String activeTarget();

    // ---- 单个目标的 slot 状态

    boolean appInstalled(String pkg);

    String currentSlot(String pkg);

    Map<String, String> conf(String pkg);

    List<String> slots(String pkg);

    Map<String, Long> slotSizes(String pkg);

    boolean createSlot(String pkg, String slot);

    /** 写 slot 文件并强制停止目标 App。 */
    boolean switchSlot(String pkg, String slot);

    boolean deleteSlot(String pkg, String slot);

    String logTail(String pkg, int lines);

    /** 生成诊断报告全文。 */
    String diagnosticsReport(String pkg);

    /** 把诊断报告落盘，返回绝对路径；失败返回 null。 */
    String saveDiagnostics(String pkg, String content);

    /** MCP 服务自身的运行状态（端口、局域网、请求数）。 */
    Map<String, Object> mcpStatus();
}
