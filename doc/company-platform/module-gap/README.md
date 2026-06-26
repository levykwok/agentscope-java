# 详细设计模块与 AgentScope 实现对照 - 分模块索引

本目录按详细设计模块拆分，每个模块一个文件。每个文件参考 `../agentscope-memory.md` 的写法，重点说明：AgentScope 链路、平台接入方式、接口调用设计、缺口和落地优先级。

## 总体判断

AgentScope 更偏“智能体运行时框架”，不是完整企业智能体平台。

它已经覆盖：

```text
Agent 执行循环
模型调用抽象
工具调用
MCP 运行时接入
Skill 加载
Workspace
会话上下文
长期记忆
子 Agent
流式事件
沙箱 / 文件系统
Plan Mode
```

它没有完整覆盖：

```text
平台控制台
模型供应商管理
租户 / 用户 / 权限
审计日志
审批工作流
配置持久化管理
数据库表结构
运行记录管理
模型监控统计
业务智能体资产管理
```

## 模块文件

- [模型统一接入](./01-model-unified-access.md)
- [对外统一 API 网关](./02-api-gateway.md)
- [智能体管理](./03-agent-management.md)
- [智能体编排](./04-agent-orchestration.md)
- [MCP Server 管理](./05-mcp-management.md)
- [Skills 管理](./06-skill-management.md)
- [会话管理](./07-session-management.md)
- [记忆管理](./08-memory-management.md)
- [多模态内容呈现](./09-multimodal-rendering.md)
- [多模态人机交互](./10-multimodal-interaction.md)
- [运行时](./11-runtime.md)
- [AI 模型监控工具](./12-model-monitoring.md)
- [用户和权限管理](./13-user-permission.md)
- [安全设计与操作日志](./14-security-audit.md)
- [运维智能体](./15-ops-agent.md)
- [监盘智能体](./16-monitoring-agent.md)
- [RAG 知识检索](./17-rag.md)

## 分层原则

```text
平台管理层：我们做
平台持久化层：我们做，当前 YAML，后续 DB
运行时适配层：我们做薄封装
Agent 执行层：优先调用 AgentScope Harness
```

## 每个模块文档的固定内容

```text
结论
平台模块功能设计
AgentScope 对应实现
当前平台已实现
运行时/管理侧调用链路
接口调用设计
HTTP 请求/响应示例
Java 调用点
DB 化边界
还缺什么
推荐下一步
```

