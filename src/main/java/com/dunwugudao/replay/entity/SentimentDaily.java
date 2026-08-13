package com.dunwugudao.replay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日情绪温度（S2 情绪资金）。对应表 sentiment_daily。
 */
@Data
public class SentimentDaily {

    private LocalDate tradeDate;

    /** 涨停家数。以 limit_up_pool 为权威口径。 */
    private Integer limitUpCnt;

    /** 跌停家数。 */
    private Integer limitDownCnt;

    /** 最高连板数（市场高度）。 */
    private Integer maxBoardPos;

    /** 昨日涨停股今日平均涨幅（%），即赚钱效应。 */
    private BigDecimal yestLimitRet;

    /** 情绪温度 0~100。 */
    private BigDecimal thermal;

    /** 情绪阶段：冰点 / 修复 / 升温 / 高潮 / 退潮。 */
    private String regime;
}
