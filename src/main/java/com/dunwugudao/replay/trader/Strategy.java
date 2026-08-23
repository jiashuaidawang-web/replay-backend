package com.dunwugudao.replay.trader;

import com.dunwugudao.replay.realtime.model.Decision;
import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.realtime.model.RealtimeFeature;

import java.util.Optional;

/**
 * 《顿悟股道》战法接口。每条战法 = 独立可统计口径（strategy_id）。
 * <p>战法只回答「我的触发条件是否满足」，情绪阶段闸门 / 资金层信号 / 仓位风控
 * 由 {@code TraderEngine} 统一施加——保证口径可拆分统计（strategy × stage × capital_confirm）。
 */
public interface Strategy {

    String id();

    String name();

    /** 适用情绪阶段码（与 strategy_catalog.applicable_stages 一致）。 */
    java.util.Set<String> applicableStages();

    /** 资金层角色。 */
    CapitalRole capitalRole();

    /** BUY 战法：无持仓时参与；SELL 战法：有持仓时参与。 */
    enum Side { BUY, SELL }

    Side side();

    /** 触发条件判定（ctx 含特征/盘口/计划/持仓/阶段）。返回空=不触发。 */
    Optional<Decision> evaluate(TradeContext ctx);

    /** 战法上下文（实时快照）。 */
    record TradeContext(RealtimeFeature feature,
                        Quote quote,
                        com.dunwugudao.replay.plan.PlanItem plan,
                        String stage,
                        PositionSnapshot position) {
    }

    /** 持仓快照（null=空仓）。 */
    record PositionSnapshot(String tsCode, double qty, double avgCost, double lastPrice,
                            double pnlPct, String entryStrategyId, String entryStage) {
    }
}
