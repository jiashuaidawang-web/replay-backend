package com.dunwugudao.replay.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Redis Stream 收盘裁剪（M2，对齐同花顺 L2 契约 v1.0）。
 *
 * <p>契约是「每股票一个 Stream」模型，逐笔流 key 为 {@code ths:l2:tick:{code}}。
 * 本类从 {@code ths:l2:pool}（SET）拿到全部订阅 code，对每个逐笔流执行 XTRIM maxLen
 * （对齐契约单股保留最近 10000 笔），控制 Redis 内存，避免无限增长。
 *
 * <p>后端加这道保险（爬虫侧也应做，但后端保证即使爬虫漏做也不爆内存/Redis）。
 * 策略：收盘后 15:05 自动执行；盘后结算也能手动触发（REST）。
 */
@Slf4j
@Component
public class StreamTrimmer {

    private static final String POOL_KEY = "ths:l2:pool";
    private static final String TICK_STREAM = "ths:l2:tick:";

    private final StringRedisTemplate redis;
    private final long maxLen;

    public StreamTrimmer(StringRedisTemplate redis,
                         @Value("${replay.stream.xtrim-max-len:10000}") long maxLen) {
        this.redis = redis;
        this.maxLen = maxLen;
    }

    /** 收盘后自动裁剪（15:05，避开交易时段）。盘中 L2 tick 已双写 CK 归档（rt_tick_archive），
     * stream 仅作传输总线，不需保留历史；保留最近 maxLen 条（默认 10000 ≈ 兜底）作异常兜底。 */
    @Scheduled(cron = "0 5 15 * * ?")
    public void trimAfterClose() {
        log.info("[xtrim] 收盘自动裁剪开始，maxLen={}", maxLen);
        trimNow();
    }

    /** 手动触发（REST 调用）。遍历 ths:l2:pool 中所有 code 的逐笔流执行 XTRIM。 */
    public void trimNow() {
        Set<String> pool;
        try {
            pool = redis.opsForSet().members(POOL_KEY);
        } catch (Exception e) {
            log.warn("[xtrim] 读取 {} 失败: {}", POOL_KEY, e.getMessage());
            return;
        }
        if (pool == null) {
            return;
        }
        int ok = 0, fail = 0;
        for (String code : pool) {
            if (code == null || code.isBlank()) {
                continue;
            }
            String stream = TICK_STREAM + code;
            try {
                Long removed = redis.opsForStream().trim(stream, maxLen);
                ok++;
                log.info("[xtrim] {} 裁剪 {} 条（保留 ~{}）", stream, removed, maxLen);
            } catch (Exception e) {
                fail++;
                log.warn("[xtrim] {} 裁剪失败（忽略）: {}", stream, e.getMessage());
            }
        }
        log.info("[xtrim] 裁剪完成：成功 {} / 失败 {}，共 {} 支", ok, fail, pool.size());
    }
}
