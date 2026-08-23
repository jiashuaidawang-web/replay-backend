package com.dunwugudao.replay.sim;

import com.dunwugudao.replay.plan.PlanItem;
import com.dunwugudao.replay.realtime.CkBufferedWriter;
import com.dunwugudao.replay.realtime.RealtimeEvent;
import com.dunwugudao.replay.realtime.RealtimeEventBus;
import com.dunwugudao.replay.realtime.TickWindow;
import com.dunwugudao.replay.realtime.model.Decision;
import com.dunwugudao.replay.realtime.model.Quote;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 模拟盘（M1：内存账户 + 每笔落 CK + 事件广播）。
 *
 * <p>定位：攒经验与数据（数据底座）。每笔成交都带 strategy/stage/capital_confirm 三口径，
 * 写入 {@code sim_trade} 供多口径胜率统计；失败不阻断决策流（记录 REJECTED 单）。
 * <ul>
 *   <li>成交价 = 最新盘口价（Quote.lastPrice），无盘口时拒绝成交；</li>
 *   <li>买入数量 = min(现金×计划仓位, 现金) / 价格，向下取整到 100 股；</li>
 *   <li>每 60s 刷新一次账户快照（equity 按最新盘口估值）。</li>
 * </ul>
 */
@Slf4j
@Service
public class SimService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final List<String> ORDER_COLUMNS = List.of(
            "order_id", "plan_id", "ts_code", "side", "price", "qty", "status",
            "strategy_id", "stage_at_entry", "capital_confirm", "create_time", "decision_id");

    private static final List<String> TRADE_COLUMNS = List.of(
            "trade_id", "order_id", "plan_id", "ts_code", "stock_name", "board_code", "role",
            "side", "price", "qty", "amount", "trade_time", "strategy_id", "stage_at_entry",
            "capital_confirm", "entry_reason", "exit_reason", "planned_action",
            "pnl", "d1_ret", "d5_ret", "is_trap");

    private static final List<String> POSITION_COLUMNS = List.of(
            "position_id", "ts_code", "qty", "avg_cost", "entry_date",
            "entry_strategy_id", "entry_stage", "entry_capital_confirm", "last_val_date");

    private static final List<String> ACCOUNT_COLUMNS = List.of(
            "account_id", "trade_date", "init_cash", "cash", "equity", "position_cost");

    private final CkBufferedWriter ckBuf;
    private final RealtimeEventBus bus;
    private final TickWindow window;

    private final String accountId;
    private final double defaultPositionPct;

    @Data
    public static class Position {
        private String positionId;
        private String tsCode;
        private String stockName;
        private String boardCode;
        private String role;
        private String planId;
        private String plannedAction;
        private double qty;
        private double avgCost;
        private LocalDate entryDate;
        private String entryStrategyId;
        private String entryStage;
        private String entryCapitalConfirm;
    }

    @Data
    public static class TradeRecord {
        private String tradeId;
        private String orderId;
        private String planId;
        private String tsCode;
        private String stockName;
        private String side;
        private double price;
        private double qty;
        private double amount;
        private String tradeTime;
        private String strategyId;
        private String stageAtEntry;
        private String capitalConfirm;
        private String reason;
        private Double pnl;
    }

    private double initCash;
    private double cash;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();
    private final List<TradeRecord> trades = new CopyOnWriteArrayList<>();

    public SimService(CkBufferedWriter ckBuf,
                      RealtimeEventBus bus,
                      TickWindow window,
                      @Value("${replay.sim.account-id:sim-001}") String accountId,
                      @Value("${replay.sim.init-cash:1000000}") double initCash,
                      @Value("${replay.sim.default-position-pct:0.1}") double defaultPositionPct) {
        this.ckBuf = ckBuf;
        this.bus = bus;
        this.window = window;
        this.accountId = accountId;
        this.initCash = initCash;
        this.cash = initCash;
        this.defaultPositionPct = defaultPositionPct;
    }

    // ==================== 交易执行 ====================

    /** 买入（由 TraderEngine 决策驱动）。返回成交单号，null=未成交。 */
    public String buy(Decision d, PlanItem plan) {
        Quote q = window.getQuote(d.getTsCode());
        double price = q != null ? q.getLastPrice() : (d.getReferencePrice() != null ? d.getReferencePrice() : 0);
        if (price <= 0) {
            log.warn("[sim] BUY 拒绝（无有效价格）: {}", d.getTsCode());
            recordRejectedOrder(d, plan, price);
            return null;
        }
        double pct = plan != null && plan.getPlannedPositionPct() != null && plan.getPlannedPositionPct() > 0
                ? plan.getPlannedPositionPct() : defaultPositionPct;
        double budget = Math.min(cash, initCash * pct + cash * 0); // 上限=现金
        budget = Math.min(cash, Math.max(0, cash) * Math.max(pct, 0.01));
        double qty = Math.floor(budget / price / 100) * 100;
        if (qty < 100) {
            log.warn("[sim] BUY 拒绝（资金不足或仓位过小）: {} cash={} price={}", d.getTsCode(), cash, price);
            recordRejectedOrder(d, plan, price);
            return null;
        }
        double amount = qty * price;

        Position pos = positions.get(d.getTsCode());
        if (pos == null) {
            pos = new Position();
            pos.setPositionId(UUID.randomUUID().toString());
            pos.setTsCode(d.getTsCode());
            pos.setEntryDate(LocalDate.now());
            pos.setEntryStrategyId(d.getStrategyId());
            pos.setEntryStage(d.getStage());
            pos.setEntryCapitalConfirm(d.getCapitalSignal() == null ? "NONE" : d.getCapitalSignal());
            if (plan != null) {
                pos.setStockName(plan.getStockName());
                pos.setBoardCode(plan.getBoardCode());
                pos.setRole(plan.getRole());
                pos.setPlannedAction(plan.getPlannedAction());
                pos.setPlanId(plan.getTsCode() + "@" + plan.getPlanDate());
            }
            positions.put(d.getTsCode(), pos);
        }
        pos.setAvgCost((pos.getAvgCost() * pos.getQty() + amount) / (pos.getQty() + qty));
        pos.setQty(pos.getQty() + qty);
        cash -= amount;

        String now = nowStr();
        String tradeId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();

        ckBuf.add("sim_order", ORDER_COLUMNS, java.util.Collections.singletonList(new Object[]{
                orderId, pos.getPlanId(), d.getTsCode(), "BUY", price, qty, "FILLED",
                d.getStrategyId(), d.getStage(), d.getCapitalSignal() == null ? "NONE" : d.getCapitalSignal(),
                now, null}));

        TradeRecord t = new TradeRecord();
        t.setTradeId(tradeId);
        t.setOrderId(orderId);
        t.setPlanId(pos.getPlanId());
        t.setTsCode(d.getTsCode());
        t.setStockName(pos.getStockName());
        t.setSide("BUY");
        t.setPrice(price);
        t.setQty(qty);
        t.setAmount(amount);
        t.setTradeTime(now);
        t.setStrategyId(d.getStrategyId());
        t.setStageAtEntry(d.getStage());
        t.setCapitalConfirm(d.getCapitalSignal());
        t.setReason(d.getReason());
        trades.add(t);

        ckBuf.add("sim_trade", TRADE_COLUMNS, java.util.Collections.singletonList(new Object[]{
                tradeId, orderId, pos.getPlanId(), d.getTsCode(), nvl(pos.getStockName()),
                nvl(pos.getBoardCode()), nvl(pos.getRole()), "BUY", price, qty, amount, now,
                d.getStrategyId(), d.getStage(),
                d.getCapitalSignal() == null ? "NONE" : d.getCapitalSignal(),
                d.getReason(), null, nvl(pos.getPlannedAction()), null, null, null, null}));

        flushPositionRow(pos);
        flushAccountRow();

        bus.publish(new RealtimeEvent(RealtimeEvent.Type.SIM_TRADE, t));
        log.info("[sim] 买入成交 {} {} 股 @ {}（{}，战法={}，阶段={}，资金={}）",
                d.getTsCode(), qty, price, d.getStrategyId(), d.getStrategyId(), d.getStage(), d.getCapitalSignal());
        return tradeId;
    }

    /** 卖出（全仓）。返回成交单号，null=无持仓/无价。 */
    public String sell(Decision d) {
        Position pos = positions.get(d.getTsCode());
        if (pos == null) {
            return null;
        }
        Quote q = window.getQuote(d.getTsCode());
        double price = q != null ? q.getLastPrice()
                : (d.getReferencePrice() != null ? d.getReferencePrice() : 0);
        if (price <= 0) {
            log.warn("[sim] SELL 拒绝（无有效价格）: {}", d.getTsCode());
            return null;
        }
        double qty = pos.getQty();
        double amount = qty * price;
        double pnl = (price - pos.getAvgCost()) * qty;
        cash += amount;

        String now = nowStr();
        String tradeId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();

        ckBuf.add("sim_order", ORDER_COLUMNS, java.util.Collections.singletonList(new Object[]{
                orderId, pos.getPlanId(), d.getTsCode(), "SELL", price, qty, "FILLED",
                d.getStrategyId(), d.getStage(),
                d.getCapitalSignal() == null ? "NONE" : d.getCapitalSignal(), now, null}));

        TradeRecord t = new TradeRecord();
        t.setTradeId(tradeId);
        t.setOrderId(orderId);
        t.setPlanId(pos.getPlanId());
        t.setTsCode(d.getTsCode());
        t.setStockName(pos.getStockName());
        t.setSide("SELL");
        t.setPrice(price);
        t.setQty(qty);
        t.setAmount(amount);
        t.setTradeTime(now);
        t.setStrategyId(d.getStrategyId());
        t.setStageAtEntry(d.getStage());
        t.setCapitalConfirm(d.getCapitalSignal());
        t.setReason(d.getReason());
        t.setPnl(pnl);
        trades.add(t);

        ckBuf.add("sim_trade", TRADE_COLUMNS, java.util.Collections.singletonList(new Object[]{
                tradeId, orderId, pos.getPlanId(), d.getTsCode(), nvl(pos.getStockName()),
                nvl(pos.getBoardCode()), nvl(pos.getRole()), "SELL", price, qty, amount, now,
                d.getStrategyId(), d.getStage(),
                d.getCapitalSignal() == null ? "NONE" : d.getCapitalSignal(),
                pos.getEntryStrategyId() + "|" + pos.getEntryStage(), d.getReason(),
                nvl(pos.getPlannedAction()), pnl, null, null, null}));

        positions.remove(d.getTsCode());
        flushAccountRow();

        bus.publish(new RealtimeEvent(RealtimeEvent.Type.SIM_TRADE, t));
        log.info("[sim] 卖出成交 {} {} 股 @ {}，盈亏 {} 元（{}）",
                d.getTsCode(), qty, price, Math.round(pnl), d.getReason());
        return tradeId;
    }

    /** 重置账户（新交易日/重新演练）。 */
    public void reset(double newInitCash) {
        if (newInitCash > 0) {
            this.initCash = newInitCash;
        }
        this.cash = initCash;
        positions.clear();
        trades.clear();
        flushAccountRow();
        log.info("[sim] 账户重置：init_cash={}", initCash);
    }

    // ==================== 查询 ====================

    public Map<String, Object> accountSnapshot() {
        double positionValue = 0;
        for (Position p : positions.values()) {
            Quote q = window.getQuote(p.getTsCode());
            double price = q != null ? q.getLastPrice() : p.getAvgCost();
            positionValue += p.getQty() * price;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", accountId);
        m.put("initCash", initCash);
        m.put("cash", round2(cash));
        m.put("positionValue", round2(positionValue));
        m.put("equity", round2(cash + positionValue));
        m.put("pnl", round2(cash + positionValue - initCash));
        m.put("pnlPct", initCash > 0 ? round2((cash + positionValue - initCash) / initCash * 100) : 0);
        m.put("positionCount", positions.size());
        return m;
    }

    public List<Position> positions() {
        return new ArrayList<>(positions.values());
    }

    /** 持仓带实时估值。 */
    public List<Map<String, Object>> positionsWithQuote() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Position p : positions.values()) {
            Quote q = window.getQuote(p.getTsCode());
            double price = q != null ? q.getLastPrice() : p.getAvgCost();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tsCode", p.getTsCode());
            m.put("stockName", p.getStockName());
            m.put("qty", p.getQty());
            m.put("avgCost", round2(p.getAvgCost()));
            m.put("lastPrice", price);
            m.put("pnl", round2((price - p.getAvgCost()) * p.getQty()));
            m.put("pnlPct", round2((price / p.getAvgCost() - 1) * 100));
            m.put("entryStrategyId", p.getEntryStrategyId());
            m.put("entryStage", p.getEntryStage());
            m.put("entryDate", p.getEntryDate());
            list.add(m);
        }
        return list;
    }

    public List<TradeRecord> trades() {
        List<TradeRecord> all = new ArrayList<>(trades);
        java.util.Collections.reverse(all);
        return all.size() > 200 ? all.subList(0, 200) : all;
    }

    public Position position(String tsCode) {
        return positions.get(tsCode);
    }

    // ==================== 内部 ====================

    private void recordRejectedOrder(Decision d, PlanItem plan, double price) {
        String orderId = UUID.randomUUID().toString();
        ckBuf.add("sim_order", ORDER_COLUMNS, java.util.Collections.singletonList(new Object[]{
                orderId, plan == null ? null : plan.getTsCode() + "@" + plan.getPlanDate(),
                d.getTsCode(), "BUY", price, 0, "REJECTED",
                d.getStrategyId(), d.getStage(),
                d.getCapitalSignal() == null ? "NONE" : d.getCapitalSignal(), nowStr(), null}));
    }

    private void flushPositionRow(Position p) {
        ckBuf.add("sim_position", POSITION_COLUMNS, java.util.Collections.singletonList(new Object[]{
                p.getPositionId(), p.getTsCode(), p.getQty(), p.getAvgCost(), p.getEntryDate(),
                p.getEntryStrategyId(), p.getEntryStage(),
                p.getEntryCapitalConfirm() == null ? "NONE" : p.getEntryCapitalConfirm(),
                LocalDate.now()}));
    }

    /** 每 60s 刷新账户快照到 CK（盘中估值曲线）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void flushAccountRow() {
        Map<String, Object> snap = accountSnapshot();
        ckBuf.add("sim_account", ACCOUNT_COLUMNS, java.util.Collections.singletonList(new Object[]{
                accountId, LocalDate.now(), initCash, snap.get("cash"),
                snap.get("equity"), snap.get("positionValue")}));
    }

    private static String nowStr() {
        return java.time.LocalDateTime.now().format(TS_FMT);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
