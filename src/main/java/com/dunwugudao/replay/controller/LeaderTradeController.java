package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.service.LeaderTradeService;
import com.dunwugudao.replay.vo.LeaderTradeIdeaVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * S5 龙头买卖 · 基于龙头池派生买卖建议。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LeaderTradeController {

    private final LeaderTradeService leaderTradeService;

    @GetMapping("/leader/trade-idea")
    public List<LeaderTradeIdeaVO> tradeIdea(@RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return leaderTradeService.tradeIdea(date);
    }
}
