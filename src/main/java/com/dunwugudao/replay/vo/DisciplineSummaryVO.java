package com.dunwugudao.replay.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * S8 纪律评分汇总（GET /trade-log/discipline 返回）。
 */
@Data
public class DisciplineSummaryVO {

    /** 平均纪律分 0~100。 */
    private Integer totalAvg;

    /** 统计区间内的交易笔数。 */
    private Integer count;

    /** 六维度平均分（维度名 -> 平均分 0~100）。 */
    private Map<String, Integer> dimAvg;

    /** 高频违规项（按出现频次降序，取 Top5）。 */
    private List<String> topViolations;

    /** 近 N 笔带评分明细的样本（含 disciplineScore / violations）。 */
    private List<TradeLogVO> samples;
}
