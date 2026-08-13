package com.dunwugudao.replay.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 龙头买卖建议视图（S5）。在 LeaderVO 基础上附加基于 role/板位/风格的启发式研判。
 * 注意：idea 为接口层派生提示，非独立计算层产物，仅作复盘参考。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LeaderTradeIdeaVO extends LeaderVO {

    /** 买卖建议文字。 */
    private String idea;

    /** 风险等级：低 / 中 / 高。 */
    private String riskLevel;

    /** 补充说明（如关注的确认信号）。 */
    private String note;
}
