package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.mapper.ck.SentimentDailyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.util.List;

/**
 * 当日情绪阶段快照（战法 stage 闸门的数据源）。
 *
 * <p>每 60s 从 {@code sentiment_daily} 拉当日温度映射为阶段短码；支持 REST 手动覆盖
 * （复盘/人工研判优先于自动映射）。阶段码与 strategy_catalog.applicable_stages 一致：
 * <pre>
 * ICE 冰点(thermal&lt;25) / DIVERGE 分歧(25~40) / REPAIR 修复(40~55)
 * STARTUP 启动(55~65) / CONSENSUS 一致(65~90) / CLIMAX 高潮(&gt;=90)
 * DIVERGE_CONSENSUS 分歧转一致（人工判定，仅覆盖入口可设）
 * </pre>
 * 无数据/未覆盖时为 {@code UNKNOWN}——交易元闸门对 UNKNOWN 放行并告警（M1 宽松策略，避免无情绪数据时全瘫）。
 */
@Slf4j
@Component
public class StageSnapshotHolder {

    private final SentimentDailyMapper sentimentMapper;

    private volatile String stage = "UNKNOWN";
    private volatile boolean manualOverride = false;

    public StageSnapshotHolder(SentimentDailyMapper sentimentMapper) {
        this.sentimentMapper = sentimentMapper;
    }

    public String currentStage() {
        return stage;
    }

    /** 手动覆盖（人工研判优先），manual=true 后自动刷新不再覆盖。 */
    public void override(String stageCode, boolean manual) {
        this.stage = normalize(stageCode);
        this.manualOverride = manual;
        log.info("[realtime] 情绪阶段覆盖为 {} (manual={})", stage, manual);
    }

    public void clearOverride() {
        this.manualOverride = false;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void refresh() {
        if (manualOverride) {
            return;
        }
        try {
            // 当日无数据则回看最近两天（sentiment_daily 为盘后产出，盘中用的是昨日温度近似）
            for (int back = 0; back <= 2; back++) {
                LocalDate d = LocalDate.now().minusDays(back);
                SentimentDaily s = sentimentMapper.selectByTradeDate(d);
                if (s != null && s.getThermal() != null) {
                    String mapped = mapThermal(s.getThermal().doubleValue());
                    if (!mapped.equals(stage)) {
                        log.info("[realtime] 情绪阶段自动刷新: {} -> {} (thermal={}, date={})",
                                stage, mapped, s.getThermal(), d);
                    }
                    stage = mapped;
                    return;
                }
            }
            // 无任何情绪数据：保持现状（通常为 UNKNOWN）
        } catch (Exception e) {
            log.warn("[realtime] 情绪阶段刷新失败（保持 {}）: {}", stage, e.getMessage());
        }
    }

    /** thermal → 阶段码映射（口径对齐 S5 市场状态：一致≥65 / 分歧<40 / 冰点<25 / 修复居中）。 */
    private String mapThermal(double thermal) {
        if (thermal >= 90) return "CLIMAX";
        if (thermal >= 65) return "CONSENSUS";
        if (thermal >= 55) return "STARTUP";
        if (thermal >= 40) return "REPAIR";
        if (thermal >= 25) return "DIVERGE";
        return "ICE";
    }

    private String normalize(String s) {
        if (s == null || s.isBlank()) {
            return "UNKNOWN";
        }
        return s.trim().toUpperCase();
    }

    /** 校验阶段码合法性（REST 入参用）。 */
    public static boolean isValidStage(String s) {
        return List.of("ICE", "CHAOS", "DIVERGE", "DIVERGE_CONSENSUS", "CONSENSUS",
                "CLIMAX", "STARTUP", "REPAIR", "UNKNOWN").contains(s);
    }
}
