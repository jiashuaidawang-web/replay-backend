package com.dunwugudao.replay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S3 主力博弈 · 个股级主力合力日级产出（CK: main_force_daily）。
 *
 * <p>基于龙虎榜个股榜（dragon_tiger）与其席位明细（dt_detail）聚合：
 * <ul>
 *   <li>席位结构：买/卖席位数、机构净买、游资(营业部)净买、北向净买；</li>
 *   <li>合力强度 consensus_score（买席占比 0~1）；</li>
 *   <li>分歧 flag（多空对决=1 / 一致=0）；</li>
 *   <li>主力可信度 credibility_flag：主力净买后次日/5日表现——胜(1)/被埋(0)/未知(2)/净卖不判定(-1)，
 *       直接体现《顿悟股道》"破除主力至上论：有主力买入≠必涨"。</li>
 * </ul>
 * 表为 ReplacingMergeTree（ORDER BY (trade_date, ts_code) + _ver），重算纯 INSERT 幂等。
 */
@Data
public class MainForceDaily {

    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;

    /** 上榜原因。 */
    private String reason;

    /** 异动类型。 */
    private String abnormalType;

    /** 龙虎榜个股净买入（元）。 */
    private BigDecimal netBuy;
    private BigDecimal totalBuy;
    private BigDecimal totalSell;

    /** 买/卖方向席位数（按席位净买正负计）。 */
    private Integer buySeatCnt;
    private Integer sellSeatCnt;

    /** 分类型净买（元）：机构 / 游资(营业部) / 北向(沪股通+深股通)。 */
    private BigDecimal orgNetBuy;
    private BigDecimal youziNetBuy;
    private BigDecimal northNetBuy;

    /** 合力强度 0~1（买席占比）。 */
    private BigDecimal consensusScore;

    /** 分歧 flag：1=多空对决(分歧大) / 0=单向一致。 */
    private Integer divergenceFlag;

    /** 主力净买后次日 / 5 日收益（%，来自 dragon_tiger 的 d1/d5_close_adjchrate）。 */
    private BigDecimal d1Return;
    private BigDecimal d5Return;

    /** 主力可信度：1=次日胜 / 0=被埋 / 2=未知 / -1=净卖不判定。 */
    private Integer credibilityFlag;

    private BigDecimal changeRate;
    private BigDecimal closePrice;
    private BigDecimal turnoverrate;

    /** 数据口径说明（如次日收益缺失、席位类型缺失等）。 */
    private String note;
}
