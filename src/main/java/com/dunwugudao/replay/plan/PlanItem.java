package com.dunwugudao.replay.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 盘前关注池条目（T-1 复盘产出 → T 日盘中交易元的作用域）。
 * <p>只有进入池子的标的才会被交易元评估（狙击圈，非全市场）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanItem implements Serializable {
    private LocalDate planDate;
    private String tsCode;
    private String stockName;
    private String direction;          // 板块/方向（多口径统计维度）
    private String boardCode;
    private String role;               // 龙一/龙二/妖/独狼/跟风
    private List<String> candidateStrategies;  // 候选战法 id（空=全战法开放）
    private String plannedAction;      // 分歧低吸/一致持有/板上换手/趋势突破...
    private Double triggerPrice;       // 触发价（可空）
    private Double plannedPositionPct; // 计划仓位比例 0~1（可空，空用默认）
    private String note;
}
