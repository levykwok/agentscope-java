/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

/** Definition of a Java tool that can be bound into an agent toolkit. */
public record ToolSpec(
        String toolId, String type, String className, String description, boolean enabled) {

    public ToolSpec {
        toolId = toolId == null || toolId.isBlank() ? className : toolId;
        type = type == null || type.isBlank() ? "java" : type.strip().toLowerCase();
        className = className == null ? "" : className.strip();
    }
}
