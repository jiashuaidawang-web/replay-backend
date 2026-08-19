package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.job.ReplayCalcJob;
import com.dunwugudao.replay.service.ConceptDeriveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 运维 · 复盘重算触发。部署后补爬数据或修正算法时，手动对指定日期（或最新交易日）重跑全套计算。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ReplayCalcJob replayCalcJob;
    private final ConceptDeriveService conceptDeriveService;

    /** 重算指定交易日（默认最新交易日）。各 skill 写入已内置「写后校验 + 静默丢自愈」
     *  （verifyAndRepair：逐表核查服务端行数，缺失则换新建连接重跑该 skill，循环到补齐），
     *  故此处单次 run 即可保证计算层完整落库，无需外层多轮重试。各写入幂等（RMT 折叠）。 */
    @PostMapping("/recalc")
    public String recalc(@RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date != null) {
            replayCalcJob.run(date);
            return "recalc done: " + date;
        }
        replayCalcJob.runForLatest();
        return "recalc done: latest";
    }

    /** 强制重派生 concept 表（题材静态属性）。concept 与交易日无关，惰性重算下已有数据则跳过，
     *  需刷新分类规则或 board_basic 更新后调用本接口。 */
    @PostMapping("/derive-concepts")
    public String deriveConcepts() {
        int n = conceptDeriveService.derive();
        return "derive concepts done: " + n + " rows";
    }

    /** 仅重算 S4 主线龙头 + 妖/独狼（轻量，不动其余 skill）。算法升级后刷新 leader_pool_daily 用。 */
    @PostMapping("/recalc-mainline")
    public String recalcMainline(@RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            return "recalc-mainline: need ?date=YYYY-MM-DD";
        }
        int rounds = 3;
        for (int i = 1; i <= rounds; i++) {
            try {
                replayCalcJob.recalcMainline(date);
                log.info("recalc-mainline 完成第 {} 轮: {}", i, date);
            } catch (Exception e) {
                log.warn("recalc-mainline 第 {} 轮异常（将重试）: {} - {}", i, date, e.getMessage());
            }
            if (i < rounds) {
                try { Thread.sleep(30000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return "recalc-mainline done (with retry): " + date;
    }
}
