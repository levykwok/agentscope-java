/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform.runtime;

public record ChatRequest(String tenantId, String userId, String sessionId, String message) {}
