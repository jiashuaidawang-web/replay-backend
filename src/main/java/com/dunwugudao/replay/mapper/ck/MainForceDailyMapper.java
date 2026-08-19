package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.MainForceDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * S3 主力博弈 · 个股级主力合力日级产出（ClickHouse）。计算层写入 + 读取都走 CK。
 *
 * <p>表为 ReplacingMergeTree（ORDER BY (trade_date, ts_code) + _ver）。重算时直接 INSERT，
 * 引擎按 _ver 保留最新版本；读取统一加 FINAL。
 */
@Mapper
public interface MainForceDailyMapper {

    int insertBatch(@Param("list") List<MainForceDaily> list);

    /** 某交易日全部个股主力合力（按净买入降序）。 */
    List<MainForceDaily> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);
}
