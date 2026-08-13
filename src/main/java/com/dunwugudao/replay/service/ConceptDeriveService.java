package com.dunwugudao.replay.service;

import com.dunwugudao.replay.entity.Concept;
import com.dunwugudao.replay.entity.ck.raw.BoardBasic;
import com.dunwugudao.replay.entity.ck.raw.BoardMemberCount;
import com.dunwugudao.replay.mapper.ck.BoardBasicMapper;
import com.dunwugudao.replay.mapper.ck.ConceptMapper;
import com.dunwugudao.replay.mapper.ck.StockBoardRelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S7 炒作思维 · 题材静态属性派生（concept 表）。
 *
 * <p>东财「概念板块」(board_type=3) 共 500+ 个，混杂大量非题材标签（融资融券/昨日涨停/小盘股…）。
 * 本步骤读 board_basic(board_type=3)，经 {@link ConceptClassifier} 分类 + 稀缺性/想象空间启发式，
 * 整批重写 concept 表（先 {@code DELETE WHERE 1=1} 再 INSERT，量级小、全量而非按日）。
 *
 * <p>该表是 S4 主线识别（只认 REAL_THEME）与 S7 炒作因子的共同输入，故放在计算编排最前置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConceptDeriveService {

    private final BoardBasicMapper boardBasicMapper;
    private final StockBoardRelMapper stockBoardRelMapper;
    private final ConceptMapper conceptMapper;

    /** 从 board_basic 派生 concept 表，返回写入行数。 */
    public int derive() {
        List<BoardBasic> boards = boardBasicMapper.selectByBoardType(3);
        Map<String, Long> memberCnt = stockBoardRelMapper.countByBoardType(3).stream()
                .collect(Collectors.toMap(BoardMemberCount::getBoardCode,
                        BoardMemberCount::getMemberCount, (a, b) -> a));

        List<Concept> concepts = boards.stream().map(b -> {
            String name = b.getBoardName();
            String type = ConceptClassifier.classify(name);
            int mc = memberCnt.getOrDefault(b.getBoardCode(), 0L).intValue();
            Concept c = new Concept();
            c.setThemeCode(b.getBoardCode());
            c.setThemeName(name);
            c.setThemeType(type);
            c.setScarcity(BigDecimal.valueOf(ConceptClassifier.scarcity(mc))
                    .setScale(4, RoundingMode.HALF_UP));
            c.setImagination(BigDecimal.valueOf(ConceptClassifier.imagination(name))
                    .setScale(4, RoundingMode.HALF_UP));
            c.setDataSource((short) 1);
            c.setSrcDetail("rule:ConceptClassifier");
            c.setCreateDate(LocalDate.now());
            return c;
        }).toList();

        conceptMapper.deleteAll();
        conceptMapper.insertBatch(concepts);

        long real = concepts.stream().filter(c -> "REAL_THEME".equals(c.getThemeType())).count();
        log.info("[概念派生] board_basic 概念板块 {} 个 → concept 表 {} 行（其中 REAL_THEME {} 个）",
                boards.size(), concepts.size(), real);
        return concepts.size();
    }
}
