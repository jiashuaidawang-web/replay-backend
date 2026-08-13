package com.dunwugudao.replay.entity.ck.raw;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 涨停池原始行（ClickHouse: limit_up_pool）。
 *
 * <p>注意两个口径坑（已在领域模型审查中定死）：
 * <ul>
 *   <li>{@link #tsCode} 带后缀（如 300686.SZ）；与 stock_board_rel 连接前必须去后缀。</li>
 *   <li>{@link #boardCode} 是<b>截断的行业名</b>（如「燃气Ⅱ」），不是 BK 代码，
 *       主线归属<b>绝不能</b>用它，必须走 stock_board_rel。</li>
 * </ul>
 */
@Data
public class LimitUpPool {

    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private BigDecimal latestPrice;
    private BigDecimal pctChg;
    /** 连板数；首板常为 null，计算时按 1 处理。 */
    private Integer boardPos;
    /** 涨停风格：换手 / 一字。 */
    private String limitStyle;
    private BigDecimal turnoverRate;
    /** 截断行业名（坑：不要用于主线归属）。 */
    private String boardCode;
    private BigDecimal ltsz;
    private BigDecimal amount;
}
