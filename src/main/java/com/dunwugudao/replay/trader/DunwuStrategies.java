package com.dunwugudao.replay.trader;

import com.dunwugudao.replay.realtime.model.Decision;
import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.realtime.model.RealtimeFeature;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 《顿悟股道》战法目录（M1 规则版）。
 *
 * <p>战法全量注册 + 资金层作正交信号（capitalRole），不预先砍任何战法——
 * 后期按 exp_log/sim_trade 多口径统计收窄（哪些策略 × 哪些阶段 × 资金确认 才赚钱）。
 * <p>M1 的触发条件为可运行的规则近似（后续由经验库调参）；每条战法的 id 与
 * CK strategy_catalog 种子一致，保证统计口径对得上。
 */
public final class DunwuStrategies {

    private DunwuStrategies() {
    }

    // ==================== 买入战法 ====================

    /** S4 分歧低吸：分歧日回调低吸换手龙/龙头（资金强确认）。 */
    public static Strategy divergeAbsorb() {
        return base("S4_DIVERGE_ABSORB", "分歧低吸", Set.of("DIVERGE", "DIVERGE_CONSENSUS"),
                CapitalRole.CONFIRM, Strategy.Side.BUY, (ctx) -> {
                    Quote q = ctx.quote();
                    double pct = q == null ? 0 : q.getPctChg();
            if (pct <= -1 && pct >= -8) {
                return Optional.of(decision(ctx, Decision.Action.BUY,
                        62 + Math.min(18, Math.abs(ctx.feature().getBigNetBuyRatio()) * 60),
                        "中", "分歧日回调低吸：跌 " + pct + "% 且资金未撤（净买入 "
                                + Math.round(ctx.feature().getBigNetBuy()) + " 元）",
                        ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** S4 板上换手回封：炸板后回封 = 游资打板信号（资金强确认）。 */
    public static Strategy boardReseal() {
        return base("S4_BOARD_RESEAL", "板上换手回封", Set.of("CONSENSUS"),
                CapitalRole.CONFIRM, Strategy.Side.BUY, (ctx) -> {
            if (ctx.feature().getIsReseal() == 1) {
                return Optional.of(decision(ctx, Decision.Action.BUY, 72, "中",
                        "炸板后回封：封单 " + Math.round(ctx.feature().getSealAmount())
                                + " 元，游资打板信号", ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** S5 龙头接力：上升期放量 + 资金确认，不追一字/涨停价（2~4 板接力）。 */
    public static Strategy leaderRelay() {
        return base("S5_LEADER_RELAY", "龙头接力", Set.of("STARTUP", "DIVERGE_CONSENSUS"),
                CapitalRole.CONFIRM, Strategy.Side.BUY, (ctx) -> {
            Quote q = ctx.quote();
            if (q == null) {
                return Optional.empty();
            }
            double pct = q.getPctChg();
            if (ctx.feature().getVolBreakout() == 1 && pct >= 3 && pct < 9.5) {
                return Optional.of(decision(ctx, Decision.Action.BUY, 75, "中",
                        "龙头接力：放量突破 + 涨 " + pct + "% 未封死，资金净买入 "
                                + Math.round(ctx.feature().getBigNetBuy()) + " 元",
                        ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** S6 趋势突破：放量 + 资金确认（M1 用日内口径近似「站上牛熊线」）。 */
    public static Strategy trendBreak() {
        return base("S6_TREND_BREAK", "趋势突破半仓", Set.of("STARTUP", "REPAIR"),
                CapitalRole.CONFIRM, Strategy.Side.BUY, (ctx) -> {
            Quote q = ctx.quote();
            if (ctx.feature().getVolBreakout() == 1 && (q == null || q.getPctChg() > 0)) {
                return Optional.of(decision(ctx, Decision.Action.BUY, 62, "中",
                        "趋势突破：放量突破且红盘，资金净买入 "
                                + Math.round(ctx.feature().getBigNetBuy()) + " 元",
                        ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** S4 妖股分歧低吸极限：连板高标分歧日深水低吸（仅妖角色，仓位必须小）。 */
    public static Strategy demonDivergeAbsorb() {
        return base("DEMON_DIVERGE_ABSORB", "妖股分歧低吸极限", Set.of("DIVERGE"),
                CapitalRole.CONFIRM, Strategy.Side.BUY, (ctx) -> {
            Quote q = ctx.quote();
            if (q == null || ctx.plan() == null || !ctx.plan().getRole().contains("妖")) {
                return Optional.empty();
            }
            double pct = q.getPctChg();
            if (pct <= -4 && ctx.feature().getBigNetBuy() > 0) {
                return Optional.of(decision(ctx, Decision.Action.BUY, 55, "高",
                        "妖股分歧深水：跌 " + pct + "% 但资金逆势净买入，极限低吸（小仓）",
                        ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** S2 冰点试错：冰点期小仓试错（资金层忽略）。 */
    public static Strategy iceTry() {
        return base("S2_ICE_TRY", "冰点试错", Set.of("ICE", "CHAOS"),
                CapitalRole.IGNORE, Strategy.Side.BUY, (ctx) -> {
            Quote q = ctx.quote();
            if (q == null) {
                return Optional.empty();
            }
            double pct = q.getPctChg();
            if (pct >= -3 && pct <= 3 && ctx.feature().getBigNetBuy() > 0) {
                return Optional.of(decision(ctx, Decision.Action.BUY, 40, "高",
                        "冰点先手区小仓试错：横盘且大单净买入，试错仓（最小仓位）",
                        ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** S4 独狼沿趋势低吸：非龙高连板沿趋势低吸（资金层忽略）。 */
    public static Strategy wolfTrendLow() {
        return base("WOLF_TREND_LOW", "独狼沿趋势低吸", Set.of("REPAIR"),
                CapitalRole.IGNORE, Strategy.Side.BUY, (ctx) -> {
            Quote q = ctx.quote();
            if (q == null || ctx.plan() == null || !ctx.plan().getRole().contains("独狼")) {
                return Optional.empty();
            }
            double pct = q.getPctChg();
            if (pct >= -3 && pct <= 0 && ctx.feature().getBigNetBuy() >= 0) {
                return Optional.of(decision(ctx, Decision.Action.BUY, 50, "中",
                        "独狼回踩趋势线：跌 " + pct + "% 缩量回踩，沿趋势低吸",
                        ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** S3 跟主力建仓早：低位堆量 + 主力净流入早跟进（资金强确认）。 */
    public static Strategy mainForceEarly() {
        return base("S3_MAIN_FORCE_EARLY", "跟主力建仓早", Set.of("STARTUP"),
                CapitalRole.CONFIRM, Strategy.Side.BUY, (ctx) -> {
            Quote q = ctx.quote();
            if (q == null) {
                return Optional.empty();
            }
            if (ctx.feature().getBigNetBuyRatio() > 0.1 && q.getPctChg() > 0 && q.getPctChg() < 6) {
                return Optional.of(decision(ctx, Decision.Action.BUY, 60, "中",
                        "低位堆量主力建仓：大单净买入占比 "
                                + String.format("%.1f%%", ctx.feature().getBigNetBuyRatio() * 100)
                                + "，早跟进", ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    // ==================== 卖出/风控战法 ====================

    /** S5 高位5板分歧卖：情绪高潮/高位分歧 → 兑现（资金层过滤）。 */
    public static Strategy highDivergeSell() {
        return base("S5_HIGH_DIVERGE_SELL", "高位5板分歧卖", Set.of("CLIMAX", "DIVERGE", "CONSENSUS"),
                CapitalRole.FILTER, Strategy.Side.SELL, (ctx) -> {
            if (ctx.position() == null) {
                return Optional.empty();
            }
            boolean climax = "CLIMAX".equals(ctx.stage());
            boolean highProfit = ctx.position().pnlPct() >= 15;
            boolean diverge = "DIVERGE".equals(ctx.stage()) && ctx.position().pnlPct() >= 8;
            if (climax || highProfit || diverge) {
                String why = climax ? "情绪高潮=收割完成，该撤" :
                        highProfit ? "浮盈 " + String.format("%.1f%%", ctx.position().pnlPct()) + " 达标兑现" :
                                "分歧期浮盈回落前兑现";
                return Optional.of(decision(ctx, Decision.Action.SELL, 70, "低", why,
                        ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** 炸板止损：持仓炸板且浮亏 → 离场（资金层忽略）。 */
    public static Strategy blastStop() {
        return base("RT_BLAST_STOP", "炸板止损", Set.of(),  // 卖出风控不受阶段闸门限制
                CapitalRole.IGNORE, Strategy.Side.SELL, (ctx) -> {
            if (ctx.position() != null && ctx.feature().getIsBlast() == 1 && ctx.position().pnlPct() < 0) {
                return Optional.of(decision(ctx, Decision.Action.SELL, 65, "高",
                        "炸板且浮亏 " + String.format("%.1f%%", ctx.position().pnlPct()) + "，纪律止损",
                        ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    /** 资金大流出落袋：大单净卖出显著 + 有浮盈 → 落袋为安（资金强确认的反向用法）。 */
    public static Strategy bigOutflowTakeProfit() {
        return base("RT_BIG_OUTFLOW", "资金大流出落袋", Set.of(),
                CapitalRole.IGNORE, Strategy.Side.SELL, (ctx) -> {
            if (ctx.position() == null) {
                return Optional.empty();
            }
            if (ctx.feature().getBigNetBuy() < 0
                    && ctx.feature().getBigNetBuyRatio() <= -0.15
                    && ctx.position().pnlPct() > 3) {
                return Optional.of(decision(ctx, Decision.Action.SELL, 60, "中",
                        "大单净流出占比 " + String.format("%.1f%%", ctx.feature().getBigNetBuyRatio() * 100)
                                + "，主力撤、落袋为安", ctx.feature().getBigNetBuy()));
            }
            return Optional.empty();
        });
    }

    // ==================== 汇总 ====================

    public static List<Strategy> all() {
        return List.of(
                divergeAbsorb(), boardReseal(), leaderRelay(), trendBreak(),
                demonDivergeAbsorb(), iceTry(), wolfTrendLow(), mainForceEarly(),
                highDivergeSell(), blastStop(), bigOutflowTakeProfit());
    }

    public static List<Strategy> buyStrategies() {
        return all().stream().filter(s -> s.side() == Strategy.Side.BUY).toList();
    }

    public static List<Strategy> sellStrategies() {
        return all().stream().filter(s -> s.side() == Strategy.Side.SELL).toList();
    }

    // ==================== 工具 ====================

    private interface Rule {
        Optional<Decision> apply(Strategy.TradeContext ctx);
    }

    private static Strategy base(String id, String name, Set<String> stages,
                                 CapitalRole role, Strategy.Side side, Rule rule) {
        return new Strategy() {
            @Override public String id() { return id; }
            @Override public String name() { return name; }
            @Override public Set<String> applicableStages() { return stages; }
            @Override public CapitalRole capitalRole() { return role; }
            @Override public Side side() { return side; }
            @Override public Optional<Decision> evaluate(TradeContext ctx) { return rule.apply(ctx); }
            @Override public String toString() { return id + "(" + name + ")"; }
        };
    }

    private static Decision decision(Strategy.TradeContext ctx, Decision.Action action,
                                     double score, String risk, String reason, double bigNetBuy) {
        Quote q = ctx.quote();
        RealtimeFeature f = ctx.feature();
        return Decision.builder()
                .tsCode(f.getTsCode())
                .ts(System.currentTimeMillis())
                .action(action)
                .score(score)
                .riskLevel(risk)
                .referencePrice(q == null ? null : q.getLastPrice())
                .reason(reason)
                .strategyId(null) // 由 TraderEngine 落库时填
                .stage(ctx.stage())
                .capitalSignal(null)
                .planId(ctx.plan() == null ? null : ctx.plan().getTsCode() + "@" + ctx.plan().getPlanDate())
                .stockName(ctx.plan() == null ? null : ctx.plan().getStockName())
                .role(ctx.plan() == null ? null : ctx.plan().getRole())
                .build();
    }
}
