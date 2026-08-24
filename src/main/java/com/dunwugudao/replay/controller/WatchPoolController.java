package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.mapper.ck.CommonMapper;
import com.dunwugudao.replay.mapper.ck.DecisionLogCkMapper;
import com.dunwugudao.replay.mapper.ck.DecisionLogRow;
import com.dunwugudao.replay.mapper.ck.SimTradeCkMapper;
import com.dunwugudao.replay.mapper.ck.SimTradeRow;
import com.dunwugudao.replay.mapper.ck.WatchPoolMapper;
import com.dunwugudao.replay.mapper.ck.WatchPoolRow;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 次日可溯源观察池（watch_pool）查询 + 全链路事件线。
 *
 * <p>一条事件线 = 选股溯源（S4/S5/S7 因何入选）→ T+1 模拟买卖（sim_trade，带战法/阶段/资金三口径）
 *  → 决策流（decision_log，含未执行的 WATCH/HOLD）。用于回看「顿悟股道战法 + L2 资金流」
 *  对当前市场是否有效。
 */
@RestController
@RequestMapping("/api/v1/watch-pool")
@RequiredArgsConstructor
public class WatchPoolController {

    private final WatchPoolMapper watchMapper;
    private final SimTradeCkMapper simTradeMapper;
    private final DecisionLogCkMapper decisionMapper;
    private final CommonMapper commonMapper;

    /**
     * 指定选股日的观察池 + 每只股关联的模拟成交 / 决策流（事件线）。
     * date 不传 → 自动取 watch_pool 最新 sel_date（无数据则返回 204 由前端渲染空态）。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = commonMapper.latestTradeDate("watch_pool");
        }
        if (date == null) {
            return ResponseEntity.noContent().build();
        }
        List<WatchPoolRow> rows = watchMapper.selectBySelDate(date);
        if (rows.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        // 批量查 sim_trade + decision_log（避免 N+1）
        List<String> codes = rows.stream().map(WatchPoolRow::getTsCode).distinct().toList();
        List<SimTradeRow> allTrades = simTradeMapper.selectByTsCodes(codes);
        List<DecisionLogRow> allDecisions = decisionMapper.selectByTsCodes(codes);

        // 按 ts_code 分组
        Map<String, List<SimTradeRow>> tradeMap = allTrades.stream()
                .collect(Collectors.groupingBy(SimTradeRow::getTsCode, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<DecisionLogRow>> decisionMap = allDecisions.stream()
                .collect(Collectors.groupingBy(DecisionLogRow::getTsCode, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (WatchPoolRow r : rows) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("tsCode", r.getTsCode());
            it.put("stockName", r.getStockName());
            it.put("sourceSkill", r.getSourceSkill());
            it.put("reason", r.getReason());
            it.put("boardCode", r.getBoardCode());
            it.put("role", r.getRole());
            it.put("selectedAction", r.getSelectedAction());
            it.put("syncedRedis", r.getSyncedRedis());
            it.put("buySignal", r.getBuySignal());
            it.put("buyReason", r.getBuyReason());
            it.put("buyPrice", r.getBuyPrice());
            it.put("sellReason", r.getSellReason());
            it.put("sellPrice", r.getSellPrice());
            it.put("pnlPct", r.getPnlPct());
            it.put("outcome", r.getOutcome());

            // 关联模拟成交
            List<SimTradeRow> trades = tradeMap.getOrDefault(r.getTsCode(), Collections.emptyList());
            List<Map<String, Object>> tradeList = new ArrayList<>(trades.size());
            for (SimTradeRow t : trades) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("side", t.getSide());
                m.put("price", t.getPrice());
                m.put("qty", t.getQty());
                m.put("amount", t.getAmount());
                m.put("tradeTime", t.getTradeTime());
                m.put("strategyId", t.getStrategyId());
                m.put("stage", t.getStageAtEntry());
                m.put("capitalConfirm", t.getCapitalConfirm());
                m.put("pnl", t.getPnl());
                m.put("d1Ret", t.getD1Ret());
                m.put("d5Ret", t.getD5Ret());
                m.put("reason", t.getSide().equals("BUY") ? t.getEntryReason() : t.getExitReason());
                tradeList.add(m);
            }
            it.put("trades", tradeList);

            // 关联决策流
            List<DecisionLogRow> decisions = decisionMap.getOrDefault(r.getTsCode(), Collections.emptyList());
            List<Map<String, Object>> decisionList = new ArrayList<>(decisions.size());
            for (DecisionLogRow d : decisions) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ts", d.getTs());
                m.put("action", d.getAction());
                m.put("score", d.getScore());
                m.put("strategyId", d.getStrategyId());
                m.put("stage", d.getStage());
                m.put("capitalSignal", d.getCapitalSignal());
                m.put("executed", d.getExecuted());
                m.put("reason", d.getReason());
                m.put("referencePrice", d.getReferencePrice());
                decisionList.add(m);
            }
            it.put("decisions", decisionList);

            items.add(it);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selDate", date);
        out.put("count", items.size());
        out.put("items", items);
        return ResponseEntity.ok(out);
    }
}
