package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.mapper.ck.LimitDownPoolMapper;
import com.dunwugudao.replay.mapper.ck.LimitUpPoolMapper;
import com.dunwugudao.replay.mapper.ck.SentimentDailyMapper;
import com.dunwugudao.replay.vo.LimitPoolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * S2 情绪资金 · 情绪温度 + 涨跌停池。
 */
@Service
@RequiredArgsConstructor
public class SentimentService {

    private final CommonService commonService;
    private final SentimentDailyMapper sentimentDailyMapper;
    private final LimitUpPoolMapper limitUpPoolMapper;
    private final LimitDownPoolMapper limitDownPoolMapper;

    public SentimentDaily sentiment(LocalDate date) {
        LocalDate d = commonService.resolveDate(date, "limit_up_pool");
        return sentimentDailyMapper.selectByTradeDate(d);
    }

    public List<LimitPoolVO> limitPool(LocalDate date, String type, Integer minPos) {
        LocalDate d = commonService.resolveDate(date, "limit_up_pool");
        List<LimitPoolVO> list;
        if ("limit_down".equalsIgnoreCase(type)) {
            list = limitDownPoolMapper.selectEnrichedDown(d);
        } else {
            list = limitUpPoolMapper.selectEnrichedUp(d);
        }
        if (minPos != null && minPos > 1) {
            final int mp = minPos;
            list = list.stream()
                    .filter(v -> v.getBoardPos() != null && v.getBoardPos() >= mp)
                    .toList();
        }
        return list;
    }
}
