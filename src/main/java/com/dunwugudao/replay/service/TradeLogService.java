package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.ck.TradeLogCk;
import com.dunwugudao.replay.mapper.ck.TradeLogCkMapper;
import com.dunwugudao.replay.vo.DisciplineSummaryVO;
import com.dunwugudao.replay.vo.TradeLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * S8 交易心法 · 个人交易日志。
 *
 * <p>数据落在 ClickHouse（crawler.trade_log，2026-08-19 由 openGauss 迁入）：
 * <ul>
 *   <li><b>读</b>：{@link TradeLogCkMapper}（FINAL），返回时实时用 {@link DisciplineCalculator}
 *       量化每笔纪律分（个人复盘数据量小、主观标签为主，不另存计算表）；</li>
 *   <li><b>写</b>：{@link CkHttpWriter} 裸 HTTP 直写（避开 JDBC 静默丢；单条 POST 同样走可靠路径），
 *       id 由应用层生成 UUID，created_at 由 CK DEFAULT now() 填充。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeLogService {

    /** 插入列清单（不含 created_at/_ver：created_at 走 DEFAULT now()，_ver 为 MATERIALIZED）。 */
    private static final List<String> INSERT_COLS = List.of(
            "id", "trade_date", "ts_code", "side", "price", "qty", "reason", "emotion_tag", "reaction");

    private final TradeLogCkMapper tradeLogCkMapper;
    private final CkHttpWriter ckHttpWriter;
    private final DisciplineCalculator disciplineCalculator;

    /** 列表查询（按区间/心态标签），并填充每笔纪律分。 */
    public List<TradeLogVO> list(LocalDate from, LocalDate to, String emotionTag) {
        return tradeLogCkMapper.selectByRange(from, to, emotionTag).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    /** 写入一笔交易记录（CK 无事务；写后不回表，直接回填 id/createdAt/纪律分）。 */
    public TradeLogVO create(TradeLogVO vo) {
        TradeLogCk e = new TradeLogCk();
        e.setId(UUID.randomUUID().toString());
        e.setTradeDate(vo.getTradeDate());
        e.setTsCode(vo.getTsCode());
        e.setSide(vo.getSide());
        e.setPrice(vo.getPrice());
        e.setQty(vo.getQty());
        e.setReason(vo.getReason());
        e.setEmotionTag(vo.getEmotionTag());
        e.setReaction(vo.getReaction());
        ckHttpWriter.insert("trade_log", INSERT_COLS, List.<Object[]>of(toRow(e)));
        vo.setId(e.getId());
        vo.setCreatedAt(OffsetDateTime.now(ZoneId.systemDefault()));
        DisciplineCalculator.DisciplineScore s = disciplineCalculator.score(vo);
        vo.setDisciplineScore(s.getTotal());
        vo.setViolations(s.getViolations());
        return vo;
    }

    /** 纪律评分汇总（GET /trade-log/discipline）。 */
    public DisciplineSummaryVO discipline(LocalDate from, LocalDate to, String emotionTag) {
        List<TradeLogCk> rows = tradeLogCkMapper.selectByRange(from, to, emotionTag);
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

    private Object[] toRow(TradeLogCk e) {
        return new Object[]{
                e.getId(), e.getTradeDate(), e.getTsCode(), e.getSide(),
                e.getPrice(), e.getQty(), e.getReason(), e.getEmotionTag(), e.getReaction()
        };
    }

    private TradeLogVO toVo(TradeLogCk e) {
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
        v.setCreatedAt(e.getCreatedAt() != null
                ? e.getCreatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime() : null);
        DisciplineCalculator.DisciplineScore s = disciplineCalculator.score(v);
        v.setDisciplineScore(s.getTotal());
        v.setViolations(s.getViolations());
        return v;
    }
}
