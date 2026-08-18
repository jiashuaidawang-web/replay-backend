package com.dunwugudao.replay.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S6 趋势战法 · 趋势候选股日快照（CK: trend_candidate_daily，ReplacingMergeTree）。
 * 由 TrendCalculator 基于 stock_weekly 八大技术特征量化后写入，按 (trade_date, ts_code) 幂等。
 */
public class TrendCandidateDaily {

    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;

    /** 命中八大特征数量（小盘特征因缺市值数据恒为 0，故实际 0~7）。 */
    private Integer featureHit;

    /** RS 代理（0~1）：收盘价在 52 周区间的相对位置，越高代表领涨性越强。 */
    private BigDecimal rsVsIndex;

    /** 趋势是否成立：站上牛熊线 + 图形牛市 + 自大底最低点涨幅 > 25%。 */
    private Integer confirmed;

    // ---- 八大特征逐维命中标记 ----
    private Integer fMa;       // ① 长期均线(10/30周)站稳并多头发散
    private Integer fShape;    // ② 漂亮图形(底部抬高、无压制)
    private Integer fVol;      // ③ 健康量价(涨放跌缩、突破放量)
    private Integer fSmallcap; // ④ 小盘子(市值50亿以下) —— 无市值数据，恒 0(N/A)
    private Integer fRs;       // ⑤ RS 强于指数(52周高位 proximity 代理)
    private Integer fRsi;      // ⑥ RSI 突破 70 买点信号
    private Integer fWeekly;   // ⑦ 周线确认(周线级上升趋势)
    private Integer fBreak;    // ⑧ 底部历史平台突破(空间打开)

    /** 自大底最低点(52周低)起涨幅 %。 */
    private BigDecimal gainFromBottom;
    private BigDecimal closePrice;
    private BigDecimal rsi;
    private BigDecimal ma10;
    private BigDecimal ma30;

    // ---- transient：接口层展示用，不入库 ----
    private java.util.List<String> hitFeatures;

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public String getTsCode() { return tsCode; }
    public void setTsCode(String tsCode) { this.tsCode = tsCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public Integer getFeatureHit() { return featureHit; }
    public void setFeatureHit(Integer featureHit) { this.featureHit = featureHit; }

    public BigDecimal getRsVsIndex() { return rsVsIndex; }
    public void setRsVsIndex(BigDecimal rsVsIndex) { this.rsVsIndex = rsVsIndex; }

    public Integer getConfirmed() { return confirmed; }
    public void setConfirmed(Integer confirmed) { this.confirmed = confirmed; }

    public Integer getFMa() { return fMa; }
    public void setFMa(Integer fMa) { this.fMa = fMa; }

    public Integer getFShape() { return fShape; }
    public void setFShape(Integer fShape) { this.fShape = fShape; }

    public Integer getFVol() { return fVol; }
    public void setFVol(Integer fVol) { this.fVol = fVol; }

    public Integer getFSmallcap() { return fSmallcap; }
    public void setFSmallcap(Integer fSmallcap) { this.fSmallcap = fSmallcap; }

    public Integer getFRs() { return fRs; }
    public void setFRs(Integer fRs) { this.fRs = fRs; }

    public Integer getFRsi() { return fRsi; }
    public void setFRsi(Integer fRsi) { this.fRsi = fRsi; }

    public Integer getFWeekly() { return fWeekly; }
    public void setFWeekly(Integer fWeekly) { this.fWeekly = fWeekly; }

    public Integer getFBreak() { return fBreak; }
    public void setFBreak(Integer fBreak) { this.fBreak = fBreak; }

    public BigDecimal getGainFromBottom() { return gainFromBottom; }
    public void setGainFromBottom(BigDecimal gainFromBottom) { this.gainFromBottom = gainFromBottom; }

    public BigDecimal getClosePrice() { return closePrice; }
    public void setClosePrice(BigDecimal closePrice) { this.closePrice = closePrice; }

    public BigDecimal getRsi() { return rsi; }
    public void setRsi(BigDecimal rsi) { this.rsi = rsi; }

    public BigDecimal getMa10() { return ma10; }
    public void setMa10(BigDecimal ma10) { this.ma10 = ma10; }

    public BigDecimal getMa30() { return ma30; }
    public void setMa30(BigDecimal ma30) { this.ma30 = ma30; }

    public java.util.List<String> getHitFeatures() { return hitFeatures; }
    public void setHitFeatures(java.util.List<String> hitFeatures) { this.hitFeatures = hitFeatures; }
}
