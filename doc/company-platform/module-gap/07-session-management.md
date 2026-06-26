# 会话管理

## 结论

AgentScope 已经有会话恢复和会话日志链路。平台不要手写运行时会话文件，应该让 `HarnessAgent.call/streamEvents` 触发 AgentScope 的 session pipeline。

平台管理侧可以读取、展示、索引这些会话资产，但必须使用同一个 `RuntimeContext`。

## 平台模块功能设计

本模块负责管理用户和 Agent 的对话会话，以及对话恢复、运行记录关联和会话资产展示。

核心功能：

```text
1. 会话创建：为指定 Agent 创建 session_id 和 title。
2. 会话列表：按用户、Agent、业务域查询会话。
3. 会话详情：展示消息、上下文、日志、任务、记忆相关资产。
4. 会话恢复：对话时带 session_id，由 AgentScope 通过 RuntimeContext 恢复状态。
5. 消息记录：后续 DB 化保存用户消息、助手消息、引用和附件。
6. 上下文管理：读取 AgentScope session jsonl/log，不手写运行时文件。
7. 会话摘要：后续接入 summary，降低长会话上下文压力。
8. 运行记录关联：session_id 关联 AgentRun、AgentRunEvent、ToolCall。
9. 会话权限：用户只能访问自己或授权范围内的会话。
10. 会话归档删除：支持逻辑删除、归档、恢复。
```

输入资源：

```text
agent_id
session_id
userId/orgId
AgentScope workspace session files
AgentRun 记录
```

输出资源：

```text
ChatSession
ChatMessage
SessionSummary
AgentScope session assets
前端会话详情 panel 数据
```

## AgentScope 对应实现

关键类和文件：

```text
RuntimeContext
AgentState
AgentStateStore
WorkspaceManager
SessionTree
SessionSearchTool
sessions.json
{sessionId}.jsonl
{sessionId}.log.jsonl
```

会话文件：

```text
agents/{agentId}/sessions/sessions.json
agents/{agentId}/sessions/{sessionId}.jsonl
agents/{agentId}/sessions/{sessionId}.log.jsonl
```

## 当前平台已实现

```text
PlatformWorkspaceSessionStore
PlatformFrontendCompatibilityController /chat/sessions
AgentRuntimeService.runtimeContext(...)
AgentWorkbench.vue
```

已有专项文档：

```text
doc/company-platform/agentscope-session.md
```

## 运行时调用链路

```text
Frontend 选择 sessionId
  -> /agent-runs/run/stream
  -> ChatRequest(sessionId)
  -> AgentRuntimeService.runtimeContext(...)
  -> HarnessAgent.call(message, runtimeContext)
  -> AgentScope 自动恢复 AgentState / offload session / 写 logs
```

RuntimeContext 当前规则：

```text
userId = tenantId + ':' + userId
sessionId = request.sessionId
```

## 平台管理侧读取链路

```java
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, relativePath)
```

不要用：

```java
RuntimeContext.empty()
```

除非明确读全局 namespace。

## 还缺什么

```text
PlatformSessionService
正式 chat_session/chat_message/agent_run 表
会话摘要管理
消息级来源引用
附件关联
会话归档/删除/恢复
会话权限
右侧 panel 按 RuntimeContext 读取 session/memory
```

## 推荐下一步

```text
P0: 修正 PlatformWorkspaceSessionStore 读取 memory/session 时的 RuntimeContext
P1: 抽 PlatformSessionService，controller 不直接操作 WorkspaceManager
P2: AgentRun 和 session 绑定落库
P3: 会话摘要和附件/RAG 来源统一进 SessionSnapshot
```

## 接口调用设计

### 创建会话

```http
POST /platform/frontend/chat/sessions
Content-Type: application/json
x-org-id: platform
x-user-id: platform_admin

{
  "agent_id": "ops_agent",
  "title": "数据库告警排查",
  "domain": "platform"
}
```

响应：

```json
{
  "session": {
    "session_id": "sess_xxx",
    "agent_id": "ops_agent",
    "title": "数据库告警排查"
  }
}
```

### 查询会话列表

```http
GET /platform/frontend/chat/sessions?agent_id=ops_agent&domain=platform
```

### 查询会话详情

```http
GET /platform/frontend/chat/sessions/{sessionId}?agent_id=ops_agent
```

响应应包含：

```json
{
  "session": {},
  "messages": [],
  "context_entries": [],
  "log_entries": [],
  "tasks": {},
  "memory": {},
  "files": {}
}
```

### 对话时如何接入 session

```http
POST /agent-runs/run/stream
Content-Type: application/json

{
  "agent_id": "ops_agent",
  "session_id": "sess_xxx",
  "payload": {
    "query": "继续刚才的问题"
  }
}
```

后端：

```java
RuntimeContext context = RuntimeContext.builder()
    .userId(tenant + ":" + user)
    .sessionId(sessionId)
    .put("tenant_id", tenant)
    .build();

harnessAgent.call(message, context);
```

### 关键原则

运行时不要手写：

```text
{sessionId}.jsonl
{sessionId}.log.jsonl
```

这些应该由 AgentScope 的 session/offload 链路写。

平台可以读：

```java
workspaceManager.readManagedWorkspaceFileUtf8(context, relativePath)
```

### DB 化边界

短期：读 AgentScope workspace。

中期：增加平台会话表保存索引和展示字段：

```text
chat_session
chat_message
session_summary
agent_run
agent_run_event
agent_tool_call
```

但运行时上下文恢复仍优先走 AgentScope：

```text
RuntimeContext + AgentStateStore + SessionTree
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
