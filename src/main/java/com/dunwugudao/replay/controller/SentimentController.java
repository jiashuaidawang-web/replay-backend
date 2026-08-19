package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.entity.SentimentDaily;
import com.dunwugudao.replay.service.SentimentService;
import com.dunwugudao.replay.vo.LimitPoolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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

    /**
     * 情绪温度：目标交易日无数据时返回 <b>204 No Content</b>（统一"无数据日期"语义，
     * 避免此前 200 空 body 导致前端 {@code res.json()} 崩溃）。
     */
    @GetMapping("/sentiment")
    public ResponseEntity<SentimentDaily> sentiment(@RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        SentimentDaily s = sentimentService.sentiment(date);
        return s != null ? ResponseEntity.ok(s) : ResponseEntity.noContent().build();
    }

    @GetMapping("/limit-pool")
    public List<LimitPoolVO> limitPool(@RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) Integer minPos) {
        return sentimentService.limitPool(date, type, minPos);
    }
}
