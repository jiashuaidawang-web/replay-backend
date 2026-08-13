package com.dunwugudao.replay.entity.ck.raw;

import lombok.Data;

/**
 * 板块基础维表行（ClickHouse: board_basic）。
 *
 * <p>用于 S7 题材派生（T64）与概念清单校验；board_type: 1=地域 2=行业 3=概念。
 */
@Data
public class BoardBasic {

    private Integer boardType;
    private String code;
    private String boardCode;
    private String boardName;
    private String features;
    private Integer status;
}
