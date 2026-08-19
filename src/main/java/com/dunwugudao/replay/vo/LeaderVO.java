package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 龙头池个股视图（S4/S5）。stockName 由 limit_up_pool 回填，boardName 由 board_basic 回填。
 */
@Data
public class LeaderVO {

    private String tsCode;
    private String stockName;
    private String boardCode;
    private String boardName;

    /** 连板数；首板为 null。 */
    private Integer boardPos;

    /** 角色：龙一 / 龙二 … / 妖股 / 独狼。 */
    private String role;

    /** 类别：龙 / 妖 / 独狼（按猎物类型分组）。 */
    private String cat;

    /** 龙头相评分 0~100。 */
    private BigDecimal score;

    /** 成交额（元），代理人气。 */
    private BigDecimal amount;

    /** 涨停风格：换手 / 一字。 */
    private String limitStyle;

    /** 妖/独狼判定说明。 */
    private String note;
}
