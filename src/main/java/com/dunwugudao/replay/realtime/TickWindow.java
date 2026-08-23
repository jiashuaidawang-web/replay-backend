package com.dunwugudao.replay.realtime;

import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.realtime.model.Tick;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 每标的（ts_code）的内存滚动窗口。
 * <p>保留最近 {@code tick-window-seconds} 秒的逐笔，用于计算大单净主动买入额；
 * 同时保存最新盘口快照。窗口外的旧 tick 在每次访问时惰性淘汰。
 */
@Component
public class TickWindow {

    private final long windowSeconds;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Tick>> ticks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Quote> latestQuote = new ConcurrentHashMap<>();

    public TickWindow(@Value("${replay.stream.tick-window-seconds:900}") long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public void addTick(Tick t) {
        ticks.computeIfAbsent(t.getTsCode(), k -> new ConcurrentLinkedDeque<>()).addLast(t);
        evict(t.getTsCode());
    }

    public void putQuote(Quote q) {
        latestQuote.put(q.getTsCode(), q);
    }

    public Quote getQuote(String tsCode) {
        return latestQuote.get(tsCode);
    }

    /** 返回并淘汰过期 tick 后的快照（只读副本）。 */
    public List<Tick> snapshot(String tsCode) {
        evict(tsCode);
        return new ArrayList<>(ticks.getOrDefault(tsCode, new ConcurrentLinkedDeque<>()));
    }

    public Set<String> trackedCodes() {
        return ticks.keySet();
    }

    /** 取出全部待归档 tick（用于批量写 rt_tick_archive），取后清空。 */
    public List<Tick> drainAll() {
        List<Tick> all = new ArrayList<>();
        for (ConcurrentLinkedDeque<Tick> d : ticks.values()) {
            all.addAll(d);
        }
        return all;
    }

    private void evict(String code) {
        long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
        ConcurrentLinkedDeque<Tick> d = ticks.get(code);
        if (d == null) {
            return;
        }
        while (!d.isEmpty() && d.peekFirst().getTs() < cutoff) {
            d.pollFirst();
        }
    }
}
