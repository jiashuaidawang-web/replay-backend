package com.dunwugudao.replay.service;

import com.dunwugudao.replay.vo.TradeLogVO;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S8 交易心法 · 纪律评分量化。
 *
 * <p>把《顿悟股道》交易心法（dunwu-jiaoyi-xinfa）的定性要求转成可执行的六维度打分，
 * 对单笔交易记录量化"知道→做到"的执行度。总分 = Σ(weightᵢ · dimᵢ)，范围 0~100。
 *
 * <p>六维度与书方法论单元的对应：
 * <ol>
 *   <li><b>主线思维</b>（破个股思维 v30）—— 是否锚定大势/主线/龙头，而非冷门独立个股；</li>
 *   <li><b>三因素综合</b>（v12）—— 是否覆盖 大势/主线/资金/情绪/技术/业绩 多维度，不执迷单一；</li>
 *   <li><b>应对三态</b>（v31，分析≠交易）—— 是否填了 买对/买错/未明 的处置预案；</li>
 *   <li><b>心态控制</b>—— 执行时情绪是否平静理性，还是贪婪/恐惧/上头；</li>
 *   <li><b>风控纪律</b>（v24 破5日线即走 / v33 不谈恋爱）—— 买卖是否备预案、卖出是否记录处置；</li>
 *   <li><b>禁杠杆</b>（x11 加杠杆/借钱）—— 触及杠杆/满仓即零分，属风控底线。</li>
 * </ol>
 *
 * <p>评分仅依赖用户录入的主观字段（reason/emotionTag/reaction/side），便于个人复盘自检；
 * 未来若接入行情，可进一步校验"破5日线是否真离场"等客观纪律。
 */
@Service
public class DisciplineCalculator {

    // —— 六维度权重（Σ=1.0）——
    private static final double W_MAINLINE = 0.25;      // 破个股思维（权重最高：毒性最大的误区）
    private static final double W_THREE_FACTOR = 0.20;  // 三因素综合
    private static final double W_REACTION = 0.20;      // 应对三态
    private static final double W_MINDSET = 0.15;       // 心态控制
    private static final double W_RISK = 0.10;          // 风控纪律
    private static final double W_NO_LEVERAGE = 0.10;   // 禁杠杆

    // —— 关键词词典 ——
    private static final Set<String> MAINLINE_KW = Set.of("主线", "龙头", "热点", "大势", "板块", "题材");
    private static final Set<String> STOCK_ONLY_KW = Set.of("个股", "独立", "单独", "自己看", "单独看");
    private static final Set<String> FACTOR_KW = Set.of("大势", "主线", "资金", "情绪", "技术", "业绩", "基本面", "政策");
    private static final Set<String> GOOD_MINDSET = Set.of("平静", "按计划", "纪律", "理性", "冷静");
    private static final Set<String> BAD_MINDSET = Set.of("贪婪", "恐惧", "上头", "急躁", "后悔", "冲动", "赌");
    private static final Set<String> LEVERAGE_KW = Set.of("杠杆", "借钱", "配资", "融资", "满仓", "梭哈", "加杠杆");
    private static final Set<String> VALID_REACTION = Set.of("买对", "买错", "未明", "持有", "加仓", "止损", "减仓", "观望");

    /** 单笔纪律评分结果。 */
    @Data
    public static class DisciplineScore {
        /** 总分 0~100。 */
        private final int total;
        /** 六维度分（0~100）。 */
        private final Map<String, Integer> dims;
        /** 命中的违规项（可读中文，供前端提示）。 */
        private final List<String> violations;
    }

    public DisciplineScore score(TradeLogVO vo) {
        String reason = vo.getReason() == null ? "" : vo.getReason();
        String emotion = vo.getEmotionTag() == null ? "" : vo.getEmotionTag();
        String reaction = vo.getReaction() == null ? "" : vo.getReaction();
        String side = vo.getSide() == null ? "" : vo.getSide();

        List<String> violations = new ArrayList<>();

        // 1. 主线思维（破个股思维）
        int mainline = 50;
        if (containsAny(reason, STOCK_ONLY_KW)) {
            mainline = 0;
            violations.add("个股思维：未锚定大势/主线/龙头，在错误方向努力");
        } else if (containsAny(reason, MAINLINE_KW)) {
            mainline = 100;
        }

        // 2. 三因素综合（不执迷单一）
        long factorHits = FACTOR_KW.stream().filter(reason::contains).count();
        int threeFactor = factorHits >= 3 ? 100 : factorHits == 2 ? 70 : factorHits == 1 ? 40 : 20;
        if (factorHits < 2) {
            violations.add("执迷单一因素：分析未覆盖 大势/主线/资金/情绪/技术 多维度");
        }

        // 3. 应对三态（分析≠交易）
        int reactionScore;
        if (reaction.isEmpty()) {
            reactionScore = 0;
            violations.add("分析=交易：未填三态处置（买对/买错/未明）");
        } else if (VALID_REACTION.contains(reaction)) {
            reactionScore = 100;
        } else {
            reactionScore = 40;
        }

        // 4. 心态控制
        int mindset;
        if (containsAny(emotion, BAD_MINDSET)) {
            mindset = 0;
            violations.add("心态失控：出现贪婪/恐惧/上头等标签");
        } else if (containsAny(emotion, GOOD_MINDSET)) {
            mindset = 100;
        } else if (emotion.isEmpty()) {
            mindset = 50;
            violations.add("心态未记录：建议标注执行时情绪（平静/按计划最佳）");
        } else {
            mindset = 70;
        }

        // 5. 风控纪律（破5日线即走 / 不谈恋爱）
        int risk;
        if ("sell".equals(side)) {
            if (reaction.isEmpty()) {
                risk = 40;
                violations.add("卖出未记录处置逻辑（买对/买错/未明）");
            } else {
                risk = 80;
            }
        } else { // buy
            if ("买错".equals(reaction)) {
                risk = 60;
                violations.add("买入后判买错：须明确止损动作，破5日线即走");
            } else if (reaction.isEmpty()) {
                risk = 0;
                violations.add("买入未备预案（破5日线即走 / 应对三态）");
            } else {
                risk = 80;
            }
        }

        // 6. 禁杠杆（风控底线）
        int noLev;
        if (containsAny(reason, LEVERAGE_KW)) {
            noLev = 0;
            violations.add("高危：涉及杠杆/借钱/满仓，违反风控底线");
        } else {
            noLev = 100;
        }

        double total = W_MAINLINE * mainline + W_THREE_FACTOR * threeFactor + W_REACTION * reactionScore
                + W_MINDSET * mindset + W_RISK * risk + W_NO_LEVERAGE * noLev;
        int totalInt = (int) Math.round(total);

        Map<String, Integer> dims = new LinkedHashMap<>();
        dims.put("主线思维", mainline);
        dims.put("三因素", threeFactor);
        dims.put("应对三态", reactionScore);
        dims.put("心态控制", mindset);
        dims.put("风控纪律", risk);
        dims.put("禁杠杆", noLev);

        return new DisciplineScore(totalInt, dims, violations);
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        if (text == null || text.isEmpty()) return false;
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
