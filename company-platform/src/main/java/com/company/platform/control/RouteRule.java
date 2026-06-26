/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.control;

public record RouteRule(String ruleId, String targetAgentId, String contains) {
    public boolean matches(String message) {
        if (message == null || contains == null || contains.isBlank()) {
            return false;
        }
        return message.toLowerCase().contains(contains.toLowerCase());
    }
}
