package com.dunwugudao.replay.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 龙头买卖建议视图（S5）。基于 leader_trade_daily 独立计算层产物。
 * <p>由 LeaderTradeCalculator 量化：分歧日/一致日/板块高潮/真龙见顶 → 买卖信号 + 评分 + 风险等级。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LeaderTradeIdeaVO extends LeaderVO {

    /** 买卖建议文字（映射 reason）。 */
    private String idea;

    /** 操作动作：买入 / 低吸 / 持有 / 减仓 / 卖出 / 观望。 */
    private String action;

    /** 买卖信号：buy / buy_dip / hold / sell / reduce / watch。 */
    private String signal;

    /** 风险等级：低 / 中 / 高 / 极高。 */
    private String riskLevel;

    /** 买卖评分 0~100（越高越值得操作）。 */
    private BigDecimal buyScore;

    /** 买卖理由（结构化说明）。 */
    private String reason;

    /** 补充说明（市场状态/板块强度等上下文）。 */
    private String note;
}
