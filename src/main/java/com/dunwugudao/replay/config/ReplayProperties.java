package com.dunwugudao.replay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 复盘计算阈值参数。集中在 application.yml 的 replay.* 下，便于按市场风格调参而不改代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "replay")
public class ReplayProperties {

    private Sentiment sentiment = new Sentiment();
    private Mainline mainline = new Mainline();
    private Leader leader = new Leader();
    private Theme theme = new Theme();
    private Trend trend = new Trend();

    @Data
    public static class Sentiment {
        /** 切换到分位数口径所需的最少历史交易日；不足则用绝对阈值降级。 */
        private int minHistoryDays = 60;
        private int iceLimitUpMax = 30;
        private int coldLimitUpMax = 60;
        private int warmLimitUpMax = 100;
        private int hotLimitUpMax = 150;
        /** 昨日涨停今日平均涨幅 >= 该值视为赚钱效应强。 */
        private double strongMoneyEffect = 3.0;
        /** 昨日涨停今日平均涨幅 <= 该值视为亏钱效应。 */
        private double weakMoneyEffect = -1.0;
    }

    @Data
    public static class Mainline {
        /** 在哪类板块里找主线：3=概念板块（题材主线），2=行业板块。 */
        private int boardType = 3;
        /** 板块至少涨停家数，低于该值不进主线候选。 */
        private int minLimitUp = 2;
        private int topN = 20;
        private double weightLimitUp = 0.5;
        private double weightPctChg = 0.3;
        private double weightFund = 0.2;
    }

    @Data
    public static class Leader {
        /** 每个主线板块输出前 N 只个股。 */
        private int topNPerBoard = 5;
    }

    @Data
    public static class Theme {
        /** 六因子权重（综合分 = Σwᵢ·因子ᵢ / Σwᵢ × 100），可缺省，缺省各 0.2 等权。 */
        private double wScarcity = 0.2;     // 稀缺性
        private double wImagination = 0.2;  // 想象空间
        private double wSudden = 0.2;       // 突发性
        private double wCertainty = 0.2;    // 确定性
        private double wMinResist = 0.2;    // 最小阻力方向
    }

    @Data
    public static class Trend {
        /** ③ 健康量价：近 4 周均量 / 前 4 周均量 >= 该值视为放量突破。 */
        private double volRatio = 1.15;
        /** ⑤ RS 代理：收盘价 52 周区间相对位置 >= 该值视为领涨（缺 index_weekly 时的代理）。 */
        private double rsPos = 0.7;
        /** ⑥ RSI(14) 周线突破该值视为买点信号。 */
        private double rsiThreshold = 70.0;
        /** 趋势确认：自大底最低点(52周低)涨幅 % > 该值。 */
        private double gainThreshold = 25.0;
        /** 扫描默认最低命中特征数。 */
        private int minFeature = 6;
    }
}
