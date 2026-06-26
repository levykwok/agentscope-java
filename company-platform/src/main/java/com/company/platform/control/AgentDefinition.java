/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

import java.nio.file.Path;
import java.util.List;

/**
 * Published agent definition consumed by the runtime plane.
 */
public record AgentDefinition(
        String agentId,
        String version,
        String name,
        String model,
        String systemPrompt,
        Path workspace,
        List<String> toolRefs,
        List<String> mcpRefs,
        List<String> skillRefs,
        OrchestrationPolicy orchestration) {

    public AgentDefinition {
        toolRefs = toolRefs == null ? List.of() : List.copyOf(toolRefs);
        mcpRefs = mcpRefs == null ? List.of() : List.copyOf(mcpRefs);
        skillRefs = skillRefs == null ? List.of() : List.copyOf(skillRefs);
        orchestration = orchestration == null ? OrchestrationPolicy.single() : orchestration;
    }
}
