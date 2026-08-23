package com.dunwugudao.replay.controller;

import com.dunwugudao.replay.realtime.RealtimeEvent;
import com.dunwugudao.replay.realtime.RealtimeEventBus;
import com.dunwugudao.replay.realtime.StageSnapshotHolder;
import com.dunwugudao.replay.realtime.TickWindow;
import com.dunwugudao.replay.realtime.model.Quote;
import com.dunwugudao.replay.plan.PlanPoolService;
import com.dunwugudao.replay.trader.TraderEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 盘中实时层 · SSE 推送 + 状态/阶段/决策流查询。
 *
 * <p>SSE（Server-Sent Events）单向推送，非 WebSocket——前端与采集链路彻底解耦，
 * 事件名 = RealtimeEvent.Type（FEATURE / DECISION / SIM_TRADE ...），前端按需订阅过滤。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RealtimeController {

    private final RealtimeEventBus bus;
    private final TickWindow window;
    private final StageSnapshotHolder stageHolder;
    private final TraderEngine traderEngine;
    private final PlanPoolService planPool;

    /** SSE 实时事件流（FEATURE/DECISION/SIM_TRADE）。 */
    @GetMapping(value = "/realtime/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(3600_000L);
        Consumer<RealtimeEvent> subscriber = e -> {
            try {
                emitter.send(SseEmitter.event().name(e.getType().name()).data(e.getPayload()));
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        };
        bus.subscribe(subscriber);
        Runnable cleanup = () -> bus.unsubscribe(subscriber);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        return emitter;
    }

    /** 实时层总览状态（窗口标的数/最新盘口/阶段/池子规模）。 */
    @GetMapping("/realtime/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stageHolder.currentStage());
        m.put("trackedCodes", window.trackedCodes().size());
        m.put("planPoolSize", planPool.list().size());
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (String code : window.trackedCodes()) {
            Quote q = window.getQuote(code);
            if (q != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tsCode", code);
                item.put("lastPrice", q.getLastPrice());
                item.put("pctChg", q.getPctChg());
                item.put("sealAmount", q.getSealAmount());
                item.put("amountDay", q.getAmountDay());
                quotes.add(item);
            }
        }
        m.put("quotes", quotes);
        return ResponseEntity.ok(m);
    }

    /** 近期决策流（默认 50 条，倒序）。 */
    @GetMapping("/realtime/decisions")
    public ResponseEntity<List<Map<String, Object>>> decisions(
            @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(200, limit));
        return ResponseEntity.ok(traderEngine.recentDecisions(n));
    }

    /** 当前情绪阶段。 */
    @GetMapping("/realtime/stage")
    public ResponseEntity<Map<String, Object>> stage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stageHolder.currentStage());
        return ResponseEntity.ok(m);
    }

    /** 手动覆盖情绪阶段（人工研判优先于自动映射）。code 见 StageSnapshotHolder。 */
    @PostMapping("/realtime/stage")
    public ResponseEntity<Map<String, Object>> overrideStage(
            @RequestParam String code,
            @RequestParam(defaultValue = "true") boolean manual) {
        String up = code == null ? "" : code.trim().toUpperCase();
        if (!StageSnapshotHolder.isValidStage(up)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_PARAM",
                    "message", "非法阶段码: " + code + "，合法值: ICE/CHAOS/DIVERGE/DIVERGE_CONSENSUS/CONSENSUS/CLIMAX/STARTUP/REPAIR"));
        }
        stageHolder.override(up, manual);
        return ResponseEntity.ok(Map.of("stage", stageHolder.currentStage(), "manual", manual));
    }

    /** 清除手动覆盖，恢复自动映射。 */
    @PostMapping("/realtime/stage/reset")
    public ResponseEntity<Map<String, Object>> resetStage() {
        stageHolder.clearOverride();
        return ResponseEntity.ok(Map.of("stage", stageHolder.currentStage()));
    }
}
