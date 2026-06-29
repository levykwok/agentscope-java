/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime;

import com.company.platform.adapter.agentscope.AgentScopeHarnessFactory;
import com.company.platform.control.AgentDefinition;
import com.company.platform.control.AgentDefinitionRegistry;
import com.company.platform.control.OrchestrationMode;
import com.company.platform.control.RouteRule;
import com.company.platform.control.WorkflowStep;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AgentRuntimeService implements AgentRuntime {

    private final AgentDefinitionRegistry registry;
    private final AgentScopeHarnessFactory harnessFactory;
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public AgentRuntimeService(
            AgentDefinitionRegistry registry, AgentScopeHarnessFactory harnessFactory) {
        this.registry = registry;
        this.harnessFactory = harnessFactory;
    }

    @Override
    public Mono<ChatResponse> chat(String agentId, ChatRequest request) {
        AgentDefinition definition = definition(agentId);
        return switch (definition.orchestration().mode()) {
            case ROUTER -> runSingle(route(definition, request), request);
            case WORKFLOW -> runWorkflow(definition, request);
            case SINGLE, SUPERVISOR -> runSingle(definition, request);
        };
    }

    @Override
    public Flux<AgentEventEnvelope> stream(String agentId, ChatRequest request) {
        AgentDefinition definition = definition(agentId);
        if (definition.orchestration().mode() == OrchestrationMode.ROUTER) {
            return stream(route(definition, request).agentId(), request);
        }
        if (definition.orchestration().mode() == OrchestrationMode.WORKFLOW) {
            return Flux.error(new AgentRuntimeException("Workflow streaming is not supported yet"));
        }
        RuntimeContext context = runtimeContext(request);
        return agent(definition).streamEvents(request.message(), context).map(this::envelope);
    }

    @Override
    public void evict(String agentId) {
        String prefix = agentId + ":";
        agentCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private Mono<ChatResponse> runSingle(AgentDefinition definition, ChatRequest request) {
        RuntimeContext context = runtimeContext(request);
        return agent(definition)
                .call(request.message(), context)
                .map(msg -> response(definition.agentId(), request, msg));
    }

    private Mono<ChatResponse> runWorkflow(AgentDefinition definition, ChatRequest request) {
        if (definition.orchestration().workflow().isEmpty()) {
            return Mono.error(
                    new AgentRuntimeException(
                            "Workflow agent has no steps: " + definition.agentId()));
        }
        Mono<String> output = Mono.just(request.message());
        for (WorkflowStep step : definition.orchestration().workflow()) {
            output = output.flatMap(input -> runWorkflowStep(step, request, input));
        }
        return output.map(
                text ->
                        new ChatResponse(
                                definition.agentId(), userKey(request), sessionKey(request), text));
    }

    private Mono<String> runWorkflowStep(WorkflowStep step, ChatRequest request, String input) {
        AgentDefinition stepAgent = definition(step.agentId());
        String message =
                (step.instruction() == null || step.instruction().isBlank())
                        ? input
                        : step.instruction() + "\n\nInput:\n" + input;
        ChatRequest stepRequest =
                new ChatRequest(
                        request.tenantId(),
                        request.userId(),
                        sessionKey(request) + "_" + pathSafe(step.stepId(), "step"),
                        message);
        return runSingle(stepAgent, stepRequest).map(ChatResponse::text);
    }

    private AgentDefinition route(AgentDefinition definition, ChatRequest request) {
        return definition.orchestration().routes().stream()
                .filter(rule -> rule.matches(request.message()))
                .map(RouteRule::targetAgentId)
                .findFirst()
                .map(this::definition)
                .orElse(definition);
    }

    private AgentDefinition definition(String agentId) {
        return registry.findPublished(agentId)
                .orElseThrow(() -> new AgentRuntimeException("Agent not found: " + agentId));
    }

    private HarnessAgent agent(AgentDefinition definition) {
        String key = definition.agentId() + ":" + definition.version();
        return agentCache.computeIfAbsent(key, ignored -> harnessFactory.create(definition));
    }

    private RuntimeContext runtimeContext(ChatRequest request) {
        return RuntimeContext.builder()
                .userId(userKey(request))
                .sessionId(sessionKey(request))
                .put("tenant_id", safe(request.tenantId(), "default"))
                .build();
    }

    private ChatResponse response(String agentId, ChatRequest request, Msg msg) {
        return new ChatResponse(
                agentId, userKey(request), sessionKey(request), msg.getTextContent());
    }

    private AgentEventEnvelope envelope(AgentEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.getMetadata() != null) {
            event.getMetadata().forEach(payload::put);
        }
        String delta = null;
        if (event instanceof TextBlockDeltaEvent text) {
            delta = text.getDelta();
            payload.put("replyId", text.getReplyId());
            payload.put("blockId", text.getBlockId());
        }
        return new AgentEventEnvelope(
                event.getId(),
                event.getType().name(),
                event.getCreatedAt(),
                event.getSource(),
                delta,
                payload.isEmpty() ? null : payload);
    }

    private String userKey(ChatRequest request) {
        String tenant = safe(request.tenantId(), "default");
        String user = safe(request.userId(), "anonymous");
        return pathSafe(tenant + "_" + user, "default_anonymous");
    }

    private String sessionKey(ChatRequest request) {
        return pathSafe(request.sessionId(), "default");
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String pathSafe(String value, String fallback) {
        String text = safe(value, fallback).replaceAll("[^A-Za-z0-9._-]", "_");
        return text.isBlank() ? fallback : text;
    }
}
