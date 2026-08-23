package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.sim.SimService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模拟盘查询与重置（账户/持仓/成交流）。
 */
@RestController
@RequestMapping("/api/v1/sim")
@RequiredArgsConstructor
public class SimController {

    private final SimService sim;

    /** 账户快照（现金/持仓市值/权益/盈亏）。 */
    @GetMapping("/account")
    public ResponseEntity<Map<String, Object>> account() {
        return ResponseEntity.ok(sim.accountSnapshot());
    }

    /** 当前持仓（带实时估值）。 */
    @GetMapping("/positions")
    public ResponseEntity<List<Map<String, Object>>> positions() {
        return ResponseEntity.ok(sim.positionsWithQuote());
    }

    /** 成交流（倒序，最多 200 条）。 */
    @GetMapping("/trades")
    public ResponseEntity<List<SimService.TradeRecord>> trades() {
        return ResponseEntity.ok(sim.trades());
    }

    /** 重置账户（可选 initCash，默认沿用原值）。 */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestParam(required = false) Double initCash) {
        sim.reset(initCash == null ? 0 : initCash);
        return ResponseEntity.ok(sim.accountSnapshot());
    }
}
