package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.realtime.model.Tick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * M3 拆单识别——主力手法分析器（独立、可单测）。
 *
 * <p>从滚动窗口内的逐笔 tick 序列，识别三类主力手法：
 * <ol>
 *   <li><b>拆单吸筹（STEALTH）</b>：同向（主买）小单（单笔额 < 大单阈值）在 stealth 窗口内累计量破当量
 *       （默认万手）→ 主力化整为零吸筹，绕过大单监控。</li>
 *   <li><b>扫单（SWEEP）</b>：短时间密集主买 + 量强度高 → 主动扫货吃掉卖档。</li>
 *   <li><b>对敲（SELF_TRADE）</b>：±selfTradeWindowMs 内量相近、方向相反成对的笔 → 自买自卖制造成交量。</li>
 * </ol>
 *
 * <p>各类用各自更小的时间窗（取"最近 N 秒/毫秒"）代表当前态势，避免对整段历史平滑掉瞬时手法。
 */
public final class OrderPatternAnalyzer {

    private OrderPatternAnalyzer() {
    }

    /** 分析配置（阈值集中传入，便于调参与测试）。 */
    public record Config(long stealthWindowSec, double stealthMinVolume,
                         long sweepWindowSec, long selfTradeWindowMs, double selfTradeVolTol,
                         double bigOrderThreshold) {
    }

    /** 分析结果。 */
    public record Result(double stealthNetBuy, double sweepDensity,
                         double selfTradeRatio, String orderPattern) {
    }

    public static Result analyze(List<Tick> ticks, long cutoff, long now, Config cfg) {
        if (ticks == null || ticks.isEmpty()) {
            return new Result(0, 0, 0, "NORMAL");
        }
        List<Tick> win = new ArrayList<>();
        for (Tick t : ticks) {
            if (t.getTs() >= cutoff) {
                win.add(t);
            }
        }
        if (win.isEmpty()) {
            return new Result(0, 0, 0, "NORMAL");
        }

        // ---- 量缺失探测（JiTu 现状：逐笔 volume 恒为 0）----
        // 量维度全缺时：拆单/对敲无法计算 → 退化 NORMAL；扫单退化为纯价格上行因子（不用量）。
        // 爬虫补量后 hasVolume=true，全部分支自动恢复。
        boolean hasVolume = false;
        for (Tick t : win) {
            if (t.getVolume() > 0) {
                hasVolume = true;
                break;
            }
        }

        // ---- 1. 拆单吸筹（依赖量，量缺失直接跳过）----
        double stealthNet = 0;
        boolean stealthHit = false;
        if (!hasVolume) {
            // 量缺失：无法识别拆单，保持 stealthNet=0、stealthHit=false（后续形态判定不会命中 STEALTH）
        } else {
            long stealthCut = now - cfg.stealthWindowSec() * 1000L;
            double stealthBuyVol = 0;
            for (Tick t : win) {
                if (t.getTs() < stealthCut) {
                    continue;
                }
                if (t.directionSign() == 1 && t.getAmount() < cfg.bigOrderThreshold()) {
                    stealthBuyVol += t.getVolume();
                    stealthNet += t.getAmount();
                }
            }
            stealthHit = stealthBuyVol >= cfg.stealthMinVolume();
            if (!stealthHit) {
                stealthNet = 0;
            }
        }

        // ---- 2. 扫单密度 ----
        // 扫单 = 密集主买 + 主动吃卖档推高成交价（价格斜率上行）。纯拆单价格平稳，不会误判。
        long sweepCut = now - cfg.sweepWindowSec() * 1000L;
        int sweepTotal = 0, sweepBuyCnt = 0;
        double sweepVol = 0;
        double sweepFirstPrice = 0, sweepLastPrice = 0;
        boolean sweepHasPrice = false;
        for (Tick t : win) {
            if (t.getTs() < sweepCut) {
                continue;
            }
            sweepTotal++;
            sweepVol += t.getVolume();
            if (t.directionSign() == 1) {
                sweepBuyCnt++;
            }
            if (!sweepHasPrice && t.getPrice() > 0) {
                sweepFirstPrice = t.getPrice();
                sweepHasPrice = true;
            }
            if (t.getPrice() > 0) {
                sweepLastPrice = t.getPrice();
            }
        }
        double sweepDensity = 0;
        if (sweepTotal > 0) {
            double buyRatio = (double) sweepBuyCnt / sweepTotal;
            double avgVol = sweepVol / sweepTotal;
            // 量强度：窗口每笔均量相对"大单阈值手数"占比（价取 ~1850 近似），封顶 1。
            // 量缺失（JiTu）时 avgVol=0 → volStrength=0，仅价格因子驱动（退化，不误判）。
            double volStrength = hasVolume
                    ? Math.min(1.0, avgVol / (cfg.bigOrderThreshold() / 100.0 / 1850.0)) : 0.0;
            // 价格上行因子：扫单吃卖档会推高成交价；拆单价格平稳→因子趋 0
            double priceUp = sweepFirstPrice > 0
                    ? Math.max(0, (sweepLastPrice - sweepFirstPrice) / sweepFirstPrice) : 0;
            double priceFactor = Math.min(1.0, priceUp * 40.0); // 0.25% 涨幅即封顶
            // 扫单核心 = 价格主动上行（吃卖档）。priceFactor 主导，主买占比+量强度作辅助门槛。
            // 拆单（价格平稳）priceFactor≈0 → sweepDensity 被压到 ~0，不会误判。
            sweepDensity = Math.min(1.0,
                    priceFactor * (0.5 + 0.3 * buyRatio + 0.2 * volStrength)
                            + (priceFactor <= 0.05 ? 0 : 0)); // 无价格上行则归零
            if (priceFactor <= 0.05) {
                sweepDensity = 0; // 价格平稳：强制非扫单（拆单特征）
            }
        }

        // ---- 3. 对敲占比 ----
        // 量缺失（JiTu）时量相近度不可计算 → 直接 0（退化 NORMAL），不进入配对逻辑避免假信号。
        if (!hasVolume) {
            return new Result(stealthNet, sweepDensity, 0, labelPattern(0, sweepDensity, false));
        }
        List<Tick> sorted = new ArrayList<>(win);
        sorted.sort(Comparator.comparingLong(Tick::getTs));
        int paired = 0;
        boolean[] used = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (used[i]) {
                continue;
            }
            Tick a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                if (used[j]) {
                    continue;
                }
                Tick b = sorted.get(j);
                long dt = Math.abs(b.getTs() - a.getTs());
                if (dt > cfg.selfTradeWindowMs()) {
                    break;
                }
                if (a.directionSign() == -b.directionSign() && a.directionSign() != 0
                        && b.directionSign() != 0) {
                    double maxV = Math.max(a.getVolume(), b.getVolume());
                    double diff = Math.abs(a.getVolume() - b.getVolume()) / (maxV > 0 ? maxV : 1);
                    if (diff <= cfg.selfTradeVolTol()) {
                        used[i] = true;
                        used[j] = true;
                        paired += 2;
                        break;
                    }
                }
            }
        }
        double selfTradeRatio = sorted.size() > 0 ? (double) paired / sorted.size() : 0;

        String pattern = labelPattern(selfTradeRatio, sweepDensity, stealthHit);
        return new Result(stealthNet, sweepDensity, selfTradeRatio, pattern);
    }

    /** 形态标签：对敲 > 0.3 / 扫单密度 > 0.6 / 拆单命中 三选一，多命中则 MIXED。 */
    private static String labelPattern(double selfTradeRatio, double sweepDensity, boolean stealthHit) {
        boolean isSelf = selfTradeRatio > 0.3;
        boolean isSweep = sweepDensity > 0.6;
        boolean isStealth = stealthHit;
        int hitCount = (isSelf ? 1 : 0) + (isSweep ? 1 : 0) + (isStealth ? 1 : 0);
        if (hitCount == 0) {
            return "NORMAL";
        } else if (hitCount > 1) {
            return "MIXED";
        } else if (isSelf) {
            return "SELF_TRADE";
        } else if (isSweep) {
            return "SWEEP";
        } else {
            return "STEALTH";
        }
    }
}
