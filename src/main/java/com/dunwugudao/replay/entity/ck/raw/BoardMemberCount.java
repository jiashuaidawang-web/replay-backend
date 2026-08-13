package com.dunwugudao.replay.entity.ck.raw;

import lombok.Data;

/**
 * 板块成分股数量（按 board_code 聚合 stock_board_rel）。用于 S7 概念稀缺性初算。
 */
@Data
public class BoardMemberCount {

    private String boardCode;

    private Long memberCount;
}
