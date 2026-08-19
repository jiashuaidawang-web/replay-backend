package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ck.raw.IndexDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 指数日线（S1 技术维度）。源表 index_daily 为 ReplacingMergeTree，读取统一加 FINAL。
 */
@Mapper
public interface IndexDailyMapper {

    /**
     * 取所有指数在 [任意历史, tradeDate] 窗口内的全部日线棒（FINAL），
     * 用于在 Java 侧按指数分组计算：当日涨跌（用收盘价推算，避免 pct_chg 口径偏差）、
     * 短周期均线位置。一次性取回，避免逐指数查询。
     */
    List<IndexDaily> selectOnOrBefore(@Param("tradeDate") LocalDate tradeDate);
}
