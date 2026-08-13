package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.ThemeFactorDaily;
import com.dunwugudao.replay.mapper.ck.BoardBasicMapper;
import com.dunwugudao.replay.mapper.ck.ThemeFactorDailyMapper;
import com.dunwugudao.replay.vo.ThemeFactorVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S7 炒作思维 · 题材炒作因子接口层。
 *
 * <p>读 theme_factor_daily FINAL，按综合分降序返回题材库；boardCode 非空时返回单题材明细。
 * boardName 不在计算层表中，这里用 CommonService.boardNameMap 从 board_basic 回填。
 */
@Service
@RequiredArgsConstructor
public class ThemeService {

    private final CommonService commonService;
    private final ThemeFactorDailyMapper themeFactorDailyMapper;
    private final BoardBasicMapper boardBasicMapper;

    /**
     * @param date      交易日（空=最新）
     * @param boardCode 题材代码（空=返回全部，按综合分降序）
     * @return 题材炒作因子视图列表
     */
    public List<ThemeFactorVO> factor(LocalDate date, String boardCode) {
        LocalDate d = commonService.resolveDate(date, "theme_factor_daily");
        if (d == null) {
            return List.of();
        }
        List<ThemeFactorDaily> list;
        if (boardCode != null && !boardCode.isBlank()) {
            ThemeFactorDaily one = themeFactorDailyMapper.selectByTradeDateAndBoard(d, boardCode);
            list = one != null ? List.of(one) : List.of();
        } else {
            list = themeFactorDailyMapper.selectByTradeDate(d);
        }
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = commonService.boardNameMap(
                list.stream().map(ThemeFactorDaily::getBoardCode).collect(Collectors.toList()));

        List<ThemeFactorVO> vo = list.stream().map(x -> {
            ThemeFactorVO v = new ThemeFactorVO();
            v.setBoardCode(x.getBoardCode());
            v.setBoardName(names.get(x.getBoardCode()));
            v.setScarcity(x.getScarcity());
            v.setImagination(x.getImagination());
            v.setSudden(x.getSudden());
            v.setCertainty(x.getCertainty());
            v.setMinResist(x.getMinResist());
            v.setTotal(x.getTotal());
            return v;
        }).collect(Collectors.toList());

        // selectByTradeDate 已 ORDER BY total DESC；明细/过滤情形再排一次确保一致
        vo.sort(Comparator.comparing(ThemeFactorVO::getTotal,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return vo;
    }
}
