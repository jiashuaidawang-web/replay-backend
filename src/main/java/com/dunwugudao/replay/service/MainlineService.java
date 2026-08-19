package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.entity.MainlineDaily;
import com.dunwugudao.replay.mapper.ck.LeaderPoolDailyMapper;
import com.dunwugudao.replay.mapper.ck.LimitUpPoolMapper;
import com.dunwugudao.replay.mapper.ck.MainlineDailyMapper;
import com.dunwugudao.replay.vo.LeaderVO;
import com.dunwugudao.replay.vo.MainlineVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S4 板学寻龙 · 主线列表 + 龙头池。
 */
@Service
@RequiredArgsConstructor
public class MainlineService {

    private final CommonService commonService;
    private final MainlineDailyMapper mainlineDailyMapper;
    private final LeaderPoolDailyMapper leaderPoolDailyMapper;
    private final LimitUpPoolMapper limitUpPoolMapper;

    public List<MainlineVO> mainline(LocalDate date) {
        LocalDate d = commonService.resolveDate(date, "leader_pool_daily");
        List<MainlineDaily> mains = mainlineDailyMapper.selectByTradeDate(d);
        Map<String, String> names = commonService.boardNameMap(
                mains.stream().map(MainlineDaily::getBoardCode).toList());
        return mains.stream().map(m -> {
            MainlineVO v = new MainlineVO();
            v.setBoardCode(m.getBoardCode());
            v.setBoardName(names.get(m.getBoardCode()));
            v.setMainLevel(m.getMainLevel());
            v.setStrength(m.getStrength());
            v.setRank(m.getRank());
            return v;
        }).toList();
    }

    public List<LeaderVO> leaders(LocalDate date, String boardCode) {
        LocalDate d = commonService.resolveDate(date, "leader_pool_daily");
        List<LeaderPoolDaily> leaders = (boardCode != null && !boardCode.isBlank())
                ? leaderPoolDailyMapper.selectByTradeDateAndBoard(d, boardCode)
                : leaderPoolDailyMapper.selectByTradeDate(d);
        Map<String, String> names = commonService.boardNameMap(
                leaders.stream().map(LeaderPoolDaily::getBoardCode)
                        .filter(Objects::nonNull).toList());
        Map<String, String> stockNames = commonService.stockNameMap(d);
        return leaders.stream().map(l -> {
            LeaderVO v = new LeaderVO();
            v.setTsCode(l.getTsCode());
            v.setStockName(stockNames.get(l.getTsCode()));
            v.setBoardCode(l.getBoardCode());
            // 妖/独狼用哨兵 __DW__ 避免与龙的主键冲突；该哨兵无对应板块名，展示为"独立"
            String bn = names.get(l.getBoardCode());
            if (bn == null && "__DW__".equals(l.getBoardCode())) {
                bn = "独立";
            }
            v.setBoardName(bn != null ? bn : l.getBoardName());
            v.setBoardPos(l.getBoardPos() == null ? null : l.getBoardPos().intValue());
            v.setRole(l.getRole());
            v.setCat(l.getCat());
            v.setScore(l.getScore());
            v.setAmount(l.getAmount());
            v.setLimitStyle(l.getLimitStyle());
            v.setNote(l.getNote());
            return v;
        }).toList();
    }
}
