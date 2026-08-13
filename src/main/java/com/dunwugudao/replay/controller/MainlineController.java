package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.service.MainlineService;
import com.dunwugudao.replay.vo.LeaderVO;
import com.dunwugudao.replay.vo.MainlineVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * S4 板学寻龙 · 主线列表 + 龙头池。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MainlineController {

    private final MainlineService mainlineService;

    @GetMapping("/mainline")
    public List<MainlineVO> mainline(@RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return mainlineService.mainline(date);
    }

    @GetMapping("/leaders")
    public List<LeaderVO> leaders(@RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  @RequestParam(required = false) String boardCode) {
        return mainlineService.leaders(date, boardCode);
    }
}
