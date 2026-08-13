package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 涨跌停池个股视图（S2）。boardName 经 stock_board_rel→board_basic 回填真实概念板块名，
 * 取不到时回退为 limit_pool 的行业名。
 */
@Data
public class LimitPoolVO {

    private String tsCode;
    private String stockName;

    /** 所属（概念）板块名。 */
    private String boardName;

    /** 连板数；首板为 null。 */
    private Integer boardPos;

    /** 涨停风格：换手 / 一字 / T字 / 自然。 */
    private String limitStyle;

    /** 涨跌幅 %。 */
    private BigDecimal pctChg;

    /** 成交额（千元）。 */
    private BigDecimal amount;

    /** 换手率 %。 */
    private BigDecimal turnoverRate;
}
