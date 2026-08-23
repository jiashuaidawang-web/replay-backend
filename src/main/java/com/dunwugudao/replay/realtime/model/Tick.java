package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 实时逐笔成交（来自同花顺 L2 Redis Stream {@code ths:l2:tick:{code}}）。
 *
 * <p><b>同花顺契约（v1.0）</b>：Stream entry 字段为 {@code t/p/v/d/a}，其中
 * <ul>
 *   <li>t：成交时间。优先 epoch millis(long)；契约允许 HHMMSSmmm，本类 {@link TickTimeDeserializer} 兼容两种。</li>
 *   <li>p：成交价（元）。</li>
 *   <li>v：成交量（手）。</li>
 *   <li>d：方向。契约为字符 {@code B}=主动买 / {@code S}=主动卖；为向后兼容 M1 旧口径，也接受 {@code 0/1/2} 数字串。</li>
 *   <li>a：成交金额（元，可选）。缺失时由 p*v 推算。</li>
 * </ul>
 *
 * <p><b>容错</b>：{@code @JsonIgnoreProperties(ignoreUnknown=true)} 已开启，爬虫侧新增字段后端不报错（向后兼容）。
 *
 * <p><b>direction 解析口径（统一归一为业务语义）</b>：
 * <pre>
 *   B / 0  → BUY  （主动买，资金流入方向，符号 +）
 *   S / 1  → SELL （主动卖，资金流出方向，符号 -）
 *   其它(2/N/null) → NEUTRAL（中性，符号 0）
 * </pre>
 * 注：同花顺契约没有独立的"中性"字符，未识别方向一律按 NEUTRAL 处理，避免错判资金。
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Tick implements Serializable {

    /** 股票代码（爬虫侧 key 已含 code，但 payload 仍建议带上 tsCode 以便后端路由；缺失时由消费层用 stream key 补全）。 */
    @JsonProperty("tsCode")
    private String tsCode;

    /** 成交时间（epoch millis）。由 {@link TickTimeDeserializer} 兼容 HHMMSSmmm 与 unix-ms 两种输入。契约 JSON key = {@code t}。 */
    @JsonDeserialize(using = TickTimeDeserializer.class)
    @JsonProperty("t")
    private long ts;

    /** 成交价（元）。契约 JSON key = {@code p}。 */
    @JsonProperty("p")
    private double price;

    /** 成交量（手，int）。契约 JSON key = {@code v}。 */
    @JsonProperty("v")
    private double volume;

    /** 成交金额（元，可选）。契约 JSON key = {@code a}，缺失时由 price*volume 反推。 */
    @JsonProperty("a")
    private double amount;

    /**
     * 方向（业务归一值）：BUY / SELL / NEUTRAL。
     * 反序列化时由 {@link DirectionDeserializer} 把 B/S/0/1/2 统一映射。契约 JSON key = {@code d}。
     */
    @JsonDeserialize(using = DirectionDeserializer.class)
    @JsonProperty("d")
    private String direction;

    /** 方向 → 资金符号（+1 买 / -1 卖 / 0 中性），供 {@link FeatureCalculator} 直接乘金额。 */
    public int directionSign() {
        if ("BUY".equals(direction)) {
            return 1;
        }
        if ("SELL".equals(direction)) {
            return -1;
        }
        return 0;
    }
}
