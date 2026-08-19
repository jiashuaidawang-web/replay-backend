package com.dunwugudao.replay.job;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.entity.MainlineDaily;
import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.entity.ck.raw.LimitDownPool;
import com.dunwugudao.replay.entity.ck.raw.LimitUpPool;
import com.dunwugudao.replay.mapper.ck.ConceptMapper;
import com.dunwugudao.replay.mapper.ck.LimitDownPoolMapper;
import com.dunwugudao.replay.mapper.ck.LimitUpPoolMapper;
import com.dunwugudao.replay.service.CkHttpWriter;
import com.dunwugudao.replay.service.ConceptDeriveService;
import com.dunwugudao.replay.service.FourDimensionCalculator;
import com.dunwugudao.replay.service.MainForceCalculator;
import com.dunwugudao.replay.service.MainlineCalculator;
import com.dunwugudao.replay.service.MainlineResult;
import com.dunwugudao.replay.service.SentimentCalculator;
import com.dunwugudao.replay.service.ThemeFactorCalculator;
import com.dunwugudao.replay.service.TrendCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 复盘计算编排（S2→S4）。所有计算层写入与写后校验均走 {@link CkHttpWriter}（裸 HTTP 直写），
 * 彻底规避 Windows CK 的 JDBC 静默丢 + 连接池中毒；原始数据读取仍走 ClickHouse 数据源（JDBC，瞬态重试）。
 *
 * <p>幂等策略：计算层表均为 ReplacingMergeTree（ORDER BY 自然键 + _ver 版本列）。重算时直接纯 INSERT，
 * 引擎按自然键折叠、保留 _ver 最大的行，彻底幂等，无需先 DELETE。读取时统一加 FINAL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayCalcJob {

    private final LimitUpPoolMapper limitUpPoolMapper;
    private final LimitDownPoolMapper limitDownPoolMapper;
    private final ConceptMapper conceptMapper;
    private final ConceptDeriveService conceptDeriveService;
    private final SentimentCalculator sentimentCalculator;
    private final MainlineCalculator mainlineCalculator;
    private final ThemeFactorCalculator themeFactorCalculator;
    private final TrendCalculator trendCalculator;
    private final FourDimensionCalculator fourDimensionCalculator;
    private final MainForceCalculator mainForceCalculator;
    private final CkHttpWriter ckHttpWriter;
    /** ch 数据源（ClickHouse）。注入用于瞬态故障后强制清空连接池，避免死连接级联污染后续读取步骤。 */
    @Qualifier("ckDataSource")
    private final javax.sql.DataSource ckDataSource;

    // ---- 各计算层写入列（与 mapper XML 的 INSERT 列一致）----
    private static final List<String> SENTIMENT_COLS = List.of(
            "trade_date", "limit_up_cnt", "limit_down_cnt", "max_board_pos", "yest_limit_ret", "thermal", "regime");
    private static final List<String> MAINLINE_COLS = List.of(
            "trade_date", "board_code", "main_level", "strength", "rank");
    private static final List<String> LEADER_COLS = List.of(
            "trade_date", "ts_code", "board_code", "board_pos", "role", "score", "cat", "amount", "limit_style", "note");
    private static final List<String> FOUR_COLS = List.of(
            "trade_date", "tech", "sentiment", "fund", "policy", "composite",
            "worth_trade", "phase", "absolute", "relative", "suggestion", "note");
    private static final List<String> MAINFORCE_COLS = List.of(
            "trade_date", "ts_code", "stock_name", "reason", "abnormal_type", "net_buy", "total_buy", "total_sell",
            "buy_seat_cnt", "sell_seat_cnt", "org_net_buy", "youzi_net_buy", "north_net_buy", "consensus_score",
            "divergence_flag", "d1_return", "d5_return", "credibility_flag", "change_rate", "close_price",
            "turnoverrate", "note");
    private static final List<String> THEME_COLS = List.of(
            "trade_date", "board_code", "scarcity", "imagination", "sudden", "certainty", "min_resist", "total");
    private static final List<String> TREND_COLS = List.of(
            "trade_date", "ts_code", "stock_name", "feature_hit", "rs_vs_index", "confirmed",
            "f_ma", "f_shape", "f_vol", "f_smallcap", "f_rs", "f_rsi", "f_weekly", "f_break",
            "gain_from_bottom", "close_price", "rsi", "ma10", "ma30");

    /** 对指定交易日跑全套计算并落库（写入全部走 HTTP 直写）。 */
    @Transactional(transactionManager = "ckTransactionManager", propagation = Propagation.NOT_SUPPORTED)
    public void run(LocalDate tradeDate) {
        log.info("====== 复盘计算开始: {} ======", tradeDate);
        List<LimitUpPool>[] upsHolder = new List[1];
        List<LimitDownPool>[] downsHolder = new List[1];
        safeStep("读取原始数据", () -> {
            upsHolder[0] = limitUpPoolMapper.selectByTradeDate(tradeDate);
            downsHolder[0] = limitDownPoolMapper.selectByTradeDate(tradeDate);
        });
        List<LimitUpPool> ups = upsHolder[0] != null ? upsHolder[0] : List.of();
        List<LimitDownPool> downs = downsHolder[0] != null ? downsHolder[0] : List.of();
        log.info("原始数据: 涨停 {} 家, 跌停 {} 家", ups.size(), downs.size());

        safeStep("题材派生", this::deriveConceptIfEmpty);
        safeStep("S2 情绪温度", () -> computeS2(tradeDate, ups, downs));
        safeStep("S4 主线龙头", () -> computeS4(tradeDate, ups));
        safeStep("S7 炒作因子", () -> computeS7(tradeDate));
        safeStep("S1 四维度", () -> computeS1(tradeDate));
        safeStep("S3 主力博弈", () -> computeS3(tradeDate));
        // S6 趋势战法（重型批量写入）放最后：即使读取/计算偶发失败也不污染前面步骤所用连接池。
        safeStep("S6 趋势战法", () -> computeS6(tradeDate));

        // 写后校验 + 缺失重跑（HTTP 直写 + HTTP count，服务端为准，杜绝静默丢漏判）
        verifyAndRepair(tradeDate);

        log.info("====== 复盘计算结束: {} ======", tradeDate);
    }

    // ============ 各 skill 计算+HTTP写入（供 run / verifyAndRepair 复用）============

    private void deriveConceptIfEmpty() {
        if (conceptMapper.count() == 0) {
            conceptDeriveService.derive();
        } else {
            log.info("[题材派生] concept 表已有 {} 行，跳过全量重算", conceptMapper.count());
        }
    }

    private void computeS2(LocalDate tradeDate, List<LimitUpPool> ups, List<LimitDownPool> downs) {
        SentimentDaily sentiment = sentimentCalculator.compute(tradeDate, ups, downs);
        ckHttpWriter.insert("sentiment_daily", SENTIMENT_COLS,
                List.<Object[]>of(new Object[]{ sentiment.getTradeDate(), sentiment.getLimitUpCnt(), sentiment.getLimitDownCnt(),
                        sentiment.getMaxBoardPos(), sentiment.getYestLimitRet(), sentiment.getThermal(), sentiment.getRegime() }));
        log.info("[S2] 情绪温度写入: {} (涨停{} 跌停{} 连板高度{} 温度{} 区间{})",
                tradeDate, sentiment.getLimitUpCnt(), sentiment.getLimitDownCnt(),
                sentiment.getMaxBoardPos(), sentiment.getThermal(), sentiment.getRegime());
    }

    private void computeS4(LocalDate tradeDate, List<LimitUpPool> ups) {
        MainlineResult result = mainlineCalculator.compute(tradeDate, ups);
        List<Object[]> mainlineRows = new ArrayList<>();
        for (com.dunwugudao.replay.entity.MainlineDaily m : result.getMainlines()) {
            mainlineRows.add(new Object[]{ m.getTradeDate(), m.getBoardCode(), m.getMainLevel(), m.getStrength(), m.getRank() });
        }
        ckHttpWriter.insert("mainline_daily", MAINLINE_COLS, mainlineRows);
        List<Object[]> leaderRows = new ArrayList<>();
        List<LeaderPoolDaily> allLeaders = new ArrayList<>(result.getLeaders());
        allLeaders.addAll(result.getDemonsWolves());
        for (LeaderPoolDaily l : allLeaders) {
            leaderRows.add(new Object[]{ l.getTradeDate(), l.getTsCode(), l.getBoardCode(), l.getBoardPos(),
                    l.getRole(), l.getScore(), l.getCat(), l.getAmount(), l.getLimitStyle(), l.getNote() });
        }
        ckHttpWriter.insert("leader_pool_daily", LEADER_COLS, leaderRows);
        log.info("[S4] 主线 {} 条, 龙头 {} 只, 妖/独狼 {} 只 写入完成",
                result.getMainlines().size(), result.getLeaders().size(), result.getDemonsWolves().size());
    }

    private void computeS7(LocalDate tradeDate) {
        List<?> rows = themeFactorCalculator.compute(tradeDate);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Object[]> out = new ArrayList<>();
        for (Object o : rows) {
            com.dunwugudao.replay.entity.ThemeFactorDaily f = (com.dunwugudao.replay.entity.ThemeFactorDaily) o;
            out.add(new Object[]{ f.getTradeDate(), f.getBoardCode(), f.getScarcity(), f.getImagination(),
                    f.getSudden(), f.getCertainty(), f.getMinResist(), f.getTotal() });
        }
        ckHttpWriter.insert("theme_factor_daily", THEME_COLS, out);
    }

    private void computeS1(LocalDate tradeDate) {
        com.dunwugudao.replay.entity.FourDimensionDaily four = fourDimensionCalculator.compute(tradeDate);
        ckHttpWriter.insert("four_dimension_daily", FOUR_COLS,
                List.<Object[]>of(new Object[]{ four.getTradeDate(), four.getTech(), four.getSentiment(), four.getFund(),
                        four.getPolicy(), four.getComposite(), four.getWorthTrade(), four.getPhase(), four.getAbsolute(),
                        four.getRelative(), four.getSuggestion(), four.getNote() }));
        log.info("[S1] 四维度写入: {} (tech={} sent={} fund={} comp={} phase={})",
                tradeDate, four.getTech(), four.getSentiment(), four.getFund(), four.getComposite(), four.getPhase());
    }

    private void computeS3(LocalDate tradeDate) {
        List<com.dunwugudao.replay.entity.MainForceDaily> mf = mainForceCalculator.compute(tradeDate);
        if (mf.isEmpty()) {
            return;
        }
        List<Object[]> out = new ArrayList<>();
        for (com.dunwugudao.replay.entity.MainForceDaily x : mf) {
            out.add(new Object[]{ x.getTradeDate(), x.getTsCode(), x.getStockName(), x.getReason(), x.getAbnormalType(),
                    x.getNetBuy(), x.getTotalBuy(), x.getTotalSell(), x.getBuySeatCnt(), x.getSellSeatCnt(),
                    x.getOrgNetBuy(), x.getYouziNetBuy(), x.getNorthNetBuy(), x.getConsensusScore(), x.getDivergenceFlag(),
                    x.getD1Return(), x.getD5Return(), x.getCredibilityFlag(), x.getChangeRate(), x.getClosePrice(),
                    x.getTurnoverrate(), x.getNote() });
        }
        ckHttpWriter.insert("main_force_daily", MAINFORCE_COLS, out);
        long win = mf.stream().filter(x -> x.getCredibilityFlag() != null && x.getCredibilityFlag() == 1).count();
        long trapped = mf.stream().filter(x -> x.getCredibilityFlag() != null && x.getCredibilityFlag() == 0).count();
        log.info("[S3] 主力博弈写入 {} 只; 主力净买后次日胜 {} / 被埋 {}", mf.size(), win, trapped);
    }

    private void computeS6(LocalDate tradeDate) {
        List<?> rows = trendCalculator.compute(tradeDate);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Object[]> out = new ArrayList<>();
        for (Object o : rows) {
            com.dunwugudao.replay.entity.TrendCandidateDaily c = (com.dunwugudao.replay.entity.TrendCandidateDaily) o;
            out.add(new Object[]{ c.getTradeDate(), c.getTsCode(), c.getStockName(), c.getFeatureHit(), c.getRsVsIndex(),
                    c.getConfirmed(), c.getFMa(), c.getFShape(), c.getFVol(), c.getFSmallcap(), c.getFRs(), c.getFRsi(),
                    c.getFWeekly(), c.getFBreak(), c.getGainFromBottom(), c.getClosePrice(), c.getRsi(), c.getMa10(), c.getMa30() });
        }
        ckHttpWriter.insert("trend_candidate_daily", TREND_COLS, out);
    }

    /**
     * 写后校验 + 静默丢自愈：逐表用 HTTP count 核查服务端真实行数，缺失则换连接重跑该 skill。
     * 以服务端为准，彻底击败 JDBC「驱动成功/服务端未提交」的静默丢。
     */
    private void verifyAndRepair(LocalDate tradeDate) {
        final int maxPasses = 5;
        for (int pass = 1; pass <= maxPasses; pass++) {
            boolean allOk = true;
            if (ckHttpWriter.count("sentiment_daily", tradeDate) == 0) {
                allOk = false;
                safeStepEvictFirst("S2 静默丢重跑", () -> computeS2(tradeDate,
                        safeList(limitUpPoolMapper.selectByTradeDate(tradeDate)),
                        safeList(limitDownPoolMapper.selectByTradeDate(tradeDate))));
            }
            if (ckHttpWriter.count("mainline_daily", tradeDate) == 0
                    || ckHttpWriter.count("leader_pool_daily", tradeDate) == 0) {
                allOk = false;
                safeStepEvictFirst("S4 静默丢重跑", () -> computeS4(tradeDate,
                        safeList(limitUpPoolMapper.selectByTradeDate(tradeDate))));
            }
            if (ckHttpWriter.count("theme_factor_daily", tradeDate) == 0) {
                allOk = false;
                safeStepEvictFirst("S7 静默丢重跑", () -> computeS7(tradeDate));
            }
            if (ckHttpWriter.count("four_dimension_daily", tradeDate) == 0) {
                allOk = false;
                safeStepEvictFirst("S1 静默丢重跑", () -> computeS1(tradeDate));
            }
            if (ckHttpWriter.count("main_force_daily", tradeDate) == 0) {
                allOk = false;
                safeStepEvictFirst("S3 静默丢重跑", () -> computeS3(tradeDate));
            }
            if (ckHttpWriter.count("trend_candidate_daily", tradeDate) == 0) {
                allOk = false;
                safeStepEvictFirst("S6 静默丢重跑", () -> computeS6(tradeDate));
            }
            if (allOk) {
                log.info("====== 写后校验通过（无静默丢）: {} (pass {}) ======", tradeDate, pass);
                return;
            }
            log.warn("====== 写后校验仍缺表，准备下一轮自愈 (date={}, pass={}/{}) ======", tradeDate, pass, maxPasses);
            softEvictConnections();
        }
        log.error("====== 写后校验未能补齐所有计算层（多次重试仍缺失），需人工排查 Windows CK: {} ======", tradeDate);
    }

    /** 单步执行前先强制清空连接池，借新建健康连接，降低读取瞬态失败概率。 */
    private void safeStepEvictFirst(String name, Runnable step) {
        softEvictConnections();
        safeStep(name, step);
    }

    private void softEvictConnections() {
        try {
            if (ckDataSource instanceof com.zaxxer.hikari.HikariDataSource) {
                ((com.zaxxer.hikari.HikariDataSource) ckDataSource).getHikariPoolMXBean().softEvictConnections();
            }
        } catch (Exception ignore) {
            // 仅辅助手段
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    // ============ 下面为单步容错（瞬态重试）============

    /** 单步容错执行：异常仅记录，不中断后续步骤。 */
    private void safeStep(String name, Runnable step) {
        safeStep(name, step, 5);
    }

    /**
     * 单步容错 + 瞬态重试：Windows CK 偶发网络级抖动（服务端间歇不可达 → "target server failed to respond" /
     * ConnectException、Broken pipe / Connection reset / timeout 污染连接池 → "Connection is closed"）。
     * 对此类瞬态错误按指数退避重试（1/2/4/8/16s，累计约 31s，覆盖多数抖动窗口），每次重试前强制清空连接池
     * 以借到新建健康连接；非瞬态错误或次数耗尽则记录并跳过，不中断整轮。
     */
    private void safeStep(String name, Runnable step, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                step.run();
                return;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean transientErr = msg.contains("Broken pipe") || msg.contains("Connection is closed")
                        || msg.contains("Connection reset") || msg.contains("timeout")
                        || msg.contains("read timed out") || msg.contains("closed")
                        || msg.contains("target server failed to respond") || msg.contains("ConnectException")
                        || msg.contains("failed to respond");
                if (!transientErr || attempt == maxAttempts) {
                    log.error("步骤 [{}] 执行失败（尝试 {}/{}，已跳过，不影响其余步骤）: {}",
                            name, attempt, maxAttempts, e.getMessage(), e);
                    return;
                }
                long backoffMs = 1000L * (long) Math.pow(2, attempt - 1);
                log.warn("步骤 [{}] 第 {} 次失败（瞬态，{}s 后重试）: {}", name, attempt, backoffMs / 1000, msg);
                softEvictConnections();
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 仅重算 S4 主线龙头 + 妖/独狼（轻量，不动 S1/S2/S3/S6/S7）。写入走 HTTP 直写，校验走 HTTP count。 */
    @Transactional(transactionManager = "ckTransactionManager", propagation = Propagation.NOT_SUPPORTED)
    public void recalcMainline(LocalDate tradeDate) {
        log.info("====== S4 主线龙头重算开始: {} ======", tradeDate);
        List<LimitUpPool>[] upsHolder = new List[1];
        safeStep("读取涨停池", () -> upsHolder[0] = limitUpPoolMapper.selectByTradeDate(tradeDate));
        List<LimitUpPool> ups = upsHolder[0] != null ? upsHolder[0] : List.of();
        safeStep("S4 主线龙头", () -> computeS4(tradeDate, ups));
        // S4 静默丢自愈：mainline/leader 缺失则换连接重跑
        for (int pass = 1; pass <= 5; pass++) {
            boolean ok = ckHttpWriter.count("mainline_daily", tradeDate) > 0
                    && ckHttpWriter.count("leader_pool_daily", tradeDate) > 0;
            if (ok) {
                log.info("====== S4 重算写后校验通过（无静默丢）: {} (pass {}) ======", tradeDate, pass);
                break;
            }
            log.warn("====== S4 重算疑似静默丢，自愈重跑 (date={}, pass={}) ======", tradeDate, pass);
            safeStepEvictFirst("S4 静默丢重跑", () -> computeS4(tradeDate,
                    safeList(limitUpPoolMapper.selectByTradeDate(tradeDate))));
        }
        log.info("====== S4 主线龙头重算结束: {} ======", tradeDate);
    }

    /** 取已入库最大交易日并跑一轮（启动触发用）。 */
    public void runForLatest() {
        LocalDate latest = limitUpPoolMapper.selectMaxTradeDate();
        if (latest == null) {
            log.warn("limit_up_pool 无数据，跳过计算");
            return;
        }
        run(latest);
    }
}
