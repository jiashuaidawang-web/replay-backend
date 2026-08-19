package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.FourDimensionDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * S1 四维度日级产出（ClickHouse）。计算层写入 + 读取都走 CK。
 *
 * <p>表为 ReplacingMergeTree（ORDER BY trade_date + _ver 版本列）。重算时直接 INSERT，
 * 引擎按 _ver 保留最新版本；读取统一加 FINAL。
 */
@Mapper
public interface FourDimensionDailyMapper {

    int insertBatch(@Param("list") List<FourDimensionDaily> list);

    FourDimensionDaily selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);
}
