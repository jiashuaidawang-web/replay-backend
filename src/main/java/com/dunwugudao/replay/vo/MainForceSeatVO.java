package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * S3 主力博弈 · 抱团席位视图（同一席位跨多股净买，体现"合力"）。
 */
@Data
public class MainForceSeatVO {

    private String seatName;

    /** 席位类型（机构 / 营业部 / 沪股通 / 深股通）。 */
    private String seatType;

    /** 该席位当日涉及的个股数（跨股越多=抱团越明显）。 */
    private Integer stockCnt;

    /** 该席位当日全部席位净买合计（元）。 */
    private BigDecimal netBuy;

    /** 涉及的股票代码列表。 */
    private List<String> tsCodes;
}
