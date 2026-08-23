package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.LeaderPoolDaily;
import com.dunwugudao.replay.entity.LeaderTradeDaily;
import com.dunwugudao.replay.entity.ThemeFactorDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 次日观察池（watch_pool）选股溯源查询——仅用于 S7 强题材展开成个股。
 * S4/S5 直接复用各自 Mapper 的 selectByTradeDate（已 FINAL）。
 */
@Mapper
public interface WatchPoolMapper {

    /** 某板块（BK 代码）的成分股（无后缀 ts_code 列表），用于把 S7 强题材展开成个股。 */
    List<String> selectBoardMembers(@Param("boardCode") String boardCode);
}
