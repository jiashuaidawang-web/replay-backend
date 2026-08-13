package com.dunwugudao.replay.entity.ck.raw;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 股票-板块归属原始行（ClickHouse: stock_board_rel）。
 *
 * <p>关键口径：{@link #tsCode} <b>无后缀</b>（如 000001），与 limit_up_pool 连接时必须先
 * {@code split_part(ts_code, '.', 1)} 去后缀，否则 join 不上。
 */
@Data
public class StockBoardRel {

    /** 无后缀股票代码。 */
    private String tsCode;
    /** BK 板块代码。 */
    private String boardCode;
    private String boardName;
    /** 1=地域 2=行业 3=概念。 */
    private Integer boardType;
    private Integer isLeader;
    private Integer isMidarm;
    private BigDecimal weight;
}
