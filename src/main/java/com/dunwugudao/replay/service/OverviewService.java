package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.MainlineDaily;
import com.dunwugudao.replay.entity.SentimentDaily;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * S1 大势择时 · 总览看板聚合。
 *
 * <p>当前：情绪维度由 sentiment_daily.thermal 回填（/100 → 0~1）；技术/资金/政策维度与牛熊周期
 * 待 S1 计算层实现后填充，未计算时为 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverviewService {

    private final CommonService commonService;
    private final SentimentDailyMapper sentimentDailyMapper;
    private final MainlineDailyMapper mainlineDailyMapper;

    public OverviewVO build(LocalDate date) {
        LocalDate d = commonService.resolveDate(date, "limit_up_pool");
        OverviewVO vo = new OverviewVO();
        vo.setTradeDate(d);

        SentimentDaily s = sentimentDailyMapper.selectByTradeDate(d);
        FourDimVO four = new FourDimVO();
        if (s != null && s.getThermal() != null) {
            four.setSentiment(s.getThermal().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        }
        vo.setFourDim(four);
        vo.setCycle(new CycleVO()); // S1 周期判定未实现
        vo.setRegime(s != null ? s.getRegime() : null);
        vo.setThermal(s != null ? s.getThermal() : null);

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
