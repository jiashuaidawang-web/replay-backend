package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 龙虎榜席位明细视图（S3）。金额单位：元。
 */
@Data
public class DtDetailVO {

    private String tsCode;
    private String seatName;

    /** 席位类型，如 机构 / 游资 / 沪股通。 */
    private String seatType;

    private Integer rank;
    private BigDecimal buy;
    private BigDecimal sell;
    private BigDecimal netBuy;

    /** 净买占比。 */
    private BigDecimal netBuyRatio;

    private BigDecimal tradeAmt;

    /** 买卖方向：1 买 / -1 卖（源表 trade_direction）。 */
    private Integer tradeDirection;
}
