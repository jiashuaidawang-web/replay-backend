package com.dunwugudao.replay.entity.og;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 个人交易日志（openGauss）—— S8 交易心法的数据底座。
 *
 * <p>仅存用户自己录入的买卖记录 + 主观标签（逻辑/心态/处置），由
 * {@code DisciplineCalculator} 量化"知道→做到"的执行度（纪律分）。
 *
 * <p>落在 {@code mapper.og} 包，由 {@link com.dunwugudao.replay.config.OgMybatisConfig}
 * 扫描，数据源为 openGauss（@Primary，支持事务）。
 */
@Data
public class TradeLog {

    private Long id;

    /** 交易日期。 */
    private LocalDate tradeDate;

    /** 标的代码，如 000001.SZ。 */
    private String tsCode;

    /** buy / sell。 */
    private String side;

    private BigDecimal price;
    private BigDecimal qty;

    /** 买卖逻辑（大势 / 热点 / 个股）。 */
    private String reason;

    /** 执行心态标签（贪婪 / 恐惧 / 平静 …）。 */
    private String emotionTag;

    /** 买对 / 买错 / 未明 三态处置。 */
    private String reaction;

    private OffsetDateTime createdAt;
}
