package com.dunwugudao.replay.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 交易元决策建议（每步都落 {@code decision_log}，并经 SSE 推前端）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Decision implements Serializable {
    public enum Action { BUY, SELL, HOLD, WATCH }

    private String tsCode;
    private long ts;
    private Action action;        // BUY/SELL/HOLD/WATCH
    private double score;         // 0~100
    private String riskLevel;     // 低/中/高
    private Double referencePrice;// 参考价
    private String reason;        // 决策理由
    private String strategyId;    // 选用的战法
    private String stage;         // 情绪阶段码
    private String capitalSignal; // 资金层口径（CONFIRM/FILTER_PASS/CONTRA/NONE）
    private String planId;        // 关联计划
    private String stockName;
    private String role;          // 龙一/妖/独狼...
}
