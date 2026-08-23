package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * 实时盘口/行情快照（来自同花顺 L2 Redis Hash {@code ths:l2:quote:{code}}）。
 *
 * <p><b>背景（REDIS_DATA_FORMAT.md 实测）</b>：
 * <ul>
 *   <li>quote 是 <b>Redis Hash</b>（不是 Stream），字段为 {@code tb10_prices/tb10_volumes/tb10_strings/tb10_count/tb10_data_len/timestamp/update_time/code}；
 *       其中 {@code tb10_prices} 是逗号分隔的价格列表（最新价/买一/卖一/买二…卖二…顺序待行情软件确认），
 *       {@code tb10_volumes} 是对应量列表。</li>
 *   <li>JiTu 逐笔（ths:l2:tick:{code}）只含方向 B/S 与时间，<b>不含价格与量</b>；
 *       成交价必须从本快照的 tb10_prices 回贴（见 RedisStreamConsumer）。</li>
 * </ul>
 *
 * <p><b>两种接入方式（爬虫二选一，后端都容错）</b>：
 * <ol>
 *   <li><b>朴素模式（当前 pcap 重放现状）</b>：Hash 只给 {@code tb10_prices/tb10_volumes} 原始串。
 *       后端用 {@link #parseTb10()} 把串拆成 {@code tb10PriceList/tb10VolList}，并按约定 index 取
 *       {@code lastPrice/bid1P/ask1P/bid1V/ask1V}（index 顺序 {@code [最新价, 买一, 卖一, 买二, 卖二, ...]}，
 *       待爬虫与行情软件确认后固化，目前用 {@link #TB10_LAST_IDX} 等常量）。</li>
 *   <li><b>拆分模式（爬虫已做好字段拆解）</b>：Hash 直接给 {@code last_price/bid1_p/bid1_v/ask1_p/ask1_v/prev_close...} 等
 *       语义字段，则直接映射（见下方 @JsonProperty），{@code tb10_prices} 可缺省。</li>
 * </ol>
 *
 * <p><b>容错</b>：{@code @JsonIgnoreProperties(ignoreUnknown=true)} 已开启；tb10 解析失败不抛异常，相关字段归 0（未封板/无盘口）。
 */
@Data
@Slf4j
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Quote implements Serializable {

    /** tb10_prices / tb10_volumes 解析后各 index 含义（待行情软件确认，当前约定顺序）。
     *  <b>两个列表同序对齐</b>：prices=[最新价,买一,卖一,买二,卖二,...]，volumes=[最新量,买一量,卖一量,买二量,卖二量,...]。
     *  即 prices[k] 与 volumes[k] 是同一档的价与量，index 直接对应（不偏移）。 */
    public static final int TB10_LAST_IDX = 0;   // 最新价 / 最新量
    public static final int TB10_BID1_IDX = 1;   // 买一价 / 买一量
    public static final int TB10_ASK1_IDX = 2;   // 卖一价 / 卖一量
    public static final int TB10_BID2_IDX = 3;   // 买二价 / 买二量
    public static final int TB10_ASK2_IDX = 4;   // 卖二价 / 卖二量

    /** 股票代码（Hash 字段 code；缺失时由消费层用 key 补全）。 */
    @JsonProperty("code")
    private String tsCode;

    /** 更新时间戳（相对毫秒，Hash 字段 timestamp）。注意：quote timestamp 是当日相对毫秒，非 epoch。 */
    @JsonProperty("timestamp")
    private long ts;

    /** 更新时间字符串（Hash 字段 update_time，人工可读）。 */
    @JsonProperty("update_time")
    private String updateTime;

    // ---------- 朴素模式：tb10 原始串 ----------
    /** 价格列表（逗号分隔）。Hash 字段 tb10_prices。 */
    @JsonProperty("tb10_prices")
    private String tb10Prices;

    /** 成交量列表（逗号分隔）。Hash 字段 tb10_volumes。 */
    @JsonProperty("tb10_volumes")
    private String tb10Volumes;

    /** 原始字符串列表（Hash 字段 tb10_strings，一般不用）。 */
    @JsonProperty("tb10_strings")
    private String tb10Strings;

    /** 结构体数量（Hash 字段 tb10_count）。 */
    @JsonProperty("tb10_count")
    private int tb10Count;

    /** 数据区长度（Hash 字段 tb10_data_len）。 */
    @JsonProperty("tb10_data_len")
    private int tb10DataLen;

    // ---------- 拆分模式：语义字段（爬虫若拆解则直接映射）----------
    /** 最新价（元）。拆分模式字段 last_price；朴素模式由 parseTb10 填充。 */
    @JsonProperty("last_price")
    private double lastPrice;

    /** 昨收（元）。拆分模式字段 prev_close（用于推算 pctChg / 涨停价） */
    @JsonProperty("prev_close")
    private double prevClose;

    /** 最高价（元）。拆分模式字段 high */
    @JsonProperty("high")
    private double high;

    /** 最低价（元）。拆分模式字段 low */
    @JsonProperty("low")
    private double low;

    /** 当日成交额（元）。拆分模式字段 amount */
    @JsonProperty("amount")
    private double amountDay;

    /** 涨停价（元，可选）。拆分模式字段 limit_up_price；缺失时由涨停规则估算。 */
    @JsonProperty("limit_up_price")
    private double limitUpPrice;

    /** 涨停封单额（元，可选显式）。契约无直接字段；缺失时由 getSealAmount() 用买一档兜底。 */
    @JsonProperty("seal_amount")
    @Getter(AccessLevel.NONE)
    private double sealAmount;

    /** 买一量（手）。拆分模式字段 bid1_v（封板估算用）；朴素模式由 parseTb10 填充。 */
    @JsonProperty("bid1_v")
    private double bid1V;

    /** 买一价（元）。拆分模式字段 bid1_p（封板估算用）；朴素模式由 parseTb10 填充。 */
    @JsonProperty("bid1_p")
    private double bid1P;

    /** 卖一价（元）。拆分模式字段 ask1_p；朴素模式由 parseTb10 填充。 */
    @JsonProperty("ask1_p")
    private double ask1P;

    /** 卖一量（手）。拆分模式字段 ask1_v；朴素模式由 parseTb10 填充。 */
    @JsonProperty("ask1_v")
    private double ask1V;

    /** 涨跌幅 %（可选，字段 pct_chg；否则由 lastPrice/prevClose 推算）。 */
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

    /**
     * 朴素模式解析：把 {@code tb10_prices/tb10_volumes} 原始串拆成列表，并回填语义字段
     * （lastPrice/bid1P/ask1P/bid1V/ask1V）。解析失败（格式异常/越界）时静默忽略，相关字段保持 0/原值。
     *
     * <p>调用时机：消费层从 Hash 读到快照后、塞进窗口前调用一次。若爬虫已给语义字段（last_price 等）则本方法不覆盖。
     * <b>注意</b>：tb10_prices 的 index 顺序（[最新价,买一,卖一,买一量,卖一量...]）来自当前约定，
     * 待爬虫对照行情软件确认后可能调整——调整只需改 {@link #TB10_LAST_IDX} 等常量。
     */
    public void parseTb10() {
        if (tb10Prices == null || tb10Prices.isBlank()) {
            return;
        }
        try {
            String[] ps = tb10Prices.split(",");
            double[] prices = new double[ps.length];
            for (int i = 0; i < ps.length; i++) {
                prices[i] = Double.parseDouble(ps[i].trim());
            }
            double[] vols = null;
            if (tb10Volumes != null && !tb10Volumes.isBlank()) {
                String[] vs = tb10Volumes.split(",");
                vols = new double[vs.length];
                for (int i = 0; i < vs.length; i++) {
                    vols[i] = Double.parseDouble(vs[i].trim());
                }
            }
            // 仅在爬虫未显式给语义字段时才用 tb10 回填（避免重复覆盖）
            if (lastPrice <= 0 && prices.length > TB10_LAST_IDX) {
                lastPrice = prices[TB10_LAST_IDX];
            }
            if (bid1P <= 0 && prices.length > TB10_BID1_IDX) {
                bid1P = prices[TB10_BID1_IDX];
            }
            if (ask1P <= 0 && prices.length > TB10_ASK1_IDX) {
                ask1P = prices[TB10_ASK1_IDX];
            }
            if (vols != null) {
                if (bid1V <= 0 && vols.length > TB10_BID1_IDX) {
                    bid1V = vols[TB10_BID1_IDX];
                }
                if (ask1V <= 0 && vols.length > TB10_ASK1_IDX) {
                    ask1V = vols[TB10_ASK1_IDX];
                }
            }
        } catch (NumberFormatException e) {
            // 格式异常：保持原值，不中断
            log.debug("[quote] tb10 解析失败（保持原值）: {}", e.getMessage());
        }
    }
}
