package com.dunwugudao.replay.mapper.ck;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * sim_trade 只读查询（M2 盘后结算用）。
 *
 * <p>读取统一加 FINAL（ReplacingMergeTree）。trade_time 为 DateTime64(3) 字符串，直接用 String 承载，
 * 避免 mybatis 与 CK DateTime64 类型映射的时区/精度问题。
 */
@Mapper
public interface SimTradeCkMapper {

    /** 指定交易日（trade_time 落在该日）的全部模拟成交。 */
    List<SimTradeRow> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 指定 ts_code 的全部模拟成交（升序，便于配对买/卖）。 */
    List<SimTradeRow> selectByTsCode(@Param("tsCode") String tsCode);

    /** 所有 d1_ret 仍为 NULL 的成交（含买入开仓 / 卖出闭环，待回填 T+1 收益）。 */
    List<SimTradeRow> selectWithNullD1();

    /** sim_trade 中出现过的全部 ts_code（去重，用于 exp_log 配对遍历）。 */
    List<String> selectDistinctTsCodes();
}
