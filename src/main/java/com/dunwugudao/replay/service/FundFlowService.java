package com.dunwugudao.replay.service;

import com.dunwugudao.replay.mapper.ck.DragonTigerMapper;
import com.dunwugudao.replay.mapper.ck.MainFundFlowMapper;
import com.dunwugudao.replay.vo.DragonTigerVO;
import com.dunwugudao.replay.vo.DtDetailVO;
import com.dunwugudao.replay.vo.FundFlowBoardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * S3 主力博弈 · 板块资金流向 + 龙虎榜。
 */
@Service
@RequiredArgsConstructor
public class FundFlowService {

    private final CommonService commonService;
    private final MainFundFlowMapper mainFundFlowMapper;
    private final DragonTigerMapper dragonTigerMapper;

    /** boardFlow 排序白名单（防 SQL 注入 + 非法值静默 200）。 */
    private static final List<String> ALLOWED_ORDER_BY = List.of("main_net", "super_big", "big_net");
    /** boardFlow top 上限（避免超大 LIMIT + 后续 500 个 IN 回填）。 */
    private static final int TOP_MAX = 200;

    public List<FundFlowBoardVO> boardFlow(LocalDate date, String orderBy, int top) {
        LocalDate d = commonService.resolveDate(date, "main_fund_flow");
        String ob = (orderBy == null || orderBy.isBlank()) ? "main_net" : orderBy;
        if (!ALLOWED_ORDER_BY.contains(ob)) {
            throw new IllegalArgumentException(
                    "orderBy 仅支持: " + String.join(", ", ALLOWED_ORDER_BY));
        }
        int t = (top <= 0) ? 10 : Math.min(top, TOP_MAX);
        List<FundFlowBoardVO> list = mainFundFlowMapper.selectBoardFlow(d, t, ob);
        // main_fund_flow.name 常为 null，统一从 board_basic 回填板块名（与其它接口一致）
        Map<String, String> names = commonService.boardNameMap(
                list.stream().map(FundFlowBoardVO::getBoardCode).toList());
        list.forEach(v -> v.setBoardName(names.get(v.getBoardCode())));
        return list;
    }

    public List<DragonTigerVO> dragonTiger(LocalDate date) {
        LocalDate d = commonService.resolveDate(date, "dragon_tiger");
        return dragonTigerMapper.selectDragonTiger(d);
    }

    public List<DtDetailVO> dtDetail(LocalDate date, String tsCode) {
        LocalDate d = commonService.resolveDate(date, "dragon_tiger");
        return dragonTigerMapper.selectDtDetail(d, tsCode);
    }
}
