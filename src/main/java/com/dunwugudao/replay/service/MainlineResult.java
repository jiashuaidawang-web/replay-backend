package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.entity.MainlineDaily;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * S4 计算产出载体：主线板块列表 + 龙头池列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MainlineResult {

    /** 主线板块（按强度降序，已裁 topN）。 */
    private List<MainlineDaily> mainlines;

    /** 龙头池（每个主线板块下的前 N 只个股）。 */
    private List<LeaderPoolDaily> leaders;
}
