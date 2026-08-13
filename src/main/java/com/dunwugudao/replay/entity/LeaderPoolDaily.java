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

    /** 角色：龙头 / 中军 / 跟风。 */
    private String role;

    /** 综合得分 0~100。 */
    private BigDecimal score;

    // ---- 中间量，不落库 ----

    private transient String stockName;
    private transient String boardName;
    private transient String limitStyle;
    private transient Integer openTimes;
    private transient BigDecimal turnoverRate;
    private transient BigDecimal amount;
}
