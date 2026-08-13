package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日龙头池产出（ClickHouse: leader_pool_daily，ReplacingMergeTree：ORDER BY (trade_date, ts_code) + _ver）。
 * 按交易日幂等重算：直接 INSERT，引擎按 _ver 保留最新。
 */
@Mapper
public interface LeaderPoolDailyMapper {

    int insertBatch(@Param("list") List<LeaderPoolDaily> list);

    /** 读某交易日全部龙头（按 score 降序）。 */
    List<LeaderPoolDaily> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 读某交易日某板块龙头（按 score 降序）。 */
    List<LeaderPoolDaily> selectByTradeDateAndBoard(@Param("tradeDate") LocalDate tradeDate,
                                                     @Param("boardCode") String boardCode);
}
