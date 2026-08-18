package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.service.TrendService;
import com.dunwugudao.replay.vo.TrendScanVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * S6 趋势战法 · 趋势股扫描 / 领涨股监控。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TrendController {

    private final TrendService trendService;

    @GetMapping("/trend/scan")
    public List<TrendScanVO> scan(@RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  @RequestParam(required = false, defaultValue = "6") int minFeature) {
        return trendService.scan(date, minFeature);
    }

    @GetMapping("/trend/leading")
    public List<TrendScanVO> leading(@RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @RequestParam(required = false, defaultValue = "4") int minFeature) {
        return trendService.leading(date, minFeature);
    }
}
