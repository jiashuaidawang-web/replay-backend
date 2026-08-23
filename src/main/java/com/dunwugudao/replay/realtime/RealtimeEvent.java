package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.realtime.model.Decision;
import com.dunwugudao.replay.realtime.model.RealtimeFeature;
import com.dunwugudao.replay.realtime.model.Sector;
import com.dunwugudao.replay.realtime.model.Tick;
import com.dunwugudao.replay.realtime.model.Quote;

import java.io.Serializable;

/**
 * 实时事件总线消息载体。
 * <p>生产者（采集/特征/决策/模拟）发布，SSE 控制器订阅后转发给前端。
 * 解耦链路：爬虫→Redis Stream→后端消费→事件总线→SSE，前端与采集链路互不依赖。
 */
public class RealtimeEvent implements Serializable {
    public enum Type { TICK, QUOTE, SECTOR, FEATURE, DECISION, SIM_TRADE }

    private final Type type;
    private final Object payload;

    public RealtimeEvent(Type type, Object payload) {
        this.type = type;
        this.payload = payload;
    }

    public Type getType() { return type; }
    public Object getPayload() { return payload; }

    // 便于直接强转的工具方法
    public static Tick asTick(Object o) { return (Tick) o; }
    public static Quote asQuote(Object o) { return (Quote) o; }
    public static Sector asSector(Object o) { return (Sector) o; }
    public static RealtimeFeature asFeature(Object o) { return (RealtimeFeature) o; }
    public static Decision asDecision(Object o) { return (Decision) o; }
}
