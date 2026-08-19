package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.ck.raw.BoardBasic;
import com.dunwugudao.replay.entity.ck.raw.LimitUpPool;
import com.dunwugudao.replay.mapper.ck.BoardBasicMapper;
import com.dunwugudao.replay.mapper.ck.CommonMapper;
import com.dunwugudao.replay.mapper.ck.LimitUpPoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 接口层通用能力：交易日解析、板块名/股票名映射回填。
 */
@Service
@RequiredArgsConstructor
public class CommonService {

    /** 板块名批量回填的 IN 分批上限（避免单条 500 个 IN 参数命中 CK 抖动/超长 SQL）。 */
    private static final int IN_BATCH = 200;

    private final CommonMapper commonMapper;
    private final BoardBasicMapper boardBasicMapper;
    private final LimitUpPoolMapper limitUpPoolMapper;

    /**
     * 解析交易日：
     * <ul>
     *   <li>请求为空 → 取指定表最新交易日；</li>
     *   <li>请求日为<b>未来日期</b>（用户在 datePicker 选了明天/更远）→ 回退到最新交易日，避免
     *       "200 空 body / 全 null / 空数组"行为不一致；</li>
     *   <li>过去但无数据的日期（如周末、源缺日）→ 原样返回，由各接口按"无数据"语义降级（204/空数组）。</li>
     * </ul>
     */
    public LocalDate resolveDate(LocalDate requested, String table) {
        if (requested == null) {
            return commonMapper.latestTradeDate(table);
        }
        if (requested.isAfter(LocalDate.now())) {
            return commonMapper.latestTradeDate(table);
        }
        return requested;
    }

    /** 批量取板块代码→板块名映射（过滤空值；分批 IN ≤200，规避大 IN 查询）。 */
    public Map<String, String> boardNameMap(List<String> boardCodes) {
        if (boardCodes == null || boardCodes.isEmpty()) {
            return Map.of();
        }
        List<String> codes = boardCodes.stream()
                .filter(c -> c != null && !c.isBlank()).distinct().toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (int start = 0; start < codes.size(); start += IN_BATCH) {
            List<String> part = codes.subList(start, Math.min(codes.size(), start + IN_BATCH));
            boardBasicMapper.selectBoardNames(part).forEach(b ->
                    result.put(b.getBoardCode(),
                            b.getBoardName() != null ? b.getBoardName() : b.getBoardCode()));
        }
        return result;
    }

    /** 取某交易日涨停池的股票代码→名称映射（用于龙头池回填股票名）。 */
    public Map<String, String> stockNameMap(LocalDate date) {
        if (date == null) {
            return Map.of();
        }
        return limitUpPoolMapper.selectByTradeDate(date).stream()
                .collect(Collectors.toMap(
                        LimitUpPool::getTsCode,
                        u -> u.getStockName() != null ? u.getStockName() : u.getTsCode(),
                        (a, b) -> a));
    }
}
