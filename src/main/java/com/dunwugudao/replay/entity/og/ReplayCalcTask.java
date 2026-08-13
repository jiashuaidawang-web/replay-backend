package com.dunwugudao.replay.entity.og;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 复盘计算任务行记录（仅存 openGauss，支撑分布式任务）。
 *
 * <p>设计目的：复盘计算（S2 情绪 / S4 主线 / S6 趋势 …）按「交易日 × 计算类型」拆成
 * 可独立认领的任务单元。多个 worker 节点抢同一批 (trade_date, calc_type) 时，
 * 通过对行加乐观锁（UPDATE ... WHERE status=0）实现互斥认领，从而：
 * <ul>
 *   <li>避免重复计算同一天同一维度；</li>
 *   <li>节点宕机后，心跳超时的任务可被其它节点重新认领（幂等重算）；</li>
 *   <li>任务状态持久化，调度层可查询进度 / 失败重试。</li>
 * </ul>
 *
 * <p>状态机：0 待计算 → 1 计算中 → 2 完成 / 3 失败。
 */
@Data
public class ReplayCalcTask {

    private Long id;

    /** 交易日。与计算类型组成业务唯一键。 */
    private LocalDate tradeDate;

    /** 计算类型，如 S2_SENTIMENT / S4_MAINLINE / S6_TREND。 */
    private String calcType;

    /** 0 待计算 / 1 计算中 / 2 完成 / 3 失败。 */
    private Integer status;

    /** 认领该任务的节点标识（host:pid），用于心跳与失联判定。 */
    private String node;

    /** 已尝试次数。 */
    private Integer attempt;

    /** 最近一次失败原因（status=3 时填充）。 */
    private String lastError;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
