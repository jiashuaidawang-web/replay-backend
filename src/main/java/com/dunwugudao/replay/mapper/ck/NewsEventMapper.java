package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ck.raw.NewsEventRaw;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 新闻/政策事件（ClickHouse · news_event）。S1 政策维度读取政策类事件情感分。
 *
 * <p>由 CkMybatisConfig 扫描本包，绑定 ck 数据源。news_event 为 MergeTree（非 RMT），无版本列，直接读取即可。
 */
@Mapper
public interface NewsEventMapper {

    /**
     * 取指定交易日当日的政策类事件（is_policy=1），用于 S1 政策维度聚合情感分。
     * 返回按时间升序。
     */
    List<NewsEventRaw> selectPolicyOn(@Param("tradeDate") LocalDate tradeDate);
}
