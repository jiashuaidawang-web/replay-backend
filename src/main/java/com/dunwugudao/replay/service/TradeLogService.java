package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.og.TradeLog;
import com.dunwugudao.replay.mapper.og.TradeLogMapper;
import com.dunwugudao.replay.vo.DisciplineSummaryVO;
import com.dunwugudao.replay.vo.TradeLogVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S8 交易心法 · 个人交易日志。
 *
 * <p>数据落在 openGauss（og 数据源）：读/写 trade_log 表，并在返回时实时用
 * {@link DisciplineCalculator} 量化每笔纪律分，不另存计算表（个人复盘数据量小、主观标签为主）。
 */
@Service
@RequiredArgsConstructor
public class TradeLogService {

    private final TradeLogMapper tradeLogMapper;
    private final DisciplineCalculator disciplineCalculator;

    /** 列表查询（按区间/心态标签），并填充每笔纪律分。 */
    public List<TradeLogVO> list(LocalDate from, LocalDate to, String emotionTag) {
        return tradeLogMapper.selectByRange(from, to, emotionTag).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    /** 写入一笔交易记录（OG 事务），回填 id 与纪律分。 */
    @Transactional
    public TradeLogVO create(TradeLogVO vo) {
        TradeLog e = new TradeLog();
        e.setTradeDate(vo.getTradeDate());
        e.setTsCode(vo.getTsCode());
        e.setSide(vo.getSide());
        e.setPrice(vo.getPrice());
        e.setQty(vo.getQty());
        e.setReason(vo.getReason());
        e.setEmotionTag(vo.getEmotionTag());
        e.setReaction(vo.getReaction());
        tradeLogMapper.insert(e);
        vo.setId(e.getId());
        vo.setCreatedAt(e.getCreatedAt());
        DisciplineCalculator.DisciplineScore s = disciplineCalculator.score(vo);
        vo.setDisciplineScore(s.getTotal());
        vo.setViolations(s.getViolations());
        return vo;
    }

    /** 纪律评分汇总（GET /trade-log/discipline）。 */
    public DisciplineSummaryVO discipline(LocalDate from, LocalDate to, String emotionTag) {
        List<TradeLog> rows = tradeLogMapper.selectByRange(from, to, emotionTag);
        DisciplineSummaryVO summary = new DisciplineSummaryVO();
        summary.setCount(rows.size());
        if (rows.isEmpty()) {
            summary.setTotalAvg(0);
            summary.setDimAvg(Collections.emptyMap());
            summary.setTopViolations(Collections.emptyList());
            summary.setSamples(Collections.emptyList());
            return summary;
        }

        List<TradeLogVO> vos = rows.stream().map(this::toVo).collect(Collectors.toList());
        int total = vos.stream().mapToInt(TradeLogVO::getDisciplineScore).sum();
        summary.setTotalAvg((int) Math.round((double) total / vos.size()));

        // 六维度均值
        Map<String, Integer> dimSum = new LinkedHashMap<>();
        Map<String, Integer> dimCount = new LinkedHashMap<>();
        Map<String, Integer> violationCount = new java.util.HashMap<>();
        for (TradeLogVO v : vos) {
            DisciplineCalculator.DisciplineScore s = disciplineCalculator.score(v);
            s.getDims().forEach((k, val) -> {
                dimSum.merge(k, val, Integer::sum);
                dimCount.merge(k, 1, Integer::sum);
            });
            for (String viol : s.getViolations()) {
                violationCount.merge(viol, 1, Integer::sum);
            }
        }
        Map<String, Integer> dimAvg = new LinkedHashMap<>();
        dimSum.forEach((k, sum) -> dimAvg.put(k, (int) Math.round((double) sum / dimCount.get(k))));
        summary.setDimAvg(dimAvg);

        List<String> top = violationCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        summary.setTopViolations(top);

        // 近 10 笔样本（selectByRange 已倒序）
        summary.setSamples(vos.stream().limit(10).collect(Collectors.toList()));
        return summary;
    }

    private TradeLogVO toVo(TradeLog e) {
        TradeLogVO v = new TradeLogVO();
        v.setId(e.getId());
        v.setTradeDate(e.getTradeDate());
        v.setTsCode(e.getTsCode());
        v.setSide(e.getSide());
        v.setPrice(e.getPrice());
        v.setQty(e.getQty());
        v.setReason(e.getReason());
        v.setEmotionTag(e.getEmotionTag());
        v.setReaction(e.getReaction());
        v.setCreatedAt(e.getCreatedAt());
        DisciplineCalculator.DisciplineScore s = disciplineCalculator.score(v);
        v.setDisciplineScore(s.getTotal());
        v.setViolations(s.getViolations());
        return v;
    }
}
