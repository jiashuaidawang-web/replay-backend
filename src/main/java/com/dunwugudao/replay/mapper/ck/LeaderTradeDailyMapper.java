package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.LeaderTradeDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/** S5 龙头买卖计算层 Mapper。 */
@Mapper
public interface LeaderTradeDailyMapper {

    /** 读某交易日全部龙头买卖建议（按 buy_score 降序）。 */
    List<LeaderTradeDaily> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 读某交易日某板块龙头买卖建议。 */
    List<LeaderTradeDaily> selectByTradeDateAndBoard(@Param("tradeDate") LocalDate tradeDate,
                                                     @Param("boardCode") String boardCode);
}
