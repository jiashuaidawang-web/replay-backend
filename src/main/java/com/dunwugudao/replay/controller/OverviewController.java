package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.service.OverviewService;
import com.dunwugudao.replay.vo.OverviewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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

    /**
     * 总览看板：核心数据（四维/情绪/主线）全无的日期返回 <b>204 No Content</b>，
     * 避免此前"200 全 null 假看板"。
     */
    @GetMapping("/overview")
    public ResponseEntity<OverviewVO> overview(@RequestParam(required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        OverviewVO vo = overviewService.build(date);
        return vo != null ? ResponseEntity.ok(vo) : ResponseEntity.noContent().build();
    }
}
