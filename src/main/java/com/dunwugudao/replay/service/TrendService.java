package com.dunwugudao.replay.service;

import com.dunwugudao.replay.vo.TrendScanVO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * S6 趋势战法 · 趋势股扫描。
 *
 * <p>趋势计算层（trend_candidate_daily）尚未实现，当前返回空列表；
 * 待 M1 的 S6 计算器落地并写入后，此处改为读 trend_candidate_daily FINAL。
 */
@Service
public class TrendService {

    public List<TrendScanVO> scan(LocalDate date, Integer minFeature) {
        return List.of();
    }
}
