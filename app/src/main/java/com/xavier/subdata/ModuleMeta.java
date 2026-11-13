package com.xavier.subdata;

/**
 * 模块自身标识与跨进程共享的常量。
 *
 * <p>纯 Java，<b>不引用任何 Xposed / Android 类</b>。这一点很关键：
 * {@link SubDataModule} implements 了 Xposed 的接口，而 Xposed API 是 {@code compileOnly}，
 * 模块 App 的 APK 里根本没有那些类；一旦模块 App 的代码（界面、广播接收器）引用到
 * {@code SubDataModule} 的静态成员，类加载就会抛 {@code NoClassDefFoundError}。
 * 所以共用常量全部放在这里，Xposed 侧与 App 侧都只依赖它。
 */
public final class ModuleMeta {

    public static final String MODULE_PKG = "com.xavier.subdata";

    /** 模块 App 与注入进程共用的 SharedPreferences 文件名。 */
    public static final String PREFS_NAME = "subdata";

    /** 白名单为空时的内置默认值（只作为兜底，真正的白名单来自 prefs）。 */
    public static final String DEFAULT_TARGETS = "com.chaoxing.mobile";

    /**
     * LSPosed 识别的 meta-data：声明后，被授权的目标进程可以用 {@code XSharedPreferences}
     * 读到模块自己的 prefs。没有它，目标进程读到的永远是默认值（白名单形同虚设）。
     */
    public static final String META_SHARED_PREFS = "xposedsharedprefs";

    /** 小米/HyperOS 的「可上岛」权限；普通 App 声明后由系统自动授予，拿不到就退化成 extras。 */
    public static final String PERM_PROMOTED = "android.permission.POST_PROMOTED_NOTIFICATIONS";

    /** 普通通知运行时权限（API 33+）。 */
    public static final String PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private ModuleMeta() {
    }
}
