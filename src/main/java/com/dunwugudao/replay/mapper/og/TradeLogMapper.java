package com.dunwugudao.replay.mapper.og;

import com.dunwugudao.replay.entity.og.TradeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 个人交易日志（openGauss）—— 数据落在 og 数据源（由 OgMybatisConfig 扫描本包）。
 */
@Mapper
public interface TradeLogMapper {

    /** 写入一笔交易记录，回填自增 id。 */
    int insert(TradeLog log);

    /** 按日期区间 + 心态标签筛选，倒序。 */
    List<TradeLog> selectByRange(@Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 @Param("emotionTag") String emotionTag);
}
