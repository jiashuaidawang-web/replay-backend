package com.dunwugudao.replay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 题材静态属性（S7 炒作思维的输入）。对应表 concept。
 *
 * <p>核心用途：东财的「概念板块」里混杂了大量非题材的技术/资金/市值标签
 * （融资融券、深股通、昨日涨停、小盘股、昨日高振幅……）。若不剔除，
 * 主线识别会被这些"伪概念"永久霸榜。本表用 theme_type 标注每个板块的性质，
 * 只有 REAL_THEME 才进入主线候选。
 */
@Data
public class Concept {

    /** 题材代码，与 board_basic.board_code 一致，如 BK0854。 */
    private String themeCode;

    /** 题材名称，如 华为概念。 */
    private String themeName;

    /**
     * 题材性质：
     * <ul>
     *   <li>REAL_THEME —— 真题材（可炒作，进主线候选）</li>
     *   <li>TECH_TAG   —— 技术标签（昨日涨停/昨日炸板/最近多板/高振幅…，是结果不是原因）</li>
     *   <li>CAPITAL_TAG—— 资金属性（融资融券/深股通/沪股通/QFII…）</li>
     *   <li>SIZE_TAG   —— 市值风格（小盘股/大盘股/中盘股…）</li>
     *   <li>MISC_TAG   —— 其它非题材属性（次新股/科创板/ST板块/预盈预增…）</li>
     * </ul>
     */
    private String themeType;

    /** 稀缺性 0~1，越高说明标的越稀缺、越容易被资金抱团。 */
    private BigDecimal scarcity;

    /** 想象空间 0~1，越高说明题材天花板越高。 */
    private BigDecimal imagination;

    private Short dataSource;
    private String srcDetail;
    private LocalDate createDate;
}
