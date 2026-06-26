/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class YamlAgentDefinitionRegistry implements AgentDefinitionRegistry {

    private final Map<String, AgentDefinition> definitions = new ConcurrentHashMap<>();
    private final PlatformConfigStore configStore;
    private final Path workspaceRoot;
    private final Environment environment;

    public YamlAgentDefinitionRegistry(
            PlatformConfigStore configStore,
            @Value("${company.platform.workspace}") Path workspaceRoot,
            Environment environment) {
        this.configStore = configStore;
        this.workspaceRoot = workspaceRoot;
        this.environment = environment;
    }

    @PostConstruct
    public void load() throws IOException {
        AgentsConfig config =
                configStore.read(PlatformConfigStore.ConfigFile.AGENTS, AgentsConfig.class);
        Map<String, AgentDefinition> loaded = new LinkedHashMap<>();
        for (AgentConfig agent : config.agents()) {
            AgentDefinition definition = toDefinition(agent);
            if (loaded.containsKey(definition.agentId())) {
                throw new IllegalStateException(
                        "Duplicate agentId in config: " + definition.agentId());
            }
            loaded.put(definition.agentId(), definition);
        }
        validate(loaded);
        definitions.clear();
        definitions.putAll(loaded);
    }

    @Override
    public List<AgentDefinition> allPublished() {
        return definitions.values().stream().toList();
    }

    @Override
    public Optional<AgentDefinition> findPublished(String agentId) {
        return Optional.ofNullable(definitions.get(agentId));
    }

    private AgentDefinition toDefinition(AgentConfig agent) {
        return new AgentDefinition(
                agent.agentId(),
                safe(agent.version(), "v1"),
                safe(resolve(agent.name()), agent.agentId()),
                safe(resolve(agent.model()), "mock"),
                safe(resolve(agent.systemPrompt()), "You are a helpful assistant."),
                workspace(agent),
                agent.toolRefs(),
                agent.mcpRefs(),
                agent.skillRefs(),
                resolveOrchestration(agent.orchestration()));
    }

    private Path workspace(AgentConfig agent) {
        String resolved = resolve(agent.workspace());
        if (resolved == null || resolved.isBlank()) {
            return workspaceRoot.resolve("workspace").resolve(agent.agentId());
        }
        Path path = Path.of(resolved);
        return path.isAbsolute() ? path : workspaceRoot.resolve(path);
    }

    private OrchestrationPolicy resolveOrchestration(OrchestrationPolicy policy) {
        if (policy == null) {
            return OrchestrationPolicy.single();
        }
        List<SubagentBinding> subagents =
                policy.subagents().stream()
                        .map(
                                s ->
                                        new SubagentBinding(
                                                resolve(s.bindingId()),
                                                resolve(s.targetAgentId()),
                                                resolve(s.role()),
                                                resolve(s.description()),
                                                s.exposeToUser(),
                                                s.toolRefs()))
                        .toList();
        List<RouteRule> routes =
                policy.routes().stream()
                        .map(
                                r ->
                                        new RouteRule(
                                                resolve(r.ruleId()),
                                                resolve(r.targetAgentId()),
                                                resolve(r.contains())))
                        .toList();
        List<WorkflowStep> workflow =
                policy.workflow().stream()
                        .map(
                                s ->
                                        new WorkflowStep(
                                                resolve(s.stepId()),
                                                resolve(s.agentId()),
                                                resolve(s.instruction())))
                        .toList();
        return new OrchestrationPolicy(policy.mode(), subagents, routes, workflow);
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolve(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            return environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Failed to resolve config placeholder: " + value, e);
        }
    }

    private void validate(Map<String, AgentDefinition> loaded) {
        for (Map.Entry<String, AgentDefinition> entry : loaded.entrySet()) {
            AgentDefinition definition = entry.getValue();
            if (definition.agentId() == null || definition.agentId().isBlank()) {
                throw new IllegalStateException("agentId cannot be blank");
            }
            OrchestrationPolicy orchestration = definition.orchestration();
            if (orchestration == null) {
                continue;
            }
            switch (orchestration.mode()) {
                case ROUTER -> validateRouter(definition, loaded);
                case WORKFLOW -> validateWorkflow(definition, loaded);
                case SUPERVISOR, SINGLE -> validateSupervisor(definition, loaded);
                default ->
                        throw new IllegalStateException(
                                "Unsupported orchestration mode: " + orchestration.mode());
            }
        }
    }

    private void validateRouter(AgentDefinition definition, Map<String, AgentDefinition> loaded) {
        if (definition.orchestration().routes().isEmpty()) {
            throw new IllegalStateException(
                    "ROUTER agent requires at least one route: " + definition.agentId());
        }
        for (RouteRule route : definition.orchestration().routes()) {
            if (route.targetAgentId() == null || route.targetAgentId().isBlank()) {
                throw new IllegalStateException(
                        "Router route targetAgentId is blank for " + definition.agentId());
            }
            if (!loaded.containsKey(route.targetAgentId())) {
                throw new IllegalStateException(
                        "Router route target not found for "
                                + definition.agentId()
                                + ": "
                                + route.targetAgentId());
            }
        }
    }

    private void validateWorkflow(AgentDefinition definition, Map<String, AgentDefinition> loaded) {
        if (definition.orchestration().workflow().isEmpty()) {
            throw new IllegalStateException(
                    "WORKFLOW agent requires at least one workflow step: " + definition.agentId());
        }
        Set<String> stepIds = new LinkedHashSet<>();
        for (WorkflowStep step : definition.orchestration().workflow()) {
            if (step.stepId() == null || step.stepId().isBlank()) {
                throw new IllegalStateException(
                        "Workflow stepId is blank for agent " + definition.agentId());
            }
            if (!stepIds.add(step.stepId())) {
                throw new IllegalStateException(
                        "Duplicate workflow stepId "
                                + step.stepId()
                                + " in agent "
                                + definition.agentId());
            }
            if (step.agentId() == null || step.agentId().isBlank()) {
                throw new IllegalStateException(
                        "Workflow agentId is blank in step "
                                + step.stepId()
                                + " for agent "
                                + definition.agentId());
            }
            if (!loaded.containsKey(step.agentId())) {
                throw new IllegalStateException(
                        "Workflow step target not found for agent "
                                + definition.agentId()
                                + ": "
                                + step.agentId());
            }
        }
    }

    private void validateSupervisor(
            AgentDefinition definition, Map<String, AgentDefinition> loaded) {
        for (SubagentBinding binding : definition.orchestration().subagents()) {
            if (binding.bindingId() == null || binding.bindingId().isBlank()) {
                throw new IllegalStateException(
                        "Subagent binding id is blank for agent " + definition.agentId());
            }
            if (binding.targetAgentId() == null || binding.targetAgentId().isBlank()) {
                throw new IllegalStateException(
                        "Subagent targetAgentId is blank for "
                                + definition.agentId()
                                + " binding "
                                + binding.bindingId());
            }
            if (!loaded.containsKey(binding.targetAgentId())) {
                throw new IllegalStateException(
                        "Subagent target not found for "
                                + definition.agentId()
                                + " binding "
                                + binding.bindingId()
                                + ": "
                                + binding.targetAgentId());
            }
        }
    }

    public record AgentsConfig(List<AgentConfig> agents) {
        public AgentsConfig {
            agents = agents == null ? List.of() : List.copyOf(agents);
        }
    }

    public record AgentConfig(
            String agentId,
            String version,
            String name,
            String model,
            String systemPrompt,
            String workspace,
            List<String> toolRefs,
            List<String> mcpRefs,
            List<String> skillRefs,
            OrchestrationPolicy orchestration) {

        public AgentConfig {
            toolRefs = toolRefs == null ? List.of() : List.copyOf(toolRefs);
            mcpRefs = mcpRefs == null ? List.of() : List.copyOf(mcpRefs);
            skillRefs = skillRefs == null ? List.of() : List.copyOf(skillRefs);
            orchestration = orchestration == null ? OrchestrationPolicy.single() : orchestration;
        }
    }
}
