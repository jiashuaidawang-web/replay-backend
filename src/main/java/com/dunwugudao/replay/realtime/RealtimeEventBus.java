package com.dunwugudao.replay.realtime;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内实时事件总线（轻量 pub/sub）。
 * <p>用于把「特征 / 决策 / 模拟成交」等事件转发给 SSE 控制器，避免后端各模块与前端直连。
 * 注意：这是 JVM 内总线，与 Redis Streams（进程间采集总线）是两层不同的解耦。
 */
@Component
public class RealtimeEventBus {

    private final List<Consumer<RealtimeEvent>> subscribers = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<RealtimeEvent> consumer) {
        subscribers.add(consumer);
    }

    public void unsubscribe(Consumer<RealtimeEvent> consumer) {
        subscribers.remove(consumer);
    }

    public void publish(RealtimeEvent event) {
        for (Consumer<RealtimeEvent> c : subscribers) {
            try {
                c.accept(event);
            } catch (Exception e) {
                // 单个订阅者异常不影响其他订阅者
            }
        }
    }
}
