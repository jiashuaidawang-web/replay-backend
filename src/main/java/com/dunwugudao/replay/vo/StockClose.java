package com.dunwugudao.replay.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * stock_daily 收盘价快照（用于 S3 由龙虎榜个股计算主力买入后 N 日收益）。
 */
@Data
public class StockClose {

    private String tsCode;
    private LocalDate tradeDate;
    private BigDecimal close;
}
