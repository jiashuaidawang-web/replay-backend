package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.entity.LeaderTradeDaily;
import com.dunwugudao.replay.entity.MainlineDaily;
import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.mapper.ck.LeaderPoolDailyMapper;
import com.dunwugudao.replay.mapper.ck.MainlineDailyMapper;
import com.dunwugudao.replay.mapper.ck.SentimentDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * S5 龙头买卖 · 独立计算层。
 *
 * <p>基于《顿悟股道》第二章/第四章龙头买卖框架，量化：
 * <ul>
 *   <li>分歧日 / 一致日（从 S2 情绪温度 regime + thermal 判断）</li>
 *   <li>板块高潮日（从 S4 主线强度 topStrength 判断）</li>
 *   <li>真龙见顶切换（高位龙头 boardPos≥5 遇分歧日 → 见顶卖出信号）</li>
 *   <li>买卖信号 buy/buy_dip/hold/sell/reduce/watch + 评分 + 风险等级</li>
 * </ul>
 *
 * <p>核心原则：
 * <ol>
 *   <li>分歧日低吸龙头，一致日不追高</li>
 *   <li>板块高潮日不追板（一致性过强=风险）</li>
 *   <li>真龙见顶（高位+分歧）要卖出</li>
 *   <li>首板不买（看不出龙头相，需二板确认）</li>
 *   <li>妖股：分歧日极限低吸，一致日不追</li>
 *   <li>独狼：沿趋势低吸，独立于板块节奏</li>
 * </ol>
 *
 * <p>输入：leader_pool_daily(S4)、sentiment_daily(S2)、mainline_daily(S4)。
 * 输出：List&lt;LeaderTradeDaily&gt;，由 ReplayCalcJob 统一写入 leader_trade_daily。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderTradeCalculator {

    private final LeaderPoolDailyMapper leaderPoolDailyMapper;
    private final SentimentDailyMapper sentimentDailyMapper;
    private final MainlineDailyMapper mainlineDailyMapper;

    // ---- 市场状态阈值 ----
    private static final double THERMAL_CONSISTENT = 65.0;
    private static final double THERMAL_DIVERGENCE = 40.0;
    private static final double THERMAL_FREEZING = 25.0;
    private static final double BOARD_CLIMAX = 80.0;
    private static final int HIGH_POS = 5;

    public List<LeaderTradeDaily> compute(LocalDate tradeDate) {
        // 1. 读情绪温度 → 市场状态
        SentimentDaily sent = sentimentDailyMapper.selectByTradeDate(tradeDate);
        String marketState = classifyMarket(sent);
        double thermal = (sent != null && sent.getThermal() != null)
                ? sent.getThermal().doubleValue() : 50.0;

        // 2. 读主线强度 → 板块高潮判断
        List<MainlineDaily> mainlines = mainlineDailyMapper.selectByTradeDate(tradeDate);
        double topStrength = mainlines.stream()
                .filter(m -> m.getStrength() != null)
                .mapToDouble(m -> m.getStrength().doubleValue())
                .max().orElse(0.0);
        boolean boardClimax = topStrength >= BOARD_CLIMAX;

        // 3. 读龙头池
        List<LeaderPoolDaily> leaders = leaderPoolDailyMapper.selectByTradeDate(tradeDate);
        if (leaders.isEmpty()) {
            log.info("[S5] {} 无龙头池数据, 跳过 (marketState={}, boardClimax={})",
                    tradeDate, marketState, boardClimax);
            return List.of();
        }

        // 4. 逐只计算买卖建议
        List<LeaderTradeDaily> result = new ArrayList<>();
        for (LeaderPoolDaily l : leaders) {
            result.add(evaluate(l, marketState, thermal, boardClimax, topStrength));
        }

        log.info("[S5] {} 市场状态={}, 板块高潮={}, 龙头 {} 只, 产出买卖建议 {} 条",
                tradeDate, marketState, boardClimax, leaders.size(), result.size());
        return result;
    }

    /** 市场状态分类：一致 / 分歧 / 冰点 / 修复。 */
    private String classifyMarket(SentimentDaily sent) {
        if (sent == null) return "未知";
        String regime = sent.getRegime();
        if (regime != null) {
            if (regime.contains("高潮") || regime.contains("升温")) return "一致";
            if (regime.contains("退潮") || regime.contains("分歧")) return "分歧";
            if (regime.contains("冰点")) return "冰点";
            if (regime.contains("修复")) return "修复";
        }
        double t = (sent.getThermal() != null) ? sent.getThermal().doubleValue() : 50.0;
        if (t >= THERMAL_CONSISTENT) return "一致";
        if (t < THERMAL_FREEZING) return "冰点";
        if (t < THERMAL_DIVERGENCE) return "分歧";
        return "修复";
    }

    private LeaderTradeDaily evaluate(LeaderPoolDaily l, String marketState,
                                       double thermal, boolean boardClimax, double topStrength) {
        LeaderTradeDaily r = new LeaderTradeDaily();
        r.setTradeDate(l.getTradeDate());
        r.setTsCode(l.getTsCode());
        r.setBoardCode(l.getBoardCode());
        r.setBoardPos(l.getBoardPos());
        r.setRole(l.getRole());
        r.setCat(l.getCat());
        r.setScore(l.getScore());

        String cat = (l.getCat() != null) ? l.getCat() : "";
        int pos = (l.getBoardPos() != null) ? l.getBoardPos().intValue() : 0;
        double score = (l.getScore() != null) ? l.getScore().doubleValue() : 0.0;
        String role = (l.getRole() != null) ? l.getRole() : "";

        double buyScore = score;
        String action, signal, riskLevel, reason;

        if ("妖".equals(cat) || role.contains("妖")) {
            // ---- 妖股：纯情绪博弈，分歧日极限低吸，一致日不追 ----
            if ("冰点".equals(marketState)) {
                action = "观望"; signal = "watch"; riskLevel = "极高";
                buyScore = score * 0.3;
                reason = "冰点日妖股人气涣散，观望为主";
            } else if ("分歧".equals(marketState)) {
                action = "低吸"; signal = "buy_dip"; riskLevel = "极高";
                buyScore = score * 0.6 + 5;
                reason = "分歧日妖股极限低吸，纯情绪博弈，随时警惕天地板";
            } else {
                action = "持有"; signal = "hold"; riskLevel = "极高";
                buyScore = score * 0.4;
                reason = "一致日妖股不追高，缩量加速或爆量滞涨即离场";
            }
        } else if ("独狼".equals(cat) || role.contains("独狼")) {
            // ---- 独狼：独立走势，沿趋势低吸 ----
            if (pos >= HIGH_POS && "分歧".equals(marketState)) {
                action = "减仓"; signal = "reduce"; riskLevel = "高";
                buyScore = score * 0.5;
                reason = "高位独狼遇分歧日，减仓避险";
            } else {
                action = "低吸"; signal = "buy"; riskLevel = "中";
                buyScore = score * 0.9;
                reason = "独狼不依赖板块节奏，沿自身趋势低吸跟随";
            }
        } else {
            // ---- 龙（龙一~龙五 / 中军）----
            if ("冰点".equals(marketState)) {
                action = "观望"; signal = "watch"; riskLevel = "低";
                buyScore = score * 0.5;
                reason = "冰点日赚钱效应差，观望为主";
            } else if (pos <= 1) {
                action = "观望"; signal = "watch"; riskLevel = "中";
                buyScore = score * 0.6;
                reason = "首板看不出龙头相，需二板确认主线地位";
            } else if (pos >= HIGH_POS && "分歧".equals(marketState)) {
                // 真龙见顶信号
                action = "卖出"; signal = "sell"; riskLevel = "高";
                buyScore = score * 0.3;
                reason = String.format("高位%d连板遇分歧日，真龙见顶信号，卖出避险", pos);
            } else if (boardClimax && ("一致".equals(marketState) || "修复".equals(marketState))) {
                // 板块高潮日不追板
                action = "持有"; signal = "hold"; riskLevel = "高";
                buyScore = score * 0.7;
                reason = "板块高潮日一致性过强，追板风险大，持有者可持新仓不追";
            } else if ("一致".equals(marketState) && pos >= 2 && pos < HIGH_POS) {
                // 一致日低吸/打板
                action = "买入"; signal = "buy"; riskLevel = "中";
                buyScore = Math.min(100, score + 10);
                reason = String.format("一致日龙头确立，板块强度延续，%d连板可低吸或打板", pos);
            } else if ("分歧".equals(marketState) && pos >= 2 && pos < HIGH_POS) {
                // 分歧日低吸
                action = "低吸"; signal = "buy_dip"; riskLevel = "中";
                buyScore = score + 5;
                reason = "分歧日龙头回踩，板块未退潮，低吸博一致日修复";
            } else if ("修复".equals(marketState) && pos >= 2) {
                // 修复日试错
                action = "低吸"; signal = "buy_dip"; riskLevel = "中";
                buyScore = score * 0.8 + 5;
                reason = "修复日情绪回升，龙头可小仓试错低吸";
            } else if (role.contains("中军")) {
                action = "低吸"; signal = "buy"; riskLevel = "低";
                buyScore = score * 0.85;
                reason = "中军稳军心，低吸跟随适合波段";
            } else {
                action = "观望"; signal = "watch"; riskLevel = "中";
                buyScore = score * 0.6;
                reason = "龙头位置与市场状态未达明确买点，观望";
            }
        }

        r.setAction(action);
        r.setSignal(signal);
        r.setRiskLevel(riskLevel);
        r.setBuyScore(BigDecimal.valueOf(buyScore).setScale(4, RoundingMode.HALF_UP));
        r.setReason(reason);
        r.setNote(String.format("市场=%s,温度=%.1f,板块高潮=%s,主线强度=%.1f",
                marketState, thermal, boardClimax, topStrength));

        return r;
    }
}
