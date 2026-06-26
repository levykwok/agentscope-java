/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.company.platform.control.AgentDefinition;
import com.company.platform.control.AgentDefinitionRegistry;
import com.company.platform.control.McpRegistry;
import com.company.platform.control.McpSpec;
import com.company.platform.control.ModelConfigRegistry;
import com.company.platform.control.ModelProviderRegistry;
import com.company.platform.control.ModelProviderSpec;
import com.company.platform.control.ModelSpec;
import com.company.platform.control.SkillRegistry;
import com.company.platform.control.ToolRegistry;
import com.company.platform.control.ToolSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class PlatformCompatibilityState {

    private final AgentDefinitionRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final SkillRegistry skillRegistry;
    private final ModelConfigRegistry modelRegistry;
    private final ModelProviderRegistry providerRegistry;
    private final PlatformWorkspaceSessionStore workspaceSessionStore;
    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<String, List<Map<String, Object>>> attachments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> attachmentsById = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> runs = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> runEvents = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> runSteps = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> waitings = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> providers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> modelRows = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> slotBindings = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> aliases = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> mcpServers = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> mcpBindings = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> knowledgeDocs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> collections = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> memories = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> audit = new ArrayList<>();

    public PlatformCompatibilityState(
            AgentDefinitionRegistry agentRegistry,
            ToolRegistry toolRegistry,
            McpRegistry mcpRegistry,
            SkillRegistry skillRegistry,
            ModelConfigRegistry modelRegistry,
            ModelProviderRegistry providerRegistry,
            PlatformWorkspaceSessionStore workspaceSessionStore) {
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.modelRegistry = modelRegistry;
        this.providerRegistry = providerRegistry;
        this.workspaceSessionStore = workspaceSessionStore;
        seedModels();
        seedMcps();
    }

    public List<Map<String, Object>> agents() {
        return agentRegistry.allPublished().stream().map(this::agentRow).toList();
    }

    public Map<String, Object> agentSpec(String agentId) {
        AgentDefinition definition = agentRegistry.findPublished(agentId).orElse(null);
        if (definition == null) {
            return Map.of();
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("agent_id", definition.agentId());
        config.put("name", definition.name());
        config.put("description", definition.systemPrompt());
        config.put("domain", "platform");
        config.put("enabled", true);
        config.put("skill_scope", Map.of("include", definition.skillRefs()));
        config.put("tool_scope", Map.of("include", definition.toolRefs()));
        config.put("model_policy", Map.of("qa", definition.model()));
        config.put(
                "prompt_policy",
                Map.of(
                        "role",
                        definition.systemPrompt(),
                        "planner_rules",
                        List.of(),
                        "require_structured_plan",
                        true));

        Map<String, Object> flows = new LinkedHashMap<>();
        flows.put("default", "agentscope_runtime");
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("flows", flows);
        workflow.put("orchestration", definition.orchestration());
        return Map.of("agent_id", agentId, "config_json", config, "workflow_json", workflow);
    }

    public Map<String, Object> upsertAgent(Map<String, Object> payload) {
        String agentId = string(payload, "agent_id", "agent_" + sequence.getAndIncrement());
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("agent_id", agentId);
        row.putIfAbsent("display_name", string(payload, "display_name", agentId));
        row.putIfAbsent("domain", "platform");
        row.putIfAbsent("source", "custom");
        row.putIfAbsent("enabled", true);
        return row;
    }

    public List<Map<String, Object>> tools() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ToolSpec spec : toolRegistry.all()) {
            rows.add(
                    row(
                            "tool_id",
                            spec.toolId(),
                            "name",
                            spec.toolId(),
                            "display_name",
                            spec.toolId(),
                            "description",
                            spec.description(),
                            "source_type",
                            spec.type(),
                            "category",
                            "standard",
                            "domain",
                            "platform",
                            "binding_status",
                            spec.enabled() ? "enabled" : "disabled",
                            "binding_visibility",
                            "discoverable",
                            "risk_level",
                            "low",
                            "side_effect",
                            "read_only",
                            "parameter_names",
                            List.of(),
                            "required",
                            List.of()));
        }
        for (Map<String, Object> server : mcpServers.values()) {
            for (Map<String, Object> tool : mcpTools(server)) {
                rows.add(tool);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> skills() {
        return skillRegistry.all().stream()
                .map(
                        spec ->
                                row(
                                        "skill_id",
                                        spec.skillId(),
                                        "name",
                                        spec.skillId(),
                                        "display_name",
                                        spec.skillId(),
                                        "description",
                                        spec.description(),
                                        "domain",
                                        sourceDomain(spec.source()),
                                        "source",
                                        spec.source(),
                                        "scope",
                                        spec.scope(),
                                        "enabled",
                                        spec.enabled(),
                                        "version",
                                        "v1"))
                .toList();
    }

    public List<Map<String, Object>> mcpServers() {
        return sorted(mcpServers.values(), "id");
    }

    public Map<String, Object> upsertMcpServer(String id, Map<String, Object> payload) {
        String serverId =
                id == null || id.isBlank() ? String.valueOf(sequence.getAndIncrement()) : id;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", serverId);
        row.put("name", string(payload, "name", "mcp-" + serverId));
        row.put("endpoint", string(payload, "endpoint", string(payload, "url", "")));
        row.put("description", string(payload, "description", ""));
        row.put("timeout_ms", number(payload.get("timeout_ms"), 5000));
        row.put("tool_filter", payload.getOrDefault("tool_filter", List.of()));
        row.put("enabled", payload.getOrDefault("enabled", true));
        row.put("has_auth", payload.containsKey("auth_header"));
        row.put(
                "metadata",
                row(
                        "health_status",
                        "healthy",
                        "last_tool_count",
                        mcpTools(row).size(),
                        "last_discovered_at",
                        Instant.now().toString()));
        mcpServers.put(serverId, row);
        audit("mcp.server.saved", serverId, row);
        return row;
    }

    public Map<String, Object> providerUpsert(String id, Map<String, Object> payload) {
        String providerId =
                id == null || id.isBlank()
                        ? string(payload, "provider_id", "provider_" + sequence.getAndIncrement())
                        : id;
        Map<String, Object> row =
                new LinkedHashMap<>(
                        providers.getOrDefault(providerId, Map.of("provider_id", providerId)));
        row.putAll(payload);
        row.put("provider_id", providerId);
        row.putIfAbsent("display_name", providerId);
        row.putIfAbsent("provider_type", "openai-compatible");
        row.putIfAbsent("status", "active");
        providers.put(providerId, row);
        providerRegistry.upsert(toProviderSpec(row));
        audit("model.provider.saved", providerId, row);
        return row;
    }

    public Map<String, Object> modelUpsert(String id, Map<String, Object> payload) {
        String modelId =
                id == null || id.isBlank()
                        ? string(payload, "model_id", "model_" + sequence.getAndIncrement())
                        : id;
        Map<String, Object> row =
                new LinkedHashMap<>(modelRows.getOrDefault(modelId, Map.of("model_id", modelId)));
        row.putAll(payload);
        row.put("model_id", modelId);
        row.putIfAbsent("display_name", modelId);
        row.putIfAbsent("provider_id", "openai-compatible");
        row.putIfAbsent("model_name", modelId);
        row.putIfAbsent("model_kind", "chat");
        row.putIfAbsent("provider_call_type", "generate");
        row.put("kind", string(row, "model_kind", string(row, "kind", "chat")));
        row.putIfAbsent("status", "active");
        modelRows.put(modelId, row);
        modelRegistry.upsert(toModelSpec(row));
        audit("model.saved", modelId, row);
        return row;
    }

    public void deleteProvider(String providerId) {
        providers.remove(providerId);
        providerRegistry.delete(providerId);
        audit("model.provider.deleted", providerId, row("provider_id", providerId));
    }

    public void deleteModel(String modelId) {
        modelRows.remove(modelId);
        modelRegistry.delete(modelId);
        slotBindings.values().removeIf(row -> modelId.equals(String.valueOf(row.get("model_id"))));
        audit("model.deleted", modelId, row("model_id", modelId));
    }

    public Map<String, Object> newSession(Map<String, Object> payload, String orgId) {
        return workspaceSessionStore.create(payload, orgId);
    }

    public Map<String, Object> createRun(String agentId, String query, String userId) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Map<String, Object> output =
                row(
                        "result",
                        row(
                                "answer",
                                "兼容层已收到请求: " + query,
                                "text",
                                "兼容层已收到请求: " + query,
                                "route",
                                "compatibility",
                                "citations",
                                List.of()));
        Map<String, Object> run =
                row(
                        "run_id",
                        runId,
                        "agent_id",
                        agentId,
                        "status",
                        "succeeded",
                        "user_id",
                        userId,
                        "trace_id",
                        "trace_" + runId,
                        "created_at",
                        now.toString(),
                        "started_at",
                        now.toString(),
                        "finished_at",
                        Instant.now().toString(),
                        "output_ref",
                        output,
                        "spec_key",
                        "compat");
        List<Map<String, Object>> steps =
                List.of(
                        row(
                                "step_id",
                                "receive",
                                "step_type",
                                "receive_input",
                                "status",
                                "succeeded",
                                "duration_ms",
                                1),
                        row(
                                "step_id",
                                "respond",
                                "step_type",
                                "compat_response",
                                "status",
                                "succeeded",
                                "duration_ms",
                                1));
        List<Map<String, Object>> events =
                List.of(
                        event(runId, "run.started", row("stage", "compat")),
                        event(runId, "run.succeeded", output));
        runs.put(runId, run);
        runSteps.put(runId, new ArrayList<>(steps));
        runEvents.put(runId, new ArrayList<>(events));
        return run;
    }

    public Map<String, Object> attach(String sessionId, String filename, String orgId) {
        String id = "att_" + sequence.getAndIncrement();
        Map<String, Object> item =
                row(
                        "attachment_id",
                        id,
                        "id",
                        id,
                        "session_id",
                        sessionId,
                        "filename",
                        filename,
                        "file_name",
                        filename,
                        "org_id",
                        orgId,
                        "status",
                        "ready",
                        "parse_status",
                        "parsed",
                        "created_at",
                        Instant.now().toString());
        attachments.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(item);
        attachmentsById.put(id, item);
        return item;
    }

    public Map<String, Object> document(String filename, String domain, String orgId) {
        String docId = "doc_" + sequence.getAndIncrement();
        Map<String, Object> doc =
                row(
                        "doc_id",
                        docId,
                        "id",
                        docId,
                        "version_id",
                        "v1",
                        "filename",
                        filename,
                        "title",
                        filename,
                        "domain",
                        domain,
                        "org_id",
                        orgId,
                        "doc_type",
                        "file",
                        "status",
                        "parsed",
                        "parse_status",
                        "parsed",
                        "block_count",
                        1,
                        "raw_available",
                        false,
                        "preview_available",
                        false,
                        "created_at",
                        Instant.now().toString());
        knowledgeDocs.put(docId, doc);
        return doc;
    }

    public Map<String, Object> memory(Map<String, Object> payload) {
        String id = String.valueOf(sequence.getAndIncrement());
        Map<String, Object> item = new LinkedHashMap<>(payload);
        item.put("id", Long.parseLong(id));
        item.putIfAbsent("scope", "user");
        item.putIfAbsent("memory_type", "preference");
        item.putIfAbsent("status", "active");
        item.putIfAbsent("confidence", 1);
        item.putIfAbsent("created_at", Instant.now().toString());
        item.put("updated_at", Instant.now().toString());
        memories.put(id, item);
        return item;
    }

    public List<Map<String, Object>> sessions(String domain) {
        return sessions(domain, "platform_knowledge_agent");
    }

    public List<Map<String, Object>> sessions(String domain, String agentId) {
        return workspaceSessionStore.list(agentId, "platform").stream()
                .filter(
                        row ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(row.get("domain")))
                .toList();
    }

    public Map<String, Object> session(String id) {
        return session(id, "platform_knowledge_agent");
    }

    public Map<String, Object> session(String id, String agentId) {
        return workspaceSessionStore.get(agentId, id, "platform");
    }

    public void appendSessionMessage(
            String agentId, String sessionId, String userId, String role, String content) {
        workspaceSessionStore.appendMessage(agentId, sessionId, userId, role, content);
    }

    public void deleteSession(String sessionId) {
        deleteSession(sessionId, "platform_knowledge_agent");
    }

    public void deleteSession(String sessionId, String agentId) {
        workspaceSessionStore.delete(agentId, sessionId);
        attachments.remove(sessionId);
    }

    public List<Map<String, Object>> attachments(String sessionId) {
        return attachments.getOrDefault(sessionId, List.of());
    }

    public Map<String, Object> attachment(String id) {
        return attachmentsById.getOrDefault(id, Map.of("attachment_id", id, "status", "ready"));
    }

    public List<Map<String, Object>> runs(String agentId, String status, int limit) {
        return runs.values().stream()
                .filter(
                        row ->
                                agentId == null
                                        || agentId.isBlank()
                                        || agentId.equals(row.get("agent_id")))
                .filter(
                        row ->
                                status == null
                                        || status.isBlank()
                                        || status.equals(row.get("status")))
                .sorted(
                        Comparator.comparing(
                                        (Map<String, Object> row) ->
                                                String.valueOf(row.get("created_at")))
                                .reversed())
                .limit(limit)
                .toList();
    }

    public Map<String, Object> run(String runId) {
        return runs.getOrDefault(runId, Map.of("run_id", runId, "status", "unknown"));
    }

    public List<Map<String, Object>> runSteps(String runId) {
        return runSteps.getOrDefault(runId, List.of());
    }

    public List<Map<String, Object>> runEvents(String runId) {
        return runEvents.getOrDefault(runId, List.of());
    }

    public Map<String, Object> waiting(String runId) {
        return waitings.get(runId);
    }

    public List<Map<String, Object>> providers() {
        return sorted(providers.values(), "provider_id");
    }

    public Map<String, Object> provider(String providerId) {
        return providers.getOrDefault(providerId, Map.of());
    }

    public List<Map<String, Object>> modelRows() {
        return sorted(modelRows.values(), "model_id");
    }

    public Map<String, Object> modelRow(String modelId) {
        return modelRows.getOrDefault(modelId, Map.of());
    }

    public List<Map<String, Object>> slots() {
        return List.of(
                row(
                        "slot_key",
                        "qa",
                        "display_name",
                        "问答模型",
                        "model_kind",
                        "chat",
                        "provider_call_type",
                        "generate",
                        "required_capabilities",
                        List.of(),
                        "is_custom",
                        false),
                row(
                        "slot_key",
                        "embedding",
                        "display_name",
                        "向量模型",
                        "model_kind",
                        "embedding",
                        "provider_call_type",
                        "embed",
                        "required_capabilities",
                        List.of(),
                        "is_custom",
                        false));
    }

    public Map<String, Object> bindSlot(String slotKey, Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("slot_key", slotKey);
        row.putIfAbsent("scope", "platform");
        row.putIfAbsent("org_id", "");
        slotBindings.put(slotKey, row);
        audit("model.slot.bound", slotKey, row);
        return row;
    }

    public List<Map<String, Object>> slotBindings() {
        return sorted(slotBindings.values(), "slot_key");
    }

    public Map<String, Object> alias(Map<String, Object> payload) {
        String id = String.valueOf(sequence.getAndIncrement());
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("id", id);
        aliases.put(id, row);
        audit("model.alias.saved", id, row);
        return row;
    }

    public List<Map<String, Object>> aliases() {
        return sorted(aliases.values(), "alias_name");
    }

    public List<Map<String, Object>> docs(String domain) {
        return knowledgeDocs.values().stream()
                .filter(
                        row ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(row.get("domain")))
                .toList();
    }

    public List<Map<String, Object>> collections(String domain) {
        return collections.values().stream()
                .filter(
                        row ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(row.get("domain")))
                .toList();
    }

    public Map<String, Object> collection(Map<String, Object> payload, String orgId) {
        String id = "col_" + sequence.getAndIncrement();
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("collection_id", id);
        row.put("org_id", orgId);
        row.putIfAbsent("items", new ArrayList<>());
        row.putIfAbsent("item_count", 0);
        collections.put(id, row);
        return row;
    }

    public List<Map<String, Object>> memories(String domain, String status) {
        return memories.values().stream()
                .filter(
                        row ->
                                domain == null
                                        || domain.isBlank()
                                        || domain.equals(row.get("domain")))
                .filter(
                        row ->
                                status == null
                                        || status.isBlank()
                                        || status.equals(row.get("status")))
                .toList();
    }

    public Map<String, Object> updateMemory(String id, Map<String, Object> payload) {
        Map<String, Object> item =
                new LinkedHashMap<>(memories.getOrDefault(id, Map.of("id", Long.parseLong(id))));
        item.putAll(payload);
        item.put("updated_at", Instant.now().toString());
        memories.put(id, item);
        return item;
    }

    public List<Map<String, Object>> audit() {
        return List.copyOf(audit);
    }

    public Map<String, Object> probe(Map<String, Object> payload) {
        String endpoint = string(payload, "endpoint", "");
        return row(
                "ok",
                true,
                "stage",
                "compat",
                "server_name",
                endpoint.isBlank() ? "compat-mcp" : endpoint,
                "server_version",
                "compat",
                "tool_count",
                2,
                "tools",
                List.of("echo", "health"));
    }

    public List<Map<String, Object>> mcpTools(Map<String, Object> server) {
        String serverId = String.valueOf(server.getOrDefault("id", "0"));
        String runtimeName = String.valueOf(server.getOrDefault("name", "mcp"));
        return List.of(
                row(
                        "tool_id",
                        "mcp:" + serverId + ":echo",
                        "tool_name",
                        "echo",
                        "name",
                        "echo",
                        "display_name",
                        runtimeName + " / echo",
                        "description",
                        "兼容层 MCP echo 工具",
                        "runtime_name",
                        runtimeName,
                        "source_type",
                        "mcp",
                        "category",
                        "mcp",
                        "domain",
                        "platform",
                        "binding_status",
                        "enabled",
                        "binding_visibility",
                        "discoverable",
                        "parameter_schema",
                        Map.of("type", "object", "properties", Map.of())));
    }

    public Map<String, Object> bindMcp(Map<String, Object> payload) {
        String id = String.valueOf(sequence.getAndIncrement());
        String serverId = String.valueOf(payload.getOrDefault("mcp_server_id", ""));
        Map<String, Object> row = new LinkedHashMap<>(payload);
        row.put("id", id);
        row.putIfAbsent("enabled", true);
        mcpBindings.computeIfAbsent(serverId, ignored -> new ArrayList<>()).add(row);
        return row;
    }

    public List<Map<String, Object>> mcpBindings(String serverId) {
        return mcpBindings.getOrDefault(serverId, List.of());
    }

    public void deleteMcpBinding(String id) {
        for (List<Map<String, Object>> list : mcpBindings.values()) {
            list.removeIf(row -> id.equals(String.valueOf(row.get("id"))));
        }
    }

    private Map<String, Object> agentRow(AgentDefinition definition) {
        return row(
                "agent_id",
                definition.agentId(),
                "display_name",
                definition.name(),
                "name",
                definition.name(),
                "description",
                definition.systemPrompt(),
                "domain",
                "platform",
                "source",
                "builtin",
                "enabled",
                true,
                "model",
                definition.model(),
                "included_tools",
                definition.toolRefs(),
                "included_skills",
                definition.skillRefs(),
                "flow_bindings",
                Map.of("default", "agentscope_runtime"));
    }

    private Map<String, Object> providerRow(ModelProviderSpec spec) {
        return row(
                "provider_id",
                spec.providerId(),
                "display_name",
                spec.displayName(),
                "provider_type",
                spec.providerType(),
                "default_base_url",
                spec.defaultBaseUrl(),
                "endpoint_path",
                spec.endpointPath(),
                "secret_ref",
                spec.secretRef(),
                "timeout_ms",
                spec.timeoutMs(),
                "description",
                spec.description(),
                "status",
                spec.status());
    }

    private Map<String, Object> event(String runId, String eventType, Map<String, Object> payload) {
        return row(
                "event_id",
                sequence.getAndIncrement(),
                "run_id",
                runId,
                "event_type",
                eventType,
                "type",
                eventType,
                "payload",
                payload,
                "created_at",
                Instant.now().toString());
    }

    private void seedModels() {
        for (ModelProviderSpec spec : providerRegistry.all()) {
            providers.put(spec.providerId(), providerRow(spec));
        }
        for (ModelSpec spec : modelRegistry.all()) {
            providers.putIfAbsent(
                    spec.provider(),
                    row(
                            "provider_id",
                            spec.provider(),
                            "display_name",
                            spec.provider(),
                            "provider_type",
                            spec.provider(),
                            "default_base_url",
                            spec.baseUrl(),
                            "endpoint_path",
                            spec.endpointPath(),
                            "status",
                            "active"));
            modelRows.put(
                    spec.modelId(),
                    row(
                            "model_id",
                            spec.modelId(),
                            "display_name",
                            spec.modelId(),
                            "provider_id",
                            spec.provider(),
                            "model_name",
                            spec.model().isBlank() ? spec.modelId() : spec.model(),
                            "base_url",
                            spec.baseUrl(),
                            "model_kind",
                            spec.kind(),
                            "provider_call_type",
                            "embedding".equals(spec.kind()) ? "embed" : "generate",
                            "kind",
                            spec.kind(),
                            "capabilities",
                            List.of(),
                            "dimensions",
                            spec.dimensions(),
                            "status",
                            spec.enabled() ? "active" : "disabled",
                            "description",
                            spec.description()));
        }
        if (modelRows.isEmpty()) {
            modelRows.put(
                    "mock",
                    row(
                            "model_id",
                            "mock",
                            "display_name",
                            "Mock",
                            "provider_id",
                            "openai-compatible",
                            "model_name",
                            "mock",
                            "model_kind",
                            "chat",
                            "provider_call_type",
                            "generate",
                            "status",
                            "active"));
        }
        slotBindings.put(
                "qa",
                row(
                        "slot_key",
                        "qa",
                        "scope",
                        "platform",
                        "org_id",
                        "",
                        "model_id",
                        modelRows.keySet().iterator().next()));
    }

    private void seedMcps() {
        for (McpSpec spec : mcpRegistry.all()) {
            mcpServers.put(
                    spec.mcpId(),
                    row(
                            "id",
                            spec.mcpId(),
                            "name",
                            spec.mcpId(),
                            "endpoint",
                            spec.url() == null ? spec.command() : spec.url(),
                            "description",
                            "AgentScope MCP",
                            "timeout_ms",
                            spec.timeout() == null ? 5000 : spec.timeout().toMillis(),
                            "tool_filter",
                            spec.enableTools(),
                            "enabled",
                            spec.enabled(),
                            "metadata",
                            row("health_status", "unknown", "last_tool_count", 0)));
        }
    }

    private static List<Map<String, Object>> sorted(
            Iterable<Map<String, Object>> rows, String key) {
        List<Map<String, Object>> list = new ArrayList<>();
        rows.forEach(list::add);
        list.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault(key, ""))));
        return list;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    private static String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String sourceDomain(String source) {
        return source == null || source.isBlank() ? "platform" : source;
    }

    @SuppressWarnings("unchecked")
    private ModelSpec toModelSpec(Map<String, Object> row) {
        Map<String, Object> provider =
                providers.getOrDefault(string(row, "provider_id", ""), Map.of());
        String secretRef = firstText(row.get("secret_ref"), provider.get("secret_ref"));
        String apiKey = "";
        String apiKeyEnv = "";
        if (secretRef.startsWith("env:")) {
            apiKeyEnv = secretRef.substring(4);
        } else if (secretRef.matches("[A-Z][A-Z0-9_]*")) {
            apiKeyEnv = secretRef;
        } else {
            apiKey = secretRef;
        }
        return new ModelSpec(
                string(row, "model_id", ""),
                string(row, "model_kind", string(row, "kind", "chat")),
                "provider",
                firstText(row.get("provider_id"), provider.get("provider_type")),
                string(row, "model_name", string(row, "model_id", "")),
                "",
                "",
                apiKey,
                apiKeyEnv,
                firstText(row.get("base_url"), provider.get("default_base_url")),
                firstText(provider.get("endpoint_path"), row.get("endpoint_path")),
                "",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                (Map<String, String>) row.getOrDefault("extra_headers", Map.of()),
                (Map<String, Object>) row.getOrDefault("extra_body", Map.of()),
                Map.of(),
                row.get("timeout_ms") instanceof Number n ? n.longValue() : null,
                null,
                null,
                null,
                null,
                "",
                "",
                null,
                "",
                "",
                row.get("dimensions") instanceof Number d ? d.intValue() : null,
                string(row, "description", ""),
                "active".equals(string(row, "status", "active")));
    }

    private ModelProviderSpec toProviderSpec(Map<String, Object> row) {
        return new ModelProviderSpec(
                string(row, "provider_id", ""),
                string(row, "display_name", string(row, "provider_id", "")),
                string(row, "provider_type", "openai-compatible"),
                string(row, "default_base_url", ""),
                string(row, "endpoint_path", ""),
                string(row, "secret_ref", ""),
                row.get("timeout_ms") instanceof Number n ? n.longValue() : null,
                string(row, "description", ""),
                string(row, "status", "active"));
    }

    private static String firstText(Object first, Object second) {
        String value = first == null ? "" : String.valueOf(first).trim();
        if (!value.isBlank()) {
            return value;
        }
        return second == null ? "" : String.valueOf(second).trim();
    }

    private void audit(String event, String targetId, Map<String, Object> payload) {
        audit.add(
                row(
                        "id",
                        sequence.getAndIncrement(),
                        "event_type",
                        event,
                        "target_id",
                        targetId,
                        "payload",
                        payload,
                        "actor",
                        "compat",
                        "created_at",
                        Instant.now().toString()));
    }
}
