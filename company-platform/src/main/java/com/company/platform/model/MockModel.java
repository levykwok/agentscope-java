/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * A local mock model used when external model keys are unavailable.
 */
public final class MockModel implements Model {

    private final String modelName;

    public MockModel() {
        this("mock");
    }

    public MockModel(String modelName) {
        this.modelName = modelName == null || modelName.isBlank() ? "mock" : modelName.strip();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String userText = latestUserText(messages);
        String output =
                "Mock model response ("
                        + modelName
                        + "): "
                        + (userText.isBlank() ? "[] empty input" : userText);
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(output).build()))
                        .build());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    private String latestUserText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
                .filter(Objects::nonNull)
                .filter(msg -> msg.getRole() == MsgRole.USER)
                .reduce((first, second) -> second)
                .map(Msg::getTextContent)
                .orElse("");
    }
}
