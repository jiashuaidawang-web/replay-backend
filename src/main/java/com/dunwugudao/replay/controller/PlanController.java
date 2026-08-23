package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.plan.PlanItem;
import com.dunwugudao.replay.plan.PlanPoolService;
import com.dunwugudao.replay.trader.CapitalRole;
import com.dunwugudao.replay.trader.DunwuStrategies;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 盘前关注池（T-1 复盘产出 → T 日狙击圈）。
 *
 * <p>用法：收盘后由复盘系统/人工研判把「明日关注方向+股票」灌进来（POST），
 * 次日盘中交易元只对池内标的做实时决策。
 */
@RestController
@RequestMapping("/api/v1/plan")
@RequiredArgsConstructor
public class PlanController {

    private final PlanPoolService planPool;

    /** 当前关注池。 */
    @GetMapping("/pool")
    public ResponseEntity<List<PlanItem>> pool() {
        return ResponseEntity.ok(planPool.list());
    }

    /** 灌入/覆盖一批计划条目（支持单条或数组）。同时落 CK plan_pool。 */
    @PostMapping("/pool")
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody List<PlanItem> items) {
        if (items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PARAM",
                    "message", "请求体需为非空数组"));
        }
        planPool.upsert(items);
        return ResponseEntity.ok(Map.of("size", planPool.list().size()));
    }

    /** 移除单条。 */
    @DeleteMapping("/pool/{tsCode}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String tsCode) {
        boolean ok = planPool.remove(tsCode);
        return ResponseEntity.ok(Map.of("removed", ok, "size", planPool.list().size()));
    }

    /** 清空（新交易日重灌前用）。 */
    @DeleteMapping("/pool")
    public ResponseEntity<Map<String, Object>> clear() {
        planPool.clear();
        return ResponseEntity.ok(Map.of("size", 0));
    }

    /** 战法目录（注册的全部《顿悟股道》战法元数据）。 */
    @GetMapping("/strategies")
    public ResponseEntity<List<Map<String, Object>>> strategies() {
        List<Map<String, Object>> out = DunwuStrategies.all().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategyId", s.id());
            m.put("name", s.name());
            m.put("side", s.side().name());
            m.put("applicableStages", s.applicableStages());
            m.put("capitalRole", s.capitalRole() == CapitalRole.IGNORE ? "IGNORE"
                    : s.capitalRole() == CapitalRole.FILTER ? "FILTER" : "CONFIRM");
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }
}
