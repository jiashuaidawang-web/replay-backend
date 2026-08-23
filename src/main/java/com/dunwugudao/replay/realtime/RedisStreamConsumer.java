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
 *   <li>{@code ths:l2:pool}（SET）：订阅股票池，SMEMBERS 发现 + {@code ths:l2:pool:change} Pub/Sub 动态增删；</li>
 *   <li>{@code ths:l2:tick:{code}}（Stream，JiTu）：逐笔成交，仅含 t(当日相对毫秒)/d(B/S)；<b>不含价格与量</b>；
 *       用 XREADGROUP 消费，ACK 保证不丢；</li>
 *   <li>{@code ths:l2:quote:{code}}（<b>Hash，不是 Stream</b>）：盘口快照 tb10_prices/tb10_volumes 等；
 *       用 HGETALL 周期拉取（Hash 无 Stream 语义，无法 XREADGROUP）。</li>
 * </ol>
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
            log.warn("[realtime] 读取 {} 失败（将重试）: {}", POOL_KEY, e.getMessage());
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
                ensureGroup(TICK_STREAM + code);
                startWorker(code);
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
                            ensureGroup(TICK_STREAM + code);
                            startWorker(code);
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

    private void ensureGroup(String stream) {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
        } catch (Exception e) {
            // BUSYGROUP 已存在 → 忽略
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
        while (running && activeCodes.contains(code)) {
            try {
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
            } catch (Exception e) {
                if (running) {
                    log.warn("[realtime] Redis Stream 读取异常 code={}（将重试）: {}", code, e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
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
                t.setTsCode(code); // 用 stream key 补全
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
