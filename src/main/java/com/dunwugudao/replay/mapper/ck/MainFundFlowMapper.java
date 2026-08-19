package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.vo.FundFlowBoardVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 主力资金（S3）。板块级资金流聚合 + 龙虎榜读取。全部走 FINAL。
 */
@Mapper
public interface MainFundFlowMapper {

    /**
     * 板块主力资金流向排行：聚合 main_fund_flow（board_code 非空），关联 board_daily 取涨跌家数。
     * orderBy 仅允许 main_net / super_big / big_net（白名单在 XML 内控制）。
     */
    List<FundFlowBoardVO> selectBoardFlow(@Param("tradeDate") LocalDate tradeDate,
                                          @Param("top") int top,
                                          @Param("orderBy") String orderBy);

    /**
     * 取股票级（obj_type='stock'）主力资金单日聚合，用于 S1 资金维度。
     * 当 tradeDate 当日无数据（如 08-12 缺资金流）时，自动回退到 <= tradeDate 的最近可用日，
     * 并返回实际使用的 usedDate，便于在 note 中说明。
     */
    com.dunwugudao.replay.entity.ck.raw.FundFlowAgg selectAggOnOrBefore(@Param("tradeDate") LocalDate tradeDate);
}
