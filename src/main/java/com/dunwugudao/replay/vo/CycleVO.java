package com.dunwugudao.replay.vo;

import lombok.Data;

/**
 * 牛熊周期判定（S1 未实现时为 null）。
 */
@Data
public class CycleVO {

    /** 相对周期阶段，如 牛末/熊初。 */
    private String phase;

    /** 绝对牛熊，如 牛 / 熊 / 震荡。 */
    private String absolute;

    /** 相对强弱，如 结构性机会 / 全面机会 / 无机会。 */
    private String relative;
}
