package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ck.raw.LimitUpPool;
import com.dunwugudao.replay.vo.LimitPoolVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface LimitUpPoolMapper {

    List<LimitUpPool> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 涨停池 enriched：经 stock_board_rel→board_basic 回填真实概念板块名（取不到回退行业名）。 */
    List<LimitPoolVO> selectEnrichedUp(@Param("tradeDate") LocalDate tradeDate);

    /** 已入库的最大交易日；为空表示尚未爬取。 */
    LocalDate selectMaxTradeDate();
}
