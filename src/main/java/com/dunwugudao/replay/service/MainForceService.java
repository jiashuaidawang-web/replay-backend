package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.MainForceDaily;
import com.dunwugudao.replay.mapper.ck.DragonTigerMapper;
import com.dunwugudao.replay.mapper.ck.MainForceDailyMapper;
import com.dunwugudao.replay.vo.DtDetailVO;
import com.dunwugudao.replay.vo.MainForceSeatVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S3 主力博弈 · 服务层。
 *
 * <p>stocks：读计算层 main_force_daily（FINAL）；seats：基于 dt_detail 实时聚合"抱团席位"——
 * 同一席位当日跨多只个股净买，是《顿悟股道》"抱团=合力"的可视化证据（互不认识的资金因共同利益同向买入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MainForceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final CommonService commonService;
    private final MainForceDailyMapper mainForceDailyMapper;
    private final DragonTigerMapper dragonTigerMapper;

    /** 个股级主力合力列表（按净买入降序）。 */
    public List<MainForceDaily> stocks(LocalDate date) {
        LocalDate d = commonService.resolveDate(date, "main_force_daily");
        if (d == null) return List.of();
        return mainForceDailyMapper.selectByTradeDate(d);
    }

    /** 抱团席位排行：同一席位当日跨股净买（按涉及股数、净买额降序）。 */
    public List<MainForceSeatVO> seats(LocalDate date) {
        LocalDate d = commonService.resolveDate(date, "dt_detail");
        if (d == null) return List.of();
        List<DtDetailVO> all = dragonTigerMapper.selectDtDetailByDate(d);

        Map<String, List<DtDetailVO>> bySeat = all.stream()
                .filter(x -> x.getSeatName() != null)
                .collect(Collectors.groupingBy(DtDetailVO::getSeatName));

        List<MainForceSeatVO> list = new ArrayList<>();
        for (Map.Entry<String, List<DtDetailVO>> e : bySeat.entrySet()) {
            List<DtDetailVO> rows = e.getValue();
            Set<String> codes = new LinkedHashSet<>();
            BigDecimal net = ZERO;
            String type = null;
            for (DtDetailVO r : rows) {
                if (r.getTsCode() != null) codes.add(r.getTsCode());
                net = net.add(r.getNetBuy() != null ? r.getNetBuy() : ZERO);
                if (type == null && r.getSeatType() != null) type = r.getSeatType();
            }
            MainForceSeatVO v = new MainForceSeatVO();
            v.setSeatName(e.getKey());
            v.setSeatType(type);
            v.setStockCnt(codes.size());
            v.setNetBuy(net.setScale(2, RoundingMode.HALF_UP));
            v.setTsCodes(new ArrayList<>(codes));
            list.add(v);
        }

        list.sort(Comparator.<MainForceSeatVO>comparingInt(MainForceSeatVO::getStockCnt).reversed()
                .thenComparing(Comparator.comparing(MainForceSeatVO::getNetBuy,
                        Comparator.nullsLast(Comparator.reverseOrder()))));
        return list.stream().limit(20).collect(Collectors.toList());
    }
}
