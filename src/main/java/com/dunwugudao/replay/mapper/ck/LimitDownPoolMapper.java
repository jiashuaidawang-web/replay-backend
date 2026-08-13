package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ck.raw.LimitDownPool;
import com.dunwugudao.replay.vo.LimitPoolVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface LimitDownPoolMapper {

    List<LimitDownPool> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 跌停池 enriched：行业名回退为板块名展示。 */
    List<LimitPoolVO> selectEnrichedDown(@Param("tradeDate") LocalDate tradeDate);
}
