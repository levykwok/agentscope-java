/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PlatformWorkspaceSessionStore {

    private static final String DEFAULT_AGENT_ID = "platform_knowledge_agent";

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkspaceManager workspaceManager;

    public PlatformWorkspaceSessionStore(@Value("${company.platform.workspace}") String workspace) {
        this.workspaceManager = new WorkspaceManager(Path.of(workspace));
    }

    public Map<String, Object> create(Map<String, Object> payload, String orgId) {
        String agentId = string(payload.get("agent_id"), DEFAULT_AGENT_ID);
        String sessionId =
                string(
                        payload.get("session_id"),
                        "sess_" + UUID.randomUUID().toString().replace("-", ""));
        String title = string(payload.get("title"), "新对话");
        RuntimeContext rc =
                runtimeContext(sessionId, string(payload.get("user_id"), "platform_admin"));
        workspaceManager.updateSessionIndex(rc, agentId, sessionId, title);
        return sessionRow(agentId, sessionId, title, Instant.now().toString(), orgId);
    }

    public List<Map<String, Object>> list(String agentId, String orgId) {
        String resolvedAgentId = string(agentId, DEFAULT_AGENT_ID);
        String rel = sessionStorePath(resolvedAgentId);
        String json = workspaceManager.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), rel);
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            JsonNode sessions = mapper.readTree(json).path("sessions");
            sessions.fields()
                    .forEachRemaining(
                            entry -> {
                                JsonNode item = entry.getValue();
                                rows.add(
                                        sessionRow(
                                                resolvedAgentId,
                                                entry.getKey(),
                                                item.path("summary").asText("新对话"),
                                                item.path("updatedAt").asText(""),
                                                orgId));
                            });
        } catch (Exception ignored) {
            return List.of();
        }
        rows.sort(
                Comparator.comparing(
                                (Map<String, Object> row) ->
                                        String.valueOf(row.getOrDefault("updated_at", "")))
                        .reversed());
        return rows;
    }

    public Map<String, Object> get(String agentId, String sessionId, String orgId) {
        String resolvedAgentId = string(agentId, DEFAULT_AGENT_ID);
        Map<String, Object> session =
                list(resolvedAgentId, orgId).stream()
                        .filter(row -> sessionId.equals(String.valueOf(row.get("session_id"))))
                        .findFirst()
                        .orElseGet(() -> sessionRow(resolvedAgentId, sessionId, "新对话", "", orgId));
        String contextPath = sessionContextPath(resolvedAgentId, sessionId);
        String logPath = sessionLogPath(resolvedAgentId, sessionId);
        String tasksPath = taskPath(resolvedAgentId, sessionId);
        String contextRaw = readRaw(contextPath);
        String logRaw = readRaw(logPath);
        String tasksRaw = readRaw(tasksPath);
        String memoryRaw = readRaw(WorkspaceConstants.MEMORY_MD);
        return map(
                "session",
                session,
                "messages",
                readMessagesFromLog(logRaw),
                "context_entries",
                parseJsonLines(contextRaw),
                "log_entries",
                parseJsonLines(logRaw),
                "tasks",
                parseJsonObject(tasksRaw),
                "memory",
                map("memory_md", memoryRaw),
                "raw",
                map("context", contextRaw, "log", logRaw, "tasks", tasksRaw),
                "files",
                map(
                        "session_index",
                        sessionStorePath(resolvedAgentId),
                        "context",
                        contextPath,
                        "log",
                        logPath,
                        "tasks",
                        tasksPath,
                        "memory",
                        WorkspaceConstants.MEMORY_MD));
    }

    public void appendMessage(
            String agentId, String sessionId, String userId, String role, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String resolvedAgentId = string(agentId, DEFAULT_AGENT_ID);
        RuntimeContext rc = runtimeContext(sessionId, string(userId, "platform_admin"));
        String text = content == null ? "" : content;
        workspaceManager.updateSessionIndex(
                rc, resolvedAgentId, sessionId, text.isBlank() ? "新对话" : text);
        String line;
        try {
            line =
                    mapper.writeValueAsString(
                                    map(
                                            "role",
                                            role,
                                            "content",
                                            text,
                                            "created_at",
                                            Instant.now().toString()))
                            + "\n";
        } catch (JsonProcessingException e) {
            return;
        }
        workspaceManager.appendUtf8WorkspaceRelative(
                rc, sessionLogPath(resolvedAgentId, sessionId), line);
    }

    public void delete(String agentId, String sessionId) {
        String resolvedAgentId = string(agentId, DEFAULT_AGENT_ID);
        String rel = sessionStorePath(resolvedAgentId);
        String json = workspaceManager.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), rel);
        try {
            JsonNode root = mapper.readTree(json);
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode objectRoot
                    && objectRoot.path("sessions")
                            instanceof com.fasterxml.jackson.databind.node.ObjectNode sessions) {
                sessions.remove(sessionId);
                workspaceManager.writeUtf8WorkspaceRelative(
                        RuntimeContext.empty(),
                        rel,
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(objectRoot));
            }
        } catch (Exception ignored) {
            // best effort
        }
        workspaceManager.writeUtf8WorkspaceRelative(
                runtimeContext(sessionId, "platform_admin"),
                sessionLogPath(resolvedAgentId, sessionId),
                "");
    }

    private List<Map<String, Object>> readMessagesFromLog(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                rows.add(
                        map(
                                "role",
                                node.path("role").asText("assistant"),
                                "content",
                                node.path("content").asText(""),
                                "created_at",
                                node.path("created_at").asText("")));
            } catch (Exception ignored) {
                // ignore malformed lines
            }
        }
        return rows;
    }

    private List<Map<String, Object>> parseJsonLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                rows.add(map("raw", node, "type", node.path("type").asText("entry")));
            } catch (Exception ignored) {
                rows.add(map("raw", line));
            }
        }
        return rows;
    }

    private Object parseJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(text, Map.class);
        } catch (Exception ignored) {
            return text;
        }
    }

    private String readRaw(String relativePath) {
        String text =
                workspaceManager.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), relativePath);
        return text == null ? "" : text;
    }

    private RuntimeContext runtimeContext(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static String sessionStorePath(String agentId) {
        return WorkspaceConstants.AGENTS_DIR
                + "/"
                + agentId
                + "/"
                + WorkspaceConstants.SESSIONS_DIR
                + "/"
                + WorkspaceConstants.SESSIONS_STORE;
    }

    private static String sessionLogPath(String agentId, String sessionId) {
        return WorkspaceConstants.AGENTS_DIR
                + "/"
                + agentId
                + "/"
                + WorkspaceConstants.SESSIONS_DIR
                + "/"
                + sessionId
                + WorkspaceConstants.SESSION_LOG_EXT;
    }

    private static String sessionContextPath(String agentId, String sessionId) {
        return WorkspaceConstants.AGENTS_DIR
                + "/"
                + agentId
                + "/"
                + WorkspaceConstants.SESSIONS_DIR
                + "/"
                + sessionId
                + WorkspaceConstants.SESSION_CONTEXT_EXT;
    }

    private static String taskPath(String agentId, String sessionId) {
        return WorkspaceConstants.AGENTS_DIR
                + "/"
                + agentId
                + "/"
                + WorkspaceConstants.TASKS_DIR
                + "/"
                + sessionId
                + ".json";
    }

    private static Map<String, Object> sessionRow(
            String agentId, String sessionId, String title, String updatedAt, String orgId) {
        String now = Instant.now().toString();
        return map(
                "session_id",
                sessionId,
                "id",
                sessionId,
                "agent_id",
                agentId,
                "title",
                title == null || title.isBlank() ? "新对话" : title,
                "domain",
                "platform",
                "org_id",
                orgId,
                "created_at",
                now,
                "updated_at",
                updatedAt == null || updatedAt.isBlank() ? now : updatedAt);
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
