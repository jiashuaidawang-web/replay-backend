package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ck.TradeLogCk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 个人交易日志（ClickHouse）—— 数据落在 ck 数据源（crawler.trade_log）。
 *
 * <p>读路径：RMT 表必须 FINAL；写路径：不走本 Mapper（避免 JDBC 静默丢），
 * 由 {@code CkHttpWriter} 裸 HTTP 直写。
 */
@Mapper
public interface TradeLogCkMapper {

    /** 按日期区间 + 心态标签筛选，倒序。 */
    List<TradeLogCk> selectByRange(@Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   @Param("emotionTag") String emotionTag);
}
