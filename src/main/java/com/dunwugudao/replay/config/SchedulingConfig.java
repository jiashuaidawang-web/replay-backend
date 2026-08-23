package com.dunwugudao.replay.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring @Scheduled 调度。
 *
 * <p>实时层（Redis Streams 归档刷新 / 特征计算 / 缓冲落库）全部依赖定时任务，
 * 缺少本注解时 {@code @Scheduled} 方法不会执行（历史工程无任何定时任务，故此前未开）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
