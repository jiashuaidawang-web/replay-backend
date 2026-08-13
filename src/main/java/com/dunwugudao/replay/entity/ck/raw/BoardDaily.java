package com.dunwugudao.replay.entity.ck.raw;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 板块日线原始行（ClickHouse: board_daily）。
 *
 * <p>board_code 为 BK 代码（与 stock_board_rel 一致，可直接 join）；
 * board_type: 1=地域 2=行业 3=概念。main_net 为板块资金净流入（元）。
 */
@Data
public class BoardDaily {

    private LocalDate tradeDate;
    private String boardCode;
    private String boardName;
    private Integer boardType;
    private BigDecimal pctChg;
    private BigDecimal amount;
    private Integer upCount;
    private Integer downCount;
    private Integer limitUpCount;
    /** 板块资金净流入（元）。 */
    private BigDecimal mainNet;
    private String leadingCode;
    private String leadingName;
}
