package com.dunwugudao.replay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日龙头买卖建议（S5 龙头买卖）。对应表 leader_trade_daily。
 * <p>独立计算层产物：基于 S4 龙头池 + S2 情绪温度 + S4 主线强度，
 * 量化分歧日/一致日/板块高潮/真龙见顶切换，输出买卖信号与评分。
 */
@Data
public class LeaderTradeDaily {

    private LocalDate tradeDate;

    /** 股票代码，带后缀。 */
    private String tsCode;

    /** 所属主线板块代码（妖/独狼用哨兵 __DW__）。 */
    private String boardCode;

    /** 连板数。 */
    private Short boardPos;

    /** 角色：龙一~龙五 / 妖股 / 独狼。 */
    private String role;

    /** 类别：龙 / 妖 / 独狼。 */
    private String cat;

    /** 龙头相综合评分 0~100（从 S4 继承）。 */
    private BigDecimal score;

    /** 操作动作：买入 / 低吸 / 持有 / 减仓 / 卖出 / 观望。 */
    private String action;

    /** 买卖信号：buy / buy_dip / hold / sell / reduce / watch。 */
    private String signal;

    /** 风险等级：低 / 中 / 高 / 极高。 */
    private String riskLevel;

    /** 买卖评分 0~100（量化可操作性，越高越值得操作）。 */
    private BigDecimal buyScore;

    /** 买卖理由（结构化说明）。 */
    private String reason;

    /** 补充说明。 */
    private String note;

    // ---- 中间量，不落库 ----
    private transient String stockName;
    private transient String boardName;
}
