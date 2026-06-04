# Agent Skill Publication Manifest 设计说明

## 1. 本阶段目标

本阶段把 `agent-runtime` 的 Skill 注册能力从“返回 descriptor 列表”推进到“可发布、可缓存、可诊断的能力目录 Manifest”。

在真实商业化 Agent 平台里，Skill 不是简单 prompt，也不是单个工具函数。它更像一个“能力包”：包含可调用工具、权限要求、风险等级、审批策略、审计要求、租户/项目隔离声明、记忆依赖和示例任务。Python Runtime、智能网关、前端 Skill 市场、后续 MCP/A2A 适配层都需要读取同一份能力事实源，否则会出现“前端显示可用、运行时缓存不可用、网关策略又不同步”的漂移问题。

因此本阶段新增 `Skill Publication Manifest`，先把内部发布快照契约固定下来，再逐步扩展为数据库发布流、灰度批次、租户可见性、完整 MCP Server 或 A2A Agent Card。

## 2. 接口路径

Java Agent Runtime 暴露以下只读接口：

```text
GET /agent-runtime/skills/publication/manifest
GET /api/agent/skills/publication/manifest
```

查询参数：

- `includeDisabled`：默认 `false`。运行时消费建议保持默认值，只读取启用 Skill；后台诊断、市场运营、管理员排障可传 `true`。
- `domain`：可选治理域过滤，例如 `data-quality`、`task-management`。
- `riskLevel`：可选风险等级过滤，例如 `low`、`medium`、`high`。

统一响应仍使用平台标准 `PlatformApiResponse`，`data` 中是 `AgentSkillPublicationManifestView`。

## 3. Manifest 字段语义

目录级字段：

- `schemaVersion`：Manifest 契约版本，当前是 `datasmart.agent.skill.publication-manifest.v1`。
- `manifestType`：固定类型，便于未来多类 Manifest 共存。
- `protocolHint`：当前为 `MCP_STYLE_SKILL_MANIFEST`，表示参考 MCP 的可发现能力思想，但还不是完整 MCP JSON-RPC Server。
- `descriptorSchemaVersion`：Manifest 中 Skill descriptor 的版本。
- `publicationMode`：当前为 `SNAPSHOT`，后续可扩展 `DRAFT`、`RELEASE`、`CANARY`。
- `contentFingerprint`：目录级内容指纹，不包含 `generatedAt`，用于运行时缓存、灰度对比和启动诊断。
- `includeDisabled/domainFilter/riskLevelFilter`：表达这份快照的边界，避免把后台诊断快照误用于模型规划。
- `consumerGuidance/compatibilityNotes/recommendedActions`：给 Python Runtime、网关和产品后台的低敏消费建议。

Skill 条目字段：

- `skillCode`：稳定能力编码，也是 Python Runtime 选择 Skill 的主键。
- `publicationState`：发布状态，当前包含 `READY`、`DISABLED`、`NEEDS_APPROVAL_POLICY`、`NEEDS_AUDIT_POLICY`、`NEEDS_ISOLATION_POLICY`。
- `contentFingerprint`：单个 Skill 内容指纹，方便运行时局部判断能力是否变化。
- `descriptorEndpoints`：可回查完整 descriptor 的 Java 控制面端点。
- `enabled/riskLevel/approvalPolicy/auditRequired/tenantScoped/projectScoped`：治理摘要。
- `requiredTools/requiredPermissions/memoryDependencies`：执行前规划、权限预检和记忆检索所需的依赖摘要。
- `publicationWarnings`：发布前后需要关注的低敏风险提示。

## 4. 发布状态设计原则

`publicationState` 不是人工随便填写的展示字段，而是由 Java 控制面根据治理元数据计算：

- `DISABLED`：Skill 已禁用，默认不进入 Python Runtime 自动规划目录。
- `NEEDS_AUDIT_POLICY`：Skill 未声明强制审计，商业化生产中不建议发布。
- `NEEDS_ISOLATION_POLICY`：Skill 未声明租户或项目隔离，容易造成多租户能力泄露。
- `NEEDS_APPROVAL_POLICY`：高风险 Skill 缺少人工确认或复核策略。
- `READY`：Skill 启用，且具备审计、隔离和必要审批策略。

这里有一个关键产品判断：高风险 Skill 不等于永远不能发布。创建治理任务、发起数据同步、执行修复动作都可能是高风险能力，但只要审批、审计、隔离和工具沙箱完整，就可以进入 `READY`。真正需要阻断的是“高风险且无控制面治理”。

## 5. Python Runtime 消费方式

`python-ai-runtime` 的 `JavaAgentSkillRegistryClient` 已新增：

```python
manifest = client.get_publication_manifest(include_disabled=False)
```

返回对象：

- `AgentSkillPublicationManifest`
- `AgentSkillPublicationItem`

Python 侧转换为不可变 dataclass，是为了减少规划链路中被某个节点意外修改的风险。运行时建议：

- 启动期或低频刷新时读取 Manifest；
- 优先比较 `content_fingerprint`，未变化时复用本地 Skill 缓存；
- 默认只把 `publication_state == "READY"` 的 Skill 放进模型规划候选集；
- 即使 Skill 是 `READY`，执行前仍必须经过 Java 控制面的权限、审批、沙箱、runtime-protection 和审计链路；
- 当 Manifest 读取失败时，应区分“远端不可用”和“目录为空”，前者应触发降级或启动诊断，后者可能是租户策略结果。

## 6. 与 MCP 最新规范的关系

本阶段不是直接实现完整 MCP Server，而是先落地 DataSmart 内部 `MCP-style Skill Manifest`。原因是 DataSmart 的 Skill 位于 MCP Tool 之上：它组织的是一组工具、记忆、审批、审计和治理策略，而不是单个可调用函数。

当前参考 MCP 的几个方向：

- MCP 最新规范把 server feature 分为 tools、resources、prompts，强调能力可发现、结构化 schema 和客户端/宿主侧安全控制。
- MCP tools 规范要求工具输入校验、访问控制、限流、输出清洗、用户确认和审计记录；DataSmart 的 Manifest 不替代这些控制，只提供能力目录事实源。
- MCP resources/prompts 可以作为后续适配方向：READY Skill 可以被转换为 prompt 模板、资源入口或工具集合说明。
- MCP elicitation、sampling、task support 等能力仍在持续演化，DataSmart 后续可以把“需要用户补充信息/确认”的 Skill 状态映射到更标准的交互协议。

参考资料：

- [MCP Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)
- [MCP Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [MCP Resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources)
- [MCP Prompts](https://modelcontextprotocol.io/specification/2025-11-25/server/prompts)
- [MCP Elicitation](https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation)

## 7. 当前边界

当前 Manifest 仍是配置快照，不是数据库发布流。

当前没有：

- Skill 发布表、版本表、灰度批次表；
- 租户级可见性、套餐级能力裁剪、按角色能力包；
- `listChanged` 事件、SSE/WebSocket 发布变更推送；
- 完整 MCP JSON-RPC `tools/list`、`resources/list`、`prompts/list`；
- 与前端 Skill 市场的发布审批 UI；
- 按 contentFingerprint 的 HTTP ETag/If-None-Match 缓存。

这些不是遗漏，而是下一阶段可以逐步推进的产品化路线。

## 8. 下一步推荐

短期建议：

- 给 Python Runtime 启动诊断接入 Manifest 指纹和 READY Skill 数量；
- 为智能网关增加“会话可见 Skill 快照”，按租户、项目、角色和权限包过滤；
- 把 Skill Manifest 与现有 tool registry、skill admission policy、tool budget policy 串起来，形成完整执行前治理链路。

中期建议：

- 将配置式 Skill Registry 迁移为数据库发布流；
- 增加 Skill 版本、灰度、回滚、租户可见性、管理员审批和审计 outbox；
- 实现 MCP/A2A 适配层，把 READY Skill 转换为标准协议对象。

性能与可靠性建议：

- Manifest 应支持低频刷新和内容指纹缓存，不应在每次用户消息规划时实时请求 Java 控制面；
- 未来 Skill 数量增加后，应支持分页、按租户过滤和变更通知；
- 生产环境应对 Manifest 读取失败设置降级策略，例如使用最近一次成功快照并记录启动诊断事件。
