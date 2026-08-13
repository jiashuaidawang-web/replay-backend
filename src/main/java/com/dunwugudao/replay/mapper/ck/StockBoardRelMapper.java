package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.ck.raw.BoardMemberCount;
import com.dunwugudao.replay.entity.ck.raw.StockBoardRel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockBoardRelMapper {

    /**
     * 按（无后缀）股票代码集合 + 板块类型，取股票-板块归属。
     * 用于 S4：涨停股 → 反查其所属概念/行业板块，避免 limit_up_pool 截断行业名坑。
     */
    List<StockBoardRel> selectByTsCodesAndBoardType(@Param("tsCodes") List<String> tsCodes,
                                                    @Param("boardType") int boardType);

    /**
     * 按板块类型聚合各板块成分股数量（用于 S7 概念稀缺性初算）。
     * 返回 board_code → 成员数。
     */
    List<BoardMemberCount> countByBoardType(@Param("boardType") int boardType);
}
