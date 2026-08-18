package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.TrendCandidateDaily;
import com.dunwugudao.replay.mapper.ck.TrendCandidateDailyMapper;
import com.dunwugudao.replay.vo.TrendScanVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * S6 趋势战法 · 趋势股扫描。读 trend_candidate_daily FINAL，按 feature_hit 过滤、综合排序。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendService {

    private final TrendCandidateDailyMapper trendCandidateDailyMapper;

    public List<TrendScanVO> scan(LocalDate date, Integer minFeature) {
        LocalDate d = (date != null) ? date : trendCandidateDailyMapper.selectLatestDate();
        if (d == null) return List.of();
        int min = (minFeature != null) ? minFeature : 6;
        List<TrendCandidateDaily> rows = trendCandidateDailyMapper.selectByTradeDate(d);
        return rows.stream()
                .filter(r -> (r.getFeatureHit() != null ? r.getFeatureHit() : 0) >= min)
                .sorted(Comparator.comparing(TrendCandidateDaily::getFeatureHit, Comparator.reverseOrder())
                        .thenComparing(r -> r.getRsVsIndex() == null ? java.math.BigDecimal.ZERO : r.getRsVsIndex(), Comparator.reverseOrder()))
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    /** 领涨股监控：自大底涨幅最高的趋势候选（先于指数/板块走出赚钱行情）。 */
    public List<TrendScanVO> leading(LocalDate date, Integer minFeature) {
        LocalDate d = (date != null) ? date : trendCandidateDailyMapper.selectLatestDate();
        if (d == null) return List.of();
        int min = (minFeature != null) ? minFeature : 4;
        List<TrendCandidateDaily> rows = trendCandidateDailyMapper.selectByTradeDate(d);
        return rows.stream()
                .filter(r -> (r.getFeatureHit() != null ? r.getFeatureHit() : 0) >= min)
                .sorted(Comparator.comparing(r -> r.getGainFromBottom() == null ? java.math.BigDecimal.ZERO : r.getGainFromBottom(), Comparator.reverseOrder()))
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    private TrendScanVO toVO(TrendCandidateDaily r) {
        TrendScanVO v = new TrendScanVO();
        v.setTsCode(r.getTsCode());
        v.setStockName(r.getStockName());
        v.setFeatureHit(r.getFeatureHit());
        v.setRsVsIndex(r.getRsVsIndex());
        v.setConfirmed(r.getConfirmed());
        v.setFMa(r.getFMa());
        v.setFShape(r.getFShape());
        v.setFVol(r.getFVol());
        v.setFSmallcap(r.getFSmallcap());
        v.setFRs(r.getFRs());
        v.setFRsi(r.getFRsi());
        v.setFWeekly(r.getFWeekly());
        v.setFBreak(r.getFBreak());
        v.setGainFromBottom(r.getGainFromBottom());
        v.setClosePrice(r.getClosePrice());
        v.setRsi(r.getRsi());
        v.setMa10(r.getMa10());
        v.setMa30(r.getMa30());
        // hitFeatures 为瞬态字段，未落库；从八大特征标志重建，保证接口层有值。
        List<String> names = new ArrayList<>();
        if (isOne(r.getFMa())) names.add("长期均线多头发散");
        if (isOne(r.getFShape())) names.add("底部抬高图形");
        if (isOne(r.getFVol())) names.add("量价健康");
        if (isOne(r.getFRs())) names.add("RS领涨");
        if (isOne(r.getFRsi())) names.add("RSI突破70");
        if (isOne(r.getFWeekly())) names.add("周线确认");
        if (isOne(r.getFBreak())) names.add("平台突破");
        if (isOne(r.getConfirmed())) names.add("趋势成立");
        v.setHitFeatures(names);
        return v;
    }

    private static boolean isOne(Integer v) { return v != null && v == 1; }
}
