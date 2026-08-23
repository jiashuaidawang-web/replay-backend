package com.dunwugudao.replay.plan;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.entity.LeaderTradeDaily;
import com.dunwugudao.replay.entity.ThemeFactorDaily;
import com.dunwugudao.replay.mapper.ck.CommonMapper;
import com.dunwugudao.replay.plan.PlanItem;
import com.dunwugudao.replay.plan.PlanPoolService;
import com.dunwugudao.replay.mapper.ck.LeaderPoolDailyMapper;
import com.dunwugudao.replay.mapper.ck.LeaderTradeDailyMapper;
import com.dunwugudao.replay.mapper.ck.ThemeFactorDailyMapper;
import com.dunwugudao.replay.mapper.ck.WatchPoolMapper;
import com.dunwugudao.replay.service.CkHttpWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 次日可溯源观察池（watch_pool）流水线——T 日复盘选股 → 同步 Redis pool → T+1 事件线。
 *
 * <p><b>全链路可溯源头</b>：每只入选股票都带 {@code sourceSkill} 标签（S4 龙头 / S5 龙头买卖 / S7 题材）
 * 与 {@code reason}（因何战法、何种条件入选）。多战法命中同一股则标签逗号拼接、理由聚合。
 *
 * <p><b>同步 Redis</b>：生成后清空 {@code ths:l2:pool} 旧测试股，SADD 真实观察标的，并
 * PUBLISH {@code ths:l2:pool:change}（action=add）通知消费端动态加载，避免重启。
 *
 * <p><b>T+1 事件线</b>：watch_pool 行预留 buy_ / sell_ / pnl_ / outcome 列，由盘后归因 job 回填同一行。
 */
@Slf4j
@Service
public class WatchPoolService {

    private static final String POOL_KEY = "ths:l2:pool";
    private static final String POOL_CHANGE_CHANNEL = "ths:l2:pool:change";

    /** watch_pool CK 表列（与 ck_ddl_intraday.sql 1.5 节一致）。 */
    private static final List<String> WATCH_COLUMNS = List.of(
            "sel_date", "ts_code", "stock_name", "source_skill", "reason",
            "board_code", "role", "selected_action", "synced_redis");

    private final LeaderPoolDailyMapper s4Mapper;
    private final LeaderTradeDailyMapper s5Mapper;
    private final ThemeFactorDailyMapper s7Mapper;
    private final WatchPoolMapper watchMapper;
    private final CommonMapper commonMapper;
    private final CkHttpWriter ck;
    private final StringRedisTemplate redis;
    private final PlanPoolService planPool;

    /** S7 题材入选阈值（total 分 0~100）。低于此分的题材不展开成个股。 */
    private final double s7MinTotal;

    public WatchPoolService(LeaderPoolDailyMapper s4Mapper,
                            LeaderTradeDailyMapper s5Mapper,
                            ThemeFactorDailyMapper s7Mapper,
                            WatchPoolMapper watchMapper,
                            CommonMapper commonMapper,
                            CkHttpWriter ck,
                            StringRedisTemplate redis,
                            PlanPoolService planPool,
                            @Value("${replay.watch-pool.s7-min-total:60}") double s7MinTotal) {
        this.s4Mapper = s4Mapper;
        this.s5Mapper = s5Mapper;
        this.s7Mapper = s7Mapper;
        this.watchMapper = watchMapper;
        this.commonMapper = commonMapper;
        this.ck = ck;
        this.redis = redis;
        this.planPool = planPool;
        this.s7MinTotal = s7MinTotal;
    }

    /** 合并后的单股入选记录（内存态，用于写 CK + 同步 Redis）。 */
    public static class WatchItem {
        String tsCode;
        String stockName = "";
        Set<String> skills = new TreeSet<>();
        List<String> reasons = new ArrayList<>();
        String boardCode = "";
        String role = "";
        String selectedAction = "";

        void add(String skill, String reason) {
            skills.add(skill);
            reasons.add(skill + ": " + reason);
        }
    }

    /**
     * 生成次日观察池并同步 Redis。
     *
     * @param date 选股日（T 日）；null → 自动回退 leader_pool_daily 最新交易日。
     * @return 入选的股票 code 列表（已同步进 Redis pool）。
     */
    public List<String> buildAndSync(LocalDate date) {
        if (date == null) {
            date = commonMapper.latestTradeDate("leader_pool_daily");
        }
        if (date == null) {
            log.warn("[watch-pool] 无法解析选股日（leader_pool_daily 无数据），中止");
            return List.of();
        }
        log.info("[watch-pool] 选股日={}，开始从 S4/S5/S7 捞取命中股", date);

        Map<String, WatchItem> merged = new LinkedHashMap<>();

        // ---- S4 龙头梯队 ----
        for (LeaderPoolDaily s4 : s4Mapper.selectByTradeDate(date)) {
            if (s4.getTsCode() == null || s4.getTsCode().isBlank()) {
                continue;
            }
            WatchItem it = merged.computeIfAbsent(s4.getTsCode(), k -> new WatchItem());
            it.tsCode = s4.getTsCode();
            if (s4.getBoardCode() != null) {
                it.boardCode = s4.getBoardCode();
            }
            if (s4.getRole() != null) {
                it.role = s4.getRole();
            }
            String reason = String.format("龙头梯队·%s%s",
                    s4.getRole() == null ? "" : s4.getRole(),
                    s4.getNote() == null ? "" : "·" + s4.getNote());
            it.add("S4", reason);
        }

        // ---- S5 龙头买卖 ----
        for (LeaderTradeDaily s5 : s5Mapper.selectByTradeDate(date)) {
            if (s5.getTsCode() == null || s5.getTsCode().isBlank()) {
                continue;
            }
            WatchItem it = merged.computeIfAbsent(s5.getTsCode(), k -> new WatchItem());
            it.tsCode = s5.getTsCode();
            if (s5.getBoardCode() != null) {
                it.boardCode = s5.getBoardCode();
            }
            if (s5.getRole() != null && it.role.isBlank()) {
                it.role = s5.getRole();
            }
            if (s5.getSignal() != null && !s5.getSignal().isBlank()) {
                it.selectedAction = s5.getSignal();
            }
            String reason = String.format("战法买卖·%s·%s",
                    s5.getSignal() == null ? "" : s5.getSignal(),
                    s5.getReason() == null ? "" : s5.getReason());
            it.add("S5", reason);
        }

        // ---- S7 强题材 → 题材共振增强（不新增股票）----
        // 收口决策（2026-08-23 用户拍板）：S7 强题材若展开全部成分股会爆炸（实测 08-21 达 3262 支，
        // 狙击圈/L2 消费端均不可承受）。改为：只给【已被 S4/S5 选中、且属于某强题材】的股票追加
        // S7 共振标签与理由——即「题材内龙头」自然保留，纯题材成分股不进池。池规模 = S4∪S5。
        Map<String, WatchItem> byBareCode = new LinkedHashMap<>();
        for (WatchItem it : merged.values()) {
            byBareCode.put(stripSuffix(it.tsCode), it);
        }
        for (ThemeFactorDaily s7 : s7Mapper.selectByTradeDate(date)) {
            if (s7.getBoardCode() == null) {
                continue;
            }
            double total = s7.getTotal() == null ? 0 : s7.getTotal().doubleValue();
            if (total < s7MinTotal) {
                continue;
            }
            List<String> members = watchMapper.selectBoardMembers(s7.getBoardCode());
            String themeReason = String.format("题材六因子强(total=%.0f)·题材共振", total);
            for (String ts : members) {
                if (ts == null || ts.isBlank()) {
                    continue;
                }
                WatchItem it = byBareCode.get(stripSuffix(ts));
                if (it != null) {
                    it.add("S7", themeReason);
                }
            }
        }

        if (merged.isEmpty()) {
            log.warn("[watch-pool] 选股日={} 三表均无命中，pool 将为空", date);
        }

        // ---- 补全股票名（去后缀批量查 stock_daily FINAL，查不到留空）----
        try {
            List<String> bareCodes = new ArrayList<>(merged.size());
            for (WatchItem it : merged.values()) {
                bareCodes.add(stripSuffix(it.tsCode));
            }
            Map<String, String> names = new java.util.HashMap<>();
            for (com.dunwugudao.replay.mapper.ck.StockNameRow r : watchMapper.selectStockNames(bareCodes)) {
                if (r.getTsCode() != null && r.getStockName() != null) {
                    names.put(r.getTsCode(), r.getStockName());
                }
            }
            for (WatchItem it : merged.values()) {
                String nm = names.get(stripSuffix(it.tsCode));
                if (nm != null && !nm.isBlank()) {
                    it.stockName = nm;
                }
            }
        } catch (Exception e) {
            log.warn("[watch-pool] 批量补股票名失败（不影响主流程）：{}", e.getMessage());
        }

        // ---- 写 CK watch_pool ----
        List<Object[]> rows = new ArrayList<>();
        for (WatchItem it : merged.values()) {
            rows.add(new Object[]{
                    date,
                    it.tsCode,
                    it.stockName,
                    String.join(",", it.skills),
                    String.join(" | ", it.reasons),
                    it.boardCode,
                    it.role,
                    it.selectedAction,
                    1   // synced_redis
            });
        }
        if (!rows.isEmpty()) {
            ck.insert("watch_pool", WATCH_COLUMNS, rows);
        }
        log.info("[watch-pool] 选股日={} 共 {} 支入选，已写 CK watch_pool", date, merged.size());

        // ---- 灌盘前狙击圈（PlanPoolService）----
        // 用户决策：战法全开放（candidateStrategies=null）。这样 TraderEngine 会对 watch_pool 内
        // 标的用全部 11 条战法评估并自动模拟交易，实现「复盘选股 → T+1 自动验证战法有效性」闭环。
        List<PlanItem> plans = new ArrayList<>();
        for (WatchItem it : merged.values()) {
            plans.add(PlanItem.builder()
                    .planDate(date)
                    .tsCode(it.tsCode)
                    .stockName(it.stockName)
                    .direction(it.boardCode)
                    .boardCode(it.boardCode)
                    .role(it.role.isBlank() ? null : it.role)
                    .candidateStrategies(null) // 全开放
                    .plannedAction(it.selectedAction.isBlank() ? null : it.selectedAction)
                    .plannedPositionPct(0.10)  // 单标默认 10% 仓位
                    .note("watch_pool 溯源：" + String.join(" | ", it.reasons))
                    .build());
        }
        if (!plans.isEmpty()) {
            planPool.upsert(plans);
            log.info("[watch-pool] 已灌狙击圈 {} 支（战法全开放），TraderEngine 将自动评估",
                    plans.size());
        }

        // ---- 同步 Redis ths:l2:pool ----
        syncRedis(merged.keySet());
        return new ArrayList<>(merged.keySet());
    }

    /** ts_code 去后缀（.SH/.SZ/.BJ），统一用于 stock_board_rel / stock_daily 关联。 */
    private static String stripSuffix(String code) {
        if (code == null) {
            return "";
        }
        int dot = code.lastIndexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }

    /** 清空旧 pool → SADD 真实 code → PUBLISH pool:change(add) 通知消费端。 */
    private void syncRedis(Set<String> codes) {
        try {
            redis.delete(POOL_KEY);
            if (!codes.isEmpty()) {
                redis.opsForSet().add(POOL_KEY, codes.toArray(new String[0]));
            }
            // 通知消费端动态加载（每条一个 add 事件，幂等）
            for (String code : codes) {
                redis.convertAndSend(POOL_CHANGE_CHANNEL,
                        String.format("{\"action\":\"add\",\"code\":\"%s\"}", code));
            }
            log.info("[watch-pool] Redis {} 已清空并重填 {} 支真实观察标的", POOL_KEY, codes.size());
        } catch (Exception e) {
            log.error("[watch-pool] 同步 Redis pool 失败：{}", e.getMessage());
        }
    }
}
