package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * L1 五档盘口快照（来自 Redis Stream {@code l1:quotes}，全局流）。
 *
 * <p>与 L2 tb10 原始串不同：L1 直接给语义字段 b1p/s1p 等。
 * 消费层转为 {@link Quote} 复用窗口 + 特征计算。
 *
 * <p>示例：{"code":"688356","ts":"2026-08-24 14:43:55.350","last":123.52,"pre_close":107.34,
 * "open":116.32,"high":128.81,"low":116.32,"vol":145667,"amount":1819849984,
 * "b1p":123.14,"b1v":6,"s1p":123.52,"s1v":99,...}
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class L1Quote implements Serializable {

    /** 股票代码（裸代码）。 */
    @JsonProperty("code")
    private String code;

    /** 抓取时间戳。 */
    @JsonProperty("ts")
    private String ts;

    /** 最新价。 */
    @JsonProperty("last")
    private double last;

    /** 昨收价。 */
    @JsonProperty("pre_close")
    private double preClose;

    /** 开盘价。 */
    @JsonProperty("open")
    private double open;

    /** 最高价。 */
    @JsonProperty("high")
    private double high;

    /** 最低价。 */
    @JsonProperty("low")
    private double low;

    /** 总成交量（手）。 */
    @JsonProperty("vol")
    private double vol;

    /** 总成交额（元）。 */
    @JsonProperty("amount")
    private double amount;

    /** 买1~5价格。 */
    @JsonProperty("b1p") private double b1p;
    @JsonProperty("b2p") private double b2p;
    @JsonProperty("b3p") private double b3p;
    @JsonProperty("b4p") private double b4p;
    @JsonProperty("b5p") private double b5p;

    /** 买1~5量（手）。 */
    @JsonProperty("b1v") private double b1v;
    @JsonProperty("b2v") private double b2v;
    @JsonProperty("b3v") private double b3v;
    @JsonProperty("b4v") private double b4v;
    @JsonProperty("b5v") private double b5v;

    /** 卖1~5价格。 */
    @JsonProperty("s1p") private double s1p;
    @JsonProperty("s2p") private double s2p;
    @JsonProperty("s3p") private double s3p;
    @JsonProperty("s4p") private double s4p;
    @JsonProperty("s5p") private double s5p;

    /** 卖1~5量（手）。 */
    @JsonProperty("s1v") private double s1v;
    @JsonProperty("s2v") private double s2v;
    @JsonProperty("s3v") private double s3v;
    @JsonProperty("s4v") private double s4v;
    @JsonProperty("s5v") private double s5v;

    /**
     * 转为 Quote（复用现有窗口 + 特征计算 + tick 方向推断）。
     * 注意：不设置 tb10_prices（L1 无需朴素模式解析）。
     */
    public Quote toQuote() {
        Quote q = new Quote();
        q.setTsCode(code);
        q.setLastPrice(last);
        q.setPrevClose(preClose);
        q.setHigh(high);
        q.setLow(low);
        q.setAmountDay(amount);
        q.setBid1P(b1p);
        q.setBid1V(b1v);
        q.setAsk1P(s1p);
        q.setAsk1V(s1v);
        // 涨停价：preClose * 1.1（创业板/科创板 20% 暂不区分，用 10% 兜底）
        if (preClose > 0) {
            q.setLimitUpPrice(Math.round(preClose * 1.1 * 100.0) / 100.0);
        }
        // 涨跌幅
        if (preClose > 0 && last > 0) {
            q.setPctChg((last - preClose) / preClose * 100.0);
        }
        return q;
    }
}
