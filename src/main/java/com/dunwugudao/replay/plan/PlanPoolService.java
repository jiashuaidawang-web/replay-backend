package com.dunwugudao.replay.plan;

import com.dunwugudao.replay.realtime.CkBufferedWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盘前关注池（T 日狙击圈）。
 *
 * <p>M1 内存态 + 落 CK {@code plan_pool}（RMT，幂等可重算）；重启后可通过 REST 重灌
 * （M1 不做 CK 回读，避免读写路径耦合）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanPoolService {

    private static final List<String> PLAN_COLUMNS = List.of(
            "plan_date", "ts_code", "stock_name", "direction", "board_code", "role",
            "candidate_strategies", "planned_action", "trigger_price",
            "planned_position_pct", "note");

    private final CkBufferedWriter ckBuf;

    private final Map<String, PlanItem> pool = new ConcurrentHashMap<>();

    public List<PlanItem> list() {
        List<PlanItem> all = new ArrayList<>(pool.values());
        all.sort(Comparator.comparing(PlanItem::getTsCode));
        return all;
    }

    public PlanItem get(String tsCode) {
        return pool.get(tsCode);
    }

    public boolean contains(String tsCode) {
        return pool.containsKey(tsCode);
    }

    /** 新增/覆盖一批计划条目（同时落 CK）。 */
    public void upsert(List<PlanItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(items.size());
        for (PlanItem it : items) {
            if (it.getTsCode() == null || it.getTsCode().isBlank()) {
                continue;
            }
            if (it.getPlanDate() == null) {
                it.setPlanDate(LocalDate.now());
            }
            pool.put(it.getTsCode(), it);
            rows.add(new Object[]{it.getPlanDate(), it.getTsCode(),
                    nvl(it.getStockName()), nvl(it.getDirection()), nvl(it.getBoardCode()), nvl(it.getRole()),
                    it.getCandidateStrategies() == null ? List.of() : it.getCandidateStrategies(),
                    nvl(it.getPlannedAction()), it.getTriggerPrice(), it.getPlannedPositionPct(), nvl(it.getNote())});
        }
        ckBuf.add("plan_pool", PLAN_COLUMNS, rows);
        log.info("[plan] 关注池更新：当前 {} 条", pool.size());
    }

    public boolean remove(String tsCode) {
        PlanItem removed = pool.remove(tsCode);
        return removed != null;
    }

    public void clear() {
        pool.clear();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
