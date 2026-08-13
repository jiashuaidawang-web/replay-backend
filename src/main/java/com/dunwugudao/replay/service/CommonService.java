package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.ck.raw.BoardBasic;
import com.dunwugudao.replay.entity.ck.raw.LimitUpPool;
import com.dunwugudao.replay.mapper.ck.BoardBasicMapper;
import com.dunwugudao.replay.mapper.ck.CommonMapper;
import com.dunwugudao.replay.mapper.ck.LimitUpPoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 接口层通用能力：交易日解析、板块名/股票名映射回填。
 */
@Service
@RequiredArgsConstructor
public class CommonService {

    private final CommonMapper commonMapper;
    private final BoardBasicMapper boardBasicMapper;
    private final LimitUpPoolMapper limitUpPoolMapper;

    /** 解析交易日：请求为空时取指定表最新交易日。 */
    public LocalDate resolveDate(LocalDate requested, String table) {
        return (requested != null) ? requested : commonMapper.latestTradeDate(table);
    }

    /** 批量取板块代码→板块名映射（过滤空值）。 */
    public Map<String, String> boardNameMap(List<String> boardCodes) {
        if (boardCodes == null || boardCodes.isEmpty()) {
            return Map.of();
        }
        List<String> codes = boardCodes.stream()
                .filter(c -> c != null && !c.isBlank()).distinct().toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        return boardBasicMapper.selectBoardNames(codes).stream()
                .collect(Collectors.toMap(
                        BoardBasic::getBoardCode,
                        b -> b.getBoardName() != null ? b.getBoardName() : b.getBoardCode(),
                        (a, b) -> a));
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
