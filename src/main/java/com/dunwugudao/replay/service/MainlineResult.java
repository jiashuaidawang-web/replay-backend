package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.entity.MainlineDaily;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * S4 计算产出载体：主线板块列表 + 龙头池列表 + 妖/独狼列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MainlineResult {

    /** 主线板块（按强度降序，已裁 topN）。 */
    private List<MainlineDaily> mainlines;

    /** 龙头池（每个主线板块下的前 N 只个股，role=龙一~龙五）。 */
    private List<LeaderPoolDaily> leaders;

    /** 妖股 + 独狼（脱离板块/独立走势的猎物，role=妖股/独狼，与 leaders 互斥）。 */
    private List<LeaderPoolDaily> demonsWolves;
}
