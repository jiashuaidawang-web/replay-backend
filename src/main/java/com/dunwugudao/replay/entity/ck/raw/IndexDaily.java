package com.dunwugudao.replay.entity.ck.raw;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 指数日线原始行情（CK: index_daily）。仅映射 S1 四维度所需的字段。
 */
public class IndexDaily {

    private LocalDate tradeDate;
    private String indexCode;
    private String indexName;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal preClose;
    private BigDecimal pctChg;
    private BigDecimal vol;
    private BigDecimal amount;

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public String getIndexCode() { return indexCode; }
    public void setIndexCode(String indexCode) { this.indexCode = indexCode; }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }

    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }

    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }

    public BigDecimal getClose() { return close; }
    public void setClose(BigDecimal close) { this.close = close; }

    public BigDecimal getPreClose() { return preClose; }
    public void setPreClose(BigDecimal preClose) { this.preClose = preClose; }

    public BigDecimal getPctChg() { return pctChg; }
    public void setPctChg(BigDecimal pctChg) { this.pctChg = pctChg; }

    public BigDecimal getVol() { return vol; }
    public void setVol(BigDecimal vol) { this.vol = vol; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
