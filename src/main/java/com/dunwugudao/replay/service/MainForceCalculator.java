package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.MainForceDaily;
import com.dunwugudao.replay.mapper.ck.DragonTigerMapper;
import com.dunwugudao.replay.mapper.ck.StockDailyMapper;
import com.dunwugudao.replay.vo.DragonTigerVO;
import com.dunwugudao.replay.vo.DtDetailVO;
import com.dunwugudao.replay.vo.StockClose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S3 主力博弈 · 个股级主力合力量化（《顿悟股道》第一章第四节：抱团=合力、破除主力至上论）。
 *
 * <p>输入：龙虎榜个股榜 dragon_tiger + 席位明细 dt_detail + stock_daily（算主力买入后 N 日收益）。
 * 输出：每只上榜个股的主力博弈画像——
 * <ul>
 *   <li>席位结构：买/卖席位数、机构/游资(营业部)/北向 净买；</li>
 *   <li>合力强度 consensus_score（买席占比 0~1）；</li>
 *   <li>分歧 flag（多空对决 vs 单向一致）；</li>
 *   <li>主力可信度 credibility_flag：主力净买后次日/5日胜(1) 还是被埋(0)，直接验证"有主力买入≠必涨"。</li>
 * </ul>
 * 注：dragon_tiger 的 d1/d5_close_adjchrate 当前全为空（爬虫未回补），故 N 日收益直接由 stock_daily
 * 收盘价推算（取榜日后第 1/第 5 个交易日），更可靠、可自愈。
 *
 * <p>数据约束（当前快照）：dt_detail 的 seat_type 实际取值为 营业部/机构/\N（游资即营业部席位、北向暂无），
 * 算法按中文名归类并对缺失做容错；stock_daily 后续交易日可能不足，d5 收益按需为 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MainForceCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final DragonTigerMapper dragonTigerMapper;
    private final StockDailyMapper stockDailyMapper;

    public List<MainForceDaily> compute(LocalDate tradeDate) {
        List<DragonTigerVO> stocks = dragonTigerMapper.selectDragonTiger(tradeDate);
        List<DtDetailVO> details = dragonTigerMapper.selectDtDetailByDate(tradeDate);
        Map<String, List<DtDetailVO>> byStock = details.stream()
                .collect(Collectors.groupingBy(DtDetailVO::getTsCode));

        // 由 stock_daily 推算主力买入后 N 日收益（ts_code -> 按日期升序的收盘价序列）
        Map<String, List<StockClose>> closesByStock = new LinkedHashMap<>();
        if (!stocks.isEmpty()) {
            List<String> codes = stocks.stream().map(DragonTigerVO::getTsCode).distinct().toList();
            List<StockClose> closes = stockDailyMapper.selectCloses(codes, tradeDate);
            closesByStock.putAll(closes.stream().collect(Collectors.groupingBy(StockClose::getTsCode)));
        }

        List<MainForceDaily> result = new ArrayList<>();
        for (DragonTigerVO s : stocks) {
            MainForceDaily r = new MainForceDaily();
            r.setTradeDate(tradeDate);
            r.setTsCode(s.getTsCode());
            r.setStockName(s.getStockName());
            r.setReason(s.getReason());
            r.setAbnormalType(s.getAbnormalType());
            r.setNetBuy(s.getNetBuy());
            r.setTotalBuy(s.getTotalBuy());
            r.setTotalSell(s.getTotalSell());
            r.setChangeRate(s.getChangeRate());
            r.setClosePrice(s.getClosePrice());
            r.setTurnoverrate(s.getTurnoverrate());

            // ---- 席位聚合 ----
            List<DtDetailVO> ds = byStock.getOrDefault(s.getTsCode(), List.of());
            int buySeat = 0, sellSeat = 0;
            BigDecimal org = ZERO, youzi = ZERO, north = ZERO;
            for (DtDetailVO d : ds) {
                BigDecimal nb = d.getNetBuy() != null ? d.getNetBuy() : ZERO;
                if (nb.signum() > 0) buySeat++;
                else if (nb.signum() < 0) sellSeat++;
                String t = d.getSeatType();
                if (t == null) continue;
                if ("机构".equals(t)) org = org.add(nb);
                else if ("营业部".equals(t)) youzi = youzi.add(nb);
                else if (t.contains("股通")) north = north.add(nb);
            }
            r.setBuySeatCnt(buySeat);
            r.setSellSeatCnt(sellSeat);
            r.setOrgNetBuy(round2(org));
            r.setYouziNetBuy(round2(youzi));
            r.setNorthNetBuy(round2(north));

            // ---- 合力强度：买席占比 0~1 ----
            int tot = buySeat + sellSeat;
            double cs = tot > 0 ? (double) buySeat / tot : 0.5;
            r.setConsensusScore(round4(cs));

            // ---- 分歧 flag：多空双方都有且力量较均衡 → 多空对决 ----
            boolean diverg = buySeat > 0 && sellSeat > 0
                    && ((double) Math.min(buySeat, sellSeat) / Math.max(buySeat, sellSeat)) >= 0.4;
            r.setDivergenceFlag(diverg ? 1 : 0);

            // ---- 主力可信度：由 stock_daily 推算净买后次日/5日收益 ----
            BigDecimal d1 = nextReturn(closesByStock.get(s.getTsCode()), tradeDate, 1);
            BigDecimal d5 = nextReturn(closesByStock.get(s.getTsCode()), tradeDate, 5);
            r.setD1Return(d1);
            r.setD5Return(d5);

            BigDecimal net = s.getNetBuy();
            int cred;
            String note = "";
            if (net == null) {
                cred = 2; // 无净买数据
                note = "无个股净买数据";
            } else if (net.signum() <= 0) {
                cred = -1; // 净卖，不判定胜败
                note = "主力当日净卖出，不做次日胜败判定";
            } else if (d1 == null) {
                cred = 2; // 次日数据未回补
                note = "主力净买，但次日收益尚未回补(榜日后无后续交易日)";
            } else if (d1.signum() > 0) {
                cred = 1; // 主力次日胜
            } else {
                cred = 0; // 主力被埋
                note = "主力净买但次日收跌——典型'被埋'，印证主力非必赢";
            }
            r.setCredibilityFlag(cred);
            if (diverg) note = (note.isEmpty() ? "" : note + "；") + "多空席位分歧较大，合力未一致";
            r.setNote(note.isEmpty() ? null : note);

            result.add(r);
        }

        long win = result.stream().filter(x -> x.getCredibilityFlag() != null && x.getCredibilityFlag() == 1).count();
        long trapped = result.stream().filter(x -> x.getCredibilityFlag() != null && x.getCredibilityFlag() == 0).count();
        long unknown = result.stream().filter(x -> x.getCredibilityFlag() != null && x.getCredibilityFlag() == 2).count();
        log.info("[S3] 主力博弈计算 {}: 上榜个股 {} 只, 席位明细 {} 行; 主力次日胜 {} / 被埋 {} / 未知 {}",
                tradeDate, result.size(), details.size(), win, trapped, unknown);
        return result;
    }

    /** 取榜日后第 n 个交易日的收益率（close_n/close_0 - 1），数据不足返回 null。
     * 入场取「第一个 >= 榜日」的交易日收盘：榜日有数据则等同榜日收盘；若榜日在 stock_daily 缺失
     * （如 08-13 部分股票跳空），则顺延到榜后首个可用交易日，保证「主力买入后 N 日收益」仍可计算。 */
    private BigDecimal nextReturn(List<StockClose> series, LocalDate base, int offset) {
        if (series == null || series.isEmpty()) return null;
        int idx = -1;
        for (int i = 0; i < series.size(); i++) {
            if (series.get(i).getTradeDate().compareTo(base) >= 0) { idx = i; break; }
        }
        if (idx < 0 || idx + offset >= series.size()) return null;
        BigDecimal c0 = series.get(idx).getClose();
        BigDecimal cn = series.get(idx + offset).getClose();
        if (c0 == null || cn == null || c0.signum() == 0) return null;
        return cn.divide(c0, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal round2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
