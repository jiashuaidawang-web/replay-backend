package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 个人交易日志视图（S8）。trade_log 原表字段 + 实时计算的纪律分（不入库）。
 */
@Data
public class TradeLogVO {

    private Long id;
    private LocalDate tradeDate;
    private String tsCode;

    /** buy / sell。 */
    private String side;

    private BigDecimal price;
    private BigDecimal qty;

    /** 买入逻辑（大势 / 热点 / 个股）。 */
    private String reason;

    /** 执行心态标签。 */
    private String emotionTag;

    /** 买对 / 买错 / 未明 三态处置。 */
    private String reaction;

    private OffsetDateTime createdAt;

    /** 纪律分 0~100（由 DisciplineCalculator 计算填充，不入库）。 */
    private Integer disciplineScore;

    /** 命中的违规项（由 DisciplineCalculator 计算填充）。 */
    private List<String> violations;
}
