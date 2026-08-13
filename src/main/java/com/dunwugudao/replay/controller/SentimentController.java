package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.service.SentimentService;
import com.dunwugudao.replay.vo.LimitPoolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * S2 情绪资金 · 情绪温度 + 涨跌停池。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SentimentController {

    private final SentimentService sentimentService;

    @GetMapping("/sentiment")
    public SentimentDaily sentiment(@RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return sentimentService.sentiment(date);
    }

    @GetMapping("/limit-pool")
    public List<LimitPoolVO> limitPool(@RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) Integer minPos) {
        return sentimentService.limitPool(date, type, minPos);
    }
}
