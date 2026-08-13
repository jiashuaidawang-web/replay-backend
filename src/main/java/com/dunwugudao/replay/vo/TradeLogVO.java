package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 个人交易日志视图（S8）。trade_log 表尚未创建（M4 里程碑），当前接口返回空。
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
}
