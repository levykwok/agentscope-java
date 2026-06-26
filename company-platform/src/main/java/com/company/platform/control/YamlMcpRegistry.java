/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class YamlMcpRegistry implements McpRegistry {

    private final Map<String, McpSpec> servers = new ConcurrentHashMap<>();
    private final PlatformConfigStore configStore;

    public YamlMcpRegistry(PlatformConfigStore configStore) {
        this.configStore = configStore;
    }

    @PostConstruct
    public void load() throws IOException {
        McpConfigRoot config =
                configStore.read(PlatformConfigStore.ConfigFile.MCPS, McpConfigRoot.class);
        Map<String, McpSpec> loaded = new LinkedHashMap<>();
        for (McpConfig server : config.mcps()) {
            McpSpec spec = toSpec(server);
            if (spec.mcpId() == null || spec.mcpId().isBlank()) {
                throw new IllegalStateException("MCP id cannot be blank");
            }
            validate(spec);
            if (loaded.containsKey(spec.mcpId())) {
                throw new IllegalStateException("Duplicate mcpId in config: " + spec.mcpId());
            }
            loaded.put(spec.mcpId(), spec);
        }
        servers.clear();
        servers.putAll(loaded);
    }

    @Override
    public List<McpSpec> all() {
        return servers.values().stream().toList();
    }

    @Override
    public Optional<McpSpec> find(String mcpId) {
        return Optional.ofNullable(servers.get(mcpId));
    }

    @Override
    public void upsert(McpSpec spec) {
        McpSpec normalized = normalize(spec);
        validate(normalized);
        servers.put(normalized.mcpId(), normalized);
        persist();
    }

    private McpSpec toSpec(McpConfig cfg) {
        return new McpSpec(
                cfg.mcpId(),
                cfg.transport(),
                cfg.command(),
                cfg.args(),
                cfg.env(),
                cfg.url(),
                cfg.headers(),
                cfg.queryParams(),
                cfg.enableTools(),
                cfg.timeout(),
                cfg.initializationTimeout(),
                cfg.enabled());
    }

    private McpSpec normalize(McpSpec spec) {
        return new McpSpec(
                spec.mcpId(),
                spec.transport(),
                spec.command(),
                spec.args(),
                spec.env(),
                spec.url(),
                spec.headers(),
                spec.queryParams(),
                spec.enableTools(),
                spec.timeout(),
                spec.initializationTimeout(),
                spec.enabled());
    }

    private McpConfig toConfig(McpSpec spec) {
        return new McpConfig(
                spec.mcpId(),
                spec.transport(),
                spec.command(),
                spec.args(),
                spec.env(),
                spec.url(),
                spec.headers(),
                spec.queryParams(),
                spec.enableTools(),
                spec.timeout(),
                spec.initializationTimeout(),
                spec.enabled());
    }

    private void validate(McpSpec spec) {
        if (spec.mcpId() == null || spec.mcpId().isBlank()) {
            throw new IllegalStateException("MCP id cannot be blank");
        }
        if (!"stdio".equals(spec.transport())
                && !"sse".equals(spec.transport())
                && !"http".equals(spec.transport())
                && !"streamable-http".equals(spec.transport())) {
            throw new IllegalStateException(
                    "Unsupported MCP transport for " + spec.mcpId() + ": " + spec.transport());
        }
        if (!"stdio".equals(spec.transport()) && (spec.url() == null || spec.url().isBlank())) {
            throw new IllegalStateException(
                    "MCP " + spec.mcpId() + " requires url for transport " + spec.transport());
        }
        if ("stdio".equals(spec.transport())
                && (spec.command() == null || spec.command().isBlank())) {
            throw new IllegalStateException(
                    "MCP " + spec.mcpId() + " requires command for stdio transport.");
        }
    }

    private void persist() {
        List<McpConfig> list =
                servers.values().stream()
                        .sorted((a, b) -> a.mcpId().compareToIgnoreCase(b.mcpId()))
                        .map(this::toConfig)
                        .collect(Collectors.toList());
        configStore.write(PlatformConfigStore.ConfigFile.MCPS, new McpConfigRoot(list));
    }

    public record McpConfigRoot(List<McpConfig> mcps) {
        public McpConfigRoot {
            mcps = mcps == null ? List.of() : List.copyOf(mcps);
        }
    }

    public record McpConfig(
            String mcpId,
            String transport,
            String command,
            List<String> args,
            java.util.Map<String, String> env,
            String url,
            java.util.Map<String, String> headers,
            java.util.Map<String, String> queryParams,
            List<String> enableTools,
            java.time.Duration timeout,
            java.time.Duration initializationTimeout,
            boolean enabled) {}
}
