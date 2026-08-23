package com.dunwugudao.replay.sim;

import com.dunwugudao.replay.mapper.ck.SimTradeCkMapper;
import com.dunwugudao.replay.mapper.ck.SimTradeRow;
import com.dunwugudao.replay.mapper.ck.StockDailyMapper;
import com.dunwugudao.replay.service.CkHttpWriter;
import com.dunwugudao.replay.vo.StockClose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 盘后结算（M2）：收盘后对接复盘流程，做两件事——
 * <ol>
 *   <li><b>d1/d5 收益回填</b>：对模拟成交（买入开仓 / 卖出闭环）按 T+1/T+5 收盘价算标准持有收益，
 *       写回 {@code sim_trade.d1_ret / d5_ret}（RMT 重 INSERT 折叠）；</li>
 *   <li><b>exp_log 经验生成</b>：以「卖出闭环 / 买入开仓」为单位生成经验反馈，带 strategy×stage×capital_confirm
 *       多口径维度，供未来量化 agent 消费（数据底座）。</li>
 * </ol>
 *
 * <p>收益口径（标准化，便于跨战法横向比较）：
 * <pre>
 *   d1_ret = (close(T+1) - basis_price) / basis_price × 100
 *   d5_ret = (close(T+5) - basis_price) / basis_price × 100
 * </pre>
 * basis_price 取「买入价」——即 sim_trade.d1_ret/d5_ret 对 BUY/SELL 笔均回填买入后持有基准；
 * exp_log 闭环经验同样用配对 BUY 笔的买入价算（代表「若买入死拿不动」基准，与主动买卖 pnl 对比）。
 * 用 stock_daily 真实收盘价（T+N 取第 N 个晚于成交日的交易日，自然日升序近似交易日）。
 */
@Slf4j
@Service
public class AfterCloseSettlement {

    /** sim_trade 写入列（与 SimService.TRADE_COLUMNS 完全一致，重 INSERT 折叠需相同列序）。 */
    private static final List<String> TRADE_COLS = List.of(
            "trade_id", "order_id", "plan_id", "ts_code", "stock_name", "board_code", "role",
            "side", "price", "qty", "amount", "trade_time", "strategy_id", "stage_at_entry",
            "capital_confirm", "entry_reason", "exit_reason", "planned_action",
            "pnl", "d1_ret", "d5_ret", "is_trap");

    private static final List<String> EXP_COLS = List.of(
            "exp_id", "trade_id", "trade_date", "ts_code", "strategy_id", "stage", "capital_confirm",
            "direction", "role", "entry_features_json", "plan_match", "outcome_pnl",
            "d1_ret", "d5_ret", "is_trap", "lesson", "feedback_tag", "consumed_by_agent");

    private final SimTradeCkMapper simTradeMapper;
    private final StockDailyMapper stockDailyMapper;
    private final CkHttpWriter ckHttpWriter;

    public AfterCloseSettlement(SimTradeCkMapper simTradeMapper,
                                StockDailyMapper stockDailyMapper,
                                CkHttpWriter ckHttpWriter) {
        this.simTradeMapper = simTradeMapper;
        this.stockDailyMapper = stockDailyMapper;
        this.ckHttpWriter = ckHttpWriter;
    }

    /** 盘后结算入口（对接 ReplayCalcJob 末尾 / 收盘定时 / 手动 REST）。 */
    public void settle(LocalDate tradeDate) {
        log.info("====== 盘后结算开始: {} ======", tradeDate);
        // 先回填 d1/d5（BUY 开仓 + SELL 闭环），再生成 exp_log（复用回填后的 d1/d5）
        safeStep("d1/d5 收益回填", this::backfillReturns);
        safeStep("exp_log 经验生成", this::generateExpLog);
        log.info("====== 盘后结算结束: {} ======", tradeDate);
    }

    // ==================== 1) d1/d5 回填 ====================

    private void backfillReturns() {
        List<SimTradeRow> pending = simTradeMapper.selectWithNullD1();
        if (pending.isEmpty()) {
            log.info("[结算] 无待回填 d1_ret 的成交（可能当日无成交或已回填）");
            return;
        }
        // 收集所有涉及的股票，批量拉收盘价序列（fromDate = 最早 trade_date）
        LocalDate minDate = pending.stream()
                .map(r -> parseDate(r.getTradeTime()))
                .min(LocalDate::compareTo).orElse(LocalDate.now());
        List<String> tsCodes = pending.stream().map(SimTradeRow::getTsCode).distinct().toList();
        Map<String, List<StockClose>> closeMap = loadCloses(tsCodes, minDate);

        List<Object[]> updRows = new ArrayList<>();
        int filled = 0;
        for (SimTradeRow r : pending) {
            LocalDate dealDate = parseDate(r.getTradeTime());
            List<StockClose> closes = closeMap.getOrDefault(r.getTsCode(), List.of());
            Double c1 = closeAtOffset(closes, dealDate, 1);
            Double c5 = closeAtOffset(closes, dealDate, 5);
            if (r.getPrice() == null || r.getPrice() <= 0) {
                continue;
            }
            Double d1 = c1 != null ? (c1 - r.getPrice()) / r.getPrice() * 100 : null;
            Double d5 = c5 != null ? (c5 - r.getPrice()) / r.getPrice() * 100 : null;
            if (d1 == null && d5 == null) {
                continue; // 数据源不足，跳过（T+1 都无收盘价说明数据缺口）
            }
            updRows.add(toTradeRow(r, d1, d5));
            filled++;
        }
        if (!updRows.isEmpty()) {
            ckHttpWriter.insert("sim_trade", TRADE_COLS, updRows);
        }
        log.info("[结算] d1/d5 回填 {} 笔（扫描 {} 笔待回填）", filled, pending.size());
    }

    // ==================== 2) exp_log 生成 ====================

    private void generateExpLog() {
        List<String> codes = simTradeMapper.selectDistinctTsCodes();
        if (codes.isEmpty()) {
            log.info("[结算] sim_trade 为空，跳过 exp_log 生成");
            return;
        }
        // 收集全部成交涉及股票，批量拉收盘价（开仓经验也需 T+1/T+5 基准）
        LocalDate minDate = LocalDate.now().minusDays(30);
        Map<String, List<StockClose>> closeMap = loadCloses(codes, minDate);

        List<Object[]> expRows = new ArrayList<>();
        for (String tsCode : codes) {
            List<SimTradeRow> rows = simTradeMapper.selectByTsCode(tsCode);
            SimTradeRow openBuy = null;
            for (SimTradeRow r : rows) {
                if ("BUY".equals(r.getSide())) {
                    openBuy = r; // 最近一笔未配对的买入
                } else if ("SELL".equals(r.getSide())) {
                    expRows.add(buildExp(r, openBuy, closeMap.getOrDefault(tsCode, List.of())));
                    openBuy = null;
                }
            }
            // 残余 openBuy（仍持仓）= 开仓经验（未闭环，算持有基准 d1/d5）
            if (openBuy != null) {
                expRows.add(buildExp(openBuy, null, closeMap.getOrDefault(tsCode, List.of())));
            }
        }
        if (!expRows.isEmpty()) {
            ckHttpWriter.insert("exp_log", EXP_COLS, expRows);
        }
        log.info("[结算] exp_log 生成 {} 条经验", expRows.size());
    }

    // ==================== 内部 ====================

    private Object[] buildExp(SimTradeRow closeOrOpen, SimTradeRow entryBuy, List<StockClose> closes) {
        boolean isClosed = "SELL".equals(closeOrOpen.getSide());
        String tradeId = closeOrOpen.getTradeId();
        LocalDate tradeDate = parseDate(closeOrOpen.getTradeTime());
        String direction = isClosed ? "SELL" : "BUY";
        Double outcomePnl = closeOrOpen.getPnl(); // SELL 有真实 pnl；BUY 开仓为 NULL（浮盈未实现）

        // d1/d5 基准口径（关键修正）：
        //  - 闭环经验（SELL）：反映「买入后持有不动」基准——用配对 BUY 笔的买入价 + 买入日算 T+N，
        //    回答「如果当初买入死拿，到 T+1/T+5 赚多少」，与主动买卖 pnl 横向对比才有意义；
        //    卖出当天 T+1 尚无数据，用 BUY 基准可立即带出 d1/d5，不必等后续交易日补齐。
        //  - 开仓经验（BUY 仍持仓）：用自身买入价 + 买入日算（代表「若持有不动」基准）。
        SimTradeRow basisRow = isClosed ? entryBuy : closeOrOpen;
        Double d1;
        Double d5;
        if (basisRow != null && basisRow.getPrice() != null && basisRow.getPrice() > 0) {
            LocalDate basisDate = parseDate(basisRow.getTradeTime());
            Double c1 = closeAtOffset(closes, basisDate, 1);
            Double c5 = closeAtOffset(closes, basisDate, 5);
            d1 = c1 != null ? (c1 - basisRow.getPrice()) / basisRow.getPrice() * 100 : null;
            d5 = c5 != null ? (c5 - basisRow.getPrice()) / basisRow.getPrice() * 100 : null;
        } else {
            // 无配对买入（极端情况）：退化用自身价现场算
            d1 = closeOrOpen.getD1Ret();
            d5 = closeOrOpen.getD5Ret();
        }
        int isTrap = closeOrOpen.getIsTrap() != null ? closeOrOpen.getIsTrap() : 0;
        String lesson = lessonFor(closeOrOpen, entryBuy, isClosed);
        List<String> tags = feedbackTags(closeOrOpen, entryBuy, isClosed);
        // exp_id 用 trade_id 派生，保证幂等（重跑不产生重复经验）
        String expId = "EXP_" + tradeId;
        return new Object[]{
                expId, tradeId, tradeDate, closeOrOpen.getTsCode(),
                nvl(closeOrOpen.getStrategyId()), nvl(closeOrOpen.getStageAtEntry()),
                nvl(closeOrOpen.getCapitalConfirm()), direction, nvl(closeOrOpen.getRole()),
                "{}",                                  // entry_features_json（M2 预留，后续补决策前特征）
                1,                                      // plan_match（M1 均来自关注池）
                outcomePnl == null ? 0.0 : outcomePnl, // outcome_pnl 空置为 0 便于聚合
                d1 == null ? 0.0 : d1,
                d5 == null ? 0.0 : d5,
                isTrap, lesson, tags, 0};
    }

    private String lessonFor(SimTradeRow r, SimTradeRow entryBuy, boolean isClosed) {
        if (!isClosed) {
            return "开仓未闭环：持有观察，等待卖出信号（战法=" + nvl(r.getStrategyId()) + "，阶段=" + nvl(r.getStageAtEntry()) + "）";
        }
        double pnl = r.getPnl() != null ? r.getPnl() : 0;
        if (pnl > 0) {
            return "闭环盈利 " + Math.round(pnl) + " 元：卖出信号有效（" + nvl(r.getExitReason()) + "）";
        }
        return "闭环亏损 " + Math.round(pnl) + " 元：需复盘卖出时机（" + nvl(r.getExitReason()) + "）";
    }

    private List<String> feedbackTags(SimTradeRow r, SimTradeRow entryBuy, boolean isClosed) {
        List<String> tags = new ArrayList<>();
        tags.add(nvl(r.getStrategyId()));
        tags.add(nvl(r.getStageAtEntry()));
        tags.add(nvl(r.getCapitalConfirm()));
        if (isClosed && r.getPnl() != null) {
            tags.add(r.getPnl() > 0 ? "WIN" : "LOSS");
        }
        return tags;
    }

    private Object[] toTradeRow(SimTradeRow r, Double d1, Double d5) {
        return new Object[]{
                r.getTradeId(), r.getOrderId(), nvl(r.getPlanId()), r.getTsCode(),
                nvl(r.getStockName()), nvl(r.getBoardCode()), nvl(r.getRole()),
                nvl(r.getSide()), r.getPrice(), r.getQty(), r.getAmount(),
                r.getTradeTime(), nvl(r.getStrategyId()), nvl(r.getStageAtEntry()),
                nvl(r.getCapitalConfirm()), nvl(r.getEntryReason()), nvl(r.getExitReason()),
                nvl(r.getPlannedAction()), r.getPnl(), d1, d5, r.getIsTrap()};
    }

    /** 批量拉收盘价，按 ts_code 分组。 */
    private Map<String, List<StockClose>> loadCloses(List<String> tsCodes, LocalDate fromDate) {
        Map<String, List<StockClose>> map = new LinkedHashMap<>();
        if (tsCodes.isEmpty()) {
            return map;
        }
        List<StockClose> all = stockDailyMapper.selectCloses(tsCodes, fromDate);
        for (StockClose c : all) {
            map.computeIfAbsent(c.getTsCode(), k -> new ArrayList<>()).add(c);
        }
        return map;
    }

    /** 取 dealDate 之后第 offset 个交易日收盘价（按自然日升序近似交易日）。 */
    private Double closeAtOffset(List<StockClose> closes, LocalDate dealDate, int offset) {
        if (closes == null || closes.isEmpty()) {
            return null;
        }
        TreeMap<LocalDate, Double> sorted = new TreeMap<>();
        for (StockClose c : closes) {
            if (c.getClose() != null) {
                sorted.put(c.getTradeDate(), c.getClose().doubleValue());
            }
        }
        LocalDate cursor = dealDate;
        Double result = null;
        for (int i = 0; i < offset; i++) {
            Map.Entry<LocalDate, Double> higher = sorted.higherEntry(cursor);
            if (higher == null) {
                return null;
            }
            cursor = higher.getKey();
            result = higher.getValue();
        }
        return result;
    }

    private static LocalDate parseDate(String tradeTime) {
        if (tradeTime == null || tradeTime.length() < 10) {
            return LocalDate.now();
        }
        return LocalDate.parse(tradeTime.substring(0, 10));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 单步容错（瞬态重试），失败仅记录不中断。 */
    private void safeStep(String name, Runnable step) {
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                step.run();
                return;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean transientErr = msg.contains("Connection is closed") || msg.contains("Connection reset")
                        || msg.contains("timeout") || msg.contains("failed to respond")
                        || msg.contains("target server failed to respond");
                if (!transientErr || attempt == 4) {
                    log.error("[结算] 步骤 [{}] 失败（已跳过）: {}", name, e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
