package com.llmstudy.rag.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花算法全局唯一 ID 生成器。
 *
 * <p>64 位 long：1 位符号位 + 41 位毫秒时间戳（自定义纪元起约 69 年）
 * + 10 位 workerId + 12 位毫秒内序列号（单实例每毫秒 4096 个）。
 * 生成的 ID 近似单调递增，替代随机 UUID 以避免 MySQL InnoDB 聚簇索引
 * 的页分裂与碎片问题。</p>
 *
 * <p>时钟回拨时短暂等待系统时间追平；回拨超过 5 秒直接拒绝生成，
 * 避免多实例并发下出现重复 ID。</p>
 */
@Component
public class SnowflakeIdGenerator {

    private static final Logger log =
            LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    /** 自定义纪元：2025-01-01 00:00:00 UTC，保证 ID 前导位足够小。 */
    private static final long EPOCH = 1735689600000L;

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 允许的最大时钟回拨（毫秒），超过则拒绝生成 ID。 */
    private static final long MAX_BACKWARD_MS = 5000L;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(
            @Value("${rag.snowflake.worker-id:1}") long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 超出范围: " + workerId);
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个全局唯一 ID。
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            long backward = lastTimestamp - timestamp;
            if (backward > MAX_BACKWARD_MS) {
                throw new IllegalStateException(
                        "系统时钟回拨 " + backward + "ms，超过上限，拒绝生成 ID");
            }
            log.warn("检测到时钟回拨 {}ms，等待时间追平", backward);
            sleepQuietly(backward + 1);
            timestamp = System.currentTimeMillis();
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 当前毫秒序列号耗尽，等待进入下一毫秒
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 自旋等待直到系统时间超过 lastTimestamp，用于序列号耗尽场景。
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("时钟回拨等待被中断", e);
        }
    }
}
