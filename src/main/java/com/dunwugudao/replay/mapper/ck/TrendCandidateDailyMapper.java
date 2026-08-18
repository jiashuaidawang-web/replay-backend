package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.TrendCandidateDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TrendCandidateDailyMapper {

    void insertBatch(@Param("list") List<TrendCandidateDaily> list);

    /** 取某交易日全部候选（FINAL 去重），接口层再按 feature_hit 过滤。 */
    List<TrendCandidateDaily> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** 取表里最大交易日（接口未传 date 时的兜底）。 */
    LocalDate selectLatestDate();
}
