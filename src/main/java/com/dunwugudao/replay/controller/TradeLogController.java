package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.service.TradeLogService;
import com.dunwugudao.replay.vo.DisciplineSummaryVO;
import com.dunwugudao.replay.vo.TradeLogVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * S8 交易心法 · 个人交易日志。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TradeLogController {

    private final TradeLogService tradeLogService;

    @GetMapping("/trade-log")
    public List<TradeLogVO> list(@RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String emotionTag) {
        return tradeLogService.list(from, to, emotionTag);
    }

    @PostMapping("/trade-log")
    public TradeLogVO create(@RequestBody TradeLogVO vo) {
        return tradeLogService.create(vo);
    }

    /** 纪律评分汇总（六维度 + 高频违规 + 近 10 笔样本）。 */
    @GetMapping("/trade-log/discipline")
    public DisciplineSummaryVO discipline(@RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) String emotionTag) {
        return tradeLogService.discipline(from, to, emotionTag);
    }
}
