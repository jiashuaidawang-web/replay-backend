package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.realtime.model.L1Quote;
import com.dunwugudao.replay.realtime.model.L1Tick;
import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.realtime.model.Tick;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * L1 全局流消费者（降级备选）：读 {@code l1:ticks} + {@code l1:quotes} 两个全局 Stream，
 * 按 {@code ths:l2:pool} 过滤后写入 {@link TickWindow}，复用特征计算 + 交易引擎全链路。
 *
 * <h3>与 L2 消费者（RedisStreamConsumer）的差异</h3>
 * <ul>
 *   <li>L1 是<b>全局流</b>（所有股票混在一个 stream），不是 per-code stream；</li>
 *   <li>L1 tick 含 price + vol，<b>无方向字段</b>，需从盘口 bid1/ask1 推断；</li>
 *   <li>L1 quote 含 b1p/s1p 等<b>语义字段</b>，无需 tb10 解析；</li>
 *   <li>L1 只在我方主动开启时消费（{@link #l1Enabled}=true 且 L2 数据不可用时）。</li>
 * </ul>
 *
 * <h3>方向推断</h3>
 * <pre>
 *   price ≥ ask1 → BUY（主动买，以卖一价成交）
 *   price ≤ bid1 → SELL（主动卖，以买一价成交）
 *   其它         → NEUTRAL
 * </pre>
 *
 * <h3>解耦</h3>
 * 与 {@link RedisStreamConsumer} 共用：
 * <ul>
 *   <li>股票池 {@code ths:l2:pool}（SET）</li>
 *   <li>窗口 {@link TickWindow}</li>
 *   <li>归档 {@link TickArchiver}（仅 tick，quote 不入 rt_tick_archive）</li>
 * </ul>
 */
@Slf4j
@Component
public class L1StreamConsumer {

    /** L1 全局流 key。 */
    private static final String L1_TICK_STREAM = "l1:ticks";
    private static final String L1_QUOTE_STREAM = "l1:quotes";
    private static final String POOL_KEY = "ths:l2:pool";

    /** 归档队列上限。 */
    private static final int ARCHIVE_MAX = 200_000;

    private final StringRedisTemplate redis;
    private final TickWindow window;
    private final TickArchiver tickArchiver;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String group;
    private final String consumerName;
    private final long blockMs;
    private final int batch;

    private volatile boolean running = true;
    private final AtomicBoolean redisDown = new AtomicBoolean(false);

    /** L1 开关状态（yml 控制是否消费）。 */
    private final boolean l1Enabled;

    /** 最新 quote 缓存（按裸代码），tick 方向推断用。 */
    private final Map<String, Quote> quoteCache = new ConcurrentHashMap<>();

    /** 待归档 tick 队列。 */
    private final ConcurrentLinkedQueue<Tick> pendingArchive = new ConcurrentLinkedQueue<>();

    /** 活跃股票集合（从 pool 同步）。 */
    private final Set<String> activeCodes = ConcurrentHashMap.newKeySet();

    private Thread workerThread;

    public L1StreamConsumer(StringRedisTemplate redis,
                            TickWindow window,
                            TickArchiver tickArchiver,
                            @Value("${replay.stream.group:replay-consumer}") String group,
                            @Value("${replay.stream.consumer:node-1}") String consumerName,
                            @Value("${replay.stream.block-ms:500}") long blockMs,
                            @Value("${replay.stream.batch:100}") int batch,
                            @Value("${replay.stream.l1.enabled:true}") boolean l1Enabled) {
        this.redis = redis;
        this.window = window;
        this.tickArchiver = tickArchiver;
        this.group = group;
        this.consumerName = consumerName;
        this.blockMs = blockMs;
        this.batch = batch;
        this.l1Enabled = l1Enabled;
    }

    @PostConstruct
    public void start() {
        if (!l1Enabled) {
            log.info("[l1] L1StreamConsumer 已禁用（replay.stream.l1.enabled=false），不启动消费线程");
            return;
        }
        workerThread = new Thread(this::loop, "l1-stream-consumer");
        workerThread.setDaemon(true);
        workerThread.start();
        refreshPool();
        log.info("[l1] L1StreamConsumer 启动，消费组={}/{}，初始池={} 支", group, consumerName, activeCodes.size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        activeCodes.clear();
        quoteCache.clear();
        log.info("[l1] L1StreamConsumer 停止");
    }

    /** 主循环：交替读 tick + quote 全局流。 */
    private void loop() {
        List<StreamOffset<String>> offsets = List.of(
                StreamOffset.create(L1_TICK_STREAM, ReadOffset.lastConsumed()),
                StreamOffset.create(L1_QUOTE_STREAM, ReadOffset.lastConsumed())
        );
        // 确保消费组存在（全局流由生产端创建，消费端不建空 stream）
        ensureGroup(L1_TICK_STREAM);
        ensureGroup(L1_QUOTE_STREAM);

        int consecutiveFails = 0;
        while (running) {
            try {
                List<MapRecord<String, String, String>> records = redis.opsForStream().read(
                        Consumer.from(group, consumerName + "-l1"),
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                .block(java.time.Duration.ofMillis(blockMs)).count(batch),
                        offsets.toArray(new StreamOffset[0]));
                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, String, String> r : records) {
                        handleRecord(r);
                        try {
                            redis.opsForStream().acknowledge(group, r.getStream(), r.getId().getValue());
                        } catch (Exception ignored) {
                        }
                    }
                }
                consecutiveFails = 0;
                if (redisDown.compareAndSet(true, false)) {
                    log.info("[l1] Redis 已恢复连通");
                }
            } catch (Exception e) {
                if (running) {
                    consecutiveFails++;
                    if (consecutiveFails == 1) {
                        if (redisDown.compareAndSet(false, true)) {
                            log.warn("[l1] Redis 不可达，L1 消费者进入待命态: {}", e.getMessage());
                        }
                    }
                    try {
                        Thread.sleep(Math.min(1000L * consecutiveFails, 5000L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /** 处理单条记录：按 stream 区分 tick / quote。 */
    private void handleRecord(MapRecord<String, String, String> r) {
        String payload = r.getValue().get("payload");
        if (payload == null) return;

        String stream = r.getStream();
        try {
            if (L1_TICK_STREAM.equals(stream)) {
                handleTick(payload);
            } else if (L1_QUOTE_STREAM.equals(stream)) {
                handleQuote(payload);
            }
        } catch (Exception e) {
            log.debug("[l1] 解析失败 stream={}: {}", stream, e.getMessage());
        }
    }

    /** 处理 L1 tick：过滤 → 方向推断 → 入窗口 + 归档。 */
    private void handleTick(String payload) throws Exception {
        L1Tick l1 = mapper.readValue(payload, L1Tick.class);
        String code = l1.getCode();
        if (code == null || code.isBlank() || !activeCodes.contains(code)) {
            return;
        }

        // 方向推断：需最新盘口
        Quote q = quoteCache.get(code);
        String direction = inferDirection(l1.getPrice(), q);

        // 时间戳解析
        long tsMillis = parseTs(l1.getTs());

        Tick t = new Tick();
        t.setTsCode(RedisStreamConsumer.withSuffix(code));
        t.setTs(tsMillis);
        t.setPrice(l1.getPrice());
        t.setVolume(l1.getVol());
        t.setAmount(l1.getPrice() * l1.getVol() * 100.0); // 手×100股×价 = 元
        t.setDirection(direction);

        window.addTick(t);
        if (pendingArchive.size() < ARCHIVE_MAX) {
            pendingArchive.add(t);
        }
    }

    /** 处理 L1 quote：过滤 → 转 Quote → 入窗口缓存。 */
    private void handleQuote(String payload) throws Exception {
        L1Quote l1 = mapper.readValue(payload, L1Quote.class);
        String code = l1.getCode();
        if (code == null || code.isBlank() || !activeCodes.contains(code)) {
            return;
        }
        Quote q = l1.toQuote();
        q.setTsCode(code); // 保持裸代码，下游用时补后缀
        quoteCache.put(code, q);
        window.putQuote(q);
    }

    /**
     * 方向推断：price vs 盘口。
     * <pre>
     *   price ≥ ask1 → BUY
     *   price ≤ bid1 → SELL
     *   其它         → NEUTRAL
     * </pre>
     */
    private static String inferDirection(double price, Quote q) {
        if (q == null) return "NEUTRAL";
        double ask1 = q.getAsk1P();
        double bid1 = q.getBid1P();
        if (ask1 > 0 && price >= ask1) return "BUY";
        if (bid1 > 0 && price <= bid1) return "SELL";
        return "NEUTRAL";
    }

    /** 解析 ts 字符串（yyyy-MM-dd HH:mm:ss.SSS）为 epoch millis。 */
    private static long parseTs(String ts) {
        if (ts == null || ts.isBlank()) return System.currentTimeMillis();
        try {
            LocalDateTime ldt = LocalDateTime.parse(ts, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                // 只给时分（14:43）时补全日期
                LocalDateTime ldt = LocalDate.now().atTime(LocalDateTime.parse(ts, DateTimeFormatter.ofPattern("HH:mm")).toLocalTime());
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ex) {
                return System.currentTimeMillis();
            }
        }
    }

    /** 注册消费组（stream 不存在则跳过，等生产端创建）。 */
    private void ensureGroup(String stream) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(stream))) return;
            redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) return;
            log.debug("[l1] 注册消费组失败 stream={}: {}", stream, e.getMessage());
        }
    }

    /** 从 pool 同步活跃股票集合。 */
    private void refreshPool() {
        try {
            Set<String> pool = redis.opsForSet().members(POOL_KEY);
            if (pool == null) return;
            activeCodes.clear();
            for (String code : pool) {
                if (code != null && !code.isBlank()) activeCodes.add(code);
            }
        } catch (Exception e) {
            log.debug("[l1] 刷新 pool 失败: {}", e.getMessage());
        }
    }

    /** 周期刷新 pool（兜底，防 pub/sub 丢失）。 */
    @Scheduled(fixedDelayString = "${replay.stream.pool-refresh-ms:30000}")
    public void scheduledRefreshPool() {
        if (running) refreshPool();
    }

    /** 每 5s 把累积的 tick 批量归档到 CK rt_tick_archive。 */
    @Scheduled(fixedDelay = 5000)
    public void flushArchive() {
        if (pendingArchive.isEmpty()) return;
        List<Tick> batchTicks = new ArrayList<>();
        Tick t;
        while ((t = pendingArchive.poll()) != null) batchTicks.add(t);
        if (!batchTicks.isEmpty()) tickArchiver.archive(batchTicks);
    }
}
