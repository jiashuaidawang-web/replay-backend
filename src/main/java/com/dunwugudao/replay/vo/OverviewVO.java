package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 总览看板（S1 大势择时）响应。
 *
 * <p>四维度（tech/情绪/资金/政策）当前仅 sentiment 由 S2 情绪温度回填，
 * 其余维度待 S1 计算层实现后填充；未计算时字段为 null，前端按"暂无"展示。
 */
@Data
public class OverviewVO {

    private LocalDate tradeDate;

    private FourDimVO fourDim;

    /** 牛熊周期判定（S1 未实现时为 null）。 */
    private CycleVO cycle;

    /** 情绪阶段（冰点/低迷/正常/活跃/高潮），取自 sentiment_daily.regime。 */
    private String regime;

    /** 情绪温度 0~100，取自 sentiment_daily.thermal。 */
    private BigDecimal thermal;

    /** 强度排名前 N 的主线，取自 mainline_daily。 */
    private List<MainlineVO> topMainline;

    /** 是否值得参与（composite >= 0.5）。 */
    private Integer worthTrade;

    /** 策略建议（中文可读），来自 four_dimension_daily.suggestion。 */
    private String suggestion;

    /** 数据口径说明，来自 four_dimension_daily.note。 */
    private String note;
}
