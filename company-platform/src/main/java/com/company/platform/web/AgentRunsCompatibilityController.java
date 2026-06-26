/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.runtime.AgentRuntime;
import com.company.platform.runtime.ChatRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/agent-runs")
public class AgentRunsCompatibilityController {

    private final PlatformCompatibilityState state;
    private final AgentRuntime runtime;

    public AgentRunsCompatibilityController(
            PlatformCompatibilityState state, AgentRuntime runtime) {
        this.state = state;
        this.runtime = runtime;
    }

    @PostMapping(value = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> streamRun(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "platform_admin") String userId,
            @RequestHeader(value = "x-org-id", defaultValue = "platform") String orgId) {
        String agentId = string(payload.get("agent_id"), "platform_knowledge_agent");
        Map<String, Object> body =
                payload.get("payload") instanceof Map<?, ?> nested ? copy(nested) : Map.of();
        String query = string(body.get("query"), string(payload.get("query"), ""));
        String sessionId = string(payload.get("session_id"), "default");
        Map<String, Object> run = state.createRun(agentId, query, userId);
        return runtime.chat(agentId, new ChatRequest(orgId, userId, sessionId, query))
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> string(response.text(), ""))
                .onErrorReturn("兼容层已收到请求: " + query)
                .flatMapMany(
                        text -> {
                            return Flux.just(
                                    sse(
                                            "activity",
                                            map(
                                                    "type",
                                                    "activity",
                                                    "step",
                                                    "receive",
                                                    "title",
                                                    "接收请求",
                                                    "status",
                                                    "success",
                                                    "summary",
                                                    agentId)),
                                    sse(
                                            "activity",
                                            map(
                                                    "type",
                                                    "activity",
                                                    "step",
                                                    "respond",
                                                    "title",
                                                    "生成回答",
                                                    "status",
                                                    "success",
                                                    "summary",
                                                    "compat")),
                                    sse(
                                            "done",
                                            map(
                                                    "type",
                                                    "done",
                                                    "run_id",
                                                    run.get("run_id"),
                                                    "status",
                                                    "succeeded",
                                                    "trace_id",
                                                    run.get("trace_id"),
                                                    "output_ref",
                                                    map(
                                                            "result",
                                                            map(
                                                                    "answer",
                                                                    text,
                                                                    "text",
                                                                    text,
                                                                    "route",
                                                                    "agentscope")))));
                        })
                .delayElements(Duration.ofMillis(25));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static ServerSentEvent<Map<String, Object>> sse(
            String event, Map<String, Object> data) {
        return ServerSentEvent.builder(data).event(event).build();
    }

    private static String string(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
