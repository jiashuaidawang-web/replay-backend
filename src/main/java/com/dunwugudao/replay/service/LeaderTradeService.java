package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.LeaderTradeDaily;
import com.dunwugudao.replay.mapper.ck.LeaderTradeDailyMapper;
import com.dunwugudao.replay.vo.LeaderTradeIdeaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S5 龙头买卖 · 接口层服务。从 leader_trade_daily 独立计算层表读取，
 * 回填板块名/股票名后返回 VO。
 *
 * <p>计算逻辑见 {@link LeaderTradeCalculator}，由 {@link com.dunwugudao.replay.job.ReplayCalcJob} 编排落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderTradeService {

    private final CommonService commonService;
    private final LeaderTradeDailyMapper leaderTradeDailyMapper;

    public List<LeaderTradeIdeaVO> tradeIdea(LocalDate date) {
        return tradeIdea(date, null);
    }

    public List<LeaderTradeIdeaVO> tradeIdea(LocalDate date, String boardCode) {
        LocalDate d = commonService.resolveDate(date, "leader_trade_daily");
        List<LeaderTradeDaily> trades;
        if (boardCode != null && !boardCode.isBlank()) {
            trades = leaderTradeDailyMapper.selectByTradeDateAndBoard(d, boardCode);
        } else {
            trades = leaderTradeDailyMapper.selectByTradeDate(d);
        }
        if (trades.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = commonService.boardNameMap(
                trades.stream().map(LeaderTradeDaily::getBoardCode)
                        .filter(Objects::nonNull).toList());
        Map<String, String> stockNames = commonService.stockNameMap(d);
        return trades.stream().map(t -> {
            LeaderTradeIdeaVO v = new LeaderTradeIdeaVO();
            v.setTsCode(t.getTsCode());
            v.setStockName(stockNames.get(t.getTsCode()));
            v.setBoardCode(t.getBoardCode());
            v.setBoardName(names.getOrDefault(t.getBoardCode(),
                    "__DW__".equals(t.getBoardCode()) ? "独立" : t.getBoardCode()));
            v.setBoardPos(t.getBoardPos() == null ? null : t.getBoardPos().intValue());
            v.setRole(t.getRole());
            v.setCat(t.getCat());
            v.setScore(t.getScore());
            v.setAction(t.getAction());
            v.setSignal(t.getSignal());
            v.setRiskLevel(t.getRiskLevel());
            v.setBuyScore(t.getBuyScore());
            v.setReason(t.getReason());
            // idea 映射 reason，保持前端兼容
            v.setIdea(t.getReason());
            v.setNote(t.getNote());
            return v;
        }).toList();
    }
}
