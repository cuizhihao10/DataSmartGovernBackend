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
