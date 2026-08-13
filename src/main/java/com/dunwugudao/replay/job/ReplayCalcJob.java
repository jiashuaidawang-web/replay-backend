package com.dunwugudao.replay.job;

import com.dunwugudao.replay.entity.MainlineDaily;
import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.entity.ck.raw.LimitDownPool;
import com.dunwugudao.replay.entity.ck.raw.LimitUpPool;
import com.dunwugudao.replay.mapper.ck.LeaderPoolDailyMapper;
import com.dunwugudao.replay.mapper.ck.LimitDownPoolMapper;
import com.dunwugudao.replay.mapper.ck.LimitUpPoolMapper;
import com.dunwugudao.replay.mapper.ck.MainlineDailyMapper;
import com.dunwugudao.replay.mapper.ck.SentimentDailyMapper;
import com.dunwugudao.replay.service.ConceptDeriveService;
import com.dunwugudao.replay.service.MainlineCalculator;
import com.dunwugudao.replay.service.MainlineResult;
import com.dunwugudao.replay.service.SentimentCalculator;
import com.dunwugudao.replay.service.ThemeFactorCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 复盘计算编排（S2→S4）。所有读写都走 ClickHouse 数据源。
 *
 * <p>CK 不支持事务，故方法声明 {@code NOT_SUPPORTED} 并指定 ckTransactionManager，
 * 避免 Spring 触发 setAutoCommit(false)。数据源 auto-commit=true，语句直接提交。
 *
     * <p>幂等策略：计算层三表（sentiment_daily / mainline_daily / leader_pool_daily）已改为
     * ReplacingMergeTree（ORDER BY 自然键 + _ver 版本列）。重算时直接纯 INSERT，引擎按自然键折叠、
     * 保留 _ver 最大的行（即最后一次重算结果），彻底幂等，无需先 DELETE。读取时统一加 FINAL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayCalcJob {

    private final LimitUpPoolMapper limitUpPoolMapper;
    private final LimitDownPoolMapper limitDownPoolMapper;
    private final SentimentDailyMapper sentimentDailyMapper;
    private final MainlineDailyMapper mainlineDailyMapper;
    private final LeaderPoolDailyMapper leaderPoolDailyMapper;
    private final SentimentCalculator sentimentCalculator;
    private final MainlineCalculator mainlineCalculator;
    private final ConceptDeriveService conceptDeriveService;
    private final ThemeFactorCalculator themeFactorCalculator;

    /** 对指定交易日跑全套计算并落库。 */
    @Transactional(transactionManager = "ckTransactionManager", propagation = Propagation.NOT_SUPPORTED)
    public void run(LocalDate tradeDate) {
        log.info("====== 复盘计算开始: {} ======", tradeDate);
        List<LimitUpPool> ups = limitUpPoolMapper.selectByTradeDate(tradeDate);
        List<LimitDownPool> downs = limitDownPoolMapper.selectByTradeDate(tradeDate);
        log.info("原始数据: 涨停 {} 家, 跌停 {} 家", ups.size(), downs.size());

        // ---- S7 前置：题材静态属性派生（concept 表，S4/S7 共同输入） ----
        conceptDeriveService.derive();

        // ---- S2 情绪温度 ----
        SentimentDaily sentiment = sentimentCalculator.compute(tradeDate, ups, downs);
        sentimentDailyMapper.insertBatch(List.of(sentiment));
        log.info("[S2] 情绪温度写入: {} (涨停{} 跌停{} 连板高度{} 温度{} 区间{})",
                tradeDate, sentiment.getLimitUpCnt(), sentiment.getLimitDownCnt(),
                sentiment.getMaxBoardPos(), sentiment.getThermal(), sentiment.getRegime());

        // ---- S4 主线龙头 ----
        MainlineResult result = mainlineCalculator.compute(tradeDate, ups);
        mainlineDailyMapper.insertBatch(result.getMainlines());
        leaderPoolDailyMapper.insertBatch(result.getLeaders());
        log.info("[S4] 主线 {} 条, 龙头 {} 只 写入完成", result.getMainlines().size(), result.getLeaders().size());

        // ---- S7 炒作因子 ----
        themeFactorCalculator.compute(tradeDate);

        log.info("====== 复盘计算结束: {} ======", tradeDate);
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
