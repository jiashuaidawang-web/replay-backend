package com.dunwugudao.replay.mapper.ck;

import lombok.Data;

/**
 * decision_log 行（事件线读模型）。
 * action/strategy_id/stage/capital_signal 读回为字符串；reference_price 可空。
 */
@Data
public class DecisionLogRow {
    private String decisionId;
    private String ts;
    private String tsCode;
    private String action;        // BUY / SELL / HOLD / WATCH
    private Double score;
    private String riskLevel;
    private Double referencePrice;
    private String reason;
    private String strategyId;
    private String stage;
    private String capitalSignal;
    private Integer executed;
    private String orderId;
}
