package com.xavier.subdata;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口：把目标 App 的数据根重定向到 {@code <dataDir>/subdata/<slot>}。
 *
 * <p>设计原则：
 * <ul>
 *   <li>只动被注入的 App 进程，不动 system_server、不改 APK、不挂载分区。</li>
 *   <li>所有 hook 都在 try/catch 里，单个方法找不到（不同 ROM 差异）不影响其它 hook。</li>
 *   <li>映射幂等：{@code <dataDir>/subdata} 永远原样返回，避免 hook 互相套娃。</li>
 * </ul>
 */
public class SubDataModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    public static final String MODULE_PKG = "com.xavier.subdata";
    public static final String PREFS_NAME = "subdata";
    public static final String DEFAULT_TARGETS = "com.chaoxing.mobile";

    private static final String TAG = "SubData";
    private static final String CONTEXT_IMPL = "android.app.ContextImpl";


    private static volatile PathMap sMap;
    private static volatile File sLogFile;
    private static volatile String sTargets;
    private static volatile boolean sInstalled;

    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log(TAG + ": loaded, path=" + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) {
            return;
        }
        try {
            final String pkg = lp.packageName;
            if (MODULE_PKG.equals(pkg) || "android".equals(pkg)) {
                return;
            }
            if (!isTarget(pkg)) {
                return;
            }
            install(pkg, lp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": handleLoadPackage failed: " + t);
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------ 配置

    /** 白名单来自模块自己的 SharedPreferences；读不到就退回内置默认包名。 */
    private static boolean isTarget(String pkg) {
        String targets = sTargets;
        if (targets == null) {
            targets = readTargets();
            sTargets = targets;
        }
        for (String s : targets.split("[,;\\s]+")) {
            if (pkg.equals(s.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String readTargets() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(MODULE_PKG, PREFS_NAME);
            prefs.reload();
            String v = prefs.getString("targets", null);
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cannot read module prefs: " + t);
        }
        return DEFAULT_TARGETS;
    }

    // ------------------------------------------------------------------ 安装

    private static void install(String pkg, XC_LoadPackage.LoadPackageParam lp) {
        if (sInstalled) {
            return;
        }
        final String dataDir = resolveDataDir(pkg, lp.appInfo);
        final String deDir = lp.appInfo != null ? lp.appInfo.deviceProtectedDataDir : null;

        final SlotConfig cfg = SlotConfig.load(new File(dataDir));
        sLogFile = new File(cfg.controlDir, SlotConfig.LOG_FILE);
        // 通知模块自己写同一份日志；不能让它反过来引用本类（见 IslandNotice.sLogFile 注释）
        IslandNotice.setLogFile(sLogFile);

        if (!cfg.enabled) {
            logFile(pkg + ": disabled by " + SlotConfig.CONF_FILE);
            return;
        }

        // 顺序不能反：DataCloner 看到 slot 目录已经存在就当「已初始化过」直接返回，
        // 所以必须先克隆、再补骨架。
        final StringBuilder sb = new StringBuilder();
        boolean cloned = false;
        if (cfg.cloneOnFirstRun) {
            cloned = DataCloner.ensureSlot(new File(dataDir), cfg.slot, sb);
            if (cloned) {
                logFile(pkg + ": " + sb);
            }
        }

        // 即使不克隆，也要把 slot 的标准目录骨架和 DE 目录建出来，
        // 否则 App 一启动就 SQLiteCantOpenDatabaseException / SharedPreferences 建不了目录。
        final SlotSkeleton.Report skel = SlotSkeleton.ensure(new File(dataDir),
                deDir == null || deDir.isEmpty() ? null : new File(deDir), cfg.slot);
        if (skel.changed()) {
            logFile(pkg + ": slot " + cfg.slot + " skeleton " + skel.summary()
                    + (deDir == null || deDir.isEmpty() ? " (no DE base)" : " de=" + deDir));
        }

        final List<String> bases = buildBases(pkg, dataDir, lp.appInfo, cfg.redirectExternal);
        final PathMap map = new PathMap(cfg.slot, bases);
        sMap = map;

        final boolean mainProcess = lp.processName == null || pkg.equals(lp.processName);

        int hooks = 0;
        hooks += hookContextImpl(lp.classLoader, map, cfg.hookDataDir);
        if (cfg.hookFilePaths) {
            hooks += hookFileConstructors(map);
        }
        hooks += hookSqlite(map);
        hooks += hookWebView(map);
        hooks += hookAppStart(lp.classLoader, cfg, mainProcess);

        sInstalled = true;
        logFile(pkg + ": installed slot=" + cfg.slot + " hooks=" + hooks
                + " bases=" + bases.size() + " proc=" + lp.processName
                + " main=" + (cfg.isMainSlot() ? "yes" : "no"));
    }

    private static String resolveDataDir(String pkg, ApplicationInfo ai) {
        String d = ai != null ? ai.dataDir : null;
        if (d == null || d.isEmpty()) {
            d = "/data/data/" + pkg;
        }
        return d;
    }

    private static List<String> buildBases(String pkg, String dataDir, ApplicationInfo ai,
                                           boolean redirectExternal) {
        List<String> bases = new ArrayList<>();
        bases.add(dataDir);
        // /data/data 与 /data/user/0 在同一台机器上可能同时出现在字符串里
        bases.add("/data/data/" + pkg);
        bases.add("/data/user/0/" + pkg);
        if (ai != null && ai.deviceProtectedDataDir != null) {
            bases.add(ai.deviceProtectedDataDir);
        }
        if (redirectExternal) {
            bases.add("/storage/emulated/0/Android/data/" + pkg);
            bases.add("/sdcard/Android/data/" + pkg);
            bases.add("/storage/self/primary/Android/data/" + pkg);
        }
        return bases;
    }

    // ------------------------------------------------------------- ContextImpl

    private static int hookContextImpl(ClassLoader cl, PathMap map, boolean hookDataDir) {
        int n = 0;
        // getDataDir 是整棵树的根，但对框架代码影响也最大，可单独关掉
        if (hookDataDir) {
            n += hookFileReturning(CONTEXT_IMPL, cl, map, "getDataDir");
        }
        // 返回 File 的无参方法
        String[] noArg = {
                "getFilesDir",
                "getCacheDir",
                "getCodeCacheDir",
                "getNoBackupFilesDir",
                "getObbDir",
                "getExternalCacheDir",
                "getPreferencesDir",
        };
        for (String m : noArg) {
            n += hookFileReturning(CONTEXT_IMPL, cl, map, m);
        }
        // 返回 File 且带参数的方法
        n += hookFileReturning(CONTEXT_IMPL, cl, map, "getExternalFilesDir", String.class);
        n += hookFileReturning(CONTEXT_IMPL, cl, map, "getDir", String.class, int.class);
        n += hookFileReturning(CONTEXT_IMPL, cl, map, "getDatabasePath", String.class);
        n += hookFileReturning(CONTEXT_IMPL, cl, map, "getFileStreamPath", String.class);
        n += hookFileReturning(CONTEXT_IMPL, cl, map, "getSharedPreferencesPath", String.class);
        n += hookFileReturning(CONTEXT_IMPL, cl, map, "getDataDirForUser", int.class);
        // 返回 File[] 的方法
        n += hookFilesReturning(CONTEXT_IMPL, cl, map, "getExternalFilesDirs", String.class);
        n += hookFilesReturning(CONTEXT_IMPL, cl, map, "getExternalCacheDirs");
        n += hookFilesReturning(CONTEXT_IMPL, cl, map, "getExternalMediaDirs");
        return n;
    }

    private static int hookFileReturning(String cls, ClassLoader cl, PathMap map,
                                         String name, Object... paramTypes) {
        try {
            Object[] args = new Object[paramTypes.length + 1];
            System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
            args[paramTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof File) {
                        File mapped = map.mapFile((File) r);
                        if (mapped != r) {
                            param.setResult(mapped);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(cls, cl, name, args);
            return 1;
        } catch (Throwable t) {
            logFile("hook " + cls + "#" + name + " skipped: " + t);
            return 0;
        }
    }

    private static int hookFilesReturning(String cls, ClassLoader cl, PathMap map,
                                          String name, Object... paramTypes) {
        try {
            Object[] args = new Object[paramTypes.length + 1];
            System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
            args[paramTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof File[]) {
                        param.setResult(map.mapFiles((File[]) r));
                    }
                }
            };
            XposedHelpers.findAndHookMethod(cls, cl, name, args);
            return 1;
        } catch (Throwable t) {
            logFile("hook " + cls + "#" + name + " skipped: " + t);
            return 0;
        }
    }

    // --------------------------------------------------------- File 字符串兜底

    /**
     * 有的 App 直接拼 "/data/data/&lt;pkg&gt;/xxx" 这种字符串。这里在 File 构造时兜一层。
     * 只在路径命中本 App 的 base 时才改写，开销可以忽略。
     */
    private static int hookFileConstructors(final PathMap map) {
        int n = 0;
        try {
            XposedHelpers.findAndHookConstructor(File.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object a0 = param.args[0];
                    if (!(a0 instanceof String)) {
                        return;
                    }
                    String p = (String) a0;
                    if (p.isEmpty() || p.charAt(0) != '/') {
                        return;
                    }
                    String m = map.map(p);
                    if (!m.equals(p)) {
                        param.args[0] = m;
                    }
                }
            });
            n++;
        } catch (Throwable t) {
            logFile("hook File(String) skipped: " + t);
        }
        try {
            XposedHelpers.findAndHookConstructor(File.class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object a0 = param.args[0];
                            if (!(a0 instanceof String)) {
                                return;
                            }
                            String p = (String) a0;
                            if (p.isEmpty() || p.charAt(0) != '/') {
                                return;
                            }
                            String m = map.map(p);
                            if (!m.equals(p)) {
                                param.args[0] = m;
                            }
                        }
                    });
            n++;
        } catch (Throwable t) {
            logFile("hook File(String,String) skipped: " + t);
        }
        try {
            XposedHelpers.findAndHookConstructor(File.class, File.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object a0 = param.args[0];
                            if (!(a0 instanceof File)) {
                                return;
                            }
                            File mapped = map.mapFile((File) a0);
                            if (mapped != a0) {
                                param.args[0] = mapped;
                            }
                        }
                    });
            n++;
        } catch (Throwable t) {
            logFile("hook File(File,String) skipped: " + t);
        }
        return n;
    }

    // ----------------------------------------------------------------- SQLite

    /** 少数代码直接传绝对路径给 SQlite，这里兜一下。 */
    private static int hookSqlite(final PathMap map) {
        try {
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "openDatabase", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0) {
                        return;
                    }
                    Object a0 = param.args[0];
                    if (!(a0 instanceof String)) {
                        return;
                    }
                    String p = (String) a0;
                    String m = map.map(p);
                    if (!m.equals(p)) {
                        param.args[0] = m;
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            logFile("hook SQLiteDatabase.openDatabase skipped: " + t);
            return 0;
        }
    }

    // ---------------------------------------------------------------- WebView

    /** WebView 的数据目录由 WebViewFactory 决定，best effort 重定向。 */
    private static int hookWebView(final PathMap map) {
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewFactory", null,
                    "getDataDirectory", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            if (r instanceof File) {
                                File mapped = map.mapFile((File) r);
                                if (mapped != r) {
                                    param.setResult(mapped);
                                }
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            logFile("hook WebViewFactory.getDataDirectory skipped: " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------- 进程启动

    /**
     * 拿 Context，并在 App 进程起来之后（Application#attach）决定要不要发超级岛提示。
     *
     * <p>这里只挂 hook，真正的判断/发送在 {@link IslandNotice} 里做，且全部 try/catch，
     * 通知失败不影响重定向。
     */
    private static int hookAppStart(ClassLoader cl, final SlotConfig cfg, final boolean mainProcess) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl, "attach",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object c = param.args.length > 0 ? param.args[0] : null;
                                if (c instanceof Context) {
                                    IslandNotice.schedule((Context) c, cfg, mainProcess);
                                }
                            } catch (Throwable t) {
                                logFile("island schedule failed: " + t);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            logFile("hook Application#attach skipped: " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ 日志

    /** 模块日志：既写 Xposed 日志，也追加到目标 App 目录下的 subdata.log。 */
    static void logFile(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // 在模块自己的进程（没有 XposedBridge）里调用会抛 NoClassDefFoundError，
            // 这里吞掉：写文件日志照常，绝不让日志把主流程带崩。
        }
        File f = sLogFile;
        if (f == null) {
            return;
        }
        try {
            if (f.length() > 256 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(ts + " " + msg + "\n");
            }
        } catch (Throwable ignored) {
            // 日志失败绝不影响主流程
        }
    }
}
