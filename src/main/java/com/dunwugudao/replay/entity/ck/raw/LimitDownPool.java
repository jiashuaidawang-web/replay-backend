package com.dunwugudao.replay.entity.ck.raw;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 跌停池原始行（ClickHouse: limit_down_pool）。
 */
@Data
public class LimitDownPool {

    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private BigDecimal pctChg;
    private BigDecimal pe;
    private BigDecimal fba;
    private Integer days;
    private Integer oc;
    private BigDecimal amount;
    private BigDecimal ltsz;
}
