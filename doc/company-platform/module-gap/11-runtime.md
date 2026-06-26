# 运行时

## 结论

运行时是最应该复用 AgentScope 的模块。我们的平台只应该做 AgentDefinition 到 HarnessAgent 的装配、请求/响应协议适配、运行记录、权限过滤和异常映射。

不要重写 ReAct 循环、AgentState、memory、session、skill loading、MCP runtime。

## 平台模块功能设计

本模块负责把平台管理的 AgentDefinition、模型、工具、MCP、Skill、会话、记忆等资源装配成一次可执行的 AgentRun。

核心功能：

```text
1. AgentHandle 获取：按 agent_id/version 获取或构建 HarnessAgent。
2. RuntimeContext 构造：注入 userId、sessionId、tenantId、runId。
3. 资源装配：装配模型、Toolkit、MCP、Skill、子 Agent、workspace。
4. 统一执行：支持 sync、stream、async。
5. 流式事件：把 AgentScope AgentEvent 映射为平台 SSE event。
6. 运行记录：记录 AgentRun、AgentRunEvent、AgentToolCall。
7. 等待用户输入：处理 clarification/confirmation/approval。
8. 异常降级：模型、工具、记忆、RAG、会话失败时按策略处理。
9. 取消和超时：支持 run cancel、step timeout、整体 timeout。
10. 缓存管理：AgentDefinition 发布新版本时刷新 HarnessAgent cache。
```

输入资源：

```text
AgentRunRequest
AgentDefinition
ModelSpec
ToolSpec/McpSpec/SkillSpec
ChatSession
ArtifactRef
Permission context
```

输出资源：

```text
AgentExecutionResult
AgentRunEvent stream
AgentRun DB record
Session updates
Memory/session side effects from AgentScope
```

## AgentScope 对应实现

关键类：

```text
HarnessAgent
ReActAgent
RuntimeContext
AgentState
AgentStateStore
Middleware
Toolkit
AgentEvent
ExecutionConfig
GenerateOptions
```

## 当前平台已实现

```text
AgentRuntime
AgentRuntimeService
ChatRequest
ChatResponse
AgentEventEnvelope
AgentRunsCompatibilityController
AgentScopeHarnessFactory
AgentCapabilityAssembler
```

## 当前调用链路

```text
AgentRuntimeService.chat(agentId, request)
  -> registry.findPublished(agentId)
  -> orchestration mode
  -> runtimeContext(request)
  -> agent(definition).call(request.message(), context)
  -> ChatResponse
```

stream 方向已有方法：

```java
agent(definition).streamEvents(request.message(), context)
```

但当前 `/agent-runs/run/stream` 主要还是把 `chat(...)` 结果包装成 SSE，不是真正透传 AgentScope event stream。

## 还缺什么

```text
真正使用 streamEvents 输出 token/event
AgentRun 表
AgentRunEvent 表
AgentToolCall 表
取消运行
超时控制
等待输入恢复
错误码映射
事件重放
异步运行
运行状态查询
```

## 推荐下一步

```text
P0: AgentRunsCompatibilityController 改成调用 runtime.stream(...)
P1: 建 PlatformRunRecorder，记录 run/event/tool_call
P2: 加 runtime timeout/cancel
P3: waiting_user_input 接 resume
P4: AgentRun 使用固定 AgentSpec snapshot
```

## 接口调用设计

### 当前同步封装

```java
public Mono<ChatResponse> chat(String agentId, ChatRequest request) {
    AgentDefinition definition = definition(agentId);
    RuntimeContext context = runtimeContext(request);
    return agent(definition)
        .call(request.message(), context)
        .map(msg -> response(definition.agentId(), request, msg));
}
```

### 目标流式封装

```java
public Flux<AgentEventEnvelope> stream(String agentId, ChatRequest request) {
    AgentDefinition definition = definition(agentId);
    RuntimeContext context = runtimeContext(request);
    return agent(definition)
        .streamEvents(request.message(), context)
        .map(this::envelope);
}
```

### Controller 应调用 stream

```java
@PostMapping(value = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Map<String, Object>>> streamRun(...) {
    return runtime.stream(agentId, request)
        .map(event -> toSse(event));
}
```

### AgentRun 生命周期

```text
created
running
waiting_user_input
succeeded
failed
cancelled
```

### 运行记录接口建议

查询 run：

```http
GET /agent-runs/{runId}
```

查询事件：

```http
GET /agent-runs/{runId}/events?after_id=0&limit=200
```

取消 run：

```http
POST /agent-runs/{runId}/cancel
```

### RuntimeContext 规范

```java
RuntimeContext.builder()
    .userId(tenantId + ":" + userId)
    .sessionId(sessionId)
    .put("tenant_id", tenantId)
    .put("run_id", runId)
    .build();
```

### 缺口落地

```text
P0: /run/stream 改为 runtime.stream
P1: AgentRunRecorder 记录 run/event/tool_call
P2: timeout/cancel/waiting resume
P3: Runtime 降级策略配置化
```

## 当前平台接入基线

当前主运行链路：

```text
Frontend AgentWorkbench
  -> POST /agent-runs/run/stream
  -> AgentRunsCompatibilityController
  -> AgentRuntimeService.chat(...)
  -> AgentScopeHarnessFactory.create(...)
  -> HarnessAgent.call(...)
  -> ReActAgent + Middleware + Toolkit + Workspace + Memory + Session
```

当前平台配置统一入口：

```text
PlatformConfigStore
  -> models.yml
  -> providers.yml
  -> agents.yml
  -> tools.yml
  -> mcps.yml
  -> skills.yml
```

当前默认 workspace：

```text
company-platform/workspace/
```
