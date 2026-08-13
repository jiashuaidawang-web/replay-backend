package com.dunwugudao.replay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;

/**
 * 顿悟股道复盘系统 —— 计算层。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只读爬虫写入的原始层（stock_daily / limit_up_pool / board_daily /
 *       stock_board_rel / main_fund_flow …），产出计算层指标表
 *       （sentiment_daily / mainline_daily / leader_pool_daily …）。</li>
 *   <li>分析数据「全部」走 ClickHouse；openGauss 仅用于分布式任务行记录。</li>
 *   <li>不做爬取、不改原始层。爬虫工程独立演进（含 ClickHouse 迁移），本工程只消费其产出。</li>
 * </ul>
 *
 * <p>数据源/MyBatis 全部手动装配（DataSourceConfig + OgMybatisConfig + CkMybatisConfig），
 * 故排除 Spring Boot 的自动配置，避免与双数据源冲突。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        MybatisAutoConfiguration.class
})
public class ReplayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReplayApplication.class, args);
    }
}
