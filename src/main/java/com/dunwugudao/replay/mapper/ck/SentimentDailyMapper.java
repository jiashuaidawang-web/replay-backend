package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.SentimentDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 情绪温度日级产出（ClickHouse）。计算层写入 + 读取都走 CK。
 *
 * <p>由 CkMybatisConfig 扫描本包，绑定 ck 数据源。
 * 表为 ReplacingMergeTree（ORDER BY trade_date + _ver 版本列）。按交易日幂等重算时直接 INSERT，
 * 引擎按 _ver 保留最新版本；读取统一加 FINAL。
 */
@Mapper
public interface SentimentDailyMapper {

    int insertBatch(@Param("list") List<SentimentDaily> list);

    SentimentDaily selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 取早于指定交易日的所有情绪行（升序），用于历史分位数温度。 */
    List<SentimentDaily> selectAllBefore(@Param("tradeDate") LocalDate tradeDate);
}
