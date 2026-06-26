/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.adapter.agentscope;

import com.company.platform.control.AgentDefinition;
import com.company.platform.control.McpRegistry;
import com.company.platform.control.McpSpec;
import com.company.platform.control.SkillRegistry;
import com.company.platform.control.SkillSpec;
import com.company.platform.control.ToolRegistry;
import com.company.platform.control.ToolSpec;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AgentCapabilityAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentCapabilityAssembler.class);

    private final ToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final SkillRegistry skillRegistry;
    private final Environment environment;
    private final Path workspaceRoot;

    public AgentCapabilityAssembler(
            ToolRegistry toolRegistry,
            McpRegistry mcpRegistry,
            SkillRegistry skillRegistry,
            Environment environment,
            @Value("${company.platform.workspace}") Path workspaceRoot) {
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.environment = environment;
        this.workspaceRoot = workspaceRoot;
    }

    public void applyToolsAndMcps(Toolkit toolkit, AgentDefinition definition) {
        applyTools(toolkit, definition.toolRefs());
        applyMcps(toolkit, definition.mcpRefs());
    }

    public List<AgentSkillRepository> buildSkillRepositories(AgentDefinition definition) {
        List<AgentSkillRepository> repos = new ArrayList<>();
        for (String skillRef : definition.skillRefs()) {
            SkillSpec spec =
                    skillRegistry
                            .find(skillRef)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Unknown skill ref "
                                                            + skillRef
                                                            + " in agent "
                                                            + definition.agentId()));
            if (!spec.enabled()) {
                continue;
            }
            repos.add(skillRepo(definition, spec));
        }
        return repos;
    }

    private void applyTools(Toolkit toolkit, List<String> toolRefs) {
        Set<String> seen = new HashSet<>();
        for (String toolRef : safeRefs(toolRefs)) {
            ToolSpec spec =
                    toolRegistry
                            .find(toolRef)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Unknown tool ref "
                                                            + toolRef
                                                            + " in agent toolkit config"));
            if (!spec.enabled()) {
                continue;
            }
            if (!"java".equalsIgnoreCase(spec.type())) {
                throw new IllegalStateException(
                        "Unsupported tool type '" + spec.type() + "' for tool " + spec.toolId());
            }
            if (spec.className() == null || spec.className().isBlank()) {
                throw new IllegalStateException("Missing className for tool spec " + spec.toolId());
            }
            if (!seen.add(spec.toolId())) {
                log.warn("Duplicate toolRef {} ignored", spec.toolId());
                continue;
            }
            try {
                Object tool =
                        Class.forName(spec.className()).getDeclaredConstructor().newInstance();
                toolkit.registration().tool(tool).apply();
                log.info("Registered Java tool {} from {}", spec.toolId(), spec.className());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to instantiate tool "
                                + spec.toolId()
                                + " ("
                                + spec.className()
                                + ")",
                        e);
            }
        }
    }

    private void applyMcps(Toolkit toolkit, List<String> mcpRefs) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        for (String mcpRef : safeRefs(mcpRefs)) {
            McpSpec spec =
                    mcpRegistry
                            .find(mcpRef)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Unknown mcp ref "
                                                            + mcpRef
                                                            + " in agent refs"));
            if (!spec.enabled()) {
                continue;
            }
            McpServerConfig cfg = toConfig(spec);
            configs.put(spec.mcpId(), cfg);
            log.info("Prepared MCP server {} ({})", spec.mcpId(), spec.transport());
        }
        if (!configs.isEmpty()) {
            McpServerRegistrar.register(toolkit, configs);
        }
    }

    private McpServerConfig toConfig(McpSpec spec) {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setTransport(resolve(spec.transport()));
        cfg.setCommand(resolve(spec.command()));
        cfg.setArgs(spec.args());
        cfg.setEnv(spec.env());
        cfg.setUrl(resolve(spec.url()));
        cfg.setHeaders(spec.headers());
        cfg.setQueryParams(spec.queryParams());
        cfg.setEnableTools(spec.enableTools());
        cfg.setTimeout(spec.timeout());
        cfg.setInitializationTimeout(spec.initializationTimeout());
        return cfg;
    }

    private AgentSkillRepository skillRepo(AgentDefinition definition, SkillSpec spec) {
        return switch (spec.type()) {
            case "classpath" -> createClasspathRepo(spec);
            case "filesystem", "local", "agent", "platform" ->
                    createFilesystemRepo(definition, spec);
            default ->
                    throw new IllegalStateException(
                            "Unsupported skill repository type '"
                                    + spec.type()
                                    + "' for "
                                    + spec.skillId());
        };
    }

    private AgentSkillRepository createClasspathRepo(SkillSpec spec) {
        try {
            return new ClasspathSkillRepository(spec.location());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to create classpath skill repo for " + spec.skillId(), e);
        }
    }

    private AgentSkillRepository createFilesystemRepo(AgentDefinition definition, SkillSpec spec) {
        Path base = resolveSkillLocation(definition, spec);
        try {
            Files.createDirectories(base);
            return new FileSystemSkillRepository(base, spec.writable(), spec.source());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to create filesystem skill repo for " + spec.skillId() + " at " + base,
                    e);
        }
    }

    private Path resolveSkillLocation(AgentDefinition definition, SkillSpec spec) {
        String resolved = resolve(spec.location());
        Path path = Path.of(resolved);
        if (path.isAbsolute()) {
            return path;
        }
        Path base;
        if ("platform".equals(spec.scope()) || "global".equals(spec.scope())) {
            base = workspaceRoot;
        } else {
            base = definition.workspace();
        }
        return base.resolve(path).normalize();
    }

    private List<String> safeRefs(List<String> refs) {
        return refs == null
                ? List.of()
                : refs.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    private String resolve(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            return environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
