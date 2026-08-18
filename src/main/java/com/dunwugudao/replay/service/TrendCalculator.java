package com.dunwugudao.replay.service;

import com.dunwugudao.replay.config.ReplayProperties;
import com.dunwugudao.replay.entity.TrendCandidateDaily;
import com.dunwugudao.replay.entity.ck.raw.StockWeekly;
import com.dunwugudao.replay.mapper.ck.StockWeeklyMapper;
import com.dunwugudao.replay.mapper.ck.TrendCandidateDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S6 趋势战法 · 八大技术特征量化（基于《顿悟股道》第三章）。
 *
 * <p>依据 stock_weekly（周线）对全市场个股做八维交叉验证：
 * ①长期均线(10/30周)站稳多头发散 ②漂亮图形(底部抬高) ③健康量价(涨放跌缩)
 * ④小盘子(市值50亿以下，缺市值数据→恒 N/A) ⑤RS强于指数(52周高位 proximity 代理，无 index_weekly)
 * ⑥RSI突破70 ⑦周线确认 ⑧底部历史平台突破。
 *
 * <p>趋势确认 = ① + ② + 自大底最低点(52周低)涨幅 > 25%。
 * 结果写 trend_candidate_daily（ReplacingMergeTree，按 (trade_date, ts_code) 幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendCalculator {

    private final StockWeeklyMapper stockWeeklyMapper;
    private final TrendCandidateDailyMapper trendCandidateDailyMapper;
    private final ReplayProperties props;

    public void compute(LocalDate tradeDate) {
        LocalDate from = tradeDate.minusDays(420); // ~60 周窗口
        List<StockWeekly> bars = stockWeeklyMapper.selectBarsBetween(from, tradeDate);
        if (bars.isEmpty()) {
            log.warn("[S6] stock_weekly 无 {} 附近数据，跳过趋势计算", tradeDate);
            return;
        }
        Map<String, List<StockWeekly>> byCode = bars.stream()
                .collect(Collectors.groupingBy(StockWeekly::getTsCode, LinkedHashMap::new, Collectors.toList()));

        ReplayProperties.Trend t = props.getTrend();
        List<TrendCandidateDaily> out = new ArrayList<>();
        int confirmed = 0;
        for (Map.Entry<String, List<StockWeekly>> e : byCode.entrySet()) {
            List<StockWeekly> series = e.getValue().stream()
                    .sorted(Comparator.comparing(StockWeekly::getTradeDate))
                    .collect(Collectors.toList());
            // 取每只个股「计算日之前最近一根」周线棒作为评估基准；过旧（>14 天）跳过。
            StockWeekly asOf = series.get(series.size() - 1);
            if (asOf.getTradeDate().isBefore(tradeDate.minusDays(14))) {
                continue;
            }
            TrendCandidateDaily c = eval(series, asOf, tradeDate, t);
            if (c.getConfirmed() == 1) confirmed++;
            out.add(c);
        }

        trendCandidateDailyMapper.insertBatch(out);
        log.info("[S6] 趋势候选写入 {} 只 (其中趋势成立 {} 只)", out.size(), confirmed);
    }

    private TrendCandidateDaily eval(List<StockWeekly> series, StockWeekly asOf, LocalDate date, ReplayProperties.Trend t) {
        int n = series.size();
        // 取最近 min(n, 60) 根用于窗口特征
        List<StockWeekly> w = series.subList(Math.max(0, n - 60), n);
        List<BigDecimal> closes = w.stream().map(StockWeekly::getClose).filter(x -> x != null).collect(Collectors.toList());
        List<BigDecimal> highs = w.stream().map(StockWeekly::getHigh).filter(x -> x != null).collect(Collectors.toList());
        List<BigDecimal> lows = w.stream().map(StockWeekly::getLow).filter(x -> x != null).collect(Collectors.toList());
        List<BigDecimal> vols = w.stream().map(StockWeekly::getVol).filter(x -> x != null).collect(Collectors.toList());

        TrendCandidateDaily c = new TrendCandidateDaily();
        c.setTradeDate(date);
        c.setTsCode(asOf.getTsCode());
        c.setStockName(asOf.getStockName());
        c.setClosePrice(asOf.getClose());
        c.setFSmallcap(0); // ④ 缺市值数据，恒 N/A

        if (closes.isEmpty()) {
            fillZero(c);
            return c;
        }

        BigDecimal close = closes.get(closes.size() - 1);
        BigDecimal ma10 = avg(closes, 10);
        BigDecimal ma30 = avg(closes, 30);
        c.setMa10(ma10);
        c.setMa30(ma30);

        // 52 周高低点（用窗口高低）
        BigDecimal hi52 = highs.stream().max(BigDecimal::compareTo).orElse(close);
        BigDecimal lo52 = lows.stream().min(BigDecimal::compareTo).orElse(close);
        BigDecimal gainFromBottom = lo52.signum() != 0
                ? close.subtract(lo52).divide(lo52, 4, RoundingMode.HALF_UP).multiply(BD(100))
                : BD(0);
        c.setGainFromBottom(gainFromBottom);

        // ⑤ RS 代理：收盘价在 52 周区间相对位置 0~1
        BigDecimal range = hi52.subtract(lo52);
        BigDecimal rs = range.signum() != 0
                ? close.subtract(lo52).divide(range, 4, RoundingMode.HALF_UP).max(BD(0)).min(BD(1))
                : BD(0);
        c.setRsVsIndex(rs);

        // ⑥ RSI(14) 周线
        BigDecimal rsi = rsi14(closes);
        c.setRsi(rsi);

        // ---- 逐特征判定 ----
        // ① 长期均线站稳并多头发散：close>ma10>ma30 且 close>ma30（站上牛熊线）
        int fMa = (n >= 30 && ma10 != null && ma30 != null
                && close.compareTo(ma10) > 0 && ma10.compareTo(ma30) > 0) ? 1 : 0;

        // ② 漂亮图形：MA30 近 5 周上行 且 close>ma30
        int fShape = 0;
        if (closes.size() >= 30) {
            BigDecimal ma30Now = ma30;
            BigDecimal ma30Prev = avg(closes.subList(Math.max(0, closes.size() - 35), closes.size() - 5), 30);
            boolean rising = ma30Prev != null && ma30Now.compareTo(ma30Prev) > 0;
            if (rising && close.compareTo(ma30) > 0) fShape = 1;
        }

        // ③ 健康量价：近 4 周均量 / 前 4 周均量 > 阈值（突破持续放量）
        int fVol = 0;
        if (vols.size() >= 8) {
            BigDecimal recent = avg(vols.subList(vols.size() - 4, vols.size()), 4);
            BigDecimal prior = avg(vols.subList(vols.size() - 8, vols.size() - 4), 4);
            if (prior.signum() != 0 && recent.divide(prior, 4, RoundingMode.HALF_UP).compareTo(BD(t.getVolRatio())) >= 0) {
                fVol = 1;
            }
        }

        // ⑤ RS 强：相对位置 >= 阈值
        int fRs = rs.compareTo(BD(t.getRsPos())) >= 0 ? 1 : 0;

        // ⑥ RSI 突破 70
        int fRsi = (rsi != null && rsi.compareTo(BD(t.getRsiThreshold())) >= 0) ? 1 : 0;

        // ⑦ 周线确认：周线级上升（ma10>ma30 且 close>ma10）
        int fWeekly = (n >= 10 && ma10 != null && ma30 != null
                && ma10.compareTo(ma30) > 0 && close.compareTo(ma10) > 0) ? 1 : 0;

        // ⑧ 底部平台突破：close 逼近 52 周高（空间打开）
        int fBreak = (hi52.compareTo(lo52.multiply(BD(1.05))) > 0
                && close.compareTo(hi52.multiply(BD(0.95))) >= 0) ? 1 : 0;

        c.setFMa(fMa);
        c.setFShape(fShape);
        c.setFVol(fVol);
        c.setFRs(fRs);
        c.setFRsi(fRsi);
        c.setFWeekly(fWeekly);
        c.setFBreak(fBreak);

        // 趋势确认：① + ② + 自大底涨幅 > 25%
        int isConfirmed = (fMa == 1 && fShape == 1 && gainFromBottom.compareTo(BD(t.getGainThreshold())) > 0) ? 1 : 0;
        c.setConfirmed(isConfirmed);

        // 命中数（小盘恒 0，故 0~7）
        int hit = fMa + fShape + fVol + fRs + fRsi + fWeekly + fBreak;
        c.setFeatureHit(hit);

        // 命中的特征名（接口层展示）
        List<String> names = new ArrayList<>();
        if (fMa == 1) names.add("长期均线多头发散");
        if (fShape == 1) names.add("底部抬高图形");
        if (fVol == 1) names.add("量价健康");
        if (fRs == 1) names.add("RS领涨");
        if (fRsi == 1) names.add("RSI突破70");
        if (fWeekly == 1) names.add("周线确认");
        if (fBreak == 1) names.add("平台突破");
        if (isConfirmed == 1) names.add("趋势成立");
        c.setHitFeatures(names);

        return c;
    }

    private void fillZero(TrendCandidateDaily c) {
        c.setFeatureHit(0);
        c.setRsVsIndex(BD(0));
        c.setConfirmed(0);
        c.setFMa(0); c.setFShape(0); c.setFVol(0); c.setFRs(0); c.setFRsi(0); c.setFWeekly(0); c.setFBreak(0);
        c.setGainFromBottom(BD(0)); c.setRsi(null); c.setMa10(null); c.setMa30(null);
        c.setHitFeatures(List.of());
    }

    private static BigDecimal avg(List<BigDecimal> xs, int k) {
        if (xs.size() < Math.min(k, 2)) return null;
        int from = Math.max(0, xs.size() - k);
        BigDecimal s = BD(0);
        int cnt = 0;
        for (int i = from; i < xs.size(); i++) {
            if (xs.get(i) != null) { s = s.add(xs.get(i)); cnt++; }
        }
        return cnt == 0 ? null : s.divide(BD(cnt), 4, RoundingMode.HALF_UP);
    }

    /** Wilder RSI(14)。返回 null 若样本不足。 */
    private static BigDecimal rsi14(List<BigDecimal> closes) {
        if (closes.size() < 15) return null;
        BigDecimal avgGain = BD(0), avgLoss = BD(0);
        int period = 14;
        for (int i = 1; i <= period; i++) {
            BigDecimal chg = closes.get(i).subtract(closes.get(i - 1));
            if (chg.compareTo(BD(0)) >= 0) avgGain = avgGain.add(chg);
            else avgLoss = avgLoss.add(chg.negate());
        }
        avgGain = avgGain.divide(BD(period), 4, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BD(period), 4, RoundingMode.HALF_UP);
        for (int i = period + 1; i < closes.size(); i++) {
            BigDecimal chg = closes.get(i).subtract(closes.get(i - 1));
            BigDecimal gain = chg.compareTo(BD(0)) >= 0 ? chg : BD(0);
            BigDecimal loss = chg.compareTo(BD(0)) < 0 ? chg.negate() : BD(0);
            avgGain = avgGain.multiply(BD(period - 1)).divide(BD(period), 4, RoundingMode.HALF_UP).add(gain.divide(BD(period), 4, RoundingMode.HALF_UP));
            avgLoss = avgLoss.multiply(BD(period - 1)).divide(BD(period), 4, RoundingMode.HALF_UP).add(loss.divide(BD(period), 4, RoundingMode.HALF_UP));
        }
        if (avgLoss.signum() == 0) return BD(100);
        BigDecimal rs = avgGain.divide(avgLoss, 4, RoundingMode.HALF_UP);
        return BD(100).subtract(BD(100).divide(rs.add(BD(1)), 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal BD(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP); }
}
