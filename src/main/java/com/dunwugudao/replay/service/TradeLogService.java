package com.dunwugudao.replay.service;

import com.dunwugudao.replay.exception.NotConfiguredException;
import com.dunwugudao.replay.vo.TradeLogVO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * S8 交易心法 · 个人交易日志。
 *
 * <p>trade_log 表尚未创建（M4 里程碑）。GET 返回空列表（不触碰不存在的表）；
 * POST 抛 {@link NotConfiguredException}，由全局异常处理返回 503 + 结构化提示。
 */
@Service
public class TradeLogService {

    public List<TradeLogVO> list(LocalDate from, LocalDate to, String emotionTag) {
        return List.of();
    }

    public TradeLogVO create(TradeLogVO vo) {
        throw new NotConfiguredException("S8 个人复盘 trade_log 表尚未创建（M4 里程碑），暂不支持写入");
    }
}
