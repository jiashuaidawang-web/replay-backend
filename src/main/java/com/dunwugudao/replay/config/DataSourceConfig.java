package com.dunwugudao.replay.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 双数据源定义。
 *
 * <p>架构边界（用户决策）：
 * <ul>
 *   <li><b>og</b>（openGauss, {@code @Primary}）—— 仅支撑「分布式任务行记录」
 *       （replay_calc_task 等），不参与任何股票分析数据的读写。</li>
 *   <li><b>ch</b>（ClickHouse）—— 业务数据 / 股票分析数据「全部」走 CK：
 *       读爬虫写入的原始层（stock_daily / limit_up_pool / board_daily …），
 *       写计算层产出（sentiment_daily / mainline_daily / leader_pool_daily …）。</li>
 * </ul>
 *
 * <p>MyBatis 的 SqlSessionFactory / Mapper 扫描分别在
 * {@link OgMybatisConfig} 与 {@link CkMybatisConfig} 中按包隔离，
 * 因此这里只负责把两个 DataSource 造出来。
 */
@Configuration
public class DataSourceConfig {

    @Primary
    @Bean(name = "ogDataSource")
    @ConfigurationProperties("spring.datasource.og")
    public DataSource ogDataSource() {
        return new HikariDataSource();
    }

    @Bean(name = "ckDataSource")
    @ConfigurationProperties("spring.datasource.ch")
    public DataSource ckDataSource() {
        return new HikariDataSource();
    }
}
