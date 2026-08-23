package com.dunwugudao.replay.realtime.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 实时板块/指数快照（来自 Redis Stream {@code rt:sector}，可选）。
 * <p>约定：爬虫 XADD 时单字段 {@code payload} = 本对象的 JSON。
 * <p><b>容错</b>：{@code @JsonIgnoreProperties(ignoreUnknown=true)} 已开启，爬虫侧新增字段后端不报错（向后兼容）。
 * <p><b>字段待爬虫侧最终确认</b>：ts 为 epoch millis(long)；leadTsCode 为领涨股代码。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Sector implements Serializable {
    private String boardCode;     // 板块代码
    private long ts;
    private double pctChg;        // 板块涨跌幅 %
    private String leadTsCode;    // 领涨股
}
