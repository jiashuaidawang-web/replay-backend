package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.service.OverviewService;
import com.dunwugudao.replay.vo.OverviewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * S1 大势择时 · 总览看板。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OverviewController {

    private final OverviewService overviewService;

    @GetMapping("/overview")
    public OverviewVO overview(@RequestParam(required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return overviewService.build(date);
    }
}
