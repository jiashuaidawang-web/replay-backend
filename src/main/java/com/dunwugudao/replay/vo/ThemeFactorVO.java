package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 题材炒作因子视图（S7）。炒作因子计算层未实现时返回 null。
 */
@Data
public class ThemeFactorVO {

    private String boardCode;
    private String boardName;

    private BigDecimal scarcity;
    private BigDecimal imagination;
    private BigDecimal sudden;
    private BigDecimal certainty;

    /** 最小阻力方向（势）。 */
    private BigDecimal minResist;

    /** 综合炒作因子分。 */
    private BigDecimal total;
}
