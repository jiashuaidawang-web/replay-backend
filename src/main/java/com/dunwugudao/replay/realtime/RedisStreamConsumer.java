package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.realtime.model.Sector;
import com.dunwugudao.replay.realtime.model.Tick;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Redis 消费者（盘中实时 L2 采集入口）—— 对齐 {@code REDIS_DATA_FORMAT.md} 实测契约。
 *
 * <p><b>契约模型（与 M1 旧"3 全局流"不同）</b>：
 * <ol>
 *   <li>{@code ths:l2:pool}（SET）：订阅股票池，SMEMBERS 发现 + {@code ths:l2:pool:change} Pub/Sub 动态增删；
 *       <b>成员为裸代码（如 003031，无 .SZ/.SH 后缀）</b>——生产端按裸代码读 pool 并建 key；</li>
 *   <li>{@code ths:l2:tick:{code}}（Stream，JiTu，code 为裸代码）：逐笔成交，仅含 t(当日相对毫秒)/d(B/S)；
 *       <b>不含价格与量</b>；用 XREADGROUP 消费，ACK 保证不丢；
 *       <b>stream 只由生产端 XADD 创建，消费端不建空 stream</b>（无 MKSTREAM），组未就绪前退避等待；</li>
 *   <li>{@code ths:l2:quote:{code}}（<b>Hash，不是 Stream</b>，code 为裸代码）：盘口快照 tb10_prices/tb10_volumes 等；
 *       用 HGETALL 周期拉取（Hash 无 Stream 语义，无法 XREADGROUP）。</li>
 * </ol>
 *
 * <p><b>代码格式桥接</b>：pool/key 用裸代码，下游（TraderEngine/PlanItem/CK）用带后缀代码，
 * tick 入窗口前经 {@link #withSuffix} 还原后缀。
 *
 * <p><b>价格回贴（关键容错）</b>：JiTu tick 价格恒为 0，消费层在 tick 入窗口前，用该 code 最新 quote 快照的
 * {@code lastPrice} 回贴到 {@code tick.price}（快照缺失则留 0，特征层对"价缺失"降级）。tick 的 volume/amount
 * JiTu 当前也为 0，待爬虫补量后自动生效（无需改代码）。
 *
 * <p><b>解耦</b>：爬虫只生产到各自 Key，本类只消费；tick 双写 CK {@code rt_tick_archive}（异步批量，崩溃可重放）。
 */
@Slf4j
@Component
public class RedisStreamConsumer {

    /** 同花顺契约 key 前缀与频道名（集中管理，便于切换环境）。 */
    private static final String POOL_KEY = "ths:l2:pool";
    private static final String TICK_STREAM = "ths:l2:tick:";      // + code
    private static final String QUOTE_HASH = "ths:l2:quote:";       // + code （Hash，非 Stream）
    private static final String POOL_CHANGE_CHANNEL = "ths:l2:pool:change";

    /** Redis 不可达时的指数退避参数（避免几百个线程同时死磕 Redis → 日志/连接风暴）。 */
    private static final long BACKOFF_BASE_MS = 1000L;
    private static final long BACKOFF_MAX_MS = 30_000L;

    private final StringRedisTemplate redis;
    private final TickWindow window;
    private final TickArchiver tickArchiver;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String group;
    private final String consumerName;
    private final long blockMs;
    private final int batch;
    private final long quoteRefreshMs;

    private final ConcurrentLinkedQueue<Tick> pendingArchive = new ConcurrentLinkedQueue<>();
    private static final int ARCHIVE_MAX = 200_000;

    /** Redis 连通状态（待命态管理）：false→true 只在首次失败时打一条 WARN，恢复时打一条 INFO。 */
    private final java.util.concurrent.atomic.AtomicBoolean redisDown = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** refreshPool 失败日志限频（毫秒时间戳）：避免 Redis 挂掉时每 30s 刷一条。 */
    private final java.util.concurrent.atomic.AtomicLong lastPoolWarnMs = new java.util.concurrent.atomic.AtomicLong(0);
    private static final long POOL_WARN_INTERVAL_MS = 300_000L; // 5 分钟最多一条

    /** code → 消费线程（每股票一个，仅消费 tick Stream）。 */
    private final Map<String, Thread> workers = new ConcurrentHashMap<>();
    private final Set<String> activeCodes = ConcurrentHashMap.newKeySet();
    /** code → 最新 quote 快照（由 quote 拉取定时任务更新；tick 回贴价格用）。 */
    private final Map<String, Quote> quoteCache = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public RedisStreamConsumer(StringRedisTemplate redis,
                               TickWindow window,
                               TickArchiver tickArchiver,
                               @Value("${replay.stream.group:replay-consumer}") String group,
                               @Value("${replay.stream.consumer:node-1}") String consumerName,
                               @Value("${replay.stream.block-ms:500}") long blockMs,
                               @Value("${replay.stream.batch:100}") int batch,
                               @Value("${replay.stream.quote-refresh-ms:1000}") long quoteRefreshMs) {
        this.redis = redis;
        this.window = window;
        this.tickArchiver = tickArchiver;
        this.group = group;
        this.consumerName = consumerName;
        this.blockMs = blockMs;
        this.batch = batch;
        this.quoteRefreshMs = quoteRefreshMs;
    }

    @PostConstruct
    public void start() {
        subscribePoolChange();
        refreshPool();
        // 启动期先拉一轮 quote（避免 tick 早于 quote 到达时价格全缺失）
        for (String code : activeCodes) {
            pullQuote(code);
        }
        log.info("[realtime] RedisStreamConsumer 启动，消费组={}/消费者={}，初始池={} 支",
                group, consumerName, activeCodes.size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        workers.values().forEach(Thread::interrupt);
        workers.clear();
        activeCodes.clear();
        quoteCache.clear();
        log.info("[realtime] RedisStreamConsumer 停止");
    }

    /** 从 ths:l2:pool 拉当前股票集合，与已启动线程做差集，增删。 */
    private void refreshPool() {
        Set<String> pool;
        try {
            pool = redis.opsForSet().members(POOL_KEY);
        } catch (Exception e) {
            // 限频：Redis 挂掉期间每 5 分钟最多一条 WARN，避免与消费线程日志叠加刷屏
            long now = System.currentTimeMillis();
            long last = lastPoolWarnMs.get();
            if (now - last >= POOL_WARN_INTERVAL_MS && lastPoolWarnMs.compareAndSet(last, now)) {
                log.warn("[realtime] 读取 {} 失败（盘中模块待命，{}分钟内不再重复提示）: {}",
                        POOL_KEY, POOL_WARN_INTERVAL_MS / 60000, e.getMessage());
            }
            return;
        }
        if (pool == null) {
            return;
        }
        for (String code : pool) {
            if (code == null || code.isBlank()) {
                continue;
            }
            if (activeCodes.add(code)) {
                startWorker(code); // 消费组注册由 loop 在 stream 就绪后完成
            }
        }
        for (String code : new ArrayList<>(activeCodes)) {
            if (!pool.contains(code)) {
                removeWorker(code);
            }
        }
    }

    /** 订阅 ths:l2:pool:change，Payload: {"action":"add|remove","code":"600519"}。 */
    private void subscribePoolChange() {
        try {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(redis.getConnectionFactory());
            container.afterPropertiesSet();
            Topic topic = new ChannelTopic(POOL_CHANGE_CHANNEL);
            container.addMessageListener((message, pattern) -> {
                try {
                    String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                    Map<?, ?> evt = mapper.readValue(body, Map.class);
                    String action = String.valueOf(evt.get("action"));
                    String code = String.valueOf(evt.get("code"));
                    if (code == null || code.isBlank()) {
                        return;
                    }
                    if ("add".equalsIgnoreCase(action)) {
                        if (activeCodes.add(code)) {
                            startWorker(code); // 消费组注册由 loop 在 stream 就绪后完成
                            pullQuote(code);
                            log.info("[realtime] pool 新增 {}，已启动消费线程", code);
                        }
                    } else if ("remove".equalsIgnoreCase(action)) {
                        removeWorker(code);
                        log.info("[realtime] pool 移除 {}，已停止消费线程", code);
                    }
                } catch (Exception e) {
                    log.warn("[realtime] 解析 pool:change 失败: {}", e.getMessage());
                }
            }, topic);
            container.start();
            log.info("[realtime] 已订阅 {}", POOL_CHANGE_CHANNEL);
        } catch (Exception e) {
            log.warn("[realtime] 订阅 {} 失败（动态增删不可用，仅依赖启动期 pool 快照）: {}",
                    POOL_CHANGE_CHANNEL, e.getMessage());
        }
    }

    /**
     * 为 tick stream 注册消费组。<b>契约约束：tick stream 只由生产端（爬虫 XADD）创建</b>，
     * 消费端绝不用 MKSTREAM 建空 stream。故先 EXISTS 探测：stream 不存在 → 返回 false，
     * 由消费线程退避重试，等生产端首条数据到达后再注册。
     *
     * @return true=消费组已就绪（新建成功或已存在）；false=stream 尚不存在，待重试。
     */
    private boolean ensureGroup(String stream) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(stream))) {
                return false; // 生产端还没 XADD，不建空 stream
            }
            redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
            return true;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("BUSYGROUP")) {
                return true; // 组已存在
            }
            // 连接类异常等：下次循环重试
            log.debug("[realtime] 注册消费组失败 stream={}: {}", stream, msg);
            return false;
        }
    }

    /** 为单个 code 启动消费线程：仅消费 tick Stream（quote 由定时任务拉 Hash）。 */
    private void startWorker(String code) {
        Thread t = new Thread(() -> loop(code), "redis-stream-consumer-" + code);
        t.setDaemon(true);
        workers.put(code, t);
        t.start();
    }

    private void removeWorker(String code) {
        Thread t = workers.remove(code);
        if (t != null) {
            t.interrupt();
        }
        activeCodes.remove(code);
        quoteCache.remove(code);
    }

    private void loop(String code) {
        String tickStream = TICK_STREAM + code;
        List<StreamOffset<String>> offsets = List.of(
                StreamOffset.create(tickStream, ReadOffset.lastConsumed()));
        // 连续失败次数（用于指数退避）；任何一次成功读即归零。
        int consecutiveFails = 0;
        // 消费组就绪标记：stream 由生产端创建，未就绪前退避等待，不发 XREADGROUP。
        boolean groupReady = false;
        while (running && activeCodes.contains(code)) {
            try {
                if (!groupReady) {
                    groupReady = ensureGroup(tickStream);
                    if (!groupReady) {
                        // 生产端尚未推流：低频等待（不打日志，盘中模块本就可能整天无数据）
                        Thread.sleep(5_000L);
                        continue;
                    }
                    log.info("[realtime] tick stream 已就绪，开始消费 code={}", code);
                }
                List<MapRecord<String, String, String>> records = redis.opsForStream().read(
                        Consumer.from(group, consumerName + "-" + code),
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                .block(java.time.Duration.ofMillis(blockMs)).count(batch),
                        offsets.toArray(new StreamOffset[0]));
                if (records != null) {
                    for (MapRecord<String, String, String> r : records) {
                        handleTick(r, code);
                        try {
                            redis.opsForStream().acknowledge(group, r.getStream(), r.getId().getValue());
                        } catch (Exception ackEx) {
                            // ack 失败下次重放
                        }
                    }
                }
                // 成功读到（含空）即视为连通，归零退避；若此前处于待命态则补一条恢复 INFO。
                consecutiveFails = 0;
                if (redisDown.compareAndSet(true, false)) {
                    log.info("[realtime] Redis 已恢复连通，盘中实时模块退出待命态");
                }
            } catch (Exception e) {
                if (running) {
                    consecutiveFails++;
                    // NOGROUP（生产端重建了 stream / 组被删）→ 回到未就绪态，下轮重新注册
                    if (String.valueOf(e.getMessage()).contains("NOGROUP")) {
                        groupReady = false;
                    }
                    // 首次失败：单机只打一条「进入待命态」WARN（多线程抢 CAS，败者静默）；
                    // 后续退避并降为 debug，避免几百个线程每秒刷满日志。
                    if (consecutiveFails == 1) {
                        if (redisDown.compareAndSet(false, true)) {
                            log.warn("[realtime] Redis 不可达（{}），盘中实时模块进入待命态——"
                                    + "不影响 S1~S8 复盘计算与接口；恢复后自动继续。首个异常 code={}",
                                    e.getMessage(), code);
                        } else {
                            log.debug("[realtime] Redis Stream 读取异常 code={}（待命态，退避重试）: {}",
                                    code, e.getMessage());
                        }
                    } else {
                        log.debug("[realtime] Redis Stream 读取异常 code={} 第{}次（退避中）: {}",
                                code, consecutiveFails, e.getMessage());
                    }
                    try {
                        sleepBackoff(consecutiveFails);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /** 指数退避 + 抖动：base·2^(n-1)，封顶 BACKOFF_MAX_MS，并加 ±25% 抖动错开各线程重试峰。 */
    private void sleepBackoff(int consecutiveFails) throws InterruptedException {
        long exp = BACKOFF_BASE_MS * (1L << Math.min(consecutiveFails - 1, 30)); // 2^(n-1)，防溢出
        long capped = Math.min(exp, BACKOFF_MAX_MS);
        long jitter = (long) (capped * 0.25 * Math.random()); // 0~25% 正偏抖动
        Thread.sleep(Math.min(capped + jitter, BACKOFF_MAX_MS));
    }

    /** 处理单条 tick：解析 JiTu → 回贴 quote 最新价 → 入窗口 + 归档队列。 */
    private void handleTick(MapRecord<String, String, String> r, String code) {
        String payload = r.getValue().get("payload");
        if (payload == null) {
            return;
        }
        try {
            Tick t = mapper.readValue(payload, Tick.class);
            if (t.getTsCode() == null || t.getTsCode().isBlank()) {
                t.setTsCode(withSuffix(code)); // pool/stream key 是裸代码，补后缀供下游匹配
            }
            // 价格回贴：JiTu 无价，用最新 quote 快照 lastPrice 兜底
            Quote q = quoteCache.get(code);
            if (t.isPriceMissing() && q != null && q.getLastPrice() > 0) {
                t.setPrice(q.getLastPrice());
                // 量缺失时金额暂不推算（volume 也缺），仅价格回贴
            }
            window.addTick(t);
            if (pendingArchive.size() < ARCHIVE_MAX) {
                pendingArchive.add(t);
            }
        } catch (Exception e) {
            log.warn("[realtime] 解析 tick payload 失败 stream={}: {}", r.getStream(), e.getMessage());
        }
    }

    /** 周期拉取某 code 的 quote Hash，parseTb10 后存入 quoteCache。 */
    private void pullQuote(String code) {
        try {
            Map<Object, Object> hash = redis.opsForHash().entries(QUOTE_HASH + code);
            if (hash == null || hash.isEmpty()) {
                return;
            }
            // 把 Hash 转成 JSON 串再复用 Quote 的 @JsonProperty 映射（键名一致：code/tb10_prices/timestamp...）
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Object, Object> e : hash.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(e.getKey()).append("\":")
                  .append(quoteValueJson(e.getValue()));
            }
            sb.append("}");
            Quote q = mapper.readValue(sb.toString(), Quote.class);
            q.parseTb10();
            if (q.getTsCode() == null || q.getTsCode().isBlank()) {
                q.setTsCode(code);
            }
            quoteCache.put(code, q);
            window.putQuote(q);
        } catch (Exception e) {
            log.debug("[realtime] 拉取 quote 失败 code={}: {}", code, e.getMessage());
        }
    }

    /**
     * 裸代码（003031）补交易所后缀（003031.SZ）：6 开头→SH，0/3→SZ，4/8/92→BJ。
     * pool 契约是裸代码（生产端按裸代码读 pool、建 tick/quote key），
     * 但下游 TraderEngine/PlanItem 用 CK 约定的带后缀代码，故在 tick 入窗口前还原。
     */
    static String withSuffix(String bare) {
        if (bare == null || bare.isBlank() || bare.contains(".")) {
            return bare;
        }
        if (bare.startsWith("6")) {
            return bare + ".SH";
        }
        if (bare.startsWith("0") || bare.startsWith("3")) {
            return bare + ".SZ";
        }
        if (bare.startsWith("4") || bare.startsWith("8") || bare.startsWith("92")) {
            return bare + ".BJ";
        }
        return bare;
    }

    /** Hash value → JSON 字面量：数字原样，其它加引号。 */
    private String quoteValueJson(Object v) {
        if (v == null) {
            return "null";
        }
        String s = v.toString();
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            return s;
        }
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    /**
     * 每 quoteRefreshMs 拉一次全部 activeCode 的 quote Hash（Hash 无流语义，只能轮询）。
     * 同时把快照推给窗口（FeatureCalculator 读 window.getQuote 取盘口特征）。
     */
    @Scheduled(fixedDelayString = "${replay.stream.quote-refresh-ms:1000}")
    public void scheduledPullQuotes() {
        if (!running) {
            return;
        }
        for (String code : activeCodes) {
            pullQuote(code);
        }
    }

    /**
     * 每 5s 把累积的逐笔批量双写到 CK {@code rt_tick_archive}（异步、可重试、失败仅告警不中断采集）。
     */
    @Scheduled(fixedDelay = 5000)
    public void flushArchive() {
        if (pendingArchive.isEmpty()) {
            return;
        }
        List<Tick> batchTicks = new ArrayList<>();
        Tick t;
        while ((t = pendingArchive.poll()) != null) {
            batchTicks.add(t);
        }
        if (batchTicks.isEmpty()) {
            return;
        }
        tickArchiver.archive(batchTicks);
    }

    /** 启动期 + 周期兜底刷新股票池（防止 pub/sub 丢失消息导致池不一致）。 */
    @Scheduled(fixedDelayString = "${replay.stream.pool-refresh-ms:30000}")
    public void scheduledRefreshPool() {
        if (running) {
            refreshPool();
        }
    }
}
