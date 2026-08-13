package com.dunwugudao.replay.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动触发：应用启动后对最新可用交易日跑一轮复盘计算（便于端到端验证）。
 *
 * <p>可通过 {@code replay.calc.on-startup=false} 关闭，改为手动/调度触发。
 * 计算异常被捕获并记录，不阻止应用启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplayStartupRunner implements CommandLineRunner {

    private final ReplayCalcJob replayCalcJob;

    @Value("${replay.calc.on-startup:true}")
    private boolean onStartup;

    @Override
    public void run(String... args) {
        if (!onStartup) {
            log.info("replay.calc.on-startup=false，跳过启动计算");
            return;
        }
        try {
            replayCalcJob.runForLatest();
        } catch (Exception e) {
            log.error("启动复盘计算失败（不影响应用运行）: {}", e.getMessage(), e);
        }
    }
}
