/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.adapter.agentscope;

import com.company.platform.control.AgentDefinition;
import com.company.platform.control.AgentDefinitionRegistry;
import com.company.platform.control.SubagentBinding;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeHarnessFactory {

    private final AgentDefinitionRegistry registry;
    private final AgentCapabilityAssembler capabilityAssembler;

    public AgentScopeHarnessFactory(
            AgentDefinitionRegistry registry, AgentCapabilityAssembler capabilityAssembler) {
        this.registry = registry;
        this.capabilityAssembler = capabilityAssembler;
    }

    public HarnessAgent create(AgentDefinition definition) {
        ensureWorkspace(definition);
        Toolkit toolkit = new Toolkit();
        capabilityAssembler.applyToolsAndMcps(toolkit, definition);
        List<AgentSkillRepository> skillRepositories =
                capabilityAssembler.buildSkillRepositories(definition);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(definition.name())
                        .sysPrompt(definition.systemPrompt())
                        .model(definition.model())
                        .workspace(definition.workspace())
                        .toolkit(toolkit)
                        .skillRepositories(skillRepositories);
        for (SubagentBinding binding : definition.orchestration().subagents()) {
            builder.subagent(toSubagentDeclaration(binding));
        }
        return builder.build();
    }

    private SubagentDeclaration toSubagentDeclaration(SubagentBinding binding) {
        AgentDefinition target =
                registry.findPublished(binding.targetAgentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Subagent target not found: "
                                                        + binding.targetAgentId()));
        ensureWorkspace(target);
        return SubagentDeclaration.builder()
                .name(binding.bindingId())
                .description(binding.description())
                .workspace(target.workspace())
                .model(target.model())
                .exposeToUser(binding.exposeToUser())
                .tools(binding.toolRefs())
                .build();
    }

    private void ensureWorkspace(AgentDefinition definition) {
        try {
            Files.createDirectories(definition.workspace());
            Files.createDirectories(definition.workspace().resolve("knowledge"));
            Files.createDirectories(definition.workspace().resolve("skills"));
            Files.createDirectories(definition.workspace().resolve("subagents"));
            var agentsFile = definition.workspace().resolve("AGENTS.md");
            if (Files.notExists(agentsFile)) {
                Files.writeString(
                        agentsFile,
                        "# " + definition.name() + "\n\n" + definition.systemPrompt() + "\n");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to initialize workspace for agent " + definition.agentId(), e);
        }
    }
}
