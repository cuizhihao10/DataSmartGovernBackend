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
