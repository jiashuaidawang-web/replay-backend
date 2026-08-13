package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 趋势股扫描视图（S6）。趋势计算层未实现时返回空列表。
 */
@Data
public class TrendScanVO {

    private String tsCode;

    /** 命中八大特征数量 0~8。 */
    private Integer featureHit;

    /** 相对指数强度。 */
    private BigDecimal rsVsIndex;

    /** 是否站上牛熊线且线上数周：1 是 / 0 否。 */
    private Integer confirmed;
}
