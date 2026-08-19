package com.dunwugudao.replay.config;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * ClickHouse MyBatis 配置（非 Primary）。
 *
 * <p>职责：扫描 {@code com.dunwugudao.replay.mapper.ck} 下的 Mapper，
 * 承载「全部」股票分析数据：读爬虫原始层 + 写计算层产出。
 *
 * <p>ClickHouse 关键约束：
 * <ul>
 *   <li>不支持事务。数据源已设 {@code auto-commit=true}，且 CK 写入 Service 一律用
 *       {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}，
 *       避免 Spring 事务管理器触发 {@code setAutoCommit(false)} 导致驱动抛异常。</li>
 *   <li>写入用 BATCH Executor + JDBC 参数 {@code rewrite_batch_inserts=true}，
 *       把多行 INSERT 合并为单条多值语句，吞吐更高。</li>
 *   <li>查询建议带 {@code FINAL}（ReplacingMergeTree）以读到合并后的最终行；
 *       或读前先 OPTIMIZE，本工程统一在 Mapper 里用 FINAL。</li>
 * </ul>
 */
@Configuration
@MapperScan(
        basePackages = "com.dunwugudao.replay.mapper.ck",
        sqlSessionFactoryRef = "ckSqlSessionFactory",
        sqlSessionTemplateRef = "ckSqlSessionTemplate"
)
public class CkMybatisConfig {

    @Bean(name = "ckSqlSessionFactory")
    public SqlSessionFactory ckSqlSessionFactory(@Qualifier("ckDataSource") DataSource dataSource,
                                                 CkRetryInterceptor ckRetryInterceptor) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/ck/*.xml"));
        bean.setPlugins(new org.apache.ibatis.plugin.Interceptor[]{ckRetryInterceptor});
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
        cfg.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(cfg);
        return bean.getObject();
    }

    /**
     * CK 不支持事务，这里仍提供事务管理器仅用于 SqlSessionTemplate 装配；
     * 由于数据源 auto-commit=true 且写入方法声明 NOT_SUPPORTED，不会被真正用于开事务。
     */
    @Bean(name = "ckTransactionManager")
    public DataSourceTransactionManager ckTransactionManager(@Qualifier("ckDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "ckSqlSessionTemplate")
    public SqlSessionTemplate ckSqlSessionTemplate(@Qualifier("ckSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory, ExecutorType.BATCH);
    }
}
