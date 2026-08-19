package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 龙虎榜个股视图（S3）。金额单位：元。
 */
@Data
public class DragonTigerVO {

    private String tsCode;
    private String stockName;

    /** 上榜原因。 */
    private String reason;

    /** 解读。 */
    private String explanation;

    /** 异动类型。 */
    private String abnormalType;

    private BigDecimal netBuy;
    private BigDecimal totalBuy;
    private BigDecimal totalSell;

    /** 上榜成交额。 */
    private BigDecimal billboardDealAmt;

    private BigDecimal changeRate;
    private BigDecimal closePrice;
    private BigDecimal turnoverrate;
    private BigDecimal freeMarketCap;

    /** 上榜后第 1 日收盘涨跌幅（回测用）。 */
    private BigDecimal d1CloseAdjchrate;

    /** 上榜后第 5 日收盘涨跌幅（回测用）。 */
    private BigDecimal d5CloseAdjchrate;
}
