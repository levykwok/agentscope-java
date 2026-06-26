/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.control.EmbeddingModelRegistry;
import com.company.platform.runtime.AgentRuntime;
import com.company.platform.runtime.ChatRequest;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/platform/frontend")
public class PlatformFrontendCompatibilityController {

    private final PlatformCompatibilityState state;
    private final AgentRuntime runtime;
    private final EmbeddingModelRegistry embeddingModelRegistry;
    private final WebClient webClient = WebClient.builder().build();

    public PlatformFrontendCompatibilityController(
            PlatformCompatibilityState state,
            AgentRuntime runtime,
            EmbeddingModelRegistry embeddingModelRegistry) {
        this.state = state;
        this.runtime = runtime;
        this.embeddingModelRegistry = embeddingModelRegistry;
    }

    @GetMapping("/infra/health")
    public Map<String, Object> health() {
        return map("status", "ok", "time", Instant.now().toString());
    }

    @GetMapping("/infra/status")
    public Map<String, Object> status() {
        return map(
                "app_domain",
                "platform",
                "domains",
                map(
                        "platform",
                        map("display_name", "平台", "org_id", "platform", "live_available", true)),
                "databases",
                map("platform_configured", true, "active_domain_configured", true),
                "redis",
                map("enabled", false, "configured", false),
                "kafka",
                map("enabled", false, "available", false),
                "object_storage",
                map("minio_enabled", false, "bucket", ""),
                "rag",
                map(
                        "bm25_service",
                        map("configured", false, "type", "compat"),
                        "vector_service",
                        map("configured", false),
                        "vector_secondary_service",
                        map("configured", false)),
                "runtime_sandbox",
                map("status", "compat", "platform_service", true, "backends", List.of("compat")));
    }

    @GetMapping("/agents")
    public Map<String, Object> agents(@RequestParam(required = false) String domain) {
        return map("items", state.agents(), "agents", state.agents());
    }

    @GetMapping("/agents/{agentId}")
    public Map<String, Object> agent(@PathVariable String agentId) {
        return state.agents().stream()
                .filter(row -> agentId.equals(row.get("agent_id")))
                .findFirst()
                .orElseGet(() -> map("agent_id", agentId, "display_name", agentId));
    }

    @GetMapping("/agents/{agentId}/spec")
    public Map<String, Object> agentSpec(@PathVariable String agentId) {
        return state.agentSpec(agentId);
    }

    @PostMapping("/agents")
    public Map<String, Object> upsertAgent(@RequestBody Map<String, Object> payload) {
        Map<String, Object> agent = state.upsertAgent(payload);
        return map("item", agent, "agent", agent);
    }

    @PatchMapping("/agents/{agentId}")
    public Map<String, Object> patchAgent(
            @PathVariable String agentId, @RequestBody Map<String, Object> payload) {
        payload.put("agent_id", agentId);
        Map<String, Object> agent = state.upsertAgent(payload);
        return map("item", agent, "agent", agent);
    }

    @GetMapping("/agents/runs")
    public Map<String, Object> runs(
            @RequestParam(required = false) String agent_id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = state.runs(agent_id, status, limit);
        return map("items", rows, "runs", rows);
    }

    @GetMapping("/agents/runs/{runId}")
    public Map<String, Object> run(@PathVariable String runId) {
        return map("run", state.run(runId));
    }

    @GetMapping("/agents/runs/{runId}/steps")
    public Map<String, Object> runSteps(@PathVariable String runId) {
        List<Map<String, Object>> rows = state.runSteps(runId);
        return map("items", rows, "steps", rows);
    }

    @GetMapping("/agents/runs/{runId}/events")
    public Map<String, Object> runEvents(@PathVariable String runId) {
        List<Map<String, Object>> rows = state.runEvents(runId);
        return map("items", rows, "events", rows, "next_after_id", rows.size());
    }

    @GetMapping("/agents/runs/{runId}/waiting")
    public Map<String, Object> waiting(@PathVariable String runId) {
        return map("item", state.waiting(runId));
    }

    @PostMapping("/agents/runs/{runId}/waiting/{waitingId}/resume")
    public Map<String, Object> resumeWaiting(
            @PathVariable String runId,
            @PathVariable String waitingId,
            @RequestBody(required = false) Map<String, Object> payload) {
        return map("ok", true, "run_id", runId, "waiting_id", waitingId, "status", "resumed");
    }

    @PostMapping("/agents/runs/{runId}/waiting/{waitingId}/reject")
    public Map<String, Object> rejectWaiting(
            @PathVariable String runId,
            @PathVariable String waitingId,
            @RequestBody(required = false) Map<String, Object> payload) {
        return map("ok", true, "run_id", runId, "waiting_id", waitingId, "status", "rejected");
    }

    @GetMapping("/flows")
    public Map<String, Object> flows() {
        List<Map<String, Object>> rows =
                List.of(
                        map(
                                "flow_id",
                                "agentscope_runtime",
                                "id",
                                "agentscope_runtime",
                                "display_name",
                                "AgentScope Runtime",
                                "capabilities",
                                map(
                                        "supports_tool_calling",
                                        true,
                                        "supports_skill_tools",
                                        true,
                                        "supports_memory",
                                        false),
                                "nodes",
                                List.of()));
        return map("items", rows, "flows", rows);
    }

    @GetMapping("/tools")
    public Map<String, Object> tools(@RequestParam(required = false) String domain) {
        return map("items", state.tools(), "tools", state.tools());
    }

    @PutMapping("/tools/bindings/{toolId}")
    public Map<String, Object> toolBinding(
            @PathVariable String toolId, @RequestBody Map<String, Object> payload) {
        return map("ok", true, "tool_id", toolId, "binding", payload);
    }

    @PutMapping("/tools/agents/{agentId}/policies/{toolId}")
    public Map<String, Object> toolPolicy(
            @PathVariable String agentId,
            @PathVariable String toolId,
            @RequestBody Map<String, Object> payload) {
        return map("ok", true, "agent_id", agentId, "tool_id", toolId, "policy", payload);
    }

    @PostMapping({"/tools/http", "/tools/db-query", "/tools/sandbox-script"})
    public Map<String, Object> createTool(@RequestBody Map<String, Object> payload) {
        return map(
                "ok",
                true,
                "tool_id",
                payload.getOrDefault("tool_id", payload.getOrDefault("name", "tool")),
                "message",
                "工具已由兼容层接收",
                "item",
                payload);
    }

    @PostMapping("/tools/{toolId}/test")
    public Map<String, Object> testTool(
            @PathVariable String toolId,
            @RequestBody(required = false) Map<String, Object> payload) {
        return map("ok", true, "tool_id", toolId, "latency_ms", 1, "result_preview", "compat ok");
    }

    @GetMapping("/tools/{toolId}/schema-snapshots")
    public Map<String, Object> schemaSnapshots(@PathVariable String toolId) {
        return map("items", List.of());
    }

    @GetMapping("/tools/audit")
    public Map<String, Object> toolAudit() {
        return map("items", state.audit());
    }

    @GetMapping("/mcp")
    public Map<String, Object> mcps() {
        return map("items", state.mcpServers(), "mcp_servers", state.mcpServers());
    }

    @PostMapping("/mcp")
    public Map<String, Object> createMcp(@RequestBody Map<String, Object> payload) {
        Map<String, Object> server = state.upsertMcpServer(null, payload);
        return map("item", server, "server", server);
    }

    @PatchMapping("/mcp/{id}")
    public Map<String, Object> patchMcp(
            @PathVariable String id, @RequestBody Map<String, Object> payload) {
        Map<String, Object> server = state.upsertMcpServer(id, payload);
        return map("item", server, "server", server);
    }

    @DeleteMapping("/mcp/{id}")
    public Map<String, Object> deleteMcp(@PathVariable String id) {
        return map("ok", true, "id", id);
    }

    @PostMapping("/mcp/probe")
    public Map<String, Object> probeMcp(
            @RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> probe = state.probe(payload == null ? Map.of() : payload);
        return map("probe", probe);
    }

    @PostMapping("/mcp/{id}/probe")
    public Map<String, Object> probeMcpById(@PathVariable String id) {
        Map<String, Object> probe = state.probe(map("endpoint", id));
        return map(
                "probe",
                probe,
                "server",
                map("id", id, "metadata", map("health_status", "healthy")));
    }

    @GetMapping("/mcp/{id}/tools")
    public Map<String, Object> mcpTools(@PathVariable String id) {
        Map<String, Object> server =
                state.mcpServers().stream()
                        .filter(row -> id.equals(String.valueOf(row.get("id"))))
                        .findFirst()
                        .orElseGet(() -> map("id", id, "name", "mcp-" + id));
        List<Map<String, Object>> rows = state.mcpTools(server);
        return map("items", rows, "tools", rows);
    }

    @GetMapping("/mcp/{id}/bindings")
    public Map<String, Object> mcpBindings(@PathVariable String id) {
        List<Map<String, Object>> rows = state.mcpBindings(id);
        return map("items", rows, "bindings", rows);
    }

    @PostMapping("/mcp/bindings")
    public Map<String, Object> createMcpBinding(@RequestBody Map<String, Object> payload) {
        Map<String, Object> binding = state.bindMcp(payload);
        return map("binding", binding, "item", binding);
    }

    @DeleteMapping("/mcp/bindings/{id}")
    public Map<String, Object> deleteMcpBinding(@PathVariable String id) {
        state.deleteMcpBinding(id);
        return map("ok", true, "id", id);
    }

    @GetMapping("/skills")
    public Map<String, Object> skills(@RequestParam(required = false) String domain) {
        return map("items", state.skills(), "skills", state.skills());
    }

    @PostMapping("/skills/sync")
    public Map<String, Object> syncSkills() {
        return map("synced", state.skills());
    }

    @PostMapping("/skills/{skillId}/enable")
    public Map<String, Object> enableSkill(@PathVariable String skillId) {
        return map("ok", true, "skill_id", skillId, "enabled", true);
    }

    @PostMapping("/skills/{skillId}/disable")
    public Map<String, Object> disableSkill(@PathVariable String skillId) {
        return map("ok", true, "skill_id", skillId, "enabled", false);
    }

    @PostMapping("/skills/{skillId}/test")
    public Map<String, Object> testSkill(@PathVariable String skillId) {
        return map("ok", true, "skill_id", skillId, "result", "compat ok");
    }

    @GetMapping("/skills/packages")
    public Map<String, Object> packages() {
        return map("items", List.of());
    }

    @PostMapping(value = "/skills/packages/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadSkillPackage(
            @RequestPart("file") FilePart file,
            @RequestParam(defaultValue = "platform") String domain) {
        return Mono.just(
                map(
                        "id",
                        "pkg_" + Instant.now().toEpochMilli(),
                        "skill_id",
                        file.filename(),
                        "version",
                        "v1",
                        "domain",
                        domain,
                        "status",
                        "validated"));
    }

    @PostMapping("/skills/packages/{id}/publish")
    public Map<String, Object> publishPackage(@PathVariable String id) {
        return map("id", id, "status", "published", "skill_id", "compat", "version", "v1");
    }

    @PostMapping("/skills/packages/{id}/reject")
    public Map<String, Object> rejectPackage(@PathVariable String id) {
        return map("id", id, "status", "rejected");
    }

    @PatchMapping("/skills/packages/{id}/permissions")
    public Map<String, Object> packagePermissions(@PathVariable String id) {
        return map("id", id, "ok", true);
    }

    @DeleteMapping("/skills/packages/{id}")
    public Map<String, Object> deletePackage(@PathVariable String id) {
        return map("id", id, "ok", true);
    }

    @GetMapping("/models/schema")
    public Map<String, Object> modelSchema() {
        return map(
                "provider_types",
                List.of(
                        "openai",
                        "openai-compatible",
                        "gpustack",
                        "vllm",
                        "ollama",
                        "http_chat",
                        "dashscope",
                        "mock",
                        "echo",
                        "custom"),
                "model_kinds",
                List.of("chat", "embedding", "rerank"),
                "provider_call_types",
                List.of("generate", "embed", "rerank"),
                "statuses",
                List.of("active", "disabled"));
    }

    @GetMapping("/models/providers")
    public Map<String, Object> providers() {
        return map("providers", state.providers());
    }

    @PostMapping("/models/providers")
    public Map<String, Object> createProvider(@RequestBody Map<String, Object> payload) {
        Map<String, Object> provider = state.providerUpsert(null, payload);
        return map("provider", provider);
    }

    @PatchMapping("/models/providers/{id}")
    public Map<String, Object> patchProvider(
            @PathVariable String id, @RequestBody Map<String, Object> payload) {
        Map<String, Object> provider = state.providerUpsert(id, payload);
        return map("provider", provider);
    }

    @DeleteMapping("/models/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable String id) {
        state.deleteProvider(id);
        return map("ok", true, "provider_id", id);
    }

    @PostMapping("/models/providers/{id}/ping")
    public Map<String, Object> pingProvider(@PathVariable String id) {
        return map("ok", true, "provider_id", id, "duration_ms", 1);
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return map("models", state.modelRows());
    }

    @PostMapping("/models")
    public Map<String, Object> createModel(@RequestBody Map<String, Object> payload) {
        Map<String, Object> model = state.modelUpsert(null, payload);
        return map("model", model);
    }

    @PatchMapping("/models/{id}")
    public Map<String, Object> patchModel(
            @PathVariable String id, @RequestBody Map<String, Object> payload) {
        Map<String, Object> model = state.modelUpsert(id, payload);
        return map("model", model);
    }

    @DeleteMapping("/models/{id}")
    public Map<String, Object> deleteModel(@PathVariable String id) {
        state.deleteModel(id);
        return map("ok", true, "model_id", id);
    }

    @PostMapping("/models/{id}/ping")
    public Map<String, Object> pingModel(@PathVariable String id) {
        return map("ok", true, "model_id", id, "duration_ms", 1);
    }

    @PostMapping("/models/{id}/test")
    public Mono<Map<String, Object>> testModel(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> model = state.modelRow(id);
        if (model.isEmpty()) {
            return Mono.just(map("ok", false, "model_id", id, "error", "model not found"));
        }
        Map<String, Object> provider =
                state.provider(string(model.get("provider_id"), "openai-compatible"));
        String callType = string(model.get("provider_call_type"), "generate");
        String modelKind = string(model.get("model_kind"), string(model.get("kind"), "chat"));
        String providerType = string(provider.get("provider_type"), "openai");
        if ("mock".equals(providerType) || "echo".equals(providerType)) {
            String prompt = string(payload == null ? null : payload.get("prompt"), "ping");
            return Mono.just(
                    map(
                            "ok",
                            true,
                            "model_id",
                            id,
                            "provider_id",
                            model.get("provider_id"),
                            "answer",
                            "无真实外部请求: " + prompt,
                            "result",
                            map("text", "无真实外部请求: " + prompt)));
        }
        if ("embedding".equals(modelKind) || "embed".equals(callType)) {
            String input =
                    firstText(
                            payload == null ? null : payload.get("input"),
                            payload == null ? null : payload.get("prompt"));
            if (input.isBlank()) {
                input = "ping";
            }
            String finalInput = input;
            long started = System.nanoTime();
            return Mono.fromCallable(
                            () -> {
                                double[] vector =
                                        embeddingModelRegistry
                                                .resolveEmbeddingModel(id)
                                                .embed(TextBlock.builder().text(finalInput).build())
                                                .block();
                                int dimensions = vector == null ? 0 : vector.length;
                                return map(
                                        "ok",
                                        true,
                                        "model_id",
                                        id,
                                        "provider_id",
                                        model.get("provider_id"),
                                        "model_name",
                                        string(model.get("model_name"), id),
                                        "duration_ms",
                                        (System.nanoTime() - started) / 1_000_000,
                                        "result",
                                        map(
                                                "dimensions",
                                                dimensions,
                                                "sample",
                                                sampleVector(vector)));
                            })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(
                            e ->
                                    Mono.just(
                                            map(
                                                    "ok",
                                                    false,
                                                    "model_id",
                                                    id,
                                                    "error",
                                                    e.getMessage())));
        }
        if (!"generate".equals(callType)) {
            return Mono.just(
                    map("ok", false, "model_id", id, "error", "当前只实现 chat/generate 模型真实测试"));
        }

        String baseUrl = firstText(model.get("base_url"), provider.get("default_base_url"));
        String endpointPath =
                firstText(
                        provider.get("endpoint_path"), defaultEndpointPath(providerType, callType));
        String apiKey =
                resolveSecret(firstText(model.get("secret_ref"), provider.get("secret_ref")));
        String modelName = string(model.get("model_name"), id);
        String prompt = string(payload == null ? null : payload.get("prompt"), "ping");
        if (baseUrl.isBlank()) {
            return Mono.just(map("ok", false, "model_id", id, "error", "base_url is required"));
        }
        if (apiKey.isBlank()) {
            return Mono.just(
                    map("ok", false, "model_id", id, "error", "secret_ref/api key is required"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", List.of(map("role", "user", "content", prompt)));
        body.put("stream", false);
        body.put("thinking", map("type", "disabled"));
        body.putAll(objectMap(model.get("extra_body")));
        if (payload != null) {
            Integer maxTokens = integer(payload.get("max_tokens"));
            if (maxTokens != null) {
                body.put("max_tokens", maxTokens);
            }
        }

        long started = System.nanoTime();
        WebClient.RequestBodySpec request =
                webClient
                        .post()
                        .uri(resolveRequestUrl(baseUrl, endpointPath, providerType))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON);
        objectMap(model.get("extra_headers"))
                .forEach(
                        (key, value) -> {
                            if (value != null && !String.valueOf(key).isBlank()) {
                                request.header(String.valueOf(key), String.valueOf(value));
                            }
                        });
        return request.bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(
                        raw -> {
                            Map<String, Object> response = objectMap(raw);
                            String text = extractOpenAiText(response);
                            return map(
                                    "ok",
                                    true,
                                    "model_id",
                                    id,
                                    "provider_id",
                                    model.get("provider_id"),
                                    "model_name",
                                    modelName,
                                    "duration_ms",
                                    (System.nanoTime() - started) / 1_000_000,
                                    "answer",
                                    text,
                                    "result",
                                    map("text", text, "raw", response));
                        })
                .onErrorResume(
                        e -> Mono.just(map("ok", false, "model_id", id, "error", e.getMessage())));
    }

    @GetMapping("/models/slots")
    public Map<String, Object> slots() {
        return map("slots", state.slots());
    }

    @GetMapping("/models/slots/bindings")
    public Map<String, Object> slotBindings() {
        return map("bindings", state.slotBindings());
    }

    @PutMapping("/models/slots/{slotKey}/platform/_")
    public Map<String, Object> bindSlot(
            @PathVariable String slotKey, @RequestBody Map<String, Object> payload) {
        return map("binding", state.bindSlot(slotKey, payload));
    }

    @DeleteMapping("/models/slots/{slotKey}/platform/_")
    public Map<String, Object> clearSlot(@PathVariable String slotKey) {
        return map("ok", true, "slot_key", slotKey);
    }

    @GetMapping("/models/aliases")
    public Map<String, Object> aliases() {
        return map("aliases", state.aliases());
    }

    @PostMapping("/models/aliases")
    public Map<String, Object> createAlias(@RequestBody Map<String, Object> payload) {
        return map("alias", state.alias(payload));
    }

    @DeleteMapping("/models/aliases/{id}")
    public Map<String, Object> deleteAlias(@PathVariable String id) {
        return map("ok", true, "id", id);
    }

    @GetMapping("/models/aliases/available")
    public Map<String, Object> aliasesAvailable() {
        return map("fixed_slots", state.slots(), "aliases", state.aliases());
    }

    @GetMapping("/models/resolve")
    public Map<String, Object> resolveModel(@RequestParam(defaultValue = "qa") String slot) {
        Map<String, Object> binding =
                state.slotBindings().stream()
                        .filter(row -> slot.equals(row.get("slot_key")))
                        .findFirst()
                        .orElse(Map.of());
        return map(
                "resolved",
                map("slot_key", slot, "scope", "platform", "model_id", binding.get("model_id")));
    }

    @GetMapping("/models/audit")
    public Map<String, Object> modelAudit() {
        return map("events", state.audit());
    }

    @GetMapping("/chat/sessions")
    public Map<String, Object> sessions(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String agent_id) {
        List<Map<String, Object>> rows = state.sessions(domain, agent_id);
        return map("items", rows, "sessions", rows);
    }

    @PostMapping("/chat/sessions")
    public Map<String, Object> createSession(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-org-id", defaultValue = "platform") String orgId) {
        return state.newSession(payload, orgId);
    }

    @GetMapping("/chat/sessions/{id}")
    public Map<String, Object> session(
            @PathVariable String id, @RequestParam(required = false) String agent_id) {
        return state.session(id, agent_id);
    }

    @DeleteMapping("/chat/sessions/{id}")
    public Map<String, Object> deleteSession(
            @PathVariable String id, @RequestParam(required = false) String agent_id) {
        state.deleteSession(id, agent_id);
        return map("ok", true, "session_id", id);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chatStream(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "platform_admin") String userId) {
        String query = string(payload.get("query"), "");
        String sessionId = string(payload.get("session_id"), "default");
        String domain = string(payload.get("domain"), "platform");
        String agentId = string(payload.get("agent_id"), "platform_knowledge_agent");
        Map<String, Object> run = state.createRun(agentId, query, userId);
        return runtime.chat(agentId, new ChatRequest(domain, userId, sessionId, query))
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> string(response.text(), ""))
                .onErrorReturn("兼容层回答: " + query)
                .flatMapMany(
                        text -> {
                            return Flux.just(
                                    sse(
                                            "activity",
                                            map(
                                                    "type",
                                                    "activity",
                                                    "id",
                                                    "receive",
                                                    "step",
                                                    "receive",
                                                    "title",
                                                    "接收问题",
                                                    "status",
                                                    "success")),
                                    sse("token", map("type", "token", "delta", text)),
                                    sse(
                                            "done",
                                            map(
                                                    "type",
                                                    "done",
                                                    "run_id",
                                                    run.get("run_id"),
                                                    "result",
                                                    map("answer", text, "text", text))));
                        })
                .delayElements(Duration.ofMillis(20));
    }

    @GetMapping("/live-context/turns/{traceId}")
    public Map<String, Object> turnContext(@PathVariable String traceId) {
        return map(
                "trace_id",
                traceId,
                "session_id",
                "compat",
                "domain",
                "platform",
                "activity",
                List.of(),
                "resources",
                emptyResources(),
                "memory_usage",
                map(
                        "session",
                        Map.of(),
                        "long_term",
                        List.of(),
                        "episodic",
                        List.of(),
                        "written",
                        map(
                                "session_messages",
                                List.of(),
                                "summary",
                                null,
                                "long_term_memory_ids",
                                List.of(),
                                "episodic_ids",
                                List.of())));
    }

    @GetMapping("/live-context/sessions/{sessionId}/resources")
    public Map<String, Object> sessionResources(@PathVariable String sessionId) {
        return emptyResources();
    }

    @PostMapping("/live-context/memory/{id}/feedback")
    public Map<String, Object> memoryFeedback(
            @PathVariable long id, @RequestBody Map<String, Object> payload) {
        return map("ok", true, "id", id, "action", payload.get("action"));
    }

    @GetMapping("/knowledge/docs")
    public Map<String, Object> docs(@RequestParam(required = false) String domain) {
        return map("items", state.docs(domain), "documents", state.docs(domain));
    }

    @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadDoc(
            @RequestPart("file") FilePart file,
            @RequestParam(defaultValue = "platform") String domain,
            @RequestHeader(value = "x-org-id", defaultValue = "platform") String orgId) {
        return Mono.just(state.document(file.filename(), domain, orgId));
    }

    @GetMapping("/knowledge/collections")
    public Map<String, Object> collections(@RequestParam(required = false) String domain) {
        return map("items", state.collections(domain));
    }

    @PostMapping("/knowledge/collections")
    public Map<String, Object> createCollection(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-org-id", defaultValue = "platform") String orgId) {
        Map<String, Object> collection = state.collection(payload, orgId);
        return map("item", collection, "collection_id", collection.get("collection_id"));
    }

    @PostMapping("/knowledge/collections/{id}/items")
    public Map<String, Object> addCollectionItem(@PathVariable String id) {
        return map("ok", true, "collection_id", id);
    }

    @DeleteMapping("/knowledge/collections/{id}/items/document/{docId}")
    public Map<String, Object> removeCollectionItem(
            @PathVariable String id, @PathVariable String docId) {
        return map("ok", true, "collection_id", id, "doc_id", docId);
    }

    @PostMapping("/knowledge/docs/{docId}/{versionId}/reindex")
    public Map<String, Object> reindexDoc(
            @PathVariable String docId, @PathVariable String versionId) {
        return map("ok", true, "bm25", map("indexed", 1), "doc_id", docId, "version_id", versionId);
    }

    @PostMapping(
            value = "/knowledge/docs/{docId}/{versionId}/replace",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> replaceDoc(
            @PathVariable String docId,
            @PathVariable String versionId,
            @RequestPart("file") FilePart file) {
        return Mono.just(
                map(
                        "doc_id",
                        docId,
                        "version_id",
                        versionId,
                        "new_version_id",
                        "v" + Instant.now().toEpochMilli(),
                        "filename",
                        file.filename()));
    }

    @PostMapping("/knowledge/docs/{docId}/{versionId}/preview/ensure")
    public Map<String, Object> ensurePreview(
            @PathVariable String docId, @PathVariable String versionId) {
        return map(
                "preview_ready",
                false,
                "preview_message",
                "兼容层未生成预览",
                "doc_id",
                docId,
                "version_id",
                versionId);
    }

    @DeleteMapping("/knowledge/docs/{docId}/{versionId}")
    public Map<String, Object> deleteDoc(
            @PathVariable String docId, @PathVariable String versionId) {
        return map("ok", true, "doc_id", docId, "version_id", versionId);
    }

    @GetMapping("/memory/long-term")
    public Map<String, Object> longTermMemory(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String status) {
        List<Map<String, Object>> rows = state.memories(domain, status);
        return map("items", rows, "count", rows.size());
    }

    @PostMapping("/memory/long-term")
    public Map<String, Object> createMemory(@RequestBody Map<String, Object> payload) {
        Map<String, Object> item = state.memory(payload);
        return map("id", item.get("id"), "item", item);
    }

    @GetMapping("/memory/long-term/{id}")
    public Map<String, Object> getMemory(@PathVariable String id) {
        return map("item", state.updateMemory(id, Map.of()));
    }

    @PatchMapping("/memory/long-term/{id}")
    public Map<String, Object> patchMemory(
            @PathVariable String id, @RequestBody Map<String, Object> payload) {
        return map("item", state.updateMemory(id, payload));
    }

    @DeleteMapping("/memory/long-term/{id}")
    public Map<String, Object> deleteMemory(@PathVariable String id) {
        return map("ok", true, "id", id);
    }

    @PostMapping("/memory/long-term/{id}/confirm")
    public Map<String, Object> confirmMemory(@PathVariable String id) {
        return map("item", state.updateMemory(id, Map.of("status", "active")));
    }

    @PostMapping("/memory/long-term/{id}/reject")
    public Map<String, Object> rejectMemory(@PathVariable String id) {
        return map("item", state.updateMemory(id, Map.of("status", "rejected")));
    }

    @PostMapping("/memory/long-term/{id}/merge")
    public Map<String, Object> mergeMemory(@PathVariable String id) {
        return map("item", state.updateMemory(id, Map.of("status", "merged")));
    }

    @GetMapping("/memory/audit")
    public Map<String, Object> memoryAudit() {
        return map("items", state.audit());
    }

    @GetMapping("/memory/episodic/status")
    public Map<String, Object> episodicStatus() {
        return map("enabled", true, "index_enabled", true, "active_count", 0, "total_count", 0);
    }

    @GetMapping("/memory/maintenance/status")
    public Map<String, Object> maintenanceStatus() {
        return map(
                "long_term_memory_configured",
                true,
                "maintenance",
                map("mode", "compat", "apply_from_live", false));
    }

    @PostMapping({
        "/memory/maintenance/dry-run",
        "/memory/episodic/maintenance",
        "/memory/episodic/rebuild",
        "/memory/episodic/clear"
    })
    public Map<String, Object> memoryOps() {
        return map(
                "scanned",
                0,
                "user_count",
                0,
                "planned_actions",
                0,
                "applied_actions",
                0,
                "indexed",
                0,
                "deleted",
                0,
                "reason_counts",
                Map.of());
    }

    @GetMapping("/runtime-sandbox/runs")
    public Map<String, Object> sandboxRuns() {
        return map("items", List.of());
    }

    @GetMapping("/runtime-sandbox/runs/{id}")
    public Map<String, Object> sandboxRun(@PathVariable String id) {
        return map("sandbox_run_id", id, "status", "unknown");
    }

    @GetMapping("/kg/graph-spaces")
    public Map<String, Object> graphSpaces(@RequestParam(defaultValue = "platform") String domain) {
        List<Map<String, Object>> rows =
                List.of(
                        map(
                                "org_id",
                                "platform",
                                "domain",
                                domain,
                                "graph_key",
                                "default",
                                "display_name",
                                "默认图谱"));
        return map("items", rows, "graph_spaces", rows);
    }

    @PostMapping({"/kg/graph-spaces", "/kg/graph-spaces/update", "/kg/graph-spaces/archive"})
    public Map<String, Object> graphSpaceMutation(
            @RequestBody(required = false) Map<String, Object> payload) {
        return map("ok", true, "item", payload == null ? Map.of() : payload);
    }

    @GetMapping({"/kg/entities", "/kg/facts", "/kg/versions", "/kg/entities/{id}/facts"})
    public Map<String, Object> kgEmpty() {
        return map("items", List.of());
    }

    private static ServerSentEvent<Map<String, Object>> sse(
            String event, Map<String, Object> data) {
        return ServerSentEvent.builder(data).event(event).build();
    }

    private static Map<String, Object> emptyResources() {
        return map(
                "attachments",
                List.of(),
                "documents",
                List.of(),
                "citations",
                List.of(),
                "kg_hits",
                List.of(),
                "tool_calls",
                List.of(),
                "artifacts",
                List.of());
    }

    private static String string(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static String firstText(Object first, Object second) {
        String value = string(first, "");
        return value.isBlank() ? string(second, "") : value;
    }

    private static String resolveSecret(String secretRef) {
        String value = secretRef == null ? "" : secretRef.trim();
        if (value.startsWith("env:")) {
            return string(System.getenv(value.substring(4)), "");
        }
        String byEnv = System.getenv(value);
        return byEnv == null || byEnv.isBlank() ? value : byEnv;
    }

    private static String resolveRequestUrl(String baseUrl, String path, String providerType) {
        String base = trimTrailingSlash(baseUrl);
        if (hasEndpoint(base) || path == null || path.isBlank()) {
            return base;
        }
        String suffix = path.startsWith("/") ? path : "/" + path;
        if (isOpenAiCompatibleProvider(providerType) && !base.matches(".*/v1(/.*)?$")) {
            return base + "/v1" + suffix;
        }
        return base + suffix;
    }

    private static String defaultEndpointPath(String providerType, String callType) {
        if ("embed".equals(callType)) {
            if ("ollama".equals(providerType)) {
                return "/api/embeddings";
            }
            if (isOpenAiCompatibleProvider(providerType)) {
                return "/embeddings";
            }
        }
        if ("ollama".equals(providerType)) {
            return "/api/chat";
        }
        if ("http_chat".equals(providerType)) {
            return "";
        }
        if ("dashscope".equals(providerType)) {
            return "/compatible-mode/v1/chat/completions";
        }
        return "/chat/completions";
    }

    private static boolean isOpenAiCompatibleProvider(String providerType) {
        return List.of("openai", "openai-compatible", "gpustack", "vllm").contains(providerType);
    }

    private static boolean hasEndpoint(String baseUrl) {
        return baseUrl.matches(".*/(chat/completions|embeddings|api/chat|api/embeddings)$");
    }

    private static String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return Map.of();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractOpenAiText(Map<String, Object> response) {
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            return "";
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return "";
        }
        Object message = choice.get("message");
        if (message instanceof Map<?, ?> messageMap) {
            Object content = messageMap.get("content");
            if (content instanceof String text) {
                return text;
            }
            if (content instanceof List<?> parts) {
                List<String> texts = new ArrayList<>();
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> partMap && partMap.get("text") != null) {
                        texts.add(String.valueOf(partMap.get("text")));
                    }
                }
                return String.join("", texts);
            }
        }
        return string(choice.get("text"), "");
    }

    private static List<Double> sampleVector(double[] vector) {
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        List<Double> sample = new ArrayList<>();
        for (int i = 0; i < Math.min(vector.length, 8); i++) {
            sample.add(vector[i]);
        }
        return sample;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }
}
