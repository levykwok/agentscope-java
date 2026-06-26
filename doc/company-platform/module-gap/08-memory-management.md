# 记忆管理

## 结论

AgentScope Harness 的 memory 是长期记忆，默认启用。它能自动抽取、手动保存、周期合并，但缺平台级记忆治理：确认、可信度、冲突合并、向量索引、权限和 UI。

详细链路见：

```text
doc/company-platform/agentscope-memory.md
```

## 平台模块功能设计

本模块负责长期记忆的治理和平台化管理。AgentScope 负责自动抽取和注入，平台负责可视化、确认、编辑、停用、冲突治理和权限。

核心功能：

```text
1. 长期记忆读取：读取当前用户/Agent namespace 下的 MEMORY.md。
2. Daily ledger 查看：展示 memory/YYYY-MM-DD.md 抽取流水。
3. 手工保存记忆：用户或管理员手动新增长期记忆。
4. 自动抽取接入：复用 MemoryFlushMiddleware 自动抽取结果。
5. 记忆确认：对高影响记忆进入 pending_confirm，人工确认后生效。
6. 记忆编辑：修正错误或过期记忆。
7. 记忆停用/删除：支持 inactive/expired/deleted/merged。
8. 记忆检索：按 scope、agent、业务对象、关键词查询。
9. 记忆冲突合并：发现相似或矛盾记忆，人工或模型辅助合并。
10. 记忆注入策略：控制哪些记忆进入运行时上下文。
```

输入资源：

```text
MEMORY.md
memory/YYYY-MM-DD.md
RuntimeContext
AgentRun/session/source_ref
用户确认操作
```

输出资源：

```text
AgentMemory
MemoryRetrievalResult
记忆审计记录
可注入上下文的记忆片段
```

## AgentScope 对应实现

关键类：

```text
MemorySaveTool
MemorySearchTool
MemoryGetTool
MemoryFlushMiddleware
MemoryFlushManager
MemoryConsolidator
MemoryMaintenanceMiddleware
MemoryConfig
```

关键文件：

```text
MEMORY.md
memory/YYYY-MM-DD.md
memory/.consolidation_state
```

## 长期记忆产生时机

自动抽取：

```text
HarnessAgent.call(...)
  -> ReActAgent 执行完成
  -> MemoryFlushMiddleware
  -> 调模型抽取长期事实
  -> append memory/YYYY-MM-DD.md
```

手动保存：

```text
Agent 调用 memory_save
  -> append MEMORY.md
  -> append memory/YYYY-MM-DD.md
```

周期合并：

```text
MemoryMaintenanceMiddleware
  -> MemoryConsolidator
  -> daily ledger 合并去重
  -> overwrite MEMORY.md
```

## 当前平台已实现

当前没有完整 Memory 管理服务。运行时通过 Harness 默认链路间接启用 memory。

已有文档：

```text
doc/company-platform/agentscope-memory.md
```

## 推荐调用方式

运行时配置：

```java
HarnessAgent.builder()
    .memory(MemoryConfig.builder()
        .flushPrompt(...)
        .flushTrigger(...)
        .consolidationPrompt(...)
        .build())
```

管理侧读取：

```java
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, "MEMORY.md")
workspaceManager.readManagedWorkspaceFileUtf8(runtimeContext, "memory/YYYY-MM-DD.md")
```

## 还缺什么

```text
PlatformMemoryService
记忆列表/详情/新增/确认/停用接口
pending_confirm / active / rejected 状态流转
可信度评分
冲突检测与合并
向量索引
scope: user/project/org/agent/global
记忆管理页面
AgentDefinition 暴露 memory 配置
```

## 推荐下一步

```text
P0: 修正会话详情 panel 的 memory 读取 RuntimeContext
P1: 增加 PlatformMemoryService，只读 MEMORY.md/daily ledger
P2: AgentDefinition 增加 memory 配置：enabled、flushPrompt、flushTrigger、scope
P3: 管理侧 DB 化 agent_memory，运行时仍先复用 AgentScope memory pipeline
```

## 接口调用设计

### 查询长期记忆

```http
GET /platform/frontend/memory?agent_id=ops_agent&scope=user&scope_id=platform:platform_admin
```

响应建议：

```json
{
  "memory_md": "...",
  "daily_ledgers": [
    {
      "date": "2026-06-26",
      "path": "memory/2026-06-26.md",
      "content": "..."
    }
  ]
}
```

### 手工保存记忆

```http
POST /platform/frontend/memory
Content-Type: application/json

{
  "agent_id": "ops_agent",
  "scope": "user",
  "scope_id": "platform:platform_admin",
  "content": "- 用户希望告警分析结果使用表格展示",
  "source_ref": {
    "session_id": "sess_xxx",
    "run_id": "run_xxx"
  }
}
```

短期实现可以 append 到 AgentScope workspace；长期应该写平台 DB 的 `agent_memory`，再决定是否同步到 AgentScope memory backend。

### 运行时自动抽取

不需要平台接口主动触发。默认链路：

```text
HarnessAgent.call
  -> MemoryFlushMiddleware
  -> MemoryFlushManager.flushMemories
  -> memory/YYYY-MM-DD.md
```

### 自定义抽取 prompt

Agent 配置建议：

```json
{
  "memory": {
    "enabled": true,
    "flush_trigger": "always",
    "flush_prompt": "你是企业运维记忆抽取器...",
    "consolidation_prompt": "...",
    "scope": "USER"
  }
}
```

运行时转换：

```java
HarnessAgent.builder()
    .memory(MemoryConfig.builder()
        .flushPrompt(memory.flushPrompt())
        .flushTrigger(MemoryConfig.FlushTrigger.always())
        .build())
```

### DB 化边界

AgentScope 文件：

```text
MEMORY.md
memory/YYYY-MM-DD.md
```

平台 DB：

```text
agent_memory
memory_embedding_index
memory_audit_log
```

短期不要破坏 AgentScope 自动链路；平台 DB 可以作为治理层。

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
