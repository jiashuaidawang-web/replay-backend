package com.dunwugudao.replay.sim;

import com.dunwugudao.replay.mapper.ck.StockDailyMapper;
import com.dunwugudao.replay.vo.StockClose;
import com.dunwugudao.replay.mapper.ck.WatchPoolMapper;
import com.dunwugudao.replay.mapper.ck.WatchPoolRow;
import com.dunwugudao.replay.service.CkHttpWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 日线批量执行器：L2 关闭时，从 watch_pool 的买入信号直接写入 sim_trade / decision_log，
 * 无需 tick 数据驱动，按收盘价 + 计划仓位自动成交。
 *
 * <p>适用场景：无 L2 tick 数据（爬虫未采集、网络中断、L2 开关关闭），
 * 但 watch_pool 已有盘后选股的买入信号（buy / buy_dip），
 * 需要按"顿悟股道"买点记录模拟成交，用于战法验证事件线回溯。
 */
@Slf4j
@Service
public class DailyBatchExecutor {

    private static final List<String> SIM_TRADE_COLS = List.of(
            "trade_id", "order_id", "plan_id", "ts_code", "stock_name", "board_code", "role",
            "side", "price", "qty", "amount", "trade_time", "strategy_id", "stage_at_entry",
            "capital_confirm", "entry_reason", "exit_reason", "planned_action",
            "pnl", "d1_ret", "d5_ret", "is_trap");

    private static final List<String> DECISION_LOG_COLS = List.of(
            "decision_id", "ts", "ts_code", "context_json", "action",
            "score", "risk_level", "reference_price", "reason",
            "strategy_id", "stage", "capital_signal", "executed", "order_id");

    private final WatchPoolMapper watchPoolMapper;
    private final StockDailyMapper stockDailyMapper;
    private final CkHttpWriter ckHttpWriter;

    /** 模拟账户初始资金（与 SimService 保持一致）。 */
    private final double initCash;

    /** 单笔默认仓位（与 SimService 保持一致）。 */
    private final double defaultPositionPct;

    public DailyBatchExecutor(WatchPoolMapper watchPoolMapper,
                               StockDailyMapper stockDailyMapper,
                               CkHttpWriter ckHttpWriter,
                               @Value("${replay.sim.init-cash:1000000}") double initCash,
                               @Value("${replay.sim.default-position-pct:0.1}") double defaultPositionPct) {
        this.watchPoolMapper = watchPoolMapper;
        this.stockDailyMapper = stockDailyMapper;
        this.ckHttpWriter = ckHttpWriter;
        this.initCash = initCash;
        this.defaultPositionPct = defaultPositionPct;
    }

    /**
     * 对指定选股日执行日线批量买入（仅 watch_pool 中 action=buy/buy_dip 的标的）。
     * 成交价 = 该选股日收盘价（stock_daily），仓位 = initCash × defaultPositionPct。
     *
     * @param selDate 选股日
     * @return 写入的买入笔数
     */
    public int batchBuy(LocalDate selDate) {
        List<WatchPoolRow> rows = watchPoolMapper.selectBySelDate(selDate);
        if (rows == null || rows.isEmpty()) {
            log.info("[batch] 选股日={} watch_pool 无数据，跳过", selDate);
            return 0;
        }

        // 过滤买入信号
        List<WatchPoolRow> buyRows = new ArrayList<>();
        for (WatchPoolRow r : rows) {
            if (r.getSelectedAction() != null
                    && (r.getSelectedAction().equals("buy") || r.getSelectedAction().equals("buy_dip"))) {
                buyRows.add(r);
            }
        }
        if (buyRows.isEmpty()) {
            log.info("[batch] 选股日={} 无买入信号，跳过", selDate);
            return 0;
        }

        // 查收盘价
        Map<String, Double> closeMap = fetchCloses(buyRows, selDate);

        // 生成 sim_trade + decision_log
        List<Object[]> simTradeRows = new ArrayList<>();
        List<Object[]> decisionRows = new ArrayList<>();
        int count = 0;
        String now = selDate + " 15:00:00.000";

        for (WatchPoolRow r : buyRows) {
            Double price = closeMap.get(r.getTsCode());
            if (price == null || price <= 0) {
                log.warn("[batch] {} 无收盘价，跳过", r.getTsCode());
                continue;
            }

            double budget = initCash * defaultPositionPct;
            double qty = Math.floor(budget / price / 100) * 100;
            if (qty < 100) {
                log.warn("[batch] {} 资金不足（price={}，budget={}），跳过", r.getTsCode(), price, budget);
                continue;
            }
            double amount = qty * price;
            String tradeId = UUID.randomUUID().toString();
            String orderId = UUID.randomUUID().toString();
            String planId = r.getTsCode() + "@" + selDate;
            String strategy = inferStrategy(r.getSourceSkill(), r.getSelectedAction());
            String stage = "L2_OFF";
            String capitalConfirm = "NONE"; // Enum8: NONE/CONFIRM/FILTER_PASS/CONTRA

            simTradeRows.add(new Object[]{
                    tradeId, orderId, planId, r.getTsCode(), nvl(r.getStockName()),
                    nvl(r.getBoardCode()), nvl(r.getRole()), "BUY", price, qty, amount, now,
                    strategy, stage, capitalConfirm,
                    buildEntryReason(r), null, nvl(r.getSelectedAction()),
                    null, null, null, null});

            decisionRows.add(new Object[]{
                    UUID.randomUUID().toString(), now, r.getTsCode(),
                    String.format("{\"l2_used\":false,\"batch_daily\":true,\"source_skill\":\"%s\"}", nvl(r.getSourceSkill())),
                    "BUY", 75.0, "MEDIUM", price,
                    buildEntryReason(r),
                    strategy, stage, capitalConfirm, 1, orderId});

            count++;
        }

        if (!simTradeRows.isEmpty()) {
            ckHttpWriter.insert("sim_trade", SIM_TRADE_COLS, simTradeRows);
            log.info("[batch] 选股日={} sim_trade 写入 {} 条买入", selDate, count);
        }
        if (!decisionRows.isEmpty()) {
            ckHttpWriter.insert("decision_log", DECISION_LOG_COLS, decisionRows);
            log.info("[batch] 选股日={} decision_log 写入 {} 条买入决策", selDate, count);
        }

        return count;
    }

    /** 查 stock_daily FINAL 取选股日收盘价。 */
    private Map<String, Double> fetchCloses(List<WatchPoolRow> rows, LocalDate date) {
        List<String> codes = new ArrayList<>();
        for (WatchPoolRow r : rows) {
            if (r.getTsCode() != null) codes.add(r.getTsCode());
        }
        Map<String, Double> map = new LinkedHashMap<>();
        try {
            List<StockClose> closes = stockDailyMapper.selectCloses(codes, date);
            for (StockClose c : closes) {
                if (c.getClose() != null) {
                    map.put(c.getTsCode(), c.getClose().doubleValue());
                }
            }
        } catch (Exception e) {
            log.warn("[batch] 查收盘价失败: {}", e.getMessage());
        }
        return map;
    }

    /** 根据 source_skill 推断策略 ID。 */
    private static String inferStrategy(String sourceSkill, String action) {
        if (sourceSkill == null || sourceSkill.isBlank()) {
            return action != null && action.equals("buy_dip") ? "S4_DIVERGE_ABSORB" : "S5_LEADER_RELAY";
        }
        if (sourceSkill.contains("S4") && action != null && action.contains("dip")) {
            return "S4_DIVERGE_ABSORB";
        }
        if (sourceSkill.contains("S5") && action != null && action.contains("dip")) {
            return "S4_DIVERGE_ABSORB";
        }
        if (sourceSkill.contains("S6")) {
            return "S6_TREND_BREAK";
        }
        if (sourceSkill.contains("S3")) {
            return "S3_MAIN_FORCE_EARLY";
        }
        return action != null && action.equals("buy_dip") ? "S4_DIVERGE_ABSORB" : "S5_LEADER_RELAY";
    }

    /** 拼装买入理由。 */
    private static String buildEntryReason(WatchPoolRow r) {
        StringBuilder sb = new StringBuilder("日线批量买入（L2关闭）：");
        if (r.getReason() != null) {
            sb.append(r.getReason());
        }
        return sb.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
