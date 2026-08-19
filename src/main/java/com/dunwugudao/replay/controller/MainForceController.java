package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.entity.MainForceDaily;
import com.dunwugudao.replay.service.MainForceService;
import com.dunwugudao.replay.vo.MainForceSeatVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * S3 主力博弈 · 接口。
 *
 * <p>GET /api/v1/main-force/stocks  个股级主力合力（合力强度/分歧/主力次日胜·被埋）。
 * GET /api/v1/main-force/seats   抱团席位（同一席位跨多股净买，体现合力）。
 * 原始龙虎榜与席位明细仍走 /api/v1/fund-flow/dragon-tiger(+ /detail)。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MainForceController {

    private final MainForceService mainForceService;

    @GetMapping("/main-force/stocks")
    public List<MainForceDaily> stocks(@RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return mainForceService.stocks(date);
    }

    @GetMapping("/main-force/seats")
    public List<MainForceSeatVO> seats(@RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return mainForceService.seats(date);
    }
}
