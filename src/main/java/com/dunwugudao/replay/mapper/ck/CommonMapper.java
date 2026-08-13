package com.dunwugudao.replay.mapper.ck;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 通用查询（ClickHouse）。仅提供"取某表最新交易日"——动态表名经 XML 白名单
 * {@code <choose>} 限定，杜绝 SQL 注入。
 */
@Mapper
public interface CommonMapper {

    /** 取指定表的最新交易日；表名不在白名单时回退 limit_up_pool。 */
    LocalDate latestTradeDate(@Param("table") String table);
}
