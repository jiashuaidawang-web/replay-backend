package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 实时逐笔成交（来自同花顺 L2 Redis Stream {@code ths:l2:tick:{code}}，JiTu 格式）。
 *
 * <p><b>REDIS_DATA_FORMAT.md 实测契约</b>：JiTu 逐笔 entry 字段为 {@code t/p/v/d/a}，其中
 * <ul>
 *   <li>t：成交时间（毫秒，<b>从当日 0 点起算</b>，如 605000 = 10:05:00.000；非 epoch millis）。本类 {@link TickTimeDeserializer} 兼容两种。</li>
 *   <li>p：成交价（元）。<b>⚠️ JiTu 格式不含价格，当前恒为 0.0</b>，真实成交价须从 {@code ths:l2:quote:{code}} 的 tb10_prices 回贴（见 RedisStreamConsumer）。</li>
 *   <li>v：成交量（手）。<b>⚠️ JiTu 当前未解析，恒为 0</b>；待爬虫逆向补量。</li>
 *   <li>d：方向。契约为字符 {@code B}=主动买 / {@code S}=主动卖；为向后兼容 M1 旧口径，也接受 {@code 0/1/2} 数字串。</li>
 *   <li>a：成交金额（元）。⚠️ 价格、量均为 0 时恒为 0；可后由 price*volume 推算（回贴价格后）。</li>
 * </ul>
 *
 * <p><b>容错口径</b>：price/volume/amount 在 JiTu 现状下为 0 是<b>预期</b>而非解析失败，消费层不报错；
 * 特征计算（尤其 M3 拆单）对"量全 0"做了退化分支，避免假信号。爬虫补量/补价后这些字段自动生效，无需改代码。
 *
 * <p><b>direction 解析口径（统一归一为业务语义）</b>：
 * <pre>
 *   B / 0  → BUY  （主动买，资金流入方向，符号 +）
 *   S / 1  → SELL （主动卖，资金流出方向，符号 -）
 *   其它(2/N/null) → NEUTRAL（中性，符号 0）
 * </pre>
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Tick implements Serializable {

    /** 股票代码（爬虫侧 key 已含 code，但 payload 仍建议带上 tsCode 以便后端路由；缺失时由消费层用 stream key 补全）。 */
    @JsonProperty("tsCode")
    private String tsCode;

    /** 成交时间（毫秒）。JiTu 为当日相对毫秒；本类兼容 epoch millis。契约 JSON key = {@code t}。 */
    @JsonDeserialize(using = TickTimeDeserializer.class)
    @JsonProperty("t")
    private long ts;

    /** 成交价（元）。JiTu 现状恒为 0，须由消费层从 quote 快照回贴。契约 JSON key = {@code p}。 */
    @JsonProperty("p")
    private double price;

    /** 成交量（手）。JiTu 现状恒为 0，待爬虫补量。契约 JSON key = {@code v}。 */
    @JsonProperty("v")
    private double volume;

    /** 成交金额（元）。price/volume 为 0 时恒为 0，回贴价格后可推算。契约 JSON key = {@code a}。 */
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

    /** JiTu 现状下成交价恒为 0，需消费层回贴；true 表示尚未回贴（用 quote 快照最新价）。 */
    public boolean isPriceMissing() {
        return price <= 0;
    }
}
