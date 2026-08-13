package com.dunwugudao.replay.mapper.og;

import com.dunwugudao.replay.entity.og.ReplayCalcTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 复盘计算任务（openGauss）—— 分布式任务行记录。
 *
 * <p>所有方法都落在 og 数据源（由 OgMybatisConfig 扫描本包）。
 */
@Mapper
public interface ReplayCalcTaskMapper {

    /** 初始化一条待计算任务（若唯一键已存在则忽略）。 */
    int upsertPending(@Param("tradeDate") LocalDate tradeDate, @Param("calcType") String calcType);

    /**
     * 乐观锁认领：仅当状态为 0（待计算）时可被某节点抢占。
     * 返回受影响行数（1=认领成功，0=已被别人认领或已完成）。
     */
    int claim(@Param("tradeDate") LocalDate tradeDate,
              @Param("calcType") String calcType,
              @Param("node") String node);

    int markRunning(@Param("tradeDate") LocalDate tradeDate,
                    @Param("calcType") String calcType,
                    @Param("node") String node);

    int markDone(@Param("tradeDate") LocalDate tradeDate, @Param("calcType") String calcType);

    int markFailed(@Param("tradeDate") LocalDate tradeDate,
                   @Param("calcType") String calcType,
                   @Param("lastError") String lastError);

    /** 心跳：更新 updated_at，证明节点仍存活。 */
    int heartbeat(@Param("tradeDate") LocalDate tradeDate,
                  @Param("calcType") String calcType,
                  @Param("node") String node);

    /** 查询某交易日下全部任务（含状态），供调度层汇总进度。 */
    List<ReplayCalcTask> selectByTradeDate(@Param("tradeDate") LocalDate tradeDate);
}
