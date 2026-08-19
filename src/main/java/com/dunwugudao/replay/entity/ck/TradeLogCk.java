package com.dunwugudao.replay.entity.ck;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个人交易日志（ClickHouse crawler.trade_log，RMT）。
 *
 * <p>S8 交易心法的数据底座：用户录入的买卖记录 + 主观标签（逻辑/心态/处置），
 * 由 {@code DisciplineCalculator} 量化纪律分（不入库，接口实时算）。
 *
 * <p>2026-08-19 由 openGauss 迁至 ClickHouse：OG 直连（Windows 5432）对 Mac 端
 * 极不稳定（Connection reset），而 CK 全链路已由裸 HTTP 直写（CkHttpWriter）根治；
 * 迁 CK 后写路径复用 CkHttpWriter，读路径走 ck SqlSessionFactory（FINAL）。
 *
 * <p>id 为应用层 UUID（CK 无自增）；created_at 走 DEFAULT now()，插入不指定。
 */
@Data
public class TradeLogCk {

    /** UUID（应用层生成）。 */
    private String id;

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

    private LocalDateTime createdAt;
}
