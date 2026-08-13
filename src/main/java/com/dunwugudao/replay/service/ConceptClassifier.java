package com.dunwugudao.replay.service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 概念板块性质分类器（纯规则，无副作用，可单测）。
 *
 * <p>背景：东财「概念板块」共 500+ 个，其中混杂大量**非题材**标签。实测 2026-08-05
 * 按涨停家数排序的前 10 名里，有 8 个是伪概念：
 * 融资融券(62)、昨日高振幅(48)、深股通(43)、沪股通(37)、最近多板(33)、
 * 昨日涨停_含一字(32)、小盘股(31)、昨日涨停(28)。
 * 唯一的真题材是「华为概念(24)」。
 *
 * <p>这些标签是**行情的结果**（涨停了才进"昨日涨停"）或**属性分类**（够大就进"融资融券"），
 * 不是资金抱团的**原因**。若不剔除，主线识别会被它们永久霸榜，S4 板学寻龙完全失效。
 *
 * <p>规则以"模式"为主、"名单"为辅，让东财后续新增的同类标签（如"昨日三板"）能被自动拦截。
 */
public final class ConceptClassifier {

    /** 真题材：可炒作，进入主线候选。 */
    public static final String REAL_THEME = "REAL_THEME";
    /** 技术标签：行情结果的二次封装（昨日涨停/近期新高/超跌股…）。 */
    public static final String TECH_TAG = "TECH_TAG";
    /** 资金属性：谁持有、能不能融资（融资融券/深股通/基金重仓…）。 */
    public static final String CAPITAL_TAG = "CAPITAL_TAG";
    /** 市值风格与指数成份（小盘股/沪深300/茅指数…）。 */
    public static final String SIZE_TAG = "SIZE_TAG";
    /** 其它非题材属性（ST股/次新股/财报预告/AH股…）。 */
    public static final String MISC_TAG = "MISC_TAG";

    private ConceptClassifier() {
    }

    // ---------------- 技术标签 ----------------

    /** "昨日"开头的一律是行情结果标签：昨日涨停/昨日炸板/昨日高振幅/昨日打二板以上表现… */
    private static final Pattern TECH_PREFIX = Pattern.compile("^昨日.*");

    /** 破净/破发类：破净股、破发股、破增发价股、长期破净、红利破净股。 */
    private static final Pattern TECH_BROKEN = Pattern.compile(".*(破净|破发|破增发价).*");

    private static final Set<String> TECH_EXACT = Set.of(
            "最近多板", "百日新高", "近期新高", "反转股", "超跌股", "趋势股", "题材股",
            "高成长股", "低价股", "百元股", "微利股", "价值股", "周期股", "红利股",
            "行业龙头", "低市净率", "高市净率", "东方财富热股"
    );

    // ---------------- 资金属性 ----------------

    /** 重仓/持股类：QFII重仓、社保重仓、基金重仓、机构重仓、证金持股。 */
    private static final Pattern CAPITAL_HOLD = Pattern.compile(".*(重仓|持股).*");

    /** 股权状态类：股权分散、股权集中、股权激励、股权转让。 */
    private static final Pattern CAPITAL_EQUITY = Pattern.compile("^股权.*");

    private static final Set<String> CAPITAL_EXACT = Set.of(
            "融资融券", "深股通", "沪股通", "转债标的", "举牌", "密集调研", "创投"
    );

    // ---------------- 市值风格与指数成份 ----------------

    /** 大/中/小/微 + 盘股|盘价值|盘成长。 */
    private static final Pattern SIZE_STYLE = Pattern.compile("^(大盘|中盘|小盘|微盘).*");

    /** "XX风格"：科技风格、消费风格、医药医疗风格、金融地产风格、先进制造风格。 */
    private static final Pattern SIZE_FLAVOR = Pattern.compile(".*风格$");

    private static final Set<String> INDEX_EXACT = Set.of(
            "HS300_", "上证180_", "上证380", "上证50_", "中证500", "深成500", "深证100R",
            "创业成份", "创业板综", "央视50_", "MSCI中国", "富时罗素", "标准普尔",
            "茅指数", "宁组合", "权重股", "微盘精选"
    );

    // ---------------- 其它属性 ----------------

    /** 财报预告：2026中报预增 / 2025三季报扭亏 / 2026一季报预减 … */
    private static final Pattern MISC_REPORT =
            Pattern.compile("^\\d{4}(一季报|中报|三季报|年报).*");

    private static final Set<String> MISC_EXACT = Set.of(
            "ST股", "近期摘帽", "次新股", "北交所概念", "科创板做市商", "科创板做市股",
            "AB股", "AH股", "B股", "GDR", "IPO受益", "贬值受益", "独角兽"
    );

    // ---------------- 想象空间启发式 ----------------

    /** 前沿科技/政策强驱动 —— 天花板高，市场愿意给远期估值。 */
    private static final Pattern HIGH_IMAGINATION = Pattern.compile(
            ".*(人工智能|人形机器人|机器人|AI|算力|芯片|半导体|光刻|核聚变|量子|商业航天|" +
            "卫星|低空经济|飞行汽车|固态电池|6G|脑机|合成生物|元宇宙|数字经济|数据要素|" +
            "创新药|基因|细胞|超导|可控核|空间站|华为|英伟达).*");

    /** 传统/消费/周期 —— 估值锚清晰，想象空间受限。 */
    private static final Pattern LOW_IMAGINATION = Pattern.compile(
            ".*(白酒|啤酒|乳业|猪肉|鸡肉|水产|粮食|调味品|预制菜|造纸|包装|化工原料|" +
            "钛白粉|草甘膦|维生素|煤化工|铁路基建|工程建设|装配建筑|旅游|酒店|零售|快递).*");

    /**
     * 判定题材性质。
     *
     * @param name 板块名称，如「华为概念」「昨日涨停」
     * @return 五种 theme_type 之一
     */
    public static String classify(String name) {
        if (name == null || name.isBlank()) {
            return MISC_TAG;
        }
        String n = name.trim();

        if (TECH_EXACT.contains(n) || TECH_PREFIX.matcher(n).matches()
                || TECH_BROKEN.matcher(n).matches()) {
            return TECH_TAG;
        }
        if (CAPITAL_EXACT.contains(n) || CAPITAL_HOLD.matcher(n).matches()
                || CAPITAL_EQUITY.matcher(n).matches()) {
            return CAPITAL_TAG;
        }
        if (INDEX_EXACT.contains(n) || SIZE_STYLE.matcher(n).matches()
                || SIZE_FLAVOR.matcher(n).matches()) {
            return SIZE_TAG;
        }
        if (MISC_EXACT.contains(n) || MISC_REPORT.matcher(n).matches()) {
            return MISC_TAG;
        }
        return REAL_THEME;
    }

    /** 是否为可炒作的真题材。主线识别只认这一类。 */
    public static boolean isRealTheme(String name) {
        return REAL_THEME.equals(classify(name));
    }

    /**
     * 想象空间启发式初值 0~1。
     *
     * <p>说明：这是**规则给出的初值**，不是精确度量。人工可在 concept 表里直接改，
     * 重跑初始化时已存在的行不会被覆盖（见 ConceptInitializer）。
     */
    public static double imagination(String name) {
        if (name == null) {
            return 0.5;
        }
        if (HIGH_IMAGINATION.matcher(name).matches()) {
            return 0.85;
        }
        if (LOW_IMAGINATION.matcher(name).matches()) {
            return 0.35;
        }
        return 0.55;
    }

    /**
     * 稀缺性 0~1：成分股越少越稀缺（资金容易抱团打出高度）。
     *
     * <p>用成分股数量做客观代理。经验区间：<=15 只极稀缺，>=200 只毫无稀缺性。
     *
     * @param memberCount 该板块成分股数量
     */
    public static double scarcity(int memberCount) {
        if (memberCount <= 0) {
            return 0.5;
        }
        if (memberCount <= 15) {
            return 1.0;
        }
        if (memberCount >= 200) {
            return 0.0;
        }
        // 15~200 之间线性衰减
        return 1.0 - (memberCount - 15) / 185.0;
    }
}
