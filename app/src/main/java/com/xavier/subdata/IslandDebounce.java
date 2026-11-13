package com.xavier.subdata;

import java.util.HashMap;
import java.util.Map;

/**
 * 简单的时间窗节流器：同一个 key 在窗口内只放行一次。
 *
 * <p>两个地方用得到：
 * <ul>
 *   <li>发送端（目标 App 的每个进程各有一份）——防一次冷启动连发多条广播；</li>
 *   <li>接收端（模块 App）——防止某个 App 反复发广播把模块进程拖住。</li>
 * </ul>
 *
 * <p>纯 Java、线程安全、无定时器：只用调用方传进来的 {@code nowMs}，
 * 这样单元测试可以随便造时间，不必 sleep。
 */
public final class IslandDebounce {

    private final long intervalMs;
    private final Map<String, Long> last = new HashMap<>();

    public IslandDebounce(long intervalMs) {
        this.intervalMs = Math.max(0L, intervalMs);
    }

    public long intervalMs() {
        return intervalMs;
    }

    /**
     * 尝试占位。
     *
     * @return true = 这次可以发/可以处理；false = 窗口内已有记录，丢弃
     */
    public boolean allow(String key, long nowMs) {
        if (key == null) {
            return true;
        }
        synchronized (last) {
            Long prev = last.get(key);
            if (prev != null) {
                long delta = nowMs - prev;
                // 时间倒退（改系统时间 / 测试造数）时不要永久挡住
                if (delta >= 0 && delta < intervalMs) {
                    return false;
                }
            }
            last.put(key, nowMs);
            // key 数量天然上限是「白名单 App 数 × slot 数 × 2」，这里只做兜底清理
            if (last.size() > 256) {
                last.clear();
                last.put(key, nowMs);
            }
            return true;
        }
    }

    /** 忘掉一个 key（例如状态被强制重置时）。 */
    public void forget(String key) {
        if (key == null) {
            return;
        }
        synchronized (last) {
            last.remove(key);
        }
    }

    /** 当前记录数，测试与日志用。 */
    public int size() {
        synchronized (last) {
            return last.size();
        }
    }
}
