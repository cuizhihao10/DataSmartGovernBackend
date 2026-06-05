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

## 5.1 Python Runtime 启动诊断

Python Runtime 已新增 Skill Publication Manifest 低敏诊断入口：

```text
GET /agent/skills/publication/diagnostics
```

诊断服务位于 `python-ai-runtime/src/datasmart_ai_runtime/services/skills/`，不会直接改变当前 Agent 规划主路径。
它的职责是回答“远端 Skill 发布事实源是否健康”，而不是立即替代 descriptor 加载。

诊断状态：

- `REMOTE_READY`：已成功读取 Java Manifest，并能返回指纹、READY 数量和非 READY 分布。
- `REMOTE_UNAVAILABLE_FALLBACK`：远端读取失败，当前使用本地默认 Skill fallback。
- `REMOTE_NOT_REFRESHED`：启用了远端诊断但尚未刷新，通常意味着 startup 刷新被关闭或尚未触发。
- `LOCAL_DEFAULT_ONLY`：未配置远端 Java base URL，当前只使用本地默认 Skill。

关键字段：

- `manifestFingerprint`：远端目录级内容指纹，可用于启动日志、运行时事件、缓存和灰度排查。
- `readySkillCount`：可进入默认规划候选集的 Skill 数量。
- `nonReadySkillCount`：非 READY Skill 数量。
- `publicationStateCounts`：按 READY、DISABLED、NEEDS_APPROVAL_POLICY 等状态聚合。
- `riskLevelCounts`：按 LOW、MEDIUM、HIGH 聚合。
- `nonReadySkills`：最多展示配置数量的低敏摘要，只包含 skillCode、publicationState、riskLevel、enabled、warningCount。
- `fallback`：是否正在使用本地默认 Skill。
- `lastError`：远端失败的脱敏错误摘要，长度受限。

常用配置：

```powershell
$env:DATASMART_AGENT_RUNTIME_BASE_URL="http://localhost:8091"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_DIAGNOSTICS_ENABLED="true"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_INCLUDE_DISABLED="true"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_ON_STARTUP="true"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REQUIRED="false"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_MAX_NON_READY_ITEMS="10"
```

商业化建议：

- 本地学习环境建议 `required=false`，保证 Java 控制面未启动时 Python Runtime 仍可用。
- 集成/生产环境如果要求所有 Agent 能力必须来自 Java 发布事实源，可设置 `required=true`，让启动期刷新失败直接暴露。
- 诊断接口应由 gateway 与 permission-admin 保护，不应直接暴露给终端用户。
- 当前 `/agent/plans` 已把 `manifestFingerprint` 写入智能网关会话快照和 `SKILL_VISIBILITY_SNAPSHOT_RECORDED`
  runtime event；后续仍建议继续接 Prometheus gauge、启动日志和持久化审计索引。

## 5.2 智能网关会话级可见性快照

Python Runtime 已在 `intelligentGatewayGovernance.skillVisibility` 中输出会话级 Skill 可见性快照。

它与 Manifest 的关系如下：

- Manifest 是平台/运行时级“全局发布目录快照”，回答“Java 控制面发布了哪些 Skill”。
- `skillVisibility` 是请求/会话级“当前可见能力快照”，回答“本轮目标、当前 workspace、角色、权限和工具预算下，哪些 Skill 真的可见”。
- 当前 `skillVisibility` 复用本轮 `AgentSkillPlan` 的选择与准入结果，不在响应阶段重新拉 Manifest 或重新请求 permission-admin。

这样设计是为了避免二次决策漂移：如果响应组装时重新计算一次 Skill 可见性，可能出现模型实际使用的 Skill 和前端展示的可见 Skill 不一致。先复用计划事实，再逐步接入 Manifest 指纹、Java 控制面审计和 WebSocket 会话状态，是更稳的商业化演进路线。

当前快照只返回低敏摘要：

- 可见 Skill 数量、隐藏 Skill 数量、条件性可见数量；
- Manifest 绑定状态、来源、`manifestFingerprint`、schemaVersion、READY/非 READY 数量和 fallback 状态；
- 可见 Skill 的 skillCode、领域、风险等级、准入状态、依赖数量；
- 被隐藏 Skill 的 skillCode、领域、风险等级、隐藏原因数量；
- 事实来源：`trusted-control-plane`、`legacy-request-variables` 或 `missing`；
- 风险等级分布、领域分布、隐藏状态分布和推荐动作。

它不会返回完整权限清单、prompt、工具参数、样本数据或密钥。

当前快照已经进一步写入 `SKILL_VISIBILITY_SNAPSHOT_RECORDED` runtime event。事件只保存低敏聚合字段：

- 可见、隐藏、条件性可见数量；
- Manifest 绑定状态、来源、`manifestFingerprint`、schemaVersion、Skill 总量、READY/非 READY 数量和 fallback 状态；
- 可见/隐藏 Skill code，并设置数量上限，避免单条事件过大；
- 权限事实来源、角色事实来源、权限数量、租户开关、workspace 风险、套餐编码和策略版本；
- 风险等级、领域和隐藏准入状态分布；
- 摘要文案和推荐动作数量。

这样做的目的不是替代 `skillVisibility` 响应字段，而是把“当时会话实际可见的能力边界”写入可 replay 的运行时事实。
后续 Java plan ingestion、WebSocket 断线恢复、Skill Marketplace 使用统计和审计报表都可以消费该事件，而不用重新推断
会话当时的权限与能力集合。事件仍然不写 prompt、SQL、工具参数、完整权限清单、样本数据或长期记忆正文。

Java `agent-runtime` 已新增专用查询视图消费该事件：

```text
GET /agent-runtime/runtime-events/skill-visibility-snapshots
GET /api/agent/runtime-events/skill-visibility-snapshots
```

该接口固定读取 `skill_visibility_snapshot_recorded`，并返回强类型低敏 DTO，而不是要求前端直接解析通用
runtime event attributes。查询仍经过 gateway Header 转换出的租户、项目、本人数据范围收口；普通用户可以
在通用 replay 中看到该事件进度，但属性会被 BASIC 策略脱敏。

当前 Java 侧已新增 `AgentSkillVisibilitySnapshotIndexStore` 专用索引端口和内存实现。runtime event consumer
首次接收 `skill_visibility_snapshot_recorded` 后，会把低敏快照同步物化到专用索引；查询服务优先读取该索引，
未启用时才 fallback 到通用 runtime event projection。Java 专用视图已经能读取
`manifestBindingStatus/manifestSource/manifestFingerprint`，并按绑定状态与来源聚合返回窗口。

当前专用索引已具备 `memory/mysql` 两种实现：

- `memory` 是默认实现，适合本地学习、单元测试和无数据库联调；
- `mysql` 写入 `agent_skill_visibility_snapshot_index` 表，支持跨实例、跨重启、长期审计、Manifest 指纹灰度对比和 Skill Marketplace 统计。

MySQL 表只保存低敏聚合事实和可治理索引字段，不保存 prompt、SQL、工具参数、连接密钥、样本数据、完整权限明细或长期记忆正文。
后续如果需要更高吞吐运营报表，可以在同一端口下继续扩展 ClickHouse/OpenSearch/审计中心实现。

Java 控制面已为该索引补充诊断和低基数指标：

```text
GET /agent-runtime/runtime-events/skill-visibility-snapshots/diagnostics
GET /api/agent/runtime-events/skill-visibility-snapshots/diagnostics
```

诊断接口返回：

- 当前是否启用专用索引、配置 store、实际 active store；
- 当前查询是否走 dedicated index 或 fallback projection；
- 当前索引大小探测状态；
- 物化成功、重复、跳过、失败计数；
- dedicated/fallback 查询次数和返回记录数；
- Manifest 绑定状态分布。

Micrometer 指标使用 `datasmart_agent_runtime_skill_visibility_index_*` 前缀，并严格限制标签为
`store/source/outcome/bindingStatus` 这类低基数枚举，不把 runId、sessionId、tenantId、projectId、
traceId 或 Manifest 指纹放入 Prometheus 标签。

consumer 侧还补充了幂等补物化语义：如果通用 projection 首次写入成功但专用索引写入失败，Kafka 重放同一事件时，
即使 projection 判定 duplicate，也会再次尝试把 Skill 可见性快照写入专用索引。这样可以降低 MySQL 短暂故障后索引长期漏写的概率。

Prometheus 告警已经接入 `docker/prometheus/rules/agent-runtime-alerts.yml`，当前重点覆盖：

- `AgentRuntimeSkillVisibilityIndexMaterializationFailureDetected`：发现新的索引物化失败；
- `AgentRuntimeSkillVisibilityIndexMaterializationFailureRatioHigh`：索引物化失败率持续偏高；
- `AgentRuntimeSkillVisibilityIndexFallbackQueryRatioHigh`：治理查询大量退回通用 runtime event projection；
- `AgentRuntimeSkillVisibilityIndexDedicatedQueryFailureDetected`：dedicated index 查询失败；
- `AgentRuntimeSkillVisibilityIndexManifestBindingUnhealthy`：新快照出现未知、fallback、本地默认或诊断不可用的 Manifest 绑定状态。

这些告警的设计意图不是替代审计查询，而是把“能力目录版本事实是否可信、专用索引是否真的承接治理事实”
变成运维可主动发现的问题。单个 run/session 的排障仍应进入 runtime event replay、诊断接口和结构化日志。
告警表达式也不使用 `manifestFingerprint` 作为 Prometheus 标签，因为指纹会随着发布批次变化，放进标签会让时序持续增长。

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

- 为会话可见 Skill 快照 MySQL 索引继续补充 outbox 式失败补偿、保留策略和按租户/Manifest 指纹的报表 API；
- 把 Skill Manifest 与现有 tool registry、skill admission policy、tool budget policy 串起来，形成完整执行前治理链路。
- 将 `manifestFingerprint` 继续接入启动日志、诊断接口和审计报表；Prometheus 只保留指纹是否存在、绑定状态等低基数摘要，避免高基数时序风险。

中期建议：

- 将配置式 Skill Registry 迁移为数据库发布流；
- 增加 Skill 版本、灰度、回滚、租户可见性、管理员审批和审计 outbox；
- 实现 MCP/A2A 适配层，把 READY Skill 转换为标准协议对象。

性能与可靠性建议：

- Manifest 应支持低频刷新和内容指纹缓存，不应在每次用户消息规划时实时请求 Java 控制面；
- 未来 Skill 数量增加后，应支持分页、按租户过滤和变更通知；
- 生产环境应对 Manifest 读取失败设置降级策略，例如使用最近一次成功快照并记录启动诊断事件。
