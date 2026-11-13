package com.xavier.subdata;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 极薄的 root 命令封装。
 *
 * <p>模块界面本身没有 root 权限，而它要读写的都是<b>目标 App 私有目录</b>
 * （{@code /data/data/<pkg>/subdata}），只能借 {@code su} 做事。设计原则：
 * <ul>
 *   <li>所有命令都带超时，magisk 弹授权框时不会把界面卡死；</li>
 *   <li>参数统一走 {@link #shq(String)} 单引号转义，不拼未转义的用户输入；</li>
 *   <li>拿不到 root 就返回失败，由界面降级提示，绝不假装成功。</li>
 * </ul>
 */
public final class RootShell {

    public static final int DEFAULT_TIMEOUT_SEC = 12;

    private static Boolean sAvailable;

    private RootShell() {
    }

    public static final class Result {
        public final int code;
        public final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out == null ? "" : out;
        }

        public boolean ok() {
            return code == 0;
        }

        public String trim() {
            return out.trim();
        }
    }

    /** su 是否真的能拿到 uid 0（结果缓存，只探测一次）。 */
    public static synchronized boolean available() {
        if (sAvailable == null) {
            Result r = run("id", 8);
            sAvailable = r.ok() && r.out.contains("uid=0");
        }
        return sAvailable;
    }

    public static Result run(String cmd) {
        return run(cmd, DEFAULT_TIMEOUT_SEC);
    }

    public static Result run(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                } catch (Throwable ignored) {
                    // 进程被杀时读流会抛，忽略
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy();
                return new Result(-1, sb + "\n[timeout after " + timeoutSec + "s]");
            }
            reader.join(800);
            return new Result(p.exitValue(), sb.toString());
        } catch (Throwable t) {
            return new Result(-1, String.valueOf(t));
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** POSIX 单引号转义，挡住空格与命令注入。 */
    public static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 用 root 写文件。内容走 base64，彻底避开引号 / 换行 / $ 展开的问题。
     */
    public static boolean writeFile(String path, String content) {
        String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        return run("echo " + b64 + " | base64 -d > " + shq(path), 10).ok();
    }

    /**
     * 原子写（临时文件 + rename）。
     *
     * <p>{@code slot} / {@code subdata.conf} 是模块唯一的权威配置，直接 {@code > file} 覆盖时
     * 掉电或进程被杀会留下空文件/半行，下次启动就退回默认 slot —— 用户看到的是「数据没了」。
     * {@code mv} 在同一个目录里是原子的：要么旧内容，要么完整的新内容。
     *
     * <p>失败时把 {@code .tmp} 删掉，绝不留下可疑文件。
     */
    public static boolean writeFileAtomic(String path, String content) {
        String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String tmp = path + AtomicFiles.TMP_SUFFIX;
        String cmd = "echo " + b64 + " | base64 -d > " + shq(tmp)
                + " && sync && mv -f " + shq(tmp) + " " + shq(path)
                + " || { rm -f " + shq(tmp) + "; echo FAIL; }";
        return run(cmd, 10).ok();
    }

    /**
     * 把由 root 新建的文件归还给目标 App：属主改成目标 uid，权限 0644，
     * 并让 SELinux 标签经 restorecon 归位（否则被注入的 App 读自己目录会被拒）。
     */
    public static void handOverToApp(String pkg, String path, boolean dir) {
        String dataDir = TargetRepo.dataDir(pkg);
        run("U=$(stat -c %u " + shq(dataDir) + " 2>/dev/null); "
                + "chown $U:$U " + shq(path) + " 2>/dev/null; "
                + "chmod " + (dir ? "0755" : "0644") + " " + shq(path) + " 2>/dev/null; "
                + "restorecon " + shq(path) + " 2>/dev/null; true", 10);
    }

    public static void handOverToApp(String pkg, String path) {
        handOverToApp(pkg, path, false);
    }
}
