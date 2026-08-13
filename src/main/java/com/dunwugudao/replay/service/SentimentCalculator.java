package com.dunwugudao.replay.service;

import com.dunwugudao.replay.config.ReplayProperties;
import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.entity.ck.raw.LimitDownPool;
import com.dunwugudao.replay.entity.ck.raw.LimitUpPool;
import com.dunwugudao.replay.mapper.ck.SentimentDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * S2 情绪温度计算（顿悟股道·情绪资金篇）。
 *
 * <p>输入：当日涨停池 / 跌停池原始行。输出：情绪温度日级一行（sentiment_daily）。
 *
 * <p>核心口径：
 * <ul>
 *   <li>涨停家数、跌停家数、连板高度取真实涨停池（limit_up_pool）为<b>权威</b>，
 *       不用 stock_daily.is_limit_up（实测有 79 家误标）。</li>
 *   <li>昨日涨停今日表现（yest_limit_ret）需要 T-1 涨停集合与 T 收益率，单日数据无法算，留 NULL。</li>
 *   <li>thermal（温度）优先用<b>历史分位数</b>：当历史交易日 ≥ minHistoryDays 时，
 *       今日涨停家数在历史分布中的分位 ×100；否则降级为<b>绝对阈值评分</b>（见 {@link #degradedThermal}）。</li>
 *   <li>regime（市场区间）按涨停家数分冰点/低迷/正常/活跃/高潮。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentCalculator {

    private final ReplayProperties props;
    private final SentimentDailyMapper sentimentDailyMapper;

    public SentimentDaily compute(LocalDate tradeDate,
                                  List<LimitUpPool> ups,
                                  List<LimitDownPool> downs) {
        int up = (ups == null) ? 0 : ups.size();
        int down = (downs == null) ? 0 : downs.size();
        int maxBoardPos = ups == null ? 0 :
                ups.stream().map(LimitUpPool::getBoardPos)
                        .filter(Objects::nonNull).max(Integer::compareTo).orElse(0);

        BigDecimal thermal = resolveThermal(tradeDate, up);
        String regime = regimeOf(up);

        SentimentDaily d = new SentimentDaily();
        d.setTradeDate(tradeDate);
        d.setLimitUpCnt(up);
        d.setLimitDownCnt(down);
        d.setMaxBoardPos(maxBoardPos);
        d.setYestLimitRet(null); // 单日/无 T-1 时留空
        d.setThermal(thermal);
        d.setRegime(regime);
        return d;
    }

    /** 历史分位数优先，不足则降级。 */
    private BigDecimal resolveThermal(LocalDate tradeDate, int up) {
        List<SentimentDaily> history = sentimentDailyMapper.selectAllBefore(tradeDate);
        if (history != null && history.size() >= props.getSentiment().getMinHistoryDays()
                && !history.isEmpty()) {
            long le = history.stream()
                    .map(SentimentDaily::getLimitUpCnt)
                    .filter(Objects::nonNull)
                    .filter(c -> c <= up).count();
            double pct = le / (double) history.size();
            BigDecimal t = BigDecimal.valueOf(pct * 100).setScale(2, RoundingMode.HALF_UP);
            log.info("[情绪] 历史分位数口径 thermal={} (历史{}天, 今日涨停{}家, 历史中位{})",
                    t, history.size(), up,
                    history.stream().map(SentimentDaily::getLimitUpCnt).filter(Objects::nonNull)
                            .sorted().skip(history.size() / 2).findFirst().orElse(0));
            return t;
        }
        BigDecimal t = degradedThermal(up);
        log.info("[情绪] 历史不足({}, 需{}), 降级绝对阈值 thermal={} (今日涨停{}家)",
                history == null ? 0 : history.size(),
                props.getSentiment().getMinHistoryDays(), t, up);
        return t;
    }

    /** 绝对阈值评分（0~100）。分段线性映射涨停家数到温度。 */
    private BigDecimal degradedThermal(int up) {
        ReplayProperties.Sentiment s = props.getSentiment();
        int ice = s.getIceLimitUpMax();
        int cold = s.getColdLimitUpMax();
        int warm = s.getWarmLimitUpMax();
        int hot = s.getHotLimitUpMax();
        double t;
        if (up <= ice) {
            t = 10 + (ice == 0 ? 0 : (up / (double) ice) * 20);
        } else if (up <= cold) {
            t = 30 + ((up - ice) / (double) (cold - ice)) * 20;
        } else if (up <= warm) {
            t = 50 + ((up - cold) / (double) (warm - cold)) * 20;
        } else if (up <= hot) {
            t = 70 + ((up - warm) / (double) (hot - warm)) * 20;
        } else {
            t = Math.min(100, 90 + ((up - hot) / (double) hot) * 10);
        }
        return BigDecimal.valueOf(Math.max(0, Math.min(100, t))).setScale(2, RoundingMode.HALF_UP);
    }

    /** 按涨停家数划分市场区间。 */
    private String regimeOf(int up) {
        ReplayProperties.Sentiment s = props.getSentiment();
        if (up <= s.getIceLimitUpMax()) return "冰点";
        if (up <= s.getColdLimitUpMax()) return "低迷";
        if (up <= s.getWarmLimitUpMax()) return "正常";
        if (up <= s.getHotLimitUpMax()) return "活跃";
        return "高潮";
    }
}
