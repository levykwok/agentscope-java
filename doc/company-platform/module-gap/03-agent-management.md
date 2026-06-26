# 智能体管理

## 结论

AgentScope 能构建 `HarnessAgent`，但不负责企业平台里的 AgentSpec 管理、版本、发布、权限、审计和 UI。

平台应把 Agent 定义保存为自己的 `AgentDefinition`，运行时再转换成 `HarnessAgent.Builder`。

## 平台模块功能设计

本模块负责管理智能体定义，即平台侧的 AgentSpec / AgentDefinition。

核心功能：

```text
1. Agent 创建：配置 agent_id、名称、描述、业务域、模型、系统提示词、workspace。
2. Agent 编辑：维护 toolRefs、mcpRefs、skillRefs、memory 配置、运行参数。
3. Agent 版本管理：草稿、发布版本、历史版本、版本复制。
4. Agent 发布管理：draft -> published -> disabled/offline。
5. Agent 权限范围：控制哪些组织、用户、项目可以查看或运行。
6. Agent 工作区管理：初始化 AGENTS.md、knowledge、skills、subagents 等目录。
7. Agent 能力绑定：绑定模型、工具、MCP、Skill、子 Agent、编排策略。
8. Agent 测试运行：管理端直接用当前草稿配置发起测试。
9. Agent 缓存失效：发布新版本后让运行时使用新 AgentDefinition。
10. Agent 审计：记录创建、编辑、发布、下线、删除。
```

输入资源：

```text
AgentDefinition 表单
模型列表
工具/MCP/Skill 列表
编排配置
权限上下文
```

输出资源：

```text
可发布 AgentDefinition
运行时 HarnessAgent 构建参数
AgentSpec 快照
Agent 版本记录
```

## AgentScope 对应实现

AgentScope 对应能力：

```text
HarnessAgent.Builder
workspace/AGENTS.md
workspace/knowledge/
workspace/skills/
workspace/subagents/
workspace/tools.json
```

AgentScope 的设计是“workspace 即 agent 资产”。但企业平台还需要 DB/YAML 管理 AgentSpec。

## 当前平台已实现

```text
AgentDefinition
AgentDefinitionRegistry
YamlAgentDefinitionRegistry
AgentScopeHarnessFactory
PlatformConfigStore
```

落盘：

```text
company-platform/workspace/agents.yml
```

当前 AgentDefinition 字段包括：

```text
agentId
version
name
model
systemPrompt
workspace
toolRefs
mcpRefs
skillRefs
orchestration
```

## 运行时转换链路

```java
HarnessAgent.builder()
    .name(definition.name())
    .sysPrompt(definition.systemPrompt())
    .model(definition.model())
    .workspace(definition.workspace())
    .toolkit(toolkit)
    .skillRepositories(skillRepositories)
    .build()
```

## 还缺什么

```text
Agent 新增/编辑 UI
Agent 版本管理
发布/下线/草稿状态
AgentSpec 快照，保证运行时使用固定版本
Agent 权限范围
Agent 工作区资产管理
Agent 变更审计
Agent 测试运行
```

## 推荐下一步

```text
P0: 做 Agent 管理页面，支持 systemPrompt/model/tool/mcp/skill/orchestration 配置
P1: AgentDefinition 增加 status、description、domain、owner、createdAt、updatedAt
P2: 增加 AgentSpec 快照，AgentRun 记录 agent_version 和 agent_spec
P3: DB 化时保留 AgentDefinitionRegistry 接口
```

## 接口调用设计

### Agent 列表

```http
GET /platform/frontend/agents?domain=platform
```

响应建议：

```json
{
  "items": [
    {
      "agent_id": "ops_agent",
      "version": "v1",
      "display_name": "运维智能体",
      "model": "deepseek-v4-flash",
      "status": "published",
      "tool_refs": [],
      "mcp_refs": [],
      "skill_refs": []
    }
  ]
}
```

### Agent 保存

```http
POST /platform/frontend/agents
Content-Type: application/json

{
  "agent_id": "ops_agent",
  "version": "v1",
  "name": "运维智能体",
  "model": "deepseek-v4-flash",
  "system_prompt": "你是运维智能体...",
  "workspace": "agents/ops_agent",
  "tool_refs": ["builtin:read_file"],
  "mcp_refs": ["ops-mcp"],
  "skill_refs": ["alarm-analysis"],
  "orchestration": {
    "mode": "SINGLE"
  }
}
```

### 发布接口建议

```http
POST /platform/frontend/agents/{agentId}/publish
Content-Type: application/json

{
  "version": "v1",
  "comment": "首次发布"
}
```

### 后端调用链路

```text
Agent 管理 UI
  -> AgentController / PlatformFrontendCompatibilityController
  -> AgentDefinitionRegistry.upsert(...)  当前待补
  -> PlatformConfigStore
  -> agents.yml
```

运行时读取：

```java
AgentDefinition definition = registry.findPublished(agentId).orElseThrow(...);
HarnessAgent agent = harnessFactory.create(definition);
```

### 运行时缓存

当前：

```java
String key = definition.agentId() + ":" + definition.version();
agentCache.computeIfAbsent(key, ignored -> harnessFactory.create(definition));
```

如果 AgentDefinition 被编辑，必须处理缓存失效：

```text
发布新 version -> 新 cache key
修改同 version -> 清理旧 cache 或禁止修改已发布版本
```

### DB 化边界

```text
AgentDefinitionRegistry
  -> findPublished(agentId)
  -> allPublished()
  -> upsertDraft(...)
  -> publish(...)
```

Controller 不直接关心 YAML/DB。

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
