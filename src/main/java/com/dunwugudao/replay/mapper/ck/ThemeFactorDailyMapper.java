package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ThemeFactorDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日题材炒作因子产出（ClickHouse: theme_factor_daily，ReplacingMergeTree：ORDER BY (trade_date, board_code) + _ver）。
 * 按交易日幂等重算：直接 INSERT，引擎按 _ver 保留最新。
 */
@Mapper
public interface ThemeFactorDailyMapper {

    int insertBatch(@Param("list") List<ThemeFactorDaily> list);

    /** 读某交易日全部题材因子（按 total 降序）。 */
    List<ThemeFactorDaily> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 读某交易日某题材因子（明细下钻用）。 */
    ThemeFactorDaily selectByTradeDateAndBoard(@Param("tradeDate") LocalDate tradeDate,
                                               @Param("boardCode") String boardCode);
}
