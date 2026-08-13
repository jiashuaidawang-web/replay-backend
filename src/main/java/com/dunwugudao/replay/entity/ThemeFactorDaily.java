package com.dunwugudao.replay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日题材炒作因子（S7 炒作思维）。对应表 theme_factor_daily（ReplacingMergeTree：ORDER BY (trade_date, board_code) + _ver）。
 *
 * <p>六因子：稀缺性 / 想象空间 / 突发性 / 确定性 / 最小阻力方向（均为 0~1），综合分 total 落在 0~100。
 * boardName 不在表中，接口层从 board_basic 回填（见 ThemeService）。
 */
@Data
public class ThemeFactorDaily {

    private LocalDate tradeDate;

    /** 板块代码，如 BK0854。 */
    private String boardCode;

    /** 稀缺性 0~1：成分股越少越稀缺。 */
    private BigDecimal scarcity;

    /** 想象空间 0~1：题材天花板高低。 */
    private BigDecimal imagination;

    /** 突发性 0~1：当日涨停集体启动的强度。 */
    private BigDecimal sudden;

    /** 确定性 0~1：题材逻辑当日被市场验证的程度。 */
    private BigDecimal certainty;

    /** 最小阻力方向（势）0~1：涨+资金流入+达主线阈值+突发 的共振。 */
    private BigDecimal minResist;

    /** 综合炒作因子分 0~100（六因子加权归一）。 */
    private BigDecimal total;
}
