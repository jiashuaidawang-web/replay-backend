package com.dunwugudao.replay.trader;

import com.dunwugudao.replay.plan.PlanItem;
import com.dunwugudao.replay.plan.PlanPoolService;
import com.dunwugudao.replay.realtime.CkBufferedWriter;
import com.dunwugudao.replay.realtime.FeatureCalculator;
import com.dunwugudao.replay.realtime.RealtimeEvent;
import com.dunwugudao.replay.realtime.RealtimeEventBus;
import com.dunwugudao.replay.realtime.StageSnapshotHolder;
import com.dunwugudao.replay.realtime.TickWindow;
import com.dunwugudao.replay.realtime.model.Decision;
import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.realtime.model.RealtimeFeature;
import com.dunwugudao.replay.sim.SimService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 交易元决策引擎（顿悟实时版，M1）。
 *
 * <p>消费 FEATURE 事件 → 只对「盘前关注池」内标的评估（狙击圈）：
 * <ol>
 *   <li><b>阶段闸门</b>：战法 applicable_stages ∋ 当前情绪阶段（UNKNOWN 放行并告警，避免无情绪数据全瘫）；</li>
 *   <li><b>资金层三态</b>：CONFIRM(净买入≥强阈值) / FILTER_PASS(净买入≥0) / CONTRA(净卖出)，
 *       按战法 capitalRole 施加（IGNORE=不管 / FILTER=拦 CONTRA / CONFIRM=必须 CONFIRM）——
 *       资金层是<b>正交可关</b>的信号，不是唯一依据；</li>
 *   <li><b>战法评估</b>：买入取触发战法中分数最高者；卖出（持仓时）任一触发即执行风控优先；</li>
 *   <li><b>自动模拟执行</b>：BUY/SELL → {@link SimService}（可关 auto-execute 只出建议）；</li>
 *   <li><b>决策全落库</b>：decision_log（每步建议都留痕，含未执行的 WATCH/HOLD）。</li>
 * </ol>
 * 决策输入用 5 分钟窗口特征（1 分钟窗口仅作快速辅助观察，不直接驱动决策）。
 */
@Slf4j
@Component
public class TraderEngine {

    private static final List<String> DECISION_COLUMNS = List.of(
            "decision_id", "ts", "ts_code", "context_json", "action", "score",
            "risk_level", "reference_price", "reason", "strategy_id", "stage",
            "capital_signal", "executed", "order_id");

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final RealtimeEventBus bus;
    private final PlanPoolService planPool;
    private final SimService sim;
    private final TickWindow window;
    private final StageSnapshotHolder stageHolder;
    private final FeatureCalculator featureCalculator;
    private final CkBufferedWriter ckBuf;
    private final boolean autoExecute;
    private final boolean l2Enabled;

    private final List<Strategy> buyStrategies = DunwuStrategies.buyStrategies();
    private final List<Strategy> sellStrategies = DunwuStrategies.sellStrategies();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 近期决策（内存，REST/SSE 查询用）。 */
    private final List<Decision> recentDecisions = new CopyOnWriteArrayList<>();
    /** 决策 ID（Decision 无 id 字段，另存映射）。 */
    private final List<String> recentDecisionIds = new CopyOnWriteArrayList<>();
    private final Map<String, Long> lastPassiveAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastBuyAt = new ConcurrentHashMap<>();

    public TraderEngine(RealtimeEventBus bus,
                        PlanPoolService planPool,
                        SimService sim,
                        TickWindow window,
                        StageSnapshotHolder stageHolder,
                        FeatureCalculator featureCalculator,
                        CkBufferedWriter ckBuf,
                        @Value("${replay.sim.auto-execute:true}") boolean autoExecute,
                        @Value("${replay.stream.l2.enabled:false}") boolean l2Enabled) {
        this.bus = bus;
        this.planPool = planPool;
        this.sim = sim;
        this.window = window;
        this.stageHolder = stageHolder;
        this.featureCalculator = featureCalculator;
        this.ckBuf = ckBuf;
        this.autoExecute = autoExecute;
        this.l2Enabled = l2Enabled;
    }

    @PostConstruct
    public void init() {
        bus.subscribe(e -> {
            if (e.getType() == RealtimeEvent.Type.FEATURE) {
                try {
                    onFeature(RealtimeEvent.asFeature(e.getPayload()));
                } catch (Exception ex) {
                    log.warn("[trader] 决策处理异常: {}", ex.getMessage());
                }
            }
        });
        log.info("[trader] TraderEngine 就绪：买入战法 {} 条，卖出战法 {} 条，autoExecute={}，l2Enabled={}",
                buyStrategies.size(), sellStrategies.size(), autoExecute, l2Enabled);
    }

    private void onFeature(RealtimeFeature f) {
        if (f.getWinMinutes() != 5) {
            return; // 决策输入 = 5 分钟窗口
        }
        PlanItem plan = planPool.get(f.getTsCode());
        if (plan == null) {
            return; // 狙击圈外不评估
        }
        String stage = stageHolder.currentStage();
        Quote q = window.getQuote(f.getTsCode());
        SimService.Position pos = sim.position(f.getTsCode());
        String capitalSignal = resolveCapitalSignal(f);

        Strategy.PositionSnapshot snap = null;
        if (pos != null) {
            double last = q != null ? q.getLastPrice() : pos.getAvgCost();
            snap = new Strategy.PositionSnapshot(pos.getTsCode(), pos.getQty(), pos.getAvgCost(),
                    last, pos.getAvgCost() > 0 ? (last / pos.getAvgCost() - 1) * 100 : 0,
                    pos.getEntryStrategyId(), pos.getEntryStage());
        }
        Strategy.TradeContext ctx = new Strategy.TradeContext(f, q, plan, stage, snap);

        if (pos != null) {
            evaluateSell(ctx, capitalSignal);
        } else {
            evaluateBuy(ctx, capitalSignal);
        }
    }

    // ==================== 买入评估 ====================

    private void evaluateBuy(Strategy.TradeContext ctx, String capitalSignal) {
        RealtimeFeature f = ctx.feature();
        String stage = ctx.stage();
        long now = System.currentTimeMillis();

        List<Decision> candidates = new ArrayList<>();
        for (Strategy s : buyStrategies) {
            // 计划候选战法限定（空=全开放）
            if (ctx.plan().getCandidateStrategies() != null && !ctx.plan().getCandidateStrategies().isEmpty()
                    && !ctx.plan().getCandidateStrategies().contains(s.id())) {
                continue;
            }
            // 阶段闸门（UNKNOWN 放行——M1 宽松策略）
            if (!stageGate(s, stage)) {
                continue;
            }
            // 资金层闸门（正交信号：IGNORE/FILTER/CONFIRM）
            if (!capitalGate(s, capitalSignal)) {
                continue;
            }
            s.evaluate(ctx).ifPresent(d -> {
                d.setStrategyId(s.id());
                d.setCapitalSignal(capitalSignal);
                candidates.add(d);
            });
        }

        if (!candidates.isEmpty()) {
            // 同一标的高频触发限流：30s 内不重复出 BUY 决策
            Long last = lastBuyAt.get(f.getTsCode());
            if (last != null && now - last < 30_000) {
                return;
            }
            lastBuyAt.put(f.getTsCode(), now);
            candidates.sort(Comparator.comparingDouble(Decision::getScore).reversed());
            Decision best = candidates.get(0);
            String orderId = null;
            if (autoExecute) {
                orderId = sim.buy(best, ctx.plan());
            }
            emit(best, f, orderId != null);
            return;
        }

        // 未触发 → WATCH（限流 60s 一次）
        passive(f, ctx, capitalSignal, Decision.Action.WATCH,
                "池内观察：无战法触发（阶段=" + stage + "，资金=" + capitalSignal + "）");
    }

    // ==================== 卖出评估（持仓时） ====================

    private void evaluateSell(Strategy.TradeContext ctx, String capitalSignal) {
        RealtimeFeature f = ctx.feature();
        String stage = ctx.stage();

        for (Strategy s : sellStrategies) {
            // 卖出风控不受阶段闸门限制（炸板止损等必须随时生效）；阶段码仅做记录
            if (!s.applicableStages().isEmpty() && !s.applicableStages().contains(stage)
                    && !"RT_BLAST_STOP".equals(s.id()) && !"RT_BIG_OUTFLOW".equals(s.id())) {
                continue;
            }
            var opt = s.evaluate(ctx);
            if (opt.isPresent()) {
                Decision d = opt.get();
                d.setStrategyId(s.id());
                d.setCapitalSignal(capitalSignal);
                String orderId = null;
                if (autoExecute) {
                    orderId = sim.sell(d);
                }
                emit(d, f, orderId != null);
                return;
            }
        }

        // 无卖出信号 → HOLD（限流 60s 一次）
        passive(f, ctx, capitalSignal, Decision.Action.HOLD,
                "持有：无卖出信号（阶段=" + stage + "，资金=" + capitalSignal
                        + "，浮盈 " + String.format("%.1f%%", ctx.position().pnlPct()) + "）");
    }

    // ==================== 闸门 ====================

    private boolean stageGate(Strategy s, String stage) {
        if ("UNKNOWN".equals(stage)) {
            return true; // 无情绪数据时放行（M1 宽松），保证系统可用
        }
        return s.applicableStages().isEmpty() || s.applicableStages().contains(stage);
    }

    /**
     * 资金层信号。L2 关闭时返回 NONE（放行所有战法，按纯技术面决策）；
     * L2 开启时返回 CONFIRM / FILTER_PASS / CONTRA。
     * <p>返回值直接写入 sim_trade.capital_confirm（Enum8: NONE/CONFIRM/FILTER_PASS/CONTRA）。
     */
    private String resolveCapitalSignal(RealtimeFeature f) {
        if (!l2Enabled) {
            return "NONE";
        }
        double strong = featureCalculator.getNetBuyStrong();
        if (f.getBigNetBuy() >= strong) {
            return "CONFIRM";
        }
        return f.getBigNetBuy() >= 0 ? "FILTER_PASS" : "CONTRA";
    }

    private boolean capitalGate(Strategy s, String signal) {
        // NONE = L2 关闭，资金闸门全开，按纯技术面决策
        if ("NONE".equals(signal)) {
            return true;
        }
        return switch (s.capitalRole()) {
            case IGNORE -> true;
            case FILTER -> !"CONTRA".equals(signal);
            case CONFIRM -> "CONFIRM".equals(signal);
        };
    }

    // ==================== 决策落库/广播 ====================

    private void passive(RealtimeFeature f, Strategy.TradeContext ctx, String capitalSignal,
                         Decision.Action action, String reason) {
        long now = System.currentTimeMillis();
        Long last = lastPassiveAt.get(f.getTsCode());
        if (last != null && now - last < 60_000) {
            return;
        }
        lastPassiveAt.put(f.getTsCode(), now);
        Decision d = Decision.builder()
                .tsCode(f.getTsCode())
                .ts(now)
                .action(action)
                .score(0)
                .riskLevel("低")
                .referencePrice(ctx.quote() == null ? null : ctx.quote().getLastPrice())
                .reason(reason)
                .strategyId(null)
                .stage(ctx.stage())
                .capitalSignal(capitalSignal)
                .planId(ctx.plan() == null ? null : ctx.plan().getTsCode() + "@" + ctx.plan().getPlanDate())
                .stockName(ctx.plan() == null ? null : ctx.plan().getStockName())
                .role(ctx.plan() == null ? null : ctx.plan().getRole())
                .build();
        emit(d, f, false);
    }

    private void emit(Decision d, RealtimeFeature f, boolean executed) {
        String decisionId = UUID.randomUUID().toString();
        recentDecisions.add(d);
        recentDecisionIds.add(decisionId);
        if (recentDecisions.size() > 500) {
            recentDecisions.subList(0, recentDecisions.size() - 500).clear();
            recentDecisionIds.subList(0, recentDecisionIds.size() - 500).clear();
        }
        bus.publish(new RealtimeEvent(RealtimeEvent.Type.DECISION, d));

        // 落 decision_log
        String tsStr = Instant.ofEpochMilli(d.getTs()).atZone(ZoneId.systemDefault())
                .toLocalDateTime().format(TS_FMT);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("bigNetBuy", f.getBigNetBuy());
        context.put("bigNetBuyRatio", round4(f.getBigNetBuyRatio()));
        context.put("sealAmount", f.getSealAmount());
        context.put("isBlast", f.getIsBlast());
        context.put("isReseal", f.getIsReseal());
        context.put("volBreakout", f.getVolBreakout());
        context.put("pctChg", contextQuotePct(d.getTsCode()));
        context.put("l2Used", l2Enabled && !"NONE".equals(d.getCapitalSignal()));
        String contextJson = "{}";
        try {
            contextJson = mapper.writeValueAsString(context);
        } catch (Exception ignored) {
        }
        ckBuf.add("decision_log", DECISION_COLUMNS, java.util.Collections.singletonList(new Object[]{
                decisionId, tsStr, d.getTsCode(), contextJson, d.getAction().name(), d.getScore(),
                d.getRiskLevel() == null ? "低" : d.getRiskLevel(), d.getReferencePrice(),
                d.getReason() == null ? "" : d.getReason(),
                d.getStrategyId() == null ? "" : d.getStrategyId(),
                d.getStage() == null ? "UNKNOWN" : d.getStage(),
                d.getCapitalSignal() == null ? "NONE" : d.getCapitalSignal(),
                executed ? 1 : 0, null}));

        if (executed) {
            log.info("[trader] 决策执行 {} {} score={}（{}）",
                    d.getAction(), d.getTsCode(), Math.round(d.getScore()), d.getReason());
        }
    }

    private Double contextQuotePct(String tsCode) {
        Quote q = window.getQuote(tsCode);
        return q == null ? null : q.getPctChg();
    }

    // ==================== 查询（REST 用） ====================

    public List<Map<String, Object>> recentDecisions(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int from = Math.max(0, recentDecisions.size() - limit);
        for (int i = recentDecisions.size() - 1; i >= from; i--) {
            Decision d = recentDecisions.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decisionId", recentDecisionIds.get(i));
            m.put("ts", d.getTs());
            m.put("tsCode", d.getTsCode());
            m.put("stockName", d.getStockName());
            m.put("action", d.getAction().name());
            m.put("score", d.getScore());
            m.put("riskLevel", d.getRiskLevel());
            m.put("referencePrice", d.getReferencePrice());
            m.put("reason", d.getReason());
            m.put("strategyId", d.getStrategyId());
            m.put("stage", d.getStage());
            m.put("capitalSignal", d.getCapitalSignal());
            m.put("role", d.getRole());
            out.add(m);
        }
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
