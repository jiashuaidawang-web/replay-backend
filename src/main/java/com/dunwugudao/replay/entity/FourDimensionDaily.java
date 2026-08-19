package com.dunwugudao.replay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S1 大势择时 · 四维度日级产出（CK: four_dimension_daily）。
 *
 * <p>tech / sentiment / fund / policy 各维归一 0~1；composite 为加权综合；
 * phase / absolute / relative / suggestion 为牛熊周期判定与策略建议；note 记录数据口径限制。
 * 表为 ReplacingMergeTree（ORDER BY trade_date + _ver），重算纯 INSERT 幂等。
 */
@Data
public class FourDimensionDaily {

    private LocalDate tradeDate;

    private BigDecimal tech;
    private BigDecimal sentiment;
    private BigDecimal fund;
    private BigDecimal policy;
    private BigDecimal composite;

    /** 是否值得参与（composite >= 0.5）。 */
    private Integer worthTrade;

    /** 相对周期阶段，如 机会期 / 结构性机会期 / 震荡磨底 / 弱势 / 风险释放期。 */
    private String phase;

    /** 绝对牛熊，如 多头 / 空头 / 震荡。 */
    private String absolute;

    /** 相对强弱，如 全面机会 / 结构性机会 / 弱势震荡 / 无机会。 */
    private String relative;

    /** 策略建议（中文可读）。 */
    private String suggestion;

    /** 数据口径说明（历史偏短 / 资金维回退日 / 政策维中性占位等）。 */
    private String note;
}
