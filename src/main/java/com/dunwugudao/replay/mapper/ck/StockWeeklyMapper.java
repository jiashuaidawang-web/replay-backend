package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ck.raw.StockWeekly;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StockWeeklyMapper {

    /**
     * 取每个股票在 [fromDate, toDate] 窗口内的全部周线棒，用于在 Java 侧计算八大特征。
     * 选用 60 周窗口（约 420 天）：足以支撑 MA30 + 52 周高低点 + RSI14。
     */
    List<StockWeekly> selectBarsBetween(@Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate);
}
