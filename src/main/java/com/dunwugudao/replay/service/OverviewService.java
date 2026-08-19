package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.MainlineDaily;
import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.mapper.ck.FourDimensionDailyMapper;
import com.dunwugudao.replay.mapper.ck.MainlineDailyMapper;
import com.dunwugudao.replay.mapper.ck.SentimentDailyMapper;
import com.dunwugudao.replay.vo.CycleVO;
import com.dunwugudao.replay.vo.FourDimVO;
import com.dunwugudao.replay.vo.MainlineVO;
import com.dunwugudao.replay.vo.OverviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * S1 大势择时 · 总览看板聚合。
 *
 * <p>四维度（tech/情绪/资金/政策/composite）与牛熊周期（phase/absolute/relative）、
 * 策略建议（suggestion）、数据口径（note）均取自 four_dimension_daily（由 S1 计算层落库）。
 * 情绪温度/区间仍复用 sentiment_daily。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverviewService {

    private final CommonService commonService;
    private final SentimentDailyMapper sentimentDailyMapper;
    private final FourDimensionDailyMapper fourDimensionDailyMapper;
    private final MainlineDailyMapper mainlineDailyMapper;

    public OverviewVO build(LocalDate date) {
        LocalDate d = commonService.resolveDate(date, "limit_up_pool");
        OverviewVO vo = new OverviewVO();
        vo.setTradeDate(d);

        // ---- S1 四维度 + 周期 ----
        com.dunwugudao.replay.entity.FourDimensionDaily fd = fourDimensionDailyMapper.selectByTradeDate(d);
        FourDimVO four = new FourDimVO();
        CycleVO cycle = new CycleVO();
        if (fd != null) {
            four.setTech(fd.getTech());
            four.setSentiment(fd.getSentiment());
            four.setFund(fd.getFund());
            four.setPolicy(fd.getPolicy());
            four.setComposite(fd.getComposite());
            cycle.setPhase(fd.getPhase());
            cycle.setAbsolute(fd.getAbsolute());
            cycle.setRelative(fd.getRelative());
            vo.setWorthTrade(fd.getWorthTrade());
            vo.setSuggestion(fd.getSuggestion());
            vo.setNote(fd.getNote());
        }
        vo.setFourDim(four);
        vo.setCycle(cycle);

        // ---- 情绪温度/区间（S2）----
        SentimentDaily s = sentimentDailyMapper.selectByTradeDate(d);
        vo.setRegime(s != null ? s.getRegime() : null);
        vo.setThermal(s != null ? s.getThermal() : null);

        // ---- 主线概览（S4）----
        List<MainlineDaily> mains = mainlineDailyMapper.selectByTradeDate(d);
        Map<String, String> names = commonService.boardNameMap(
                mains.stream().map(MainlineDaily::getBoardCode).toList());
        List<MainlineVO> mainlineVos = mains.stream().map(m -> {
            MainlineVO v = new MainlineVO();
            v.setBoardCode(m.getBoardCode());
            v.setBoardName(names.get(m.getBoardCode()));
            v.setMainLevel(m.getMainLevel());
            v.setStrength(m.getStrength());
            v.setRank(m.getRank());
            return v;
        }).toList();
        vo.setTopMainline(mainlineVos.stream().limit(5).toList());
        return vo;
    }
}
