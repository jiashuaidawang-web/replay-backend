package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 主线板块视图（S4）。boardName 由接口层从 board_basic 回填（计算层不落库）。
 */
@Data
public class MainlineVO {

    private String boardCode;
    private String boardName;

    /** 主线层级：一线 / 二线 / 三线。 */
    private String mainLevel;

    /** 综合强度 0~100。 */
    private BigDecimal strength;

    /** 强度排名，1 为最强。 */
    private Integer rank;
}
