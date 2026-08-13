package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 板块主力资金流向视图（S3）。资金单位：元（与源表 main_net 口径一致）。
 */
@Data
public class FundFlowBoardVO {

    private String boardCode;
    private String boardName;

    /** 主力净流入。 */
    private BigDecimal mainNet;

    /** 超大单净流入。 */
    private BigDecimal superBig;

    /** 大单净流入。 */
    private BigDecimal bigNet;

    /** 板块内上涨家数。 */
    private Integer upCount;

    /** 板块内下跌家数。 */
    private Integer downCount;
}
