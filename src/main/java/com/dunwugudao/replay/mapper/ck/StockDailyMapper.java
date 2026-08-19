package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.vo.StockClose;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * stock_daily 只读查询（S3 计算主力买入后 N 日收益用）。
 */
@Mapper
public interface StockDailyMapper {

    /** 指定股票、从某日起（含）的收盘价序列，按股票+日期升序。 */
    List<StockClose> selectCloses(@Param("tsCodes") List<String> tsCodes,
                                   @Param("fromDate") LocalDate fromDate);
}
