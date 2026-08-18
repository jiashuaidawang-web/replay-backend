package com.dunwugudao.replay.entity.ck.raw;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 股票周线原始行情（CK: stock_weekly）。
 * 仅映射 S6 趋势计算所需的字段。
 */
public class StockWeekly {

    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal vol;
    private BigDecimal amount;
    private BigDecimal chgAmount;
    private BigDecimal amplitude;
    private BigDecimal mainNet;

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public String getTsCode() { return tsCode; }
    public void setTsCode(String tsCode) { this.tsCode = tsCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }

    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }

    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }

    public BigDecimal getClose() { return close; }
    public void setClose(BigDecimal close) { this.close = close; }

    public BigDecimal getVol() { return vol; }
    public void setVol(BigDecimal vol) { this.vol = vol; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getChgAmount() { return chgAmount; }
    public void setChgAmount(BigDecimal chgAmount) { this.chgAmount = chgAmount; }

    public BigDecimal getAmplitude() { return amplitude; }
    public void setAmplitude(BigDecimal amplitude) { this.amplitude = amplitude; }

    public BigDecimal getMainNet() { return mainNet; }
    public void setMainNet(BigDecimal mainNet) { this.mainNet = mainNet; }
}
