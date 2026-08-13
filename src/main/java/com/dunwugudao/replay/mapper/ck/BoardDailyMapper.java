package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ck.raw.BoardDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface BoardDailyMapper {

    List<BoardDaily> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 按板块代码集合取某交易日板块日线（板块强度/资金流用）。 */
    List<BoardDaily> selectByBoardCodesAndDate(@Param("boardCodes") List<String> boardCodes,
                                               @Param("tradeDate") LocalDate tradeDate);
}
