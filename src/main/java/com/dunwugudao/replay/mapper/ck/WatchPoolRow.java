package com.dunwugudao.replay.mapper.ck;

import lombok.Data;

import java.time.LocalDate;

/**
 * watch_pool 行（事件线页读模型）。buy_/sell_/pnl_/outcome 由盘后归因 job 回填（阶段二），此处可空。
 */
@Data
public class WatchPoolRow {
    private LocalDate selDate;
    private String tsCode;
    private String stockName;
    private String sourceSkill;   // S4,S5,S7 逗号拼接
    private String reason;
    private String boardCode;
    private String role;
    private String selectedAction;
    private Integer syncedRedis;
    // ---- T+1 归因回填（可空）----
    private String buySignal;
    private String buyReason;
    private Double buyPrice;
    private String sellReason;
    private Double sellPrice;
    private Double pnlPct;
    private String outcome;
}
