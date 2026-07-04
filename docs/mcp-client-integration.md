# DataSmart 出站 MCP Client 接入说明

## 1. 当前定位

DataSmart 现在具备真实的出站 MCP Client，不再只有 MCP/A2A 映射预览：

- 支持官方 MCP Python SDK `1.x`；
- 支持 Streamable HTTP 与受控 stdio；
- 支持 `initialize`、分页 `tools/list` 和真实 `tools/call`；
- 远端 Tool 会转换为项目统一 `ToolDefinition`；
- 工具名称统一为 `mcp.<serverId>.<remoteToolName>`，防止不同 Server 同名覆盖；
- 真实调用必须携带 readiness、permission、approval、workspace 和本轮工具 allowlist。

MCP Client 不替代 Java Agent Runtime。Java 仍负责权限、审批、outbox、worker receipt、审计和 durable
command；Python MCP Client 只负责协议连接与结果归一。

## 2. SDK 版本策略

截至 2026-07-03，官方 MCP Python SDK 的稳定版本线仍是 `1.x`，`2.x` 处于预发布阶段。项目固定：

```text
mcp>=1.27,<2
```

这样可以继续获得 1.x 安全修复，同时避免镜像重建时自动跨到包含破坏性变更的 2.x。升级 2.x 时只应修改
`services/tools/mcp/official_sdk.py` 和兼容测试，不应重写 ToolPlan、权限或 Agent 业务代码。

官方参考：

- https://github.com/modelcontextprotocol/python-sdk/tree/v1.x
- https://modelcontextprotocol.io/

## 3. 配置

默认完全关闭：

```text
DATASMART_AI_MCP_ENABLED=false
DATASMART_AI_MCP_DISCOVERY_ON_STARTUP=false
DATASMART_AI_MCP_FAIL_OPEN=true
DATASMART_AI_MCP_STDIO_ENABLED=false
```

Server 清单使用 JSON 数组。凭据不得写入 JSON，只能通过 `authTokenEnv` 指向 Secret 环境变量。

### 3.1 Streamable HTTP

```json
[
  {
    "serverId": "enterprise-search",
    "displayName": "企业搜索 MCP",
    "enabled": true,
    "required": true,
    "transport": "streamable-http",
    "endpoint": "https://mcp.example.internal/mcp",
    "allowedHosts": ["mcp.example.internal"],
    "authTokenEnv": "DATASMART_ENTERPRISE_SEARCH_MCP_TOKEN",
    "connectTimeoutSeconds": 10,
    "readTimeoutSeconds": 60,
    "maxTools": 100,
    "maxResultBytes": 65536,
    "defaultPermission": "agent:mcp:enterprise-search:call"
  }
]
```

将 JSON 放入：

```text
DATASMART_AI_MCP_SERVERS_JSON=<上面的 JSON>
```

HTTP 安全约束：

- 默认只允许 HTTPS；
- endpoint host 必须命中 `allowedHosts`；
- 禁止 endpoint 携带 username、password、query 或 fragment；
- HTTP Client 不跟随重定向，防止 Authorization 被带到其他 host；
- Bearer Token 只在连接生命周期中从 Secret 环境变量读取；
- `required=true` 或 `FAIL_OPEN=false` 时，发现失败会阻断启动目录装配。

### 3.2 stdio

stdio 会在 Python Runtime 主机中启动进程，风险高于远程 HTTP，因此需要额外开关：

```text
DATASMART_AI_MCP_STDIO_ENABLED=true
DATASMART_AI_MCP_STDIO_ALLOWED_COMMANDS=python,node,npx
DATASMART_AI_MCP_STDIO_ALLOWED_ROOTS=/opt/datasmart/mcp-servers
```

Server 配置示例：

```json
[
  {
    "serverId": "local-governance",
    "enabled": true,
    "transport": "stdio",
    "command": "python",
    "args": ["server.py"],
    "cwd": "/opt/datasmart/mcp-servers/local-governance",
    "environmentKeys": ["LOCAL_GOVERNANCE_CONFIG"]
  }
]
```

stdio 不使用 shell，只允许命令 basename 白名单、cwd 根目录白名单和显式环境变量 allowlist。生产 Kubernetes
环境更推荐把 Server 独立部署为 Streamable HTTP 服务，而不是让主 Runtime 启动大量子进程。

## 4. 工具风险映射

MCP Tool annotations 只是远端提示，不是可信授权事实：

- `readOnlyHint` 只有显式 `true` 才按只读处理；
- 未声明 `destructiveHint` 时按可能破坏处理；
- `idempotentHint` 只有显式 `true` 才按幂等处理；
- 未声明 `openWorldHint` 时按可能访问外部世界处理；
- 可写、破坏型或 open-world 工具统一映射为高风险并要求人工审批。

即使远端声明只读，permission-admin 仍可根据租户、项目、角色和 Server 单独拒绝。

## 5. tools/call 准入

`McpToolCallAdmission` 必须同时具备：

- tenantId、projectId、workspaceKey、actorId、runId、callId；
- `readinessDecision=READY`；
- `permissionGranted=true`；
- 工具位于本轮 `allowedInternalToolNames`；
- 高风险工具具备 `approvalVerified=true`。

工具参数最大 64KB，并拒绝内联 password、token、api_key、authorization 等凭据字段。凭据必须由 MCP
Server 的环境、OAuth 或 Secret Manager 注入，不能由模型生成。

## 6. 结果治理

真实工具结果分为两个视图：

- 运行时视图：`content_blocks + structured_content`，只用于本轮 Agent；
- 低敏摘要：工具名、错误状态、字节数、截断标记和 SHA-256，不含正文。

当前单次结果最大 1MB，默认 64KB。超过预算时仅保留有界文本前缀，结构化大对象不进入 Agent 上下文。
后续大结果应写入 MinIO，并只向 Agent 返回经过授权的 artifactReference。

## 7. 验证

镜像内可执行：

```powershell
docker run --rm --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m `
  -v "${PWD}:/workspace:ro" -w /workspace `
  datasmart/python-ai-runtime:local `
  python /workspace/scripts/mcp-client-smoke.py
```

生产优先的 Streamable HTTP 传输可执行：

```powershell
docker run --rm --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m `
  -v "${PWD}:/workspace:ro" -w /workspace `
  datasmart/python-ai-runtime:local `
  python /workspace/scripts/mcp-streamable-http-smoke.py
```

两个 smoke 分别启动本地 stdio 与 `127.0.0.1` Streamable HTTP Server，真实执行：

```text
initialize -> tools/list -> ToolDefinition 映射 -> admission -> tools/call
```

它不会访问公网、数据库或业务文件，也不会在输出中打印工具结果正文。

## 8. 当前边界与下一步

本批已经完成 MCP 协议连接和受控调用底座，但还没有把模型 tool call 的 READY 分支自动接入 MCP 调用：

1. 下一步把 `McpClientRuntime.call_tool()` 接到 durable Agent Loop 的工具执行节点；
2. 调用前从 Java command proposal/outbox/approval fact 构造可信 admission；
3. 调用后写 worker receipt、runtime event，并把低敏结果或 artifactReference 回填模型第二轮；
4. 增加按 Server 的连接池、并发隔离、熔断、Redis 目录缓存和 tools/list_changed 刷新；
5. 后续再实现 DataSmart 入站 MCP Server，向外部 Agent 暴露平台工具。

为了项目收敛，下一批只推进第 1-3 项的最小闭环，不同时扩展 resources、prompts、sampling、elicitation 和
MCP Marketplace。

## 9. Durable Agent Loop 执行桥

当前已新增 `McpDurableToolExecutionService`，用于把 MCP `tools/call` 接入 Agent 执行闭环的内部节点。它不是一个新的公开调用接口，而是给 LangGraph 节点、command worker 或后续 outbox consumer 复用的执行桥。

执行桥的输入是 `McpDurableToolExecutionRequest`：
- `server_id` 与 `internal_tool_name` 定位已发现的 MCP 工具；
- `arguments` 仅在本轮内存中传给 MCP SDK，不写 checkpoint、event 或 receipt；
- `McpToolCallAdmission` 必须由 Java/permission/readiness 链路形成，不能由模型或外部请求自称已授权；
- `execution_node_id` 用于挂接 LangGraph/Durable Loop 可观测节点；
- `checkpoint_id`、`command_proposal_id`、`outbox_message_id`、`trace_id` 只作为低敏关联引用。

执行桥的输出是 `McpDurableToolExecutionResult`：
- `runtime_result` 可以包含工具正文，只允许本轮 Agent 二次推理使用；
- `to_summary()` 只输出状态、错误码、结果大小、哈希、截断标记和低敏引用；
- `worker_receipt_draft` 固定了后续写 Java worker receipt 所需的最小字段，但当前版本仍不直接写 Java receipt；
- `sideEffectBoundary` 明确标记是否调用了 MCP、是否写入 Java receipt、是否持久化参数或结果正文。

状态分类：
- `SUCCEEDED`：MCP 工具真实调用成功，结果正文仅保留在运行时对象中；
- `FAILED_PRECHECK`：admission、权限、审批、allowlist、参数大小或 inline secret 等执行前条件失败；
- `FAILED_TOOL_CALL`：通过执行前检查后，远端 MCP Server 或 SDK 调用失败。

当前边界：
- 已完成 MCP Runtime 到 Durable 执行桥的代码路径；
- FastAPI `app.state.mcp_durable_tool_execution_service` 已统一装配；
- 尚未把 Java outbox consumer 自动接到该服务；
- 尚未把 `worker_receipt_draft` 提交给 `JavaCommandWorkerReceiptClient`；
- 尚未把 `runtime_result` 通过二轮模型回填服务统一消费。

下一步收敛路线：
1. 由 Java command proposal/outbox facts 生成可信 `McpToolCallAdmission`；
2. worker 消费 outbox 后调用 `McpDurableToolExecutionService.execute(...)`；
3. 调用完成后把 `worker_receipt_draft` 写回 Java agent-runtime；
4. 对可安全回填的短结果直接进入模型 tool message，对大结果写 MinIO 并只返回 artifactReference。

## 10. Java 控制面 Facts 到 Admission 的映射

当前已新增 `McpToolCallAdmissionBuilder`，用于把 Java command proposal、outbox、permission-admin、readiness graph 和审批事实转换为 `McpToolCallAdmission`。这一步非常关键，因为 admission 不能由模型、MCP 客户端或普通请求自称已经授权。

推荐 Java/Python worker 传入的低敏事实字段：
- `tenantId`、`projectId`、`workspaceKey`、`actorId`：确定租户、项目、工作区和操作者边界；
- `runId`、`callId` 或 `commandId`：确定本次 Agent run 与工具调用；
- `readinessDecision=READY` 或兼容值 `ready_to_execute`；
- `permissionGranted=true`：必须来自 permission-admin 或 Java 控制面；
- `approvalVerified=true`：高风险工具需要，最终仍由 MCP Runtime 根据工具目录判断是否必须审批；
- `allowedInternalToolNames`：本轮允许调用的 MCP 内部工具名列表；
- `commandProposalId`、`outboxMessageId`、`checkpointId`、`traceId`：低敏关联引用。

`McpDurableToolExecutionService.request_from_control_facts(...)` 会使用上述 facts 构造完整 `McpDurableToolExecutionRequest`。如果事实不完整，会抛出 `McpAdmissionBuildError`，调用方应该写失败 receipt 或进入补偿流程，而不是继续调用外部 MCP Server。

当前边界：
- facts -> admission -> durable request 已完成；
- 还没有实现 Java outbox consumer 自动投递这些 facts；
- admission builder 不读取 payloadReference 正文，也不读取工具 arguments，arguments 仍由短生命周期执行请求单独传入。

## 11. MCP Durable Worker Adapter

当前已新增 `McpDurableWorkerAdapter`，用于把“拿到一条 Java outbox/facts 后如何执行 MCP 并生成 worker receipt”固定为可测试合同。

输入 `McpDurableWorkerRunRequest`：
- `server_id`、`internal_tool_name`：定位 MCP Server 与工具；
- `arguments`：短生命周期实参，只进入 MCP SDK，不进入 receipt/event/checkpoint；
- `control_facts`：Java command proposal、outbox、permission、readiness、approval 的低敏事实；
- `fallback_context`：可选可信上下文，只用于补齐 tenant/project/workspace 等范围字段；
- `post_to_java`：是否通过现有 `JavaCommandWorkerReceiptClient` 写回 Java，默认关闭；
- `session_id`、`trace_id`：Java receipt 路由与链路追踪所需的低敏定位字段。

Worker 执行语义：
- admission 构造失败：不调用外部 MCP Server，生成 `FAILED_PRECHECK` receipt；
- MCP 执行成功：生成 `EXECUTION_SUCCEEDED` receipt，结果正文只留在 `execution_result.runtime_result`；
- MCP 执行失败：生成 `EXECUTION_FAILED` receipt，不回显远端错误正文；
- 显式开启 `post_to_java=true` 且具备 `sessionId/runId` 时，才 POST 到 Java worker receipt 接口。

Receipt 映射：
- 使用现有 `ControlledCommandWorkerReceipt` 合同，不新增临时私有 DTO；
- `targetService=python-ai-runtime-mcp-client`；
- `toolCode` 使用 `mcp.<serverId>.<toolName>` 内部工具名；
- `mcpResultSummary` 只包含结果摘要、哈希、大小、截断标记和状态，不包含工具正文；
- `artifactReference` 当前只是 `agent-artifact:<run>/<tool>/mcp-result-<digest>` 低敏占位，真实大结果落盘仍需要后续 MinIO writer。

当前边界：
- Python 侧 “facts -> admission -> durable request -> MCP execute -> receipt” 最小闭环已完成；
- Java 侧 outbox dispatcher 尚未自动调用该 adapter；
- Java receipt schema 当前复用 command worker receipt，后续可以根据产品需要新增更专用的 MCP receipt DTO；
- 模型第二轮 tool message 回填仍未消费 `execution_result.runtime_result`。

## 12. MCP Worker 结果到模型二轮 Tool Feedback

当前已新增 `McpToolFeedbackAdapter`，用于把 `McpDurableWorkerRunResult` 转换为项目统一的
`ToolExecutionFeedback`。这一层的意义是把“工具已经真实执行”推进为“模型可以基于受控工具结果继续
推理”，同时继续遵守 DataSmart 的治理边界。

输入与输出：
- 输入是 `McpDurableWorkerRunResult`，包含低敏 `ControlledCommandWorkerReceipt` 和可选
  `execution_result.runtime_result`；
- 输出是 `ToolExecutionFeedback`，可直接交给 `ModelToolResultFeedbackBuilder` 构建 OpenAI-compatible
  assistant/tool messages；
- 诊断输出 `McpToolFeedbackBuildResult.summary` 只包含状态、决策原因、大小、digest 和引用存在性，不包含
  MCP 工具正文。

安全回填规则：
- 只有 MCP 执行成功、`runtime_result.is_error=false`、结果未截断、大小小于模型反馈预算、且 content/structured
  content 未命中敏感字段或敏感文本时，才允许把短结果放入 `feedback.result`；
- 短结果使用 `agent-runtime://tool-results/{callId}` 作为上下文引用，并标记
  `output_context_policy=model_summary_allowed`，让统一 builder 继续执行 workspace 与字段级过滤；
- 大结果、截断结果、疑似敏感结果、远端错误结果和 admission/precheck 失败结果，均不会把正文放入模型；
- 这类结果只保留低敏摘要与 `artifactReference`，并标记 `output_context_policy=audit_only`；
- adapter 不读取 MinIO、不读取 Java 审计表、不反查 checkpoint，也不会从 worker receipt 中重建工具正文。

当前边界：
- Python 侧已完成“真实 MCP 执行结果 -> 统一 ToolExecutionFeedback -> 模型二轮消息构建器”的代码合同；
- Java outbox dispatcher 仍未自动调用 Python MCP worker；
- `agent-artifact:` 当前仍是低敏占位引用，真实大结果落盘仍需后续 MinIO artifact writer；
- MCP feedback adapter 当前只允许 text content block 进入模型，image/resource 等类型应先进入 artifact/resolver 链路。

下一步收敛路线：
1. 在 Java agent-runtime outbox consumer 或内部 worker API 中自动调用 Python `McpDurableWorkerAdapter`；
2. 将 MCP worker 结果通过 `McpToolFeedbackAdapter` 接入真实多步 Agent loop 的二轮模型调用；
3. 为大结果补 MinIO artifact writer 与授权读取 resolver；
4. 完成该链路后，不继续发散 MCP resources/prompts/sampling，转向全平台 Agent 闭环 smoke。

## 13. MCP Durable Worker 内部 API

当前已新增内部执行路由，用于让 Java outbox consumer、受控 worker 或后续 Kafka/gRPC adapter 触发 Python
侧 MCP durable worker：

```text
POST /internal/agent/mcp/durable-worker/run
POST /api/internal/agent/mcp/durable-worker/run
```

请求字段：
- `serverId/server_id`：目标 MCP Server ID；
- `internalToolName/internal_tool_name/toolCode`：DataSmart 内部 MCP 工具名，例如 `mcp.enterprise.search`；
- `arguments`：短生命周期 MCP 实参，只进入本轮内存执行，不进入响应 summary、event、checkpoint 或 receipt；
- `controlFacts/control_facts`：Java command proposal、outbox、permission、readiness、approval 的低敏 facts；
- `fallbackContext/fallback_context`：可选可信上下文补齐字段；
- `postToJava/post_to_java`：是否通过现有 `JavaCommandWorkerReceiptClient` 写回 Java receipt；
- `sessionId/session_id`、`traceId/trace_id`：Java 路由和链路追踪低敏字段；
- `includeModelFeedback`：是否返回 `McpToolFeedbackAdapter` 生成的模型二轮 feedback，默认开启。

响应字段：
- `workerResult`：`McpDurableWorkerRunResult.to_summary()`，不包含 arguments 或 MCP 工具正文；
- `receipt`：低敏 worker receipt summary；
- `modelFeedback`：可选的模型二轮 feedback。只有 feedback adapter 判定安全的小结果才会进入
  `modelFeedback.feedback.result`；
- `modelSecondTurn`：可选的真实二轮模型调用摘要。它只在 Python Runtime 装配
  `McpModelFeedbackSecondTurnService` 且 `includeModelFeedback=true` 时生成；二轮调用走统一
  `ModelQueryEngine`，继承模型路由、预算、限流、fallback、token-limit 和低敏 Provider 错误处理；
- `payloadPolicy`：明确标记 `MCP_ARGUMENTS_NEVER_RETURNED`。

设计边界：
- 该路由是内部执行合同，不是前端或模型直连工具的公开入口；
- 生产部署必须放在 gateway/service account/OIDC、mTLS 或等价企业内网认证之后；
- Python 仍不领取 Java outbox、不管理 visibility timeout、不做死信重试；这些仍属于 Java 控制面或后续 worker runtime；
- 当前完成的是 HTTP 调用合同和安全 `modelFeedback -> modelSecondTurn` 最小真实闭环，后续可在不改变 payload
  语义的前提下迁移到 Kafka/gRPC 或 LangGraph 节点。

下一步收敛路线：
1. 把 MCP `modelSecondTurn` 节点迁入 LangGraph PostgreSQL Durable Checkpointer，支持暂停、恢复、分支和循环；
2. 大结果接 MinIO artifact writer 与授权 resolver；
3. 完成包含 permission-admin、agent-runtime、Python Runtime 和测试 MCP Server 的真实 E2E smoke。

## 14. Java Agent Runtime MCP Durable Worker Client

当前已新增 Java 侧 `AgentMcpDurableWorkerClient` 边界，用于让后续 outbox dispatcher 以稳定接口调用 Python 内部
`/internal/agent/mcp/durable-worker/run`，而不是在 dispatcher 中直接拼 HTTP。

核心类：
- `AgentMcpDurableWorkerClientProperties`：配置开关、Python Runtime baseUrl、内部路由、连接/读取超时、是否请求模型二轮反馈、是否让 Python 直接回写 Java、服务账户认证头与令牌；
- `AgentMcpDurableWorkerRunRequest`：Java -> Python 请求合同，包含 `serverId`、`internalToolName`、短生命周期 `arguments`、可信 `controlFacts`、低敏 `fallbackContext`、`sessionId`、`traceId`、`toolCallId` 和工作区边界；
- `AgentMcpDurableWorkerRunResponse`：Python -> Java 低敏响应合同，只接收 `workerResult`、`receipt`、`modelFeedback` 与 `payloadPolicy`，不接收 MCP arguments 或工具正文；
- `AgentMcpDurableWorkerCallResult`：Java HTTP 调用视角的低敏结果，用于后续 dispatcher 判断是否重试、失败、跳过或继续写 receipt；
- `HttpAgentMcpDurableWorkerClient`：当前 HTTP 实现，使用 Spring `RestClient`，支持短超时、trace/source header、可选服务账户 token，并隐藏 URL、参数、token 和原始响应正文。

配置入口：
```yaml
datasmart:
  agent-runtime:
    mcp-durable-worker:
      enabled: false
      base-url: http://localhost:8090
      run-path: /internal/agent/mcp/durable-worker/run
      connect-timeout-ms: 1500
      read-timeout-ms: 30000
      include-model-feedback: true
      post-to-java: false
      max-resolved-arguments-bytes: 262144
      auth-header-name: Authorization
      service-account-token:
```

Compose 环境已显式设置：
- `DATASMART_AGENT_RUNTIME_MCP_DURABLE_WORKER_ENABLED=true`
- `DATASMART_AGENT_RUNTIME_MCP_DURABLE_WORKER_BASE_URL=http://python-ai-runtime:8090`
- `DATASMART_AGENT_RUNTIME_MCP_DURABLE_WORKER_POST_TO_JAVA=false`

设计边界：
- Java client 只负责受控 HTTP 通信；outbox 领取、lease、receipt、状态推进仍由 dispatcher 和控制面服务负责；
- Python 侧只返回低敏 summary/receipt/modelFeedback，不返回 MCP arguments；
- 生产部署必须把内部 API 放在 gateway/service account/OIDC、mTLS 或等价企业内网认证之后，不能作为公开工具调用接口。

## MCP 正式命令生产与参数临执行解析

MCP command 不新增一个允许调用方直接提交 `permissionGranted=true` 的自由写入口，而是复用已有生产链：

1. Java 重新执行 DAG dry-run；
2. 调用方显式选择节点并提交最新 selection fingerprint；
3. selected-node 服务验证节点仍为 `ASYNC_OUTBOX_ENQUEUE_PREVIEW`；
4. 对 MCP 节点强制要求 permission-admin 返回 `PERMISSION_ADMIN_ALLOWED`；
5. 同时要求 policyVersion、SERVICE_ACCOUNT delegationEvidence 和稳定 confirmationId；
6. Java 才派生 `readinessDecision=READY`、`permissionGranted=true`、`approvalVerified=true`；
7. command 以 `AGENT_MCP_TOOL_CALL_REQUESTED`、`datasmart.agent.mcp.commands`、
   `python-ai-runtime-mcp-client` 进入既有 durable outbox。

这条链路刻意不接受 `LOCAL_PREVIEW_ALLOWED`。本地预览只能证明授权上下文字段大体完整，不能证明企业权限中心已经允许真实外部调用。

### 为什么 outbox 不保存 arguments

正式 producer 的 payload 只保存：

- `serverId` 与 `internalToolName`；
- session/run/audit/tenant/project/workspace/actor 等隔离字段；
- confirmation、permission policy、delegation 与 readiness 低敏事实；
- `agent-tool-audit://.../plan-arguments` 引用；
- 参数名和敏感参数名，不保存参数值。

dispatcher 即将调用 Python Runtime 时，`AgentMcpCommandArgumentsResolver` 才读取审计快照，并逐项复核：

- payloadReference；
- sessionId、runId、auditId；
- toolCode；
- tenantId、projectId、workspaceId、actorId；
- 审计状态仍为 `PLANNED`；
- 参数序列化大小不超过 `max-resolved-arguments-bytes`。

任一字段不一致都会 fail-closed。解析出的 Map 只存在于当前 Java -> Python 请求生命周期，不写回 outbox、日志、runtime event 或 receipt。历史 command 的内联 `arguments/toolArguments` 暂时保留兼容读取，新的正式 producer 不再生成这种记录。

当前 MCP 控制面闭环为：

`DAG dry-run -> permission-admin -> selected-node confirmation -> durable outbox -> just-in-time arguments -> claim lease -> Python MCP tools/call -> Java receipt ingestion -> release lease -> PUBLISHED`

剩余收敛项：

1. 把已完成的安全 `modelFeedback -> modelSecondTurn` 调用迁入 LangGraph PostgreSQL Durable Checkpointer；
2. 为超大 MCP 结果接入 MinIO artifact writer 与授权 resolver；
3. 完成包含 permission-admin、agent-runtime、Python Runtime 和测试 MCP Server 的真实 E2E smoke；
4. 冻结 MCP resources/prompts/sampling 扩展，回到全项目收敛验收。

## 15. MCP Command Outbox Dispatch Target

Java agent-runtime 现已新增 `McpAgentAsyncTaskCommandDispatchTarget`，把 MCP command 接入已有
`AgentAsyncTaskCommandOutboxDispatcher`，不再另建一套重试状态机。

路由规则：
- `toolCode` 以 `mcp.` 开头时识别为 MCP command；
- 或 `targetService/consumerService=python-ai-runtime-mcp-client` 时识别为 MCP command；
- `AgentAsyncTaskCommandDispatchTarget` 新增 `supports(record)`，dispatcher 只调用匹配当前记录的 target；
- task-management HTTP/Kafka target 会排除 MCP command，避免同一命令被错误广播到任务中心。

请求映射：
- `serverId` 优先读取 payload 的 `serverId/mcpServerId`，否则从 `mcp.<serverId>.<toolName>` 解析；
- `arguments/toolArguments` 仅作为短生命周期 JSON object 传给 Python，不进入 Java 调用结果或异常；
- `tenantId/projectId/workspaceKey/actorId/runId/callId/commandId/auditId/outboxMessageId` 来自 outbox record 和白名单字段；
- `allowedInternalToolNames` 固定为当前内部工具名，避免 worker 扩大本轮工具权限；
- `permissionGranted/approvalVerified` 只读取显式布尔事实，不根据 confirmationId 猜测；
- 只有 Java dispatcher pre-check 已开启并通过时，缺失的 readiness 才补为 `READY`；
- payload 其他字段不会被整体复制到 `controlFacts`。

状态语义：
- Python API `accepted=true` 且响应声明 `MCP_ARGUMENTS_NEVER_RETURNED` 后，target 正常返回，dispatcher 标记 outbox 为 `PUBLISHED`；
- client 关闭、HTTP 失败、Python 未接受、响应缺少安全策略声明时，target 抛出低敏异常；
- dispatcher 继续复用既有 `FAILED -> backoff retry -> BLOCKED -> 人工 requeue/dead-letter/ignore` 流程；
- Python 返回的 FAILED_PRECHECK/EXECUTION_FAILED receipt 尚未在本阶段写入 Java receipt index，下一阶段单独完成映射与幂等写入。

## 16. MCP Worker Lease 与 Java Receipt 写回闭环

MCP command 现在已完成受控执行回执闭环：

```text
outbox PUBLISHING
  -> Java claim command worker lease
  -> lease facts 注入 Python controlFacts
  -> Python MCP admission/tools/call
  -> Python 生成低敏 receipt + javaReceiptPayload
  -> Java receipt service 校验 fencing/status/artifact
  -> runtime event projection + receipt index 幂等物化
  -> Java release lease
  -> dispatcher 标记 PUBLISHED
```

关键安全设计：
- fencing token 只由 Java `AgentCommandWorkerLeaseService` 签发，Python 不生成也不修改；
- Python `receipt.to_summary()` 继续隐藏 token，只显示 `fencingTokenPresent=true`；
- 内部 API 新增 `javaReceiptPayload`，仅用于 Java service-to-service receipt 写回，其中不包含 MCP arguments、工具结果正文、远端 endpoint、prompt 或 SQL；
- Java `AgentMcpWorkerReceiptIngestionService` 只做字段白名单映射，最终仍调用现有 `AgentToolActionCommandWorkerReceiptService`；
- 真实执行回执必须通过 session/run/command/executor/token/version/expiresAt 一致性校验；
- receipt 写入失败、lease 不可获取、Python 调用失败或 lease 释放失败都会让 dispatcher 把 outbox 写回 FAILED 并退避重试；
- receipt identity/idempotencyKey 保证重试不会重复污染 timeline 与 receipt index。

完成该阶段后，`PUBLISHED` 不再只代表 Python HTTP 200，而代表 Python 已处理且 Java 已接受并物化 worker receipt。

当前剩余边界：
- 尚缺正式 MCP command producer，把 Agent tool plan/command proposal 转为带显式 permission/approval/readiness 的 MCP outbox payload；
- `modelFeedback` 已能通过 Python Runtime 的 `McpModelFeedbackSecondTurnService` 进入受治理二轮模型调用，
  但尚未迁入 LangGraph PostgreSQL Durable Checkpointer 节点；
- `agent-artifact:` 仍是低敏占位引用，大结果尚未由 MinIO writer 真正落盘。
