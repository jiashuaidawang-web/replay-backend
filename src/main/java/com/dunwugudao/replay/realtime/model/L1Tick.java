package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * L1 逐笔成交（来自 Redis Stream {@code l1:ticks}，全局流，按 ths:l2:pool 过滤）。
 *
 * <p>与 L2 JiTu 不同：L1 直接给 price + vol，但无方向字段。
 * 方向由消费层根据 price vs 盘口 bid1/ask1 推断：
 * <pre>
 *   price >= ask1 → BUY（主动买，以卖价成交）
 *   price <= bid1 → SELL（主动卖，以买价成交）
 *   其它          → NEUTRAL
 * </pre>
 *
 * <p>示例：{"code":"002716","time":"14:43","price":11.39,"vol":587,"trade":1,"ts":"2026-08-24 14:43:57.910"}
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class L1Tick implements Serializable {

    /** 股票代码（裸代码，无后缀）。 */
    @JsonProperty("code")
    private String code;

    /** 成交时间（时分，如 14:43）。 */
    @JsonProperty("time")
    private String time;

    /** 成交价格（元）。 */
    @JsonProperty("price")
    private double price;

    /** 成交量（手）。 */
    @JsonProperty("vol")
    private double vol;

    /** 是否真实成交（0=集合竞价, 1=连续竞价）。 */
    @JsonProperty("trade")
    private int trade;

    /** 抓取时间戳（yyyy-MM-dd HH:mm:ss.SSS）。 */
    @JsonProperty("ts")
    private String ts;
}
