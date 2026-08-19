package com.dunwugudao.replay.entity.ck.raw;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 主力资金日级聚合（S1 资金维度）。由 MainFundFlowMapper 单日聚合返回。
 */
public class FundFlowAgg {

    /** 当日全市场股票主力净流入合计（元）。 */
    private BigDecimal totalNet;

    /** 主力净流入为正的股票数。 */
    private Long positiveCnt;

    /** 有资金流数据的股票总数。 */
    private Long totalCnt;

    /** 实际使用的资金流日期（可能为回退到的最近可用日）。 */
    private LocalDate usedDate;

    public BigDecimal getTotalNet() { return totalNet; }
    public void setTotalNet(BigDecimal totalNet) { this.totalNet = totalNet; }

    public Long getPositiveCnt() { return positiveCnt; }
    public void setPositiveCnt(Long positiveCnt) { this.positiveCnt = positiveCnt; }

    public Long getTotalCnt() { return totalCnt; }
    public void setTotalCnt(Long totalCnt) { this.totalCnt = totalCnt; }

    public LocalDate getUsedDate() { return usedDate; }
    public void setUsedDate(LocalDate usedDate) { this.usedDate = usedDate; }
}
