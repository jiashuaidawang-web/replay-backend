package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.service.FundFlowService;
import com.dunwugudao.replay.vo.DragonTigerVO;
import com.dunwugudao.replay.vo.DtDetailVO;
import com.dunwugudao.replay.vo.FundFlowBoardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * S3 主力博弈 · 板块资金流向 + 龙虎榜。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FundFlowController {

    private final FundFlowService fundFlowService;

    @GetMapping("/fund-flow/board")
    public List<FundFlowBoardVO> board(@RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                       @RequestParam(required = false, defaultValue = "main_net") String orderBy,
                                       @RequestParam(required = false, defaultValue = "10") int top) {
        return fundFlowService.boardFlow(date, orderBy, top);
    }

    @GetMapping("/fund-flow/dragon-tiger")
    public List<DragonTigerVO> dragonTiger(@RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return fundFlowService.dragonTiger(date);
    }

    @GetMapping("/fund-flow/dragon-tiger/detail")
    public List<DtDetailVO> detail(@RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam String tsCode) {
        return fundFlowService.dtDetail(date, tsCode);
    }
}
