package com.dunwugudao.replay.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 流式派生特征（决策引擎输入，同时落 CK {@code realtime_feature}）。
 * <p>由 {@code TickWindow} 滚动窗口 + 最新 {@code Quote} 计算而来。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealtimeFeature implements Serializable {
    private String tsCode;
    private LocalDate tradeDate;
    private long ts;              // 窗口结束时间（epoch millis）
    private int winMinutes;       // 窗口分钟数（1/5/15）
    private double bigNetBuy;     // 大单净主动买入额（元）
    private double bigNetBuyRatio;// 占成交额比
    private double sealAmount;    // 封单额（元）
    private int isBlast;          // 炸板
    private int isReseal;         // 回封
    private int volBreakout;      // 放量突破
    private String stageSnapshot; // 当日情绪阶段码（取自情绪快照/S2）

    // ---------------- M3 拆单识别（主力手法：拆单/扫单/对敲）----------------
    /** 拆单净主动买入额（元）：同向小单(<大单阈值)在 stealth 窗口内累计破当量，归一为净买方向额。识别主力化整为零吸筹。 */
    private double stealthNetBuy;
    /** 扫单密度（0~1）：窗口内主买笔数占比 × 单位时间成交量强度。识别主动扫货吃卖档。 */
    private double sweepDensity;
    /** 对敲占比（0~1）：±selfTradeWindowMs 内量相近、方向相反成对笔数 / 总笔数。识别自买自卖制造成交。 */
    private double selfTradeRatio;
    /** 主委托形态标签：NORMAL / STEALTH(拆单吸筹) / SWEEP(扫单) / SELF_TRADE(对敲) / MIXED(混合)。 */
    private String orderPattern;
}
