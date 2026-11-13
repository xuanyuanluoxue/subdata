package com.xavier.subdata;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 「临时文件 + rename」原子写。
 *
 * <p>为什么必须原子：{@code subdata/slot} 和 {@code subdata/subdata.conf} 是模块运行的
 * 唯一权威配置。写到一半掉电 / 被 kill，文件就会变成空文件或半行，
 * 下次启动直接退回默认 slot —— 对用户来说就是「数据不见了」。
 * rename 在同一文件系统上是原子的：要么看到旧文件，要么看到完整的新文件。
 *
 * <p>另外两个细节：
 * <ul>
 *   <li>临时文件放在<b>同一个目录</b>里，保证 rename 不跨文件系统；</li>
 *   <li>写完先 {@code fsync}，否则 rename 后掉电可能留下一个大小正确的空文件。</li>
 * </ul>
 *
 * <p>纯 {@code java.io} / {@code java.nio}，不依赖 Android，可单测。
 */
public final class AtomicFiles {

    /** 临时文件后缀；与目标文件同目录，便于 rename。 */
    public static final String TMP_SUFFIX = ".tmp";

    private AtomicFiles() {
    }

    public static File tmpFor(File target) {
        return new File(target.getParentFile(), target.getName() + TMP_SUFFIX);
    }

    public static boolean write(File target, String content) {
        return write(target, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 原子写入。
     *
     * @return true = 新内容已经落盘；false = 任一步失败（此时尽量不破坏旧文件）
     */
    public static boolean write(File target, byte[] data) {
        if (target == null) {
            return false;
        }
        final byte[] bytes = data == null ? new byte[0] : data;
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            return false;
        }
        final File tmp = tmpFor(target);
        try {
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(bytes);
                out.flush();
                try {
                    out.getFD().sync();
                } catch (Throwable ignored) {
                    // 某些文件系统（FUSE / DrvFs）不支持 fsync，忽略
                }
            }
            return rename(tmp, target);
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    private static boolean rename(File tmp, File target) {
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable ignored) {
            // ATOMIC_MOVE 不被支持时退回普通 rename
        }
        if (tmp.renameTo(target)) {
            return true;
        }
        // 极少数情况（目标被占用 / 跨设备）先删再 rename，至少不留下 .tmp
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        return tmp.renameTo(target);
    }
}
