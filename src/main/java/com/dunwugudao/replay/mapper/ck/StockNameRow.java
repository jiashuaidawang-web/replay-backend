package com.dunwugudao.replay.mapper.ck;

import lombok.Data;

/**
 * stock_daily 股票名查询行：tsCode 为去后缀代码（splitByChar 第一段）。
 * 多行结果用 List 返回（避免 MyBatis 单列 Map 多行映射的 TooManyResults 坑），由服务层聚合成 Map。
 */
@Data
public class StockNameRow {
    private String tsCode;
    private String stockName;
}
