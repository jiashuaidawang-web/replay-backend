package com.dunwugudao.replay.service;

import com.dunwugudao.replay.config.ReplayProperties;
import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.entity.MainlineDaily;
import com.dunwugudao.replay.entity.ck.raw.BoardDaily;
import com.dunwugudao.replay.entity.ck.raw.LimitUpPool;
import com.dunwugudao.replay.entity.ck.raw.StockBoardRel;
import com.dunwugudao.replay.mapper.ck.BoardDailyMapper;
import com.dunwugudao.replay.mapper.ck.StockBoardRelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * S4 板学寻龙 · 主线识别与龙头定位（顿悟股道·板学篇）。
 *
 * <p>算法要点（已规避三大口径坑）：
 * <ul>
 *   <li><b>不用</b> limit_up_pool.board_code（截断行业名），改用
 *       {@code limit_up_pool → stock_board_rel(按 board_type) → board_code} 反查真实 BK 板块。</li>
 *   <li>limit_up_pool.ts_code 带后缀，stock_board_rel.ts_code 无后缀 → 先 {@link #strip} 去后缀再 join。</li>
 *   <li>用 {@link ConceptClassifier#isRealTheme} 过滤伪概念（融资融券/昨日涨停/小盘股…），
 *       只认 board_type=3 且为<b>真题材</b>的板块作为主线候选。</li>
 * </ul>
 *
 * <p>强度 = w1·涨停家数(归一) + w2·板块涨幅 + w3·板块资金净流入(归一)，落在 0~100。
 * 龙头评分 = 连板高度 + 换手风格加分 + 板块强度贡献；板内按评分排 龙一/龙二…。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MainlineCalculator {

    private final ReplayProperties props;
    private final StockBoardRelMapper stockBoardRelMapper;
    private final BoardDailyMapper boardDailyMapper;

    public MainlineResult compute(LocalDate tradeDate, List<LimitUpPool> ups) {
        if (ups == null || ups.isEmpty()) {
            return new MainlineResult(List.of(), List.of());
        }
        int boardType = props.getMainline().getBoardType();
        double w1 = props.getMainline().getWeightLimitUp();
        double w2 = props.getMainline().getWeightPctChg();
        double w3 = props.getMainline().getWeightFund();
        int minLimitUp = props.getMainline().getMinLimitUp();
        int topN = props.getMainline().getTopN();
        int topNPerBoard = props.getLeader().getTopNPerBoard();

        // 1) 涨停股（去后缀）→ 原始行映射
        Map<String, LimitUpPool> upByStripped = new LinkedHashMap<>();
        for (LimitUpPool u : ups) {
            upByStripped.put(strip(u.getTsCode()), u);
        }

        // 2) 反查真实板块归属（仅 board_type 指定类型）
        List<StockBoardRel> rels = stockBoardRelMapper
                .selectByTsCodesAndBoardType(new ArrayList<>(upByStripped.keySet()), boardType);

        // 3) 过滤伪概念，按板块聚合
        Map<String, List<StockBoardRel>> byBoard = rels.stream()
                .filter(r -> ConceptClassifier.isRealTheme(r.getBoardName()))
                .collect(Collectors.groupingBy(StockBoardRel::getBoardCode, LinkedHashMap::new, Collectors.toList()));

        // 4) 各板块涨停家数（去重 ts_code），淘汰低于阈值的
        Map<String, Long> limitUpCntByBoard = new LinkedHashMap<>();
        for (Map.Entry<String, List<StockBoardRel>> e : byBoard.entrySet()) {
            long cnt = e.getValue().stream().map(StockBoardRel::getTsCode).distinct().count();
            if (cnt >= minLimitUp) {
                limitUpCntByBoard.put(e.getKey(), cnt);
            }
        }
        if (limitUpCntByBoard.isEmpty()) {
            log.warn("[主线] 无满足 minLimitUp={} 的真题材板块", minLimitUp);
            return new MainlineResult(List.of(), List.of());
        }

        // 5) 取这些板块的当日日线（涨幅/资金流）
        List<BoardDaily> boards = boardDailyMapper
                .selectByBoardCodesAndDate(new ArrayList<>(limitUpCntByBoard.keySet()), tradeDate);
        Map<String, BoardDaily> boardMap = boards.stream()
                .collect(Collectors.toMap(BoardDaily::getBoardCode, b -> b, (a, b) -> a));

        // 6) 计算强度并构建主线
        List<MainlineDaily> mains = new ArrayList<>();
        for (Map.Entry<String, Long> e : limitUpCntByBoard.entrySet()) {
            String boardCode = e.getKey();
            long cnt = e.getValue();
            BoardDaily bd = boardMap.get(boardCode);
            double pct = (bd != null && bd.getPctChg() != null) ? bd.getPctChg().doubleValue() : 0.0;
            double net = (bd != null && bd.getMainNet() != null) ? bd.getMainNet().doubleValue() : 0.0;

            double c1 = Math.min(cnt / 10.0, 1.0);          // 10+ 家涨停饱和
            double c2 = Math.min(Math.max(pct, 0) / 5.0, 1.0); // 5%+ 涨幅饱和
            double c3 = Math.min(Math.abs(net) / 5e8, 1.0);   // 5 亿资金流饱和
            double strength = 100 * (w1 * c1 + w2 * c2 + w3 * c3);

            MainlineDaily m = new MainlineDaily();
            m.setTradeDate(tradeDate);
            m.setBoardCode(boardCode);
            m.setStrength(BigDecimal.valueOf(strength).setScale(2, RoundingMode.HALF_UP));
            // 中间量（日志/接口用）
            m.setBoardName(byBoard.get(boardCode).get(0).getBoardName());
            m.setLimitUpCnt((int) cnt);
            m.setPctChg(bd != null ? bd.getPctChg() : null);
            m.setMainNet(bd != null ? bd.getMainNet() : null);
            mains.add(m);
        }

        // 7) 排序 + 排名 + 层级
        mains.sort(Comparator.comparing(MainlineDaily::getStrength).reversed());
        for (int i = 0; i < mains.size(); i++) {
            MainlineDaily m = mains.get(i);
            m.setRank(i + 1);
            int r = i + 1;
            m.setMainLevel(r <= 3 ? "一线" : (r <= 10 ? "二线" : "三线"));
        }
        if (mains.size() > topN) {
            mains = mains.subList(0, topN);
        }

        // 8) 龙头池：每个主线板块下的涨停股，按评分排 龙一/龙二…
        List<LeaderPoolDaily> leaders = new ArrayList<>();
        for (MainlineDaily m : mains) {
            String boardCode = m.getBoardCode();
            BigDecimal strength = m.getStrength();
            List<StockBoardRel> members = byBoard.get(boardCode);
            List<LeaderPoolDaily> boardLeaders = new ArrayList<>();
            for (StockBoardRel rel : members) {
                LimitUpPool up = upByStripped.get(rel.getTsCode());
                if (up == null) {
                    continue; // 防御：理论上都在 upByStripped 内
                }
                int pos = (up.getBoardPos() == null) ? 1 : up.getBoardPos();
                boolean isHuanshou = "换手".equals(up.getLimitStyle());
                double raw = pos * 1.0 + (isHuanshou ? 1.0 : 0.0)
                        + strength.doubleValue() / 100.0 * 2.0;
                BigDecimal score = BigDecimal.valueOf(Math.min(100.0, raw * 20.0))
                        .setScale(2, RoundingMode.HALF_UP);

                LeaderPoolDaily lp = new LeaderPoolDaily();
                lp.setTradeDate(tradeDate);
                lp.setTsCode(up.getTsCode());
                lp.setBoardCode(boardCode);
                lp.setBoardPos(pos == 1 ? null : (short) pos); // 首板不记录连板
                lp.setRole(""); // 排序后回填
                lp.setScore(score);
                // 中间量
                lp.setStockName(up.getStockName());
                lp.setBoardName(rel.getBoardName());
                lp.setLimitStyle(up.getLimitStyle());
                lp.setAmount(up.getAmount());
                lp.setTurnoverRate(up.getTurnoverRate());
                boardLeaders.add(lp);
            }
            boardLeaders.sort(Comparator.comparing(LeaderPoolDaily::getScore).reversed());
            for (int i = 0; i < boardLeaders.size() && i < topNPerBoard; i++) {
                boardLeaders.get(i).setRole("龙" + cnNum(i + 1));
                leaders.add(boardLeaders.get(i));
            }
        }

        log.info("[主线] 交易日 {} 识别主线 {} 条, 龙头 {} 只 (board_type={}, minLimitUp={})",
                tradeDate, mains.size(), leaders.size(), boardType, minLimitUp);
        return new MainlineResult(mains, leaders);
    }

    /** 去后缀：300686.SZ → 300686。 */
    private static String strip(String tsCode) {
        if (tsCode == null) {
            return null;
        }
        int i = tsCode.indexOf('.');
        return i < 0 ? tsCode : tsCode.substring(0, i);
    }

    /** 1~10 中文数字。 */
    private static String cnNum(int n) {
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        return (n >= 1 && n <= 10) ? cn[n - 1] : String.valueOf(n);
    }
}
