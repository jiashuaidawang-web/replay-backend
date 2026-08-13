package com.dunwugudao.replay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日主线板块（S4 板学寻龙）。对应表 mainline_daily。
 */
@Data
public class MainlineDaily {

    private LocalDate tradeDate;

    /** 板块代码，如 BK0854。 */
    private String boardCode;

    /** 主线层级：主线 / 分支 / 杂毛。 */
    private String mainLevel;

    /** 综合强度 0~100。 */
    private BigDecimal strength;

    /** 强度排名，1 为最强。 */
    private Integer rank;

    // ---- 以下为计算过程中的中间量，不落库，仅用于日志与接口展示 ----

    private transient String boardName;
    private transient Integer limitUpCnt;
    private transient BigDecimal pctChg;
    private transient BigDecimal mainNet;
}
