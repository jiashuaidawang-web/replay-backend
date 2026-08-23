package com.dunwugudao.replay.mapper.ck;

import lombok.Data;

/**
 * sim_trade 行（M2 盘后结算读模型）。
 * 字段对齐 DDL：side/capital_confirm 是 Enum8，读回为字符串。
 */
@Data
public class SimTradeRow {
    private String tradeId;
    private String orderId;
    private String planId;
    private String tsCode;
    private String stockName;
    private String boardCode;
    private String role;
    private String side;          // BUY / SELL
    private Double price;
    private Double qty;
    private Double amount;
    private String tradeTime;     // DateTime64(3) 字符串
    private String strategyId;
    private String stageAtEntry;
    private String capitalConfirm;
    private String entryReason;
    private String exitReason;
    private String plannedAction;
    private Double pnl;
    private Double d1Ret;
    private Double d5Ret;
    private Integer isTrap;
}
