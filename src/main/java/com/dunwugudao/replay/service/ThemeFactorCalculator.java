package com.dunwugudao.replay.service;

import com.dunwugudao.replay.config.ReplayProperties;
import com.dunwugudao.replay.entity.Concept;
import com.dunwugudao.replay.entity.ThemeFactorDaily;
import com.dunwugudao.replay.entity.ck.raw.BoardDaily;
import com.dunwugudao.replay.entity.ck.raw.LimitUpPool;
import com.dunwugudao.replay.entity.ck.raw.StockBoardRel;
import com.dunwugudao.replay.mapper.ck.BoardDailyMapper;
import com.dunwugudao.replay.mapper.ck.ConceptMapper;
import com.dunwugudao.replay.mapper.ck.LimitUpPoolMapper;
import com.dunwugudao.replay.mapper.ck.StockBoardRelMapper;
import com.dunwugudao.replay.mapper.ck.ThemeFactorDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S7 炒作思维 · 题材炒作因子计算（顿悟股道·炒作篇）。
 *
 * <p>对交易日 T 的每个 REAL_THEME 板块（且有当日板块日线），算六因子：
 * <ul>
 *   <li><b>稀缺性 scarcity</b>（0~1，静态）：成分股越少越稀缺，取自 concept 表。</li>
 *   <li><b>想象空间 imagination</b>（0~1，静态）：题材天花板高低，取自 concept 表。</li>
 *   <li><b>突发性 sudden</b>（0~1）：当日涨停集体启动强度（越多越突发）。</li>
 *   <li><b>确定性 certainty</b>（0~1）：题材逻辑当日被市场验证（涨停+涨幅正向）。</li>
 *   <li><b>最小阻力方向 min_resist</b>（0~1）：涨+资金净流入+达主线阈值+突发 的四维共振。</li>
 *   <li><b>综合分 total</b>（0~100）：五因子加权归一。</li>
 * </ul>
 *
 * <p>关键口径：<b>board_daily.limit_up_count 对概念板块恒为 NULL</b>（东财日线不携带该字段），
 * 故"当日涨停家数"与 S4 同源——从 limit_up_pool 经 stock_board_rel(board_type=3) 反查真实 BK 板块再计数。
 * 因子口径说明：数据仅单日（历史区间尚未回补）时，sudden 用"当日涨停绝对量"近似"爆发度"；
 * 待历史日线补齐，可升级为"较前一交易日涨停骤增"的 delta 口径，更准确刻画"突发"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeFactorCalculator {

    private final ReplayProperties props;
    private final ConceptMapper conceptMapper;
    private final BoardDailyMapper boardDailyMapper;
    private final LimitUpPoolMapper limitUpPoolMapper;
    private final StockBoardRelMapper stockBoardRelMapper;
    private final ThemeFactorDailyMapper themeFactorDailyMapper;

    /** 计算某交易日全部题材因子并落库，返回写入行数。 */
    public int compute(LocalDate tradeDate) {
        List<Concept> concepts = conceptMapper.selectRealThemes();
        if (concepts.isEmpty()) {
            log.warn("[S7] concept 表无 REAL_THEME，题材派生可能未跑，跳过因子计算");
            return 0;
        }
        Set<String> realCodes = concepts.stream().map(Concept::getThemeCode).collect(Collectors.toSet());

        // 板块日线（涨幅 / 资金流；limit_up_count 对概念板为 NULL，不取）
        List<BoardDaily> boards = boardDailyMapper.selectByBoardCodesAndDate(new ArrayList<>(realCodes), tradeDate);
        Map<String, BoardDaily> bm = boards.stream()
                .collect(Collectors.toMap(BoardDaily::getBoardCode, b -> b, (a, b) -> a));

        // 当日各 REAL_THEME 板块涨停家数（与 S4 同源）
        Map<String, Long> luByBoard = limitUpCountByBoard(tradeDate, realCodes);

        ReplayProperties.Theme t = props.getTheme();
        double wSum = t.getWScarcity() + t.getWImagination() + t.getWSudden()
                + t.getWCertainty() + t.getWMinResist();

        List<ThemeFactorDaily> out = new ArrayList<>();
        for (Concept c : concepts) {
            BoardDaily bd = bm.get(c.getThemeCode());
            if (bd == null) {
                continue; // 当日无板块日线（未交易的冷门概念），跳过
            }
            double sc = c.getScarcity() != null ? c.getScarcity().doubleValue() : 0.5;
            double im = c.getImagination() != null ? c.getImagination().doubleValue() : 0.5;
            int lu = luByBoard.getOrDefault(c.getThemeCode(), 0L).intValue();
            double pct = bd.getPctChg() != null ? bd.getPctChg().doubleValue() : 0.0;
            double net = bd.getMainNet() != null ? bd.getMainNet().doubleValue() : 0.0;

            // 突发性：当日涨停集体启动（越多越突发），基线 0.15
            double sudden = clamp(0.15 + Math.min(lu / 8.0, 1.0) * 0.85, 0, 1);
            // 确定性：题材逻辑当日被市场验证（涨停家数 + 涨幅正向）
            double certainty = clamp(0.20
                    + Math.min(lu / 10.0, 1.0) * 0.5
                    + (pct > 0 ? Math.min(pct / 5.0, 1.0) * 0.3 : 0), 0, 1);
            // 最小阻力方向（势）：涨+资金流入+达主线阈值+突发 共振
            double minResist = clamp((pct > 0 ? 0.30 : 0)
                    + (net > 0 ? 0.30 : 0)
                    + (lu >= props.getMainline().getMinLimitUp() ? 0.20 : 0)
                    + (sudden > 0.50 ? 0.20 : 0), 0, 1);

            double raw = t.getWScarcity() * sc + t.getWImagination() * im
                    + t.getWSudden() * sudden + t.getWCertainty() * certainty
                    + t.getWMinResist() * minResist;
            double total = clamp(raw / wSum * 100.0, 0, 100);

            ThemeFactorDaily f = new ThemeFactorDaily();
            f.setTradeDate(tradeDate);
            f.setBoardCode(c.getThemeCode());
            f.setScarcity(scale(sc));
            f.setImagination(scale(im));
            f.setSudden(scale(sudden));
            f.setCertainty(scale(certainty));
            f.setMinResist(scale(minResist));
            f.setTotal(scale(total));
            out.add(f);
        }

        if (!out.isEmpty()) {
            themeFactorDailyMapper.insertBatch(out);
        }
        long withLu = out.stream().filter(f -> luByBoard.getOrDefault(f.getBoardCode(), 0L) > 0).count();
        log.info("[S7] 题材炒作因子计算 {} 个题材（交易日 {}，其中当日有涨停 {} 个）",
                out.size(), tradeDate, withLu);
        return out.size();
    }

    /** 当日各 REAL_THEME 板块涨停家数：limit_up_pool(去后缀) → stock_board_rel(board_type) → 计数。 */
    private Map<String, Long> limitUpCountByBoard(LocalDate tradeDate, Set<String> realCodes) {
        List<LimitUpPool> ups = limitUpPoolMapper.selectByTradeDate(tradeDate);
        if (ups == null || ups.isEmpty()) {
            return Map.of();
        }
        List<String> stripped = ups.stream()
                .map(u -> strip(u.getTsCode()))
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        List<StockBoardRel> rels = stockBoardRelMapper
                .selectByTsCodesAndBoardType(stripped, props.getMainline().getBoardType());
        Map<String, Long> cnt = new LinkedHashMap<>();
        for (StockBoardRel r : rels) {
            if (!realCodes.contains(r.getBoardCode())) {
                continue; // 只认 REAL_THEME 板块
            }
            cnt.put(r.getBoardCode(), cnt.getOrDefault(r.getBoardCode(), 0L) + 1);
        }
        return cnt;
    }

    private static String strip(String tsCode) {
        if (tsCode == null) {
            return null;
        }
        int i = tsCode.indexOf('.');
        return i < 0 ? tsCode : tsCode.substring(0, i);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
