package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 趋势股扫描视图（S6）。基于 trend_candidate_daily 八大技术特征量化结果。
 */
@Data
public class TrendScanVO {

    private String tsCode;
    private String stockName;

    /** 命中八大特征数量（小盘特征因缺市值数据恒 0，故实际 0~7）。 */
    private Integer featureHit;

    /** RS 代理（0~1）：收盘价在 52 周区间的相对位置。 */
    private BigDecimal rsVsIndex;

    /** 趋势是否成立：1 是 / 0 否。 */
    private Integer confirmed;

    // ---- 八大特征逐维命中 ----
    private Integer fMa;
    private Integer fShape;
    private Integer fVol;
    private Integer fSmallcap;
    private Integer fRs;
    private Integer fRsi;
    private Integer fWeekly;
    private Integer fBreak;

    /** 自大底最低点涨幅 %。 */
    private BigDecimal gainFromBottom;
    private BigDecimal closePrice;
    private BigDecimal rsi;
    private BigDecimal ma10;
    private BigDecimal ma30;

    /** 命中的特征中文名（便于前端直接展示）。 */
    private List<String> hitFeatures;
}
