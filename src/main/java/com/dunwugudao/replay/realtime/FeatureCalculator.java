package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.realtime.model.RealtimeFeature;
import com.dunwugudao.replay.realtime.model.Tick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式特征计算器（决策引擎输入）。
 *
 * <p>每 {@code feature-interval-ms}（默认 3s）对滚动窗口内全部标的计算 1/5 分钟窗口特征：
 * <ul>
 *   <li>bigNetBuy：大单净主动买入额（单笔 ≥ big-order-threshold 视为大单，主动买+/主动卖-）；</li>
 *   <li>bigNetBuyRatio：占窗口成交额比；</li>
 *   <li>isBlast / isReseal：炸板/回封（由封单状态迁移推断）；</li>
 *   <li>volBreakout：放量突破（1 分钟成交额 ≥ vol-breakout-ratio × 当日分钟均量）。</li>
 * </ul>
 * 产出：① FEATURE 事件进总线（→交易元/SSE）；② 攒批写 CK {@code realtime_feature}。
 * <p>注意：本类是「计算」，与传输（Redis Streams）解耦——对应方案文档 2.3 的显式分工。
 */
@Slf4j
@Component
public class FeatureCalculator {

    private static final List<String> FEATURE_COLUMNS = List.of(
            "trade_date", "ts_code", "ts", "win_minutes", "big_net_buy", "big_net_buy_ratio",
            "seal_amount", "is_blast", "is_reseal", "vol_breakout", "stage_snapshot",
            "stealth_net_buy", "sweep_density", "self_trade_ratio", "order_pattern");

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int[] WINDOWS = {1, 5};

    private final TickWindow window;
    private final RealtimeEventBus bus;
    private final StageSnapshotHolder stageHolder;
    private final CkBufferedWriter ckBuf;

    private final double bigOrderThreshold;
    private final double netBuyStrong;
    private final double breakoutRatio;

    // ---------------- M3 拆单识别阈值 ----------------
    /** 拆单识别窗口（秒）：在该窗口内统计同向小单累计量。 */
    private final long stealthWindowSec;
    /** 拆单"破当量"阈值（手）：同向小单累计达到该手数视为拆单吸筹（万手=10000 手）。 */
    private final double stealthMinVolume;
    /** 扫单窗口（秒）：在该窗口内统计主买密度与价格台阶。 */
    private final long sweepWindowSec;
    /** 对敲配对时间容差（ms）：±该值内量相近、反向成对的笔计入对敲。 */
    private final long selfTradeWindowMs;
    /** 对敲量相近容差（比例）：|v1-v2|/max(v1,v2) <= 该值视为"量相近"。 */
    private final double selfTradeVolTol;

    /** 炸板/回封状态迁移（按标的记忆）。 */
    private final Map<String, Boolean> prevSealed = new ConcurrentHashMap<>();
    private final Map<String, Boolean> wasBlasted = new ConcurrentHashMap<>();

    public FeatureCalculator(TickWindow window,
                             RealtimeEventBus bus,
                             StageSnapshotHolder stageHolder,
                             CkBufferedWriter ckBuf,
                             @Value("${replay.stream.big-order-threshold:500000}") double bigOrderThreshold,
                             @Value("${replay.stream.net-buy-strong:2000000}") double netBuyStrong,
                             @Value("${replay.stream.vol-breakout-ratio:3.0}") double breakoutRatio,
                             @Value("${replay.stream.stealth-window-sec:30}") long stealthWindowSec,
                             @Value("${replay.stream.stealth-min-volume:10000}") double stealthMinVolume,
                             @Value("${replay.stream.sweep-window-sec:10}") long sweepWindowSec,
                             @Value("${replay.stream.self-trade-window-ms:200}") long selfTradeWindowMs,
                             @Value("${replay.stream.self-trade-vol-tol:0.2}") double selfTradeVolTol) {
        this.window = window;
        this.bus = bus;
        this.stageHolder = stageHolder;
        this.ckBuf = ckBuf;
        this.bigOrderThreshold = bigOrderThreshold;
        this.netBuyStrong = netBuyStrong;
        this.breakoutRatio = breakoutRatio;
        this.stealthWindowSec = stealthWindowSec;
        this.stealthMinVolume = stealthMinVolume;
        this.sweepWindowSec = sweepWindowSec;
        this.selfTradeWindowMs = selfTradeWindowMs;
        this.selfTradeVolTol = selfTradeVolTol;
    }

    public double getNetBuyStrong() {
        return netBuyStrong;
    }

    @Scheduled(fixedDelayString = "${replay.stream.feature-interval-ms:3000}")
    public void compute() {
        Set<String> codes = window.trackedCodes();
        if (codes.isEmpty()) {
            return;
        }
        String stage = stageHolder.currentStage();
        long now = System.currentTimeMillis();
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();
        List<Object[]> rows = new ArrayList<>();

        for (String code : codes) {
            List<Tick> ticks = window.snapshot(code);
            Quote q = window.getQuote(code);

            // --- 炸板/回封状态迁移 ---
            int isBlast = 0;
            int isReseal = 0;
            double sealAmount = 0;
            if (q != null && q.getLimitUpPrice() > 0) {
                sealAmount = q.getSealAmount();
                boolean sealed = q.getSealAmount() > 0 && q.getLastPrice() >= q.getLimitUpPrice() - 0.001;
                boolean prev = Boolean.TRUE.equals(prevSealed.get(code));
                boolean blasted = Boolean.TRUE.equals(wasBlasted.get(code));
                if (prev && !sealed) {
                    isBlast = 1;      // 封板 → 开板
                    blasted = true;
                }
                if (blasted && sealed) {
                    isReseal = 1;     // 开板 → 回封
                    blasted = false;
                }
                prevSealed.put(code, sealed);
                wasBlasted.put(code, blasted);
            }

            // --- 各窗口大单净主动买入 ---
            for (int winMin : WINDOWS) {
                long cutoff = now - winMin * 60_000L;
                double bigNet = 0;
                double total = 0;
                for (Tick t : ticks) {
                    if (t.getTs() < cutoff) {
                        continue;
                    }
                    total += t.getAmount();
                    if (t.getAmount() >= bigOrderThreshold) {
                        bigNet += t.directionSign() * t.getAmount();
                    }
                }
                double ratio = total > 0 ? bigNet / total : 0;

                int volBreakout = 0;
                if (winMin == 1 && q != null && q.getAmountDay() > 0) {
                    double elapsedMin = elapsedTradeMinutes(now);
                    if (elapsedMin > 0) {
                        double avgMinuteAmount = q.getAmountDay() / elapsedMin;
                        volBreakout = (total > 0 && total >= breakoutRatio * avgMinuteAmount) ? 1 : 0;
                    }
                }

                // --- M3 拆单识别（基于该 winMin 窗口内的逐笔序列）---
                OrderPatternAnalyzer.Config cfg = new OrderPatternAnalyzer.Config(
                        stealthWindowSec, stealthMinVolume, sweepWindowSec,
                        selfTradeWindowMs, selfTradeVolTol, bigOrderThreshold);
                OrderPatternAnalyzer.Result of = OrderPatternAnalyzer.analyze(ticks, cutoff, now, cfg);

                RealtimeFeature f = RealtimeFeature.builder()
                        .tsCode(code)
                        .tradeDate(today)
                        .ts(now)
                        .winMinutes(winMin)
                        .bigNetBuy(bigNet)
                        .bigNetBuyRatio(ratio)
                        .sealAmount(sealAmount)
                        .isBlast(isBlast)
                        .isReseal(isReseal)
                        .volBreakout(volBreakout)
                        .stageSnapshot(stage)
                        .stealthNetBuy(of.stealthNetBuy())
                        .sweepDensity(of.sweepDensity())
                        .selfTradeRatio(of.selfTradeRatio())
                        .orderPattern(of.orderPattern())
                        .build();
                bus.publish(new RealtimeEvent(RealtimeEvent.Type.FEATURE, f));

                String tsStr = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
                        .toLocalDateTime().format(TS_FMT);
                rows.add(new Object[]{today, code, tsStr, winMin, bigNet, ratio,
                        sealAmount, isBlast, isReseal, volBreakout, stage,
                        of.stealthNetBuy(), of.sweepDensity(), of.selfTradeRatio(), of.orderPattern()});
            }
        }
        if (!rows.isEmpty()) {
            ckBuf.add("realtime_feature", FEATURE_COLUMNS, rows);
        }
    }

    /** 当日已交易分钟数（09:30 起算，简单线性，不含午休折算——M1 近似口径）。 */
    private static double elapsedTradeMinutes(long nowMs) {
        LocalTime t = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalTime();
        double mins = (t.getHour() - 9) * 60 + t.getMinute() - 30;
        return Math.max(1, Math.min(240, mins));
    }
}
