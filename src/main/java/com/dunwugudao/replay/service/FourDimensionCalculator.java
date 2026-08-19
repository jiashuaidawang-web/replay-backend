package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.FourDimensionDaily;
import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.entity.ck.raw.FundFlowAgg;
import com.dunwugudao.replay.entity.ck.raw.IndexDaily;
import com.dunwugudao.replay.mapper.ck.IndexDailyMapper;
import com.dunwugudao.replay.mapper.ck.MainFundFlowMapper;
import com.dunwugudao.replay.mapper.ck.NewsEventMapper;
import com.dunwugudao.replay.mapper.ck.SentimentDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S1 大势择时 · 四维度量化（《顿悟股道》第一章/第三章：技术/情绪/资金/政策四维交叉验证大势）。
 *
 * <p>各维归一 0~1；composite 为加权综合；再据 composite/tech/sentiment 派生牛熊周期
 * （absolute/phase/relative）与策略建议（suggestion）。note 记录数据口径限制，保证结论可解释、可溯源。
 *
 * <p>数据约束（当前快照 2026-08-19）：index_daily 仅约 5 个交易日、main_fund_flow 缺部分日（回退最近可用日）。
 * 政策维度已接 news_event（当日 is_policy=1 事件情感分聚合）；news_event 当前为 2026-08 仿真种子事件，
 * 待用户爬虫灌入真实政策新闻后结论自动更可靠。算法对缺失做了容错，待历史区间补齐后结论自动更可靠。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FourDimensionCalculator {

    /** 四维度权重：技术 0.35 / 情绪 0.25 / 资金 0.25 / 政策 0.15。 */
    private static final double W_TECH = 0.35;
    private static final double W_SENTIMENT = 0.25;
    private static final double W_FUND = 0.25;
    private static final double W_POLICY = 0.15;

    /** 代表宽基指数（用于短周期均线位置判断）。 */
    private static final String[] REP_INDICES = {"000300.SH", "000001.SH"};

    private final SentimentDailyMapper sentimentDailyMapper;
    private final IndexDailyMapper indexDailyMapper;
    private final MainFundFlowMapper mainFundFlowMapper;
    private final NewsEventMapper newsEventMapper;

    public FourDimensionDaily compute(LocalDate tradeDate) {
        FourDimensionDaily r = new FourDimensionDaily();
        r.setTradeDate(tradeDate);

        // ---- 情绪维度（S2 情绪温度 /100）----
        SentimentDaily s = sentimentDailyMapper.selectByTradeDate(tradeDate);
        BigDecimal sentiment = (s != null && s.getThermal() != null)
                ? s.getThermal().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                : null;
        if (sentiment == null) {
            r.setNote(appendNote(r.getNote(), "情绪温度缺失，情绪维按中性 0.5"));
            sentiment = BigDecimal.valueOf(0.5);
        }

        // ---- 技术维度 / 资金维度（含数据口径 note）----
        BigDecimal tech = computeTech(tradeDate, r);
        BigDecimal fund = computeFund(tradeDate, r);

        // ---- 政策维度：读 news_event 当日政策类事件情感分聚合 ----
        BigDecimal policy = computePolicy(tradeDate, r);

        // ---- 综合分（缺失维自动剔除并重归一）----
        List<double[]> wv = new ArrayList<>();
        if (tech != null) wv.add(new double[]{W_TECH, tech.doubleValue()});
        if (sentiment != null) wv.add(new double[]{W_SENTIMENT, sentiment.doubleValue()});
        if (fund != null) wv.add(new double[]{W_FUND, fund.doubleValue()});
        wv.add(new double[]{W_POLICY, policy.doubleValue()});
        double wsum = wv.stream().mapToDouble(a -> a[0]).sum();
        double comp = wv.stream().mapToDouble(a -> a[0] * a[1]).sum() / wsum;

        r.setTech(tech);
        r.setSentiment(sentiment);
        r.setFund(fund);
        r.setPolicy(policy);
        r.setComposite(round1(clamp(comp, 0, 1)));

        // ---- 周期判定 + 策略建议 ----
        deriveCycle(r, tech, r.getComposite(), sentiment);
        log.info("[S1] 四维度 {}: tech={} sent={} fund={} policy={} comp={} phase={} abs={} worth={}",
                tradeDate, r.getTech(), r.getSentiment(), r.getFund(), r.getPolicy(), r.getComposite(),
                r.getPhase(), r.getAbsolute(), r.getWorthTrade());
        return r;
    }

    /** 技术维度：短周期均线位置（代表指数）+ 全市场涨跌广度（用收盘价推算，避免 pct_chg 口径偏差）。 */
    private BigDecimal computeTech(LocalDate tradeDate, FourDimensionDaily r) {
        List<IndexDaily> rows = indexDailyMapper.selectOnOrBefore(tradeDate);
        if (rows == null || rows.isEmpty()) {
            r.setNote(appendNote(r.getNote(), "指数数据缺失，技术维按中性 0.5"));
            return BigDecimal.valueOf(0.5);
        }
        Map<String, List<IndexDaily>> byIndex = rows.stream()
                .collect(Collectors.groupingBy(IndexDaily::getIndexCode));

        // 广度：每个指数最近两日收盘收益率 > 0 的占比
        int up = 0, tot = 0;
        for (List<IndexDaily> ser : byIndex.values()) {
            if (ser.size() < 2) continue;
            IndexDaily last = ser.get(ser.size() - 1);
            IndexDaily prev = ser.get(ser.size() - 2);
            if (last.getClose() != null && prev.getClose() != null && prev.getClose().signum() != 0) {
                double ret = last.getClose().subtract(prev.getClose())
                        .divide(prev.getClose(), 8, RoundingMode.HALF_UP).doubleValue();
                if (ret > 0) up++;
                tot++;
            }
        }
        double breadth = tot > 0 ? (double) up / tot : 0.5;
        if (tot == 0) r.setNote(appendNote(r.getNote(), "指数历史不足 2 日，广度无法计算"));

        // 代表指数短周期均线位置（最近 5 根收盘的 MA）
        double sumPos = 0;
        int cnt = 0;
        for (String code : REP_INDICES) {
            List<IndexDaily> ser = byIndex.get(code);
            if (ser == null || ser.size() < 2) continue;
            int n = ser.size();
            List<IndexDaily> window = ser.subList(Math.max(0, n - 5), n);
            double ma = window.stream().filter(x -> x.getClose() != null)
                    .mapToDouble(x -> x.getClose().doubleValue()).average().orElse(Double.NaN);
            double close = ser.get(n - 1).getClose().doubleValue();
            if (!Double.isNaN(ma) && ma != 0) {
                double pos = 0.5 + clamp((close / ma - 1) / 0.05, -0.5, 0.5);
                sumPos += pos;
                cnt++;
            }
        }
        double avgPos = cnt > 0 ? sumPos / cnt : 0.5;
        if (cnt == 0) r.setNote(appendNote(r.getNote(), "代表指数(沪深300/上证)历史不足，均线位置按 0.5"));

        double tech = clamp(0.6 * breadth + 0.4 * avgPos, 0, 1);
        r.setNote(appendNote(r.getNote(), String.format(
                "指数历史仅 %d 条，技术判定为短周期结构参考(breadth=%.2f, avgPos=%.2f)", rows.size(), breadth, avgPos)));
        return round1(tech);
    }

    /** 资金维度：股票级主力资金净流入广度（回退到最近可用日）。 */
    private BigDecimal computeFund(LocalDate tradeDate, FourDimensionDaily r) {
        FundFlowAgg agg = mainFundFlowMapper.selectAggOnOrBefore(tradeDate);
        if (agg == null || agg.getTotalCnt() == null || agg.getTotalCnt() == 0) {
            r.setNote(appendNote(r.getNote(), "资金流数据缺失，资金维按中性 0.5"));
            return BigDecimal.valueOf(0.5);
        }
        if (agg.getUsedDate() != null && !agg.getUsedDate().equals(tradeDate)) {
            r.setNote(appendNote(r.getNote(),
                    String.format("资金维使用最近可用日 %s（当日无资金流）", agg.getUsedDate())));
        }
        double totalNet = agg.getTotalNet() != null ? agg.getTotalNet().doubleValue() : 0;
        long pos = agg.getPositiveCnt() != null ? agg.getPositiveCnt() : 0;
        long tot = agg.getTotalCnt();
        double posRatio = (double) pos / tot;
        double fund = clamp(0.5 + (posRatio - 0.5) * 0.8 + (totalNet >= 0 ? 0.1 : -0.1), 0, 1);
        return round1(fund);
    }

    /** 政策维度：聚合 news_event 当日政策类事件(is_policy=1)情感分 → 0~1。无事件则中性 0.5。 */
    private BigDecimal computePolicy(LocalDate tradeDate, FourDimensionDaily r) {
        // Windows CK 的 JDBC 读偶发失败（连接池中毒/网络抖动），重试 3 次避免误判中性。
        List<com.dunwugudao.replay.entity.ck.raw.NewsEventRaw> evs = null;
        boolean ok = false;
        for (int attempt = 1; attempt <= 3 && !ok; attempt++) {
            try {
                evs = newsEventMapper.selectPolicyOn(tradeDate);
                ok = true;
            } catch (Exception e) {
                log.warn("[S1] 读取 news_event 失败(重试 {}/3): {}", attempt, e.getMessage());
                try { Thread.sleep(800L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (!ok || evs == null) {
            r.setNote(appendNote(r.getNote(), "news_event 读取异常，政策维中性 0.5"));
            return BigDecimal.valueOf(0.5);
        }
        if (evs.isEmpty()) {
            r.setNote(appendNote(r.getNote(), "当日无政策事件，政策维中性 0.5"));
            return BigDecimal.valueOf(0.5);
        }
        double avg = evs.stream()
                .filter(e -> e.getSentimentScore() != null)
                .mapToDouble(e -> e.getSentimentScore().doubleValue())
                .average().orElse(0.0);
        BigDecimal policy = round1(clamp(0.5 + 0.5 * avg, 0, 1));
        r.setNote(appendNote(r.getNote(),
                String.format("政策维：%d条政策事件, 情感均分=%.2f → 政策强度=%.2f", evs.size(), avg, policy.doubleValue())));
        return policy;
    }

    /** 据综合分/技术分/情绪分派生牛熊周期与策略建议。 */
    private void deriveCycle(FourDimensionDaily r, BigDecimal tech, BigDecimal composite, BigDecimal sentiment) {
        double c = composite.doubleValue();
        double t = tech != null ? tech.doubleValue() : 0.5;
        double se = sentiment != null ? sentiment.doubleValue() : 0.5;

        String absolute = t >= 0.6 ? "多头" : (t <= 0.4 ? "空头" : "震荡");

        String relative;
        if (c >= 0.65 && se >= 0.55) relative = "全面机会";
        else if (c >= 0.5) relative = "结构性机会";
        else if (c >= 0.4) relative = "弱势震荡";
        else relative = "无机会";

        String phase;
        if (c >= 0.7) phase = "机会期(顺势参与)";
        else if (c >= 0.55) phase = "结构性机会期";
        else if (c >= 0.45) phase = "震荡磨底";
        else if (c >= 0.4) phase = "弱势";
        else phase = "风险释放期";

        String suggestion;
        switch (phase) {
            case "机会期(顺势参与)" -> suggestion = "大势四维共振偏多，择时>选股，可积极参与主线龙头与趋势股。";
            case "结构性机会期" -> suggestion = "可参与但非全面牛市，聚焦最强主线与龙头，控仓、不追杂毛。";
            case "震荡磨底" -> suggestion = "大势未明，轻仓试错，等方向确认后再加仓。";
            case "弱势" -> suggestion = "降低仓位，仅极小仓试错，规避弱势股破位。";
            default -> suggestion = "失时而守，空仓或极轻仓保存实力，不逆势硬攻。";
        }

        r.setWorthTrade(c >= 0.5 ? 1 : 0);
        r.setAbsolute(absolute);
        r.setRelative(relative);
        r.setPhase(phase);
        r.setSuggestion(suggestion);
    }

    private BigDecimal round1(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private String appendNote(String base, String add) {
        if (base == null || base.isBlank()) return add;
        return base + "；" + add;
    }
}
