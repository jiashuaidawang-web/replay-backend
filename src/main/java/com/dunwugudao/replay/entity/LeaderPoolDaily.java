package com.dunwugudao.replay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日龙头池（S5 龙头买卖）。对应表 leader_pool_daily。
 */
@Data
public class LeaderPoolDaily {

    private LocalDate tradeDate;

    /** 股票代码，带后缀，如 003032.SZ。 */
    private String tsCode;

    /** 所属主线板块代码。 */
    private String boardCode;

    /** 连板数。 */
    private Short boardPos;

    /** 角色：龙一 / 龙二 … / 妖股 / 独狼。 */
    private String role;

    /** 综合得分 0~100。 */
    private BigDecimal score;

    /** 类别：龙 / 妖 / 独狼（便于按猎物类型分组过滤）。 */
    private String cat;

    /** 成交额（元），代理人气/关注度。 */
    private BigDecimal amount;

    /** 涨停风格：换手 / 一字。 */
    private String limitStyle;

    /** 妖/独狼判定说明（如"脱离板块·8连板·换手充分·跨6题材"）。 */
    private String note;

    // ---- 中间量，不落库（仅接口/前端展示用） ----

    private transient String stockName;
    private transient String boardName;
    private transient Integer openTimes;
    private transient BigDecimal turnoverRate;
}
