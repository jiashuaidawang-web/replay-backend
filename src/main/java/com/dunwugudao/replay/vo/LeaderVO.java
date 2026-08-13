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

    /** 角色：龙一 / 龙二 … / 中军 / 跟风。 */
    private String role;

    /** 龙头相评分 0~100。 */
    private BigDecimal score;
}
