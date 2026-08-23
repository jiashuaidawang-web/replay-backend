package com.dunwugudao.replay.mapper.ck;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * decision_log 只读查询（事件线页用）。
 *
 * <p>读取统一加 FINAL（ReplacingMergeTree）。ts/reference_price 为 DateTime64 / Nullable(Float64)，
 * 直接用 String/Double 承载，避免 mybatis 与 CK 类型映射的时区/精度问题。
 */
@Mapper
public interface DecisionLogCkMapper {

    /** 指定 ts_code 的全部决策（升序，含未执行的 WATCH/HOLD）。 */
    List<DecisionLogRow> selectByTsCode(@Param("tsCode") String tsCode);

    /** 指定 ts_code + 仅已执行（executed=1，即真实模拟成交触发的决策）。 */
    List<DecisionLogRow> selectExecutedByTsCode(@Param("tsCode") String tsCode);
}
