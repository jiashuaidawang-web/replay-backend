package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 实时盘口/行情快照（来自同花顺 L2 Redis Stream {@code ths:l2:quote:{code}} 的 payload JSON）。
 *
 * <p><b>背景</b>：同花顺契约原文中 quote 是 Redis Hash（{@code ths:l2:quote:{code}}，带 60s TTL，每 100ms 覆盖写）。
 * 但后端特征引擎是「消费 Stream 逐帧进窗口」模型，无法直接吃 Hash。约定：
 * <b>爬虫在写 Hash 的同时，额外 {@code XADD ths:l2:quote:{code} * payload=<本对象JSON>}</b> 喂后端窗口；
 * Hash 那份留给前端/爬虫侧直接 HGETALL 使用，互不打扰。
 *
 * <p><b>字段映射（同花顺 key → 本 POJO）</b>：last_price→lastPrice、pctChg 由 (last_price-prev_close)/prev_close 推算、
 * amountDay←amount、high/low 同名、limitUpPrice 由涨停规则推算（或爬虫显式给）、sealAmount 由买一档封单估算。
 * 为减少爬虫负担，本 POJO 容错：只认 last_price/prev_close/amount/high/low 等核心字段，封板信息缺失时归 0（未封板）。
 *
 * <p><b>容错</b>：{@code @JsonIgnoreProperties(ignoreUnknown=true)} 已开启。
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Quote implements Serializable {

    /** 股票代码（payload 建议带，缺失时由消费层用 stream key 补全）。 */
    @JsonProperty("tsCode")
    private String tsCode;

    /** 更新时间戳（epoch millis）。契约 Hash 字段 timestamp(ms)。 */
    @JsonProperty("timestamp")
    private long ts;

    /** 最新价（元）。契约字段 last_price */
    @JsonProperty("last_price")
    private double lastPrice;

    /** 昨收（元）。契约字段 prev_close（用于推算 pctChg / 涨停价） */
    @JsonProperty("prev_close")
    private double prevClose;

    /** 最高价（元）。契约字段 high */
    @JsonProperty("high")
    private double high;

    /** 最低价（元）。契约字段 low */
    @JsonProperty("low")
    private double low;

    /** 当日成交额（元）。契约字段 amount */
    @JsonProperty("amount")
    private double amountDay;

    /** 涨停价（元，可选）。契约字段 limit_up_price；缺失时由涨停规则估算。 */
    @JsonProperty("limit_up_price")
    private double limitUpPrice;

    /** 涨停封单额（元，可选显式）。契约无直接字段；缺失时由 getSealAmount() 用买一档兜底。 */
    @JsonProperty("seal_amount")
    @Getter(AccessLevel.NONE)
    private double sealAmount;

    /** 买一量（手）。契约字段 bid1_v（封板估算用）。 */
    @JsonProperty("bid1_v")
    private double bid1V;

    /** 买一价（元）。契约字段 bid1_p（封板估算用）。 */
    @JsonProperty("bid1_p")
    private double bid1P;

    /** 涨跌幅 %（可选，契约字段 pct_chg；否则由 lastPrice/prevClose 推算）。 */
    @JsonProperty("pct_chg")
    private double pctChg;

    /**
     * 涨停封单额（元）：显式 seal_amount > 0 用之；否则用买一档买一量×买一价估算
     * （bid1V 手 × bid1P 元 × 100 股/手 = 元）。0 表示未封板。
     */
    public double getSealAmount() {
        if (sealAmount > 0) {
            return sealAmount;
        }
        if (bid1V > 0 && bid1P > 0) {
            return bid1V * bid1P * 100.0;
        }
        return 0.0;
    }
}
