package com.dunwugudao.replay.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * L2 订阅池（ths:l2:pool）手工管理——盘中实时监控页面对应的增删查接口。
 *
 * <p><b>契约</b>：pool 成员为<b>裸代码</b>（6 位数字，无 .SZ/.SH 后缀）。生产端（爬虫）
 * SMEMBERS 读 pool 决定采集范围，并监听 {@code ths:l2:pool:change} 做动态增删。
 * 本接口所有变更都 PUBLISH pool:change，生产端与后端消费端双端即时生效，无需重启。
 *
 * <p>注意：这里只管理 Redis 订阅池本身（"要抓哪些股的 L2"），不动 watch_pool 复盘选股表
 * 与狙击圈——那是 sync-watch-pool 的职责。手工加进来的股仅表示"我要看它的 L2 数据"。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/l2-pool")
public class L2PoolController {

    private static final String POOL_KEY = "ths:l2:pool";
    private static final String POOL_CHANGE_CHANNEL = "ths:l2:pool:change";

    private final StringRedisTemplate redis;

    public L2PoolController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 当前 pool 全部成员（裸代码，升序）。 */
    @GetMapping
    public ResponseEntity<List<String>> list() {
        Set<String> members = redis.opsForSet().members(POOL_KEY);
        List<String> out = members == null ? List.of() : new ArrayList<>(members);
        Collections.sort(out);
        return ResponseEntity.ok(out);
    }

    /** 新增一只（裸代码，幂等；已存在则返回 existed=true）。 */
    @PostMapping("/add")
    public ResponseEntity<java.util.Map<String, Object>> add(@RequestParam String code) {
        String c = normalize(code);
        if (c == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "INVALID_PARAM",
                    "message", "code 必须为 6 位数字裸代码（如 003031），不带后缀"));
        }
        Long added = redis.opsForSet().add(POOL_KEY, c);
        boolean isNew = added != null && added > 0;
        if (isNew) {
            publish("add", c);
        }
        log.info("[l2-pool] 手工新增 {}（{}）", c, isNew ? "新成员" : "已存在");
        return ResponseEntity.ok(java.util.Map.of(
                "code", c, "existed", !isNew, "size", size()));
    }

    /** 删除一只（幂等；不存在则返回 removed=false）。 */
    @PostMapping("/remove")
    public ResponseEntity<java.util.Map<String, Object>> remove(@RequestParam String code) {
        String c = normalize(code);
        if (c == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "INVALID_PARAM",
                    "message", "code 必须为 6 位数字裸代码（如 003031），不带后缀"));
        }
        Long removed = redis.opsForSet().remove(POOL_KEY, c);
        boolean wasRemoved = removed != null && removed > 0;
        if (wasRemoved) {
            publish("remove", c);
        }
        log.info("[l2-pool] 手工删除 {}（{}）", c, wasRemoved ? "已移除" : "本就不在池中");
        return ResponseEntity.ok(java.util.Map.of(
                "code", c, "removed", wasRemoved, "size", size()));
    }

    private long size() {
        Long n = redis.opsForSet().size(POOL_KEY);
        return n == null ? 0 : n;
    }

    private void publish(String action, String code) {
        try {
            redis.convertAndSend(POOL_CHANGE_CHANNEL,
                    String.format("{\"action\":\"%s\",\"code\":\"%s\"}", action, code));
        } catch (Exception e) {
            log.warn("[l2-pool] 发布 pool:change 失败（{} {}）：{}", action, code, e.getMessage());
        }
    }

    /** 归一化：去空白、去后缀（容错用户粘贴带后缀代码），校验 6 位数字。 */
    private static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        int dot = c.lastIndexOf('.');
        if (dot > 0) {
            c = c.substring(0, dot); // 容错：带后缀的自动截成裸代码
        }
        return c.matches("\\d{6}") ? c : null;
    }
}
