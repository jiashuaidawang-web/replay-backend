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
import org.springframework.data.redis.serializer.RedisSerializer;
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
 * Redis 消费者（盘中实时 L2 采集入口）—— 对齐「同花顺 L2 Redis 数据契约 v1.0」。
 *
 * <p><b>范式变更</b>：契约是「每支股票一个 Stream」模型（{@code ths:l2:tick:{code}} / {@code ths:l2:quote:{code}}），
 * 与 M1 的「3 个全局流」不同。本类改为：
 * <ol>
 *   <li>从 {@code ths:l2:pool}（SET）发现订阅股票池；</li>
 *   <li>为每个 {@code code} 启动一个消费线程，XREADGROUP 读 {@code ths:l2:tick:{code}}（逐笔）与
 *       {@code ths:l2:quote:{code}}（盘口，爬虫额外 XADD 的 Stream，详见 Quote.java 注释）；</li>
 *   <li>订阅 {@code ths:l2:pool:change}（Pub/Sub）动态增删 code 的消费线程；</li>
 *   <li>逐笔双写 CK {@code rt_tick_archive}（零丢失，异步批量）。</li>
 * </ol>
 * 解耦：爬虫只生产到各自 Stream，本类只消费，崩溃重启从 lastConsumed 重放。
 */
@Slf4j
@Component
public class RedisStreamConsumer {

    /** 同花顺契约 key 前缀与频道名（集中管理，便于切换环境）。 */
    private static final String POOL_KEY = "ths:l2:pool";
    private static final String TICK_STREAM = "ths:l2:tick:";      // + code
    private static final String QUOTE_STREAM = "ths:l2:quote:";     // + code
    private static final String SECTOR_STREAM = "ths:l2:sector";    // 可选全局板块流
    private static final String POOL_CHANGE_CHANNEL = "ths:l2:pool:change";

    private final StringRedisTemplate redis;
    private final TickWindow window;
    private final TickArchiver tickArchiver;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String group;
    private final String consumerName;
    private final long blockMs;
    private final int batch;

    private final ConcurrentLinkedQueue<Tick> pendingArchive = new ConcurrentLinkedQueue<>();
    private static final int ARCHIVE_MAX = 200_000;

    /** code → 消费线程（每股票一个）。 */
    private final Map<String, Thread> workers = new ConcurrentHashMap<>();
    private final Set<String> activeCodes = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    public RedisStreamConsumer(StringRedisTemplate redis,
                               TickWindow window,
                               TickArchiver tickArchiver,
                               @Value("${replay.stream.group:replay-consumer}") String group,
                               @Value("${replay.stream.consumer:node-1}") String consumerName,
                               @Value("${replay.stream.block-ms:500}") long blockMs,
                               @Value("${replay.stream.batch:100}") int batch) {
        this.redis = redis;
        this.window = window;
        this.tickArchiver = tickArchiver;
        this.group = group;
        this.consumerName = consumerName;
        this.blockMs = blockMs;
        this.batch = batch;
    }

    @PostConstruct
    public void start() {
        // 订阅股票池变更（动态增删）
        subscribePoolChange();
        // 初始从 pool 拉全量 code 并启动消费线程
        refreshPool();
        log.info("[realtime] RedisStreamConsumer 启动，消费组={}/消费者={}，初始池={} 支",
                group, consumerName, activeCodes.size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        workers.values().forEach(Thread::interrupt);
        workers.clear();
        activeCodes.clear();
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
        // 新增
        for (String code : pool) {
            if (code == null || code.isBlank()) {
                continue;
            }
            if (activeCodes.add(code)) {
                ensureGroup(TICK_STREAM + code);
                ensureGroup(QUOTE_STREAM + code);
                startWorker(code);
            }
        }
        // 移除（爬虫从 pool 删掉的）
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
                            ensureGroup(QUOTE_STREAM + code);
                            startWorker(code);
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

    /** 为单个 code 启动消费线程：同时读 tick 流与 quote 流（两个 StreamOffset）。 */
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
    }

    private void loop(String code) {
        String tickStream = TICK_STREAM + code;
        String quoteStream = QUOTE_STREAM + code;
        List<StreamOffset<String>> offsets = List.of(
                StreamOffset.create(tickStream, ReadOffset.lastConsumed()),
                StreamOffset.create(quoteStream, ReadOffset.lastConsumed()));
        while (running && activeCodes.contains(code)) {
            try {
                List<MapRecord<String, String, String>> records = redis.opsForStream().read(
                        Consumer.from(group, consumerName + "-" + code),
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                .block(java.time.Duration.ofMillis(blockMs)).count(batch),
                        offsets.toArray(new StreamOffset[0]));
                if (records != null) {
                    for (MapRecord<String, String, String> r : records) {
                        handle(r);
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

    private void handle(MapRecord<String, String, String> r) {
        String key = r.getStream();
        String payload = r.getValue().get("payload");
        if (payload == null) {
            return;
        }
        try {
            if (key.startsWith(TICK_STREAM)) {
                String code = key.substring(TICK_STREAM.length());
                Tick t = mapper.readValue(payload, Tick.class);
                if (t.getTsCode() == null || t.getTsCode().isBlank()) {
                    t.setTsCode(code); // 用 stream key 补全
                }
                window.addTick(t);
                if (pendingArchive.size() < ARCHIVE_MAX) {
                    pendingArchive.add(t);
                }
            } else if (key.startsWith(QUOTE_STREAM)) {
                String code = key.substring(QUOTE_STREAM.length());
                Quote q = mapper.readValue(payload, Quote.class);
                if (q.getTsCode() == null || q.getTsCode().isBlank()) {
                    q.setTsCode(code);
                }
                window.putQuote(q);
            } else if (SECTOR_STREAM.equals(key)) {
                mapper.readValue(payload, Sector.class); // 暂仅留存，不进窗口
            } else {
                log.debug("[realtime] 未知 stream: {}", key);
            }
        } catch (Exception e) {
            log.warn("[realtime] 解析 payload 失败 stream={}: {}", key, e.getMessage());
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
