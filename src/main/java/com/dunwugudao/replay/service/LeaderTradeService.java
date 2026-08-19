package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.mapper.ck.LeaderPoolDailyMapper;
import com.dunwugudao.replay.mapper.ck.LimitUpPoolMapper;
import com.dunwugudao.replay.vo.LeaderTradeIdeaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S5 龙头买卖 · 基于龙头池派生买卖建议（启发式，非独立计算层）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderTradeService {

    private final CommonService commonService;
    private final LeaderPoolDailyMapper leaderPoolDailyMapper;
    private final LimitUpPoolMapper limitUpPoolMapper;

    public List<LeaderTradeIdeaVO> tradeIdea(LocalDate date) {
        LocalDate d = commonService.resolveDate(date, "leader_pool_daily");
        List<LeaderPoolDaily> leaders = leaderPoolDailyMapper.selectByTradeDate(d);
        if (leaders.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = commonService.boardNameMap(
                leaders.stream().map(LeaderPoolDaily::getBoardCode)
                        .filter(Objects::nonNull).toList());
        Map<String, String> stockNames = commonService.stockNameMap(d);
        return leaders.stream().map(l -> {
            LeaderTradeIdeaVO v = new LeaderTradeIdeaVO();
            v.setTsCode(l.getTsCode());
            v.setStockName(stockNames.get(l.getTsCode()));
            v.setBoardCode(l.getBoardCode());
            v.setBoardName(names.get(l.getBoardCode()));
            v.setBoardPos(l.getBoardPos() == null ? null : l.getBoardPos().intValue());
            v.setRole(l.getRole());
            v.setCat(l.getCat());
            v.setScore(l.getScore());

            int pos = (l.getBoardPos() == null) ? 1 : l.getBoardPos().intValue();
            String role = (l.getRole() == null) ? "" : l.getRole();
            String cat = (l.getCat() == null) ? "" : l.getCat();
            if ("妖".equals(cat) || role.contains("妖")) {
                v.setRiskLevel("极高");
                v.setIdea("妖股：纯情绪博弈、波动剧烈，仅极限分歧日低吸，严禁一致日追高；随时警惕监管与天地板");
                v.setNote("妖股靠人气与换手续命，缩量加速或爆量滞涨即离场信号");
            } else if ("独狼".equals(cat) || role.contains("独狼")) {
                v.setRiskLevel("中");
                v.setIdea("独狼：无板块支撑、独立换手走强，不依赖板块节奏，沿自身趋势低吸跟随");
                v.setNote("独狼不看出板块效应，看个股自身逻辑与换手健康度");
            } else if (role.contains("中军")) {
                v.setRiskLevel("低");
                v.setIdea("中军趋势标的：低吸跟随，不追板");
                v.setNote("中军稳军心，适合波段");
            } else if (pos >= 5) {
                v.setRiskLevel("高");
                v.setIdea("高位龙头：分歧低吸为主，谨慎追高；观察换手放量是否健康");
                v.setNote("高位缩量一字需警惕接盘风险");
            } else if (pos >= 2) {
                v.setRiskLevel("中");
                v.setIdea("试错/打板候选：关注板块强度延续与梯队完整性");
                v.setNote("确认日可低吸或打板，分歧日不追一致");
            } else {
                v.setRiskLevel("中");
                v.setIdea("首板启动：观察次日是否晋级，确认主线地位再加仓");
                v.setNote("首板看不出龙头相，需二板确认");
            }
            return v;
        }).toList();
    }
}
