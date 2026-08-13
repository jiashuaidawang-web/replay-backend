package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.service.ThemeService;
import com.dunwugudao.replay.vo.ThemeFactorVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * S7 炒作思维 · 题材炒作因子（按综合分降序返回题材库；boardCode 非空返回单题材明细）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ThemeController {

    private final ThemeService themeService;

    @GetMapping("/theme/factor")
    public List<ThemeFactorVO> factor(@RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam(required = false) String boardCode) {
        return themeService.factor(date, boardCode);
    }
}
