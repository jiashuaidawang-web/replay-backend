package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * S1 四维度评分（技术 / 情绪 / 资金 / 政策），各维 0~1 归一；composite 为加权综合。
 * 未计算的维度为 null。
 */
@Data
public class FourDimVO {

    private BigDecimal tech;
    private BigDecimal sentiment;
    private BigDecimal fund;
    private BigDecimal policy;
    private BigDecimal composite;
}
