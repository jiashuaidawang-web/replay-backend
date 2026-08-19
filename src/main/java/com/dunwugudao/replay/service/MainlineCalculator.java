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
            return new MainlineResult(List.of(), List.of(), List.of());
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
            return new MainlineResult(List.of(), List.of(), List.of());
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
                lp.setCat("龙");
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

        // 9) 妖·独狼增强（S4 板学寻龙）
        //    板学框架：龙 = 主线板块里被捧上来的明牌龙头（下面有一群小弟）；妖 = 市场高度龙、纯情绪博弈
        //    （钱少炒不动板块才出妖，常跨多题材、换手充分、人气极高，往往由龙进化而来）；独狼 = 无板块支撑、
        //    靠自身逻辑独立走强的换手股（从题材活口走出）。
        //    关键判定：A 股个股被贴上 19~25 个概念标签，几乎所有高位股都「沾边」某主线板块，故不能简单用
        //    「不属任何主线板块」来判妖/独狼（那样会全部落空）。正确做法：妖/独狼 = 有连板高度、但**未被封为
        //    主线龙一~龙五**的个股；而连板足够高（≥demonMinPos）的明牌龙头，同时追加「妖」标签（龙妖一身）。
        //    为保证与龙同行不冲突（排序键 trade_date,ts_code,board_code），妖/独狼统一用哨兵 board_code，
        //    真实弱板块名存于 board_name 供展示。
        List<LeaderPoolDaily> demonsWolves = new ArrayList<>();
        Map<String, List<StockBoardRel>> relsByTs = new LinkedHashMap<>();
        for (StockBoardRel r : rels) {
            relsByTs.computeIfAbsent(r.getTsCode(), k -> new ArrayList<>()).add(r);
        }
        java.util.Set<String> leaderTs = leaders.stream()
                .map(LeaderPoolDaily::getTsCode).collect(Collectors.toSet());
        final String DW_BOARD = "__DW__";
        int demonMinPos = props.getLeader().getDemonMinPos();
        int demonMinBoards = props.getLeader().getDemonMinBoards();
        int wolfMinPos = props.getLeader().getWolfMinPos();
        int demonTopN = props.getLeader().getDemonTopN();
        int wolfTopN = props.getLeader().getWolfTopN();
        for (LimitUpPool up : upByStripped.values()) {
            int pos = (up.getBoardPos() == null) ? 1 : up.getBoardPos();
            boolean isLeader = leaderTs.contains(up.getTsCode());
            boolean isDemon;
            if (isLeader) {
                // 已是明牌龙头：只有连板足够高才追加「妖」标签（龙妖一身）；否则仅龙
                isDemon = pos >= demonMinPos;
                if (!isDemon) {
                    continue;
                }
            } else {
                // 非龙头：连板不足直接跳过；够高则为妖，中段为独狼
                if (pos < wolfMinPos) {
                    continue;
                }
                isDemon = pos >= demonMinPos;
            }
            String ts = strip(up.getTsCode());
            List<StockBoardRel> myRels = relsByTs.getOrDefault(ts, List.of());
            long boardCnt = myRels.stream().map(StockBoardRel::getBoardCode).distinct().count();
            boolean isHuanshou = "换手".equals(up.getLimitStyle());
            double amtYi = (up.getAmount() != null) ? up.getAmount().doubleValue() / 1e8 : 0.0;

            String cat = isDemon ? "妖" : "独狼";
            String role = isDemon ? "妖股" : "独狼";
            double raw = isDemon
                    ? Math.min(100.0, pos * 12.0 + boardCnt * 4.0 + (isHuanshou ? 6.0 : 0.0) + Math.min(amtYi, 8.0))
                    : Math.min(100.0, pos * 16.0 + (isHuanshou ? 5.0 : 0.0) + Math.min(amtYi, 10.0));
            BigDecimal score = BigDecimal.valueOf(raw).setScale(2, RoundingMode.HALF_UP);

            // 代表（弱）板块：取该股本日涨停家数最多的概念板块，仅用于展示；落库用哨兵 board_code
            String repBoardName = "独立（无板块支撑）";
            StockBoardRel best = null;
            int bestCnt = -1;
            for (StockBoardRel r : myRels) {
                int c = byBoard.getOrDefault(r.getBoardCode(), List.of()).size();
                if (c > bestCnt) {
                    bestCnt = c;
                    best = r;
                }
            }
            if (best != null) {
                repBoardName = best.getBoardName();
            }

            StringBuilder note = new StringBuilder();
            if (isDemon) {
                note.append(isLeader ? "明牌龙头·叠加市场高度妖性" : "市场高度龙·纯情绪博弈");
            } else {
                note.append("独立走势·无板块支撑");
            }
            note.append("·").append(pos).append("连板");
            if (isHuanshou) {
                note.append("·换手充分");
            }
            if (boardCnt >= demonMinBoards) {
                note.append("·跨").append(boardCnt).append("题材");
            }
            if (amtYi >= 10) {
                note.append("·人气爆棚(").append(String.format("%.0f", amtYi)).append("亿)");
            }

            LeaderPoolDaily dw = new LeaderPoolDaily();
            dw.setTradeDate(tradeDate);
            dw.setTsCode(up.getTsCode());
            dw.setBoardCode(DW_BOARD);
            dw.setBoardPos(pos == 1 ? null : (short) pos);
            dw.setRole(role);
            dw.setScore(score);
            dw.setCat(cat);
            dw.setAmount(up.getAmount());
            dw.setLimitStyle(up.getLimitStyle());
            dw.setNote(note.toString());
            // 中间量（接口/前端展示用）
            dw.setStockName(up.getStockName());
            dw.setBoardName(repBoardName);
            dw.setTurnoverRate(up.getTurnoverRate());
            demonsWolves.add(dw);
        }
        List<LeaderPoolDaily> demons = demonsWolves.stream().filter(d -> "妖".equals(d.getCat()))
                .sorted(Comparator.comparing(LeaderPoolDaily::getScore).reversed()).limit(demonTopN).toList();
        List<LeaderPoolDaily> wolves = demonsWolves.stream().filter(d -> "独狼".equals(d.getCat()))
                .sorted(Comparator.comparing(LeaderPoolDaily::getScore).reversed()).limit(wolfTopN).toList();
        List<LeaderPoolDaily> finalDW = new ArrayList<>(demons);
        finalDW.addAll(wolves);

        log.info("[主线] 交易日 {} 识别主线 {} 条, 龙头 {} 只, 妖/独狼 {} 只 (妖{}, 独狼{}) (board_type={}, minLimitUp={})",
                tradeDate, mains.size(), leaders.size(), finalDW.size(), demons.size(), wolves.size(),
                boardType, minLimitUp);
        return new MainlineResult(mains, leaders, finalDW);
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
