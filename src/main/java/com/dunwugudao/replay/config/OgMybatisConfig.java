package com.dunwugudao.replay.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * openGauss MyBatis 配置（@Primary）。
 *
 * <p>职责：扫描 {@code com.dunwugudao.replay.mapper.og} 下的 Mapper，
 * 仅服务于分布式任务行记录（replay_calc_task 等）。
 *
 * <p>注意：OG 支持事务，这里提供了标准的 DataSourceTransactionManager 并标注 @Primary，
 * 使未显式指定事务管理器的 {@code @Transactional} 默认落到 OG。
 */
@Configuration
@MapperScan(
        basePackages = "com.dunwugudao.replay.mapper.og",
        sqlSessionFactoryRef = "ogSqlSessionFactory",
        sqlSessionTemplateRef = "ogSqlSessionTemplate"
)
public class OgMybatisConfig {

    @Primary
    @Bean(name = "ogSqlSessionFactory")
    public SqlSessionFactory ogSqlSessionFactory(@Qualifier("ogDataSource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/og/*.xml"));
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
        cfg.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(cfg);
        return bean.getObject();
    }

    @Primary
    @Bean(name = "ogTransactionManager")
    public DataSourceTransactionManager ogTransactionManager(@Qualifier("ogDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Primary
    @Bean(name = "ogSqlSessionTemplate")
    public SqlSessionTemplate ogSqlSessionTemplate(@Qualifier("ogSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
