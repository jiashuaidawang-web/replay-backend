package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.MainlineDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日主线板块产出（ClickHouse: mainline_daily，ReplacingMergeTree：ORDER BY (trade_date, board_code) + _ver）。
 * 按交易日幂等重算：直接 INSERT，引擎按 _ver 保留最新。
 */
@Mapper
public interface MainlineDailyMapper {

    int insertBatch(@Param("list") List<MainlineDaily> list);

    /** 读某交易日主线（按 rank 升序）。接口层消费，需 FINAL。 */
    List<MainlineDaily> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);
}
