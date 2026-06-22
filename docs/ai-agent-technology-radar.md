# DataSmart Govern AI Agent 技术雷达
## 2026-06-22 落地补充：OpenAI-compatible Provider errors must be governance facts, not echoed payloads

- 本轮趋势核验：
  - vLLM 持续提供 OpenAI-compatible server，覆盖 Chat Completions 等 API，适合作为 DataSmart 自托管推理入口之一；
  - SGLang 同样提供 OpenAI-compatible API，并继续扩展 tool choice、reasoning parser、structured output 等 Agent 关键能力；
  - LiteLLM 的健康检查驱动路由强调在用户请求命中前主动剔除不健康部署，这与 DataSmart 现有 provider health / fallback / active probe 方向一致。
- 本轮落地到代码的能力：
  - 新增 `model_provider_error_sanitizer.py`；
  - `OpenAICompatibleModelProvider` 的 HTTP、网络、超时、流式解析错误不再回显上游原始正文；
  - 非流式 `ModelInvocationResult.content` 与流式 `ModelInvocationChunk.content_delta` 只返回稳定错误码对应的低敏中文说明；
  - 保留 `error_code`、providerName、modelName、latencyMs 等治理事实，继续支持预算、健康台账、fallback 和运维诊断。
- 低敏与安全边界：
  - 不读取或回显 HTTPError response body；
  - 不返回 URL、query、API Key、内部 endpoint、prompt、messages、toolArguments、SQL、样本数据、模型输出或上游异常栈；
  - 上游原始错误正文应进入受控日志或供应商网关排障链路，不应进入 Agent runtime event、前端实时输出或 Java 控制面投影。
- 产品判断：
  - 当前模型层路线继续保持“成熟推理服务 + 可替换 Provider + 治理型模型网关”，不做自研推理内核、微调或后训练；
  - 接下来如果继续模型网关主线，优先补真实 provider 调用的观测与安全闭环，例如调用审计、fallback 事件、provider quality score、KV/prefix cache 命中率，而不是追逐单个模型家族；
  - 为避免局部发散，模型网关完成错误低敏和健康闭环后，应尽快转向 Agent runner 真实编排或智能网关执行闭环。
- 参考资料：
  - vLLM OpenAI-Compatible Server: `https://docs.vllm.ai/en/latest/serving/online_serving/openai_compatible_server/`
  - SGLang OpenAI APIs: `https://docs.sglang.ai/basic_usage/openai_api_completions.html`
  - SGLang Tool Parser: `https://docs.sglang.ai/advanced_features/tool_parser.html`
  - LiteLLM Health Check Driven Routing: `https://docs.litellm.ai/docs/proxy/health_check_routing`

## 2026-06-19 落地补充：protocol adapters need one internal resume-fact DTO contract

- 本轮趋势判断：
  - MCP `tools/call`、A2A task/action 和模型原生 tool_call 都在把“外部工具意图”变成更标准化的协议输入；但对 DataSmart 来说，真正商业化的关键不是多开几个入口，而是让所有入口进入同一套 `ToolPlan -> readiness -> resume gate -> outbox/worker` 控制流。
  - LangGraph/OpenAI Agents 类 HITL/interrupt/resume 模式强调 checkpoint state 与 host-controlled facts 分层。映射到 DataSmart，Python checkpoint 负责图状态，Java host facts 负责审批、澄清、outbox、receipt 和恢复门控验真。
  - 因此本阶段优先做结构解耦：把恢复事实查询 DTO 独立成共享契约，让 fact bundle、gate graph、后续 MCP/A2A adapter 和 runner 都复用同一套低敏定位规则。
- 本轮落地到代码的能力：
  - 新增 `bootstrap_env.py`，统一 Agent API 启动期环境变量解析；
  - 新增 `resume_fact_provider_factory.py`，把恢复事实 provider 装配从 `orchestrator_factory.py` 拆出；
  - 新增 `tool_action_resume_fact_bundle_payload.py`，统一构造 Java 恢复事实查询 DTO；
  - `tool_action_resume_gate_graph_client.py` 直接调用 payload builder，不再实例化旧 fact bundle client；
  - `tool_action_resume_fact_bundle_client.py` 从 550 行降到 317 行，职责收敛为 HTTP 调用、Header、响应解析和 fail-closed。
- 低敏与安全边界：
  - 共享 DTO 只携带 checkpoint/thread/run/session/command/outbox、approval/clarification fact id、toolCode、policyVersion、tenant/project/actor 和 requiredFactTypes；
  - 不携带 prompt、messages、SQL、arguments、payload body、样本数据、模型输出、凭证、token 或内部 endpoint；
  - 该 DTO 是 Java 控制面查询线索，不是 Python 采信调用方自报事实的授权依据。
- 产品判断：
  - 这是“看起来不酷但非常关键”的商业化步骤：先把内部控制面契约做稳，再继续接 MCP/A2A/模型工具调用和真实 runner；
  - 后续新增协议入口时，不应绕过 `ToolActionIntakeService`、readiness、resume gate 和 Java host facts；
  - 下一阶段应基于现有 `tool_action_control_flow.py` 做统一 adapter contract，而不是重复创建平行入口。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Tools: `https://modelcontextprotocol.io/specification/2025-06-18/server/tools`

## 2026-06-18 落地补充：Python resume-preview should consume the host gate graph before trusting local facts

- 本轮趋势核验：
  - LangGraph Persistence 把 thread checkpoint、interrupt 恢复和长期 store 区分开，强调恢复运行需要可靠的持久状态。映射到 DataSmart，Python checkpoint 只说明 Agent 图停在何处，不能证明审批、澄清、outbox 或 worker receipt 已满足。
  - OpenAI Agents SDK Human-in-the-loop 把敏感工具调用暂停为 interruption，并要求恢复时携带原 RunState 与 approve/reject 结果。映射到 DataSmart，Python resume-preview 必须优先读取 Java host-controlled gate graph，而不是相信调用方自报 fact id。
  - MCP Tools 规范继续强调工具 schema、调用边界、执行状态和外部工具发现。映射到 DataSmart，未来 MCP `tools/call`、A2A action 和模型 tool_call 都应进入统一 ToolPlan 与 host facts 链路，再由 resume gate 决定等待、阻断或进入预览。
- 本轮落地到代码的能力：
  - Python 5.86 新增 `JavaAgentRuntimeToolActionResumeGateGraphClient`；
  - `/agent/tool-actions/checkpoints/resume-preview` 的 server-side facts provider 可以优先调用 Java 5.85 `POST /agent-runtime/tool-action-resume-gates/graphs/preview`；
  - `ToolActionResumeFactSnapshot` 新增低敏 `resumeGateGraph` 摘要出口；
  - `orchestrator_factory` 的 provider 装配顺序调整为 gate graph -> fact bundle -> permission-admin；
  - 新增客户端测试和启动装配测试，覆盖默认关闭、READY 图解析、Java 缺失事实覆盖请求自报事实、敏感字段不回显。
- 低敏与安全边界：
  - Python 只暴露 graphState、terminalState、resumePreviewReady、事实类型集合、低基数计数和 recommendedActions；
  - 不暴露 Java requestedLocator、nodes/edges 原文、approvalFactId、clarificationFactId、outboxId、payloadReference、SQL、prompt、arguments、样本数据、模型输出、token 或内部 endpoint；
  - Java gate graph 不可用时采用 fail-closed：返回缺失/拒绝事实和低敏错误码，不把远程异常正文透传给调用方。
- 产品判断：
  - DataSmart 的 Agent Host 能力从“Java 有恢复图、Python 仍本地判断”推进为“Python 预检真实消费 Java 控制面图”；
  - 这更接近 Codex/Claude Code/OpenAI Agents/LangGraph 类 Agent 的 HITL 恢复思想：用户补事实、宿主验真、运行时只做恢复预览；
  - 下一阶段应把同一条链路扩展到 MCP/A2A/model tool_call adapter 和 OpenClaw-style runner，而不是继续围绕单个 provider 堆字段。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Tools: `https://modelcontextprotocol.io/specification/2025-06-18/server/tools`

## 2026-06-18 落地补充：resume should be a host-controlled gate graph, not a self-reported flag

- 本轮趋势核验：
  - LangGraph Persistence 将 checkpointer 用于 thread-scoped graph state，同时通过持久化能力支持中断恢复、失败恢复和 time travel。映射到 DataSmart，Python checkpoint 只能说明“运行停在哪里”，不能单独证明审批、澄清、outbox 或 worker receipt 已经满足。
  - OpenAI Agents SDK Human-in-the-loop 将敏感工具调用暂停为 interruption，并在 approve/reject 后用保存的 RunState 恢复。映射到 DataSmart，恢复前必须有显式 resume gate，不能让调用方自报 approvalFactId、commandId 或 outboxId 后直接继续。
  - MCP Tools/Authorization 说明外部工具调用需要 schema、授权和用户确认等边界。映射到 DataSmart，MCP `tools/call`、A2A action 和模型 tool_call 都应先进入 host-level facts 与 gate graph，再决定审批、澄清、入箱、等待 receipt 或阻断。
- 本轮落地到代码的能力：
  - Java 5.85 新增 `AgentToolActionResumeGateGraphPreviewController`；
  - 新增 `AgentToolActionResumeGateGraphPreviewService`，复用恢复事实包服务作为唯一事实来源；
  - 新增 `AgentToolActionResumeGateGraphBuilder`，将 fact bundle 转为 `checkpoint locator -> security scope -> fact gates -> resume gate`；
  - 新增 `AgentToolActionResumeGateGraphView` 和 `AgentToolActionResumeGateGraphQueryResponse`，提供图状态、节点/边、低基数计数、缺失事实和推荐动作。
- 低敏与安全边界：
  - 图只返回事实类型、状态、布尔存在性、低敏 evidence/issue code、低基数计数和中文解释；
  - 不返回 approvalFactId、clarificationFactId、outboxId 原文、payloadReference、服务间命令体、targetEndpoint、prompt、SQL、工具参数、样本数据、模型输出、凭证或内部服务响应正文；
  - `READY_FOR_RESUME_PREVIEW` 只表示可以进入 Python resume-preview，不表示真实工具已经执行。
- 产品判断：
  - 这是把 DataSmart 从“有工具事实列表”推进到“有 host-controlled resume gate graph”的阶段性节点；
  - 下一步不应继续只给 Java 视图加字段，而应让 Python Runtime/智能网关真实消费该门控图；
  - 真正商业化执行还需要 service-account 签名或 mTLS、budget/backlog 实时策略、durable audit event store、worker receipt 的真实副作用回执和 artifact 二次鉴权。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Tools: `https://modelcontextprotocol.io/specification/2025-11-25/server/tools`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-18 落地补充：HITL clarification should be visible as low-sensitive timeline events

- 本轮趋势验证：
  - LangGraph Persistence 将 checkpointer 用于 thread-scoped graph state，将 store 用于跨 thread durable data；映射到 DataSmart，澄清事实既要能作为 durable host fact 保存，也要能被 timeline/replay 安全观察。
  - OpenAI Agents SDK Human-in-the-loop 将工具审批建模为 interruption、RunState 序列化和 approve/reject 后 resume；映射到 DataSmart，用户澄清事实需要有状态演进事件，才能让长时间 pending 的恢复窗口可排障。
  - MCP Authorization 强调资源绑定、scope 和最小权限；映射到 DataSmart，澄清事实事件即使写入 timeline，也必须只暴露低敏状态和范围摘要，不允许暴露 factId 原文或用户补充正文。
- 本轮落地到代码的能力：
  - Java 5.84 新增 `AgentToolActionClarificationFactEventPublisher`；
  - 新增 `agent.tool_action.clarification_fact.recorded` 低敏 runtime event；
  - 新增 `AgentToolActionClarificationFactEventDisplayBuilder`，将事件解释为 Human-in-the-loop 澄清事实卡片；
  - `AgentToolActionClarificationFactRegistrationService` 在 Store upsert 成功后发布事件，但发布失败不阻断登记主流程；
  - `AgentRuntimeEventDisplaySupport` 已接入该 eventType。
- 低敏与安全边界：
  - 事件只保存 status、available/expired、run/session/command 是否存在、toolCode、policyVersion、evidence/issue code 计数和 securityBoundary 摘要；
  - 不保存 clarificationFactId 原文、用户澄清正文、prompt、SQL、arguments、payload、样本数据、模型输出、凭证、token、内部 endpoint 或工具结果正文；
  - display 文案不能作为自动执行依据，真实恢复仍必须读取稳定 attributes、permission-admin 策略、outbox、worker receipt 和审计事实。
- 产品判断：
  - 这一步补的是 Agent Host 的“可解释暂停点”能力，而不是继续开放真实工具执行；
  - 澄清事实从 durable store 进入 timeline 后，管理员可以按 run/session 观察 AVAILABLE/REVOKED/REJECTED/EXPIRED 状态；
  - 下一步应转向低基数指标、TTL/归档和 OpenClaw-style execution graph 条件节点，避免继续只围绕澄清事实局部打磨。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-18 落地补充：Human clarification facts must survive long-running resume windows

- 本轮趋势核验：
  - LangGraph Persistence 将 checkpointer 与 store 区分为 thread state 与跨 thread durable data，并强调恢复中断、故障恢复和跨交互记忆需要持久化。映射到 DataSmart，用户澄清事实属于 Java host facts，不能只保存在 Python 图状态或单 JVM memory store。
  - OpenAI Agents SDK Human-in-the-loop 文档说明敏感工具调用可以暂停等待审批，`RunState` 可序列化并在决策后恢复；同时提醒长时间 pending task 需要版本标记。映射到 DataSmart，clarification fact 必须保存 requestedPolicyVersion、expiresAt 和状态，防止旧澄清跨策略复用。
  - MCP Authorization 强调最小权限、资源绑定和 token audience 校验。映射到 DataSmart，澄清事实即使按 factId 找到，也必须继续做 tenant/project/actor/run/session/command/tool 范围验真，不能让外部协议层用 factId 绕过 Java 控制面。
- 本轮落地到代码的能力：
  - Java 5.83 新增 `JdbcAgentToolActionClarificationFactStore` 和 `JdbcAgentToolActionClarificationFactRecordMapper`；
  - 新增 `agent_tool_action_clarification_fact` MySQL migration；
  - 新增 `clarification-fact-store=memory/mysql` 配置，内存和 MySQL Store 条件化注册；
  - fact bundle 的 `productionReadiness` 会展示当前 clarification fact store 是否已经是 MySQL durable 模式。
- 低敏与安全边界：
  - 表和 JDBC Store 只保存 factId、run/session/command/tool、policyVersion、tenant/project/actor、status、低敏 evidence/issue code、expiresAt 和时间戳；
  - 不保存用户澄清原文、prompt、SQL、arguments、payload body、样本数据、模型输出、凭证、token、内部 endpoint 或工具结果正文；
  - Store 只按 factId 读取记录，evaluator 继续统一执行范围验真，避免 memory/mysql 两套实现安全语义漂移。
- 产品判断：
  - 这是把 HITL clarification 从“请求体/内存态”推进到 durable host fact 的关键一步；
  - 它仍不是开放真实 resume 的信号。真实副作用执行仍必须等待 execution graph、审批、outbox、worker receipt、配额和审计闭环；
  - 下一阶段应补低敏 runtime event、TTL/归档和指标，然后进入 OpenClaw-style execution graph。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-18 落地补充：Tool-call host facts need low-sensitive operator query surfaces

- 本轮趋势核验：
  - LangGraph Persistence 明确把 checkpointer 用于短期 thread state，把 store 用于跨 thread 的长期应用数据，并强调中断恢复、失败恢复和跨交互记忆都依赖持久化状态。映射到 DataSmart，worker receipt 这类 host fact 不应只存在于内部索引，也应有安全查询面用于恢复前排障。
  - OpenAI Agents SDK Tracing 把 agent run、LLM generation、tool call、handoff、guardrail 等都纳入 trace/span，并专门提醒函数工具输入输出可能包含敏感数据，需要控制是否采集。映射到 DataSmart，工具 receipt 查询必须低敏化，不能把 message、payload、SQL、prompt 或工具参数带到运维接口。
  - MCP Tools 规范持续强调 tool 的 `inputSchema`、`outputSchema`、annotations 和 execution/taskSupport。映射到 DataSmart，未来 MCP `tools/call` 进入平台后，应先转成 DataSmart ToolPlan + readiness/outbox/receipt host facts，而不是绕过 Java 控制面直接执行。
- 本轮落地到代码的能力：
  - Java 5.82 新增 `AgentToolActionWorkerReceiptIndexController`，提供按 `commandId` 查询 worker receipt 低敏索引的只读 API；
  - 新增 `AgentToolActionWorkerReceiptIndexQueryService`，把请求参数与 gateway/permission-admin Header 数据范围求交集；
  - 新增 query/view DTO，只返回 commandId、scope、toolCode、taskStatus、outcome、preCheckPassed、sideEffectExecuted、errorCode、replaySequence 和时间戳；
  - eventIdentityKey 只返回 SHA-256 短指纹，不返回原文；
  - `commandId` 必填，PROJECT 空授权返回空结果，显式越权项目返回 `TENANT_SCOPE_DENIED`。
- 产品判断：
  - 这是“可恢复 Agent Host”的运营接口，而不是“真实工具恢复执行”接口；
  - 它让管理员和智能网关能看到 receipt 是否存在、是否被执行前阻断、是否可能需要补审批/澄清/预算，而不泄露工具上下文；
  - 下一步应继续补 TTL/归档、低基数指标和 clarification durable store，然后再进入 OpenClaw-style execution graph。
- 后续趋势落地建议：
  1. 把 worker receipt query 接入 Micrometer，暴露 `records_found/not_found/scope_empty/denied` 这类低基数指标。
  2. 将 clarification fact 持久化，并像 receipt 一样提供低敏查询与 timeline 状态。
  3. 把 MCP tool definition 的 input/output schema、tool annotations 和 DataSmart tool catalog 对齐，形成统一 ToolPlan contract。
  4. 将 LangGraph checkpointer/store 的思想映射到 Java host facts 与 Python graph state 的双层持久化：Python 保存图状态，Java 保存企业控制面事实。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Tracing: `https://openai.github.io/openai-agents-python/tracing/`
  - MCP Tools: `https://modelcontextprotocol.io/specification/2025-11-25/server/tools`

## 2026-06-18 落地补充：Worker receipt indexes need durable host storage before real resume

- 本轮趋势延续：
  - LangGraph/OpenAI Agents 类 HITL/interrupt/resume 模式都在强调“暂停点恢复依赖 durable state”，因此 worker receipt 不能长期停留在单 JVM 内存索引里；
  - MCP/A2A/模型 tool_call 等多入口工具意图最终都需要同一套宿主事实链路，receipt 查询必须可审计、可重放、可按租户/项目/actor 范围验证；
  - 对 DataSmart 来说，durable receipt index 不是为了更快开放副作用执行，而是为了让恢复前置条件能够在服务重启、多实例部署和审计排障中保持可信。
- 本轮落地到代码的能力：
  - Java 5.81 新增 `JdbcAgentToolActionWorkerReceiptIndexStore` 和 `JdbcAgentToolActionWorkerReceiptIndexRecordMapper`；
  - 新增 `agent_tool_action_worker_receipt_index` MySQL migration，建立 eventIdentityKey 幂等唯一索引和 commandId/tenant/project/run/session/replaySequence 组合索引；
  - 新增 `worker-receipt-index-store=memory/mysql` 配置，内存和 MySQL Store 条件化注册，避免本地学习模式误连数据库；
  - fact bundle 的 `productionReadiness` 会展示当前 worker receipt index 是否已经是 MySQL durable 模式。
- 低敏与安全边界：
  - MySQL 表和 JDBC Store 只保存 commandId、run/session、tenant/project/actor、toolCode、taskStatus、outcome、preCheckPassed、sideEffectExecuted、errorCode、replaySequence 和时间戳；
  - 不保存 receipt message、payload、prompt、SQL、工具参数、样本数据、模型输出、凭证、token、内部 endpoint 或工具结果正文；
  - 查询将 authorizedProjectIds、tenantId、projectId、actorId、runId、sessionId 和 toolCode 下沉到 SQL，避免先取回越权记录再在 JVM 过滤。
- 产品判断：
  - 这是从 memory host fact 迈向 durable host fact 的关键一步，更接近 Codex/Claude Code 类 Agent Host 的恢复控制面；
  - 它仍然不是“真实 resume 已开放”的信号。真实副作用执行仍必须等待审批、澄清、outbox、worker receipt、配额、服务账号认证、审计和运维补偿闭环；
  - 下一阶段不宜继续无限细化 receipt 字段，应补 TTL/归档、管理员低敏查询、Micrometer 指标，随后转向 OpenClaw-style execution graph 条件节点。
- 后续趋势落地建议：
  1. 将 clarification fact 也升级为 MySQL durable host fact，并登记低敏状态事件。
  2. 把 readiness、approval、budget、locator、clarification、outbox、receipt 统一建模为 execution graph 的条件节点。
  3. 将 MCP `tools/call`、A2A action 和模型 tool_call 都收束到同一组 host facts，避免协议适配层各自实现执行前治理。
- 参考资料：
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-17 落地补充：Worker receipts should become indexed host facts, not only timeline events

- 本轮趋势核验：
  - LangGraph Interrupts 强调 checkpoint/thread 是恢复执行的持久游标，并提醒生产环境使用 durable checkpointer。映射到 DataSmart，resume gate 不能每次临时扫描事件窗口，而应把恢复依赖事实索引化。
  - OpenAI Agents SDK Human-in-the-loop 把工具审批、pending interruption、state 持久化和 resume 连接在一起。映射到 DataSmart，worker/dry-run receipt 是恢复前的宿主事实之一，应能被 Java host 按 commandId 验真。
  - MCP Authorization 强调受保护资源必须验证 token audience 和 scope。映射到 DataSmart，receipt 查询不能只靠 commandId 命中，还必须执行 tenant/project/actor/run/session 数据范围过滤。
- 对 DataSmart 的架构映射：
  - Java 5.80 新增 worker receipt 低敏专用索引，让 `WORKER_RECEIPT_PROJECTION` 先查 `commandId` 索引，再 fallback 到 runtime event projection；
  - receipt 接收服务和 Kafka consumer 都会幂等物化索引，避免“projection 已写入但索引未写入”的补偿缺口；
  - fact bundle 主服务不再亲自扫描 receipt projection，改为调用独立 evaluator，保持恢复事实聚合、receipt 验真和索引仓储解耦。
- 本轮落地到代码的能力：
  - 新增 `AgentToolActionWorkerReceiptIndexRecord/Query/Store` 与 `InMemoryAgentToolActionWorkerReceiptIndexStore`；
  - 新增 `AgentToolActionWorkerReceiptIndexService` 和 `AgentToolActionWorkerReceiptFactEvaluator`；
  - `AgentToolActionControlledDryRunReceiptService` 与 `AgentRuntimeEventConsumerService` 接入 receipt index materialization；
  - `AgentToolActionResumeFactBundleService` 主服务从 457 行降到 392 行；
  - 新增/扩展测试覆盖索引物化、脱敏、projection fallback、跨项目隐藏和 receipt 接收入口索引写入。
- 产品判断：
  - 这不是单纯的性能优化，而是把“恢复执行前的 worker 证据”提升为 host-level indexed fact；
  - 当前 memory index 仍不能替代生产持久化，下一阶段必须补 MySQL durable index、TTL/归档、管理员查询和低基数指标；
  - 真正工具 resume 仍应等待 outbox、worker receipt、审批、clarification、服务账号签名/mTLS、租户配额和 durable audit store 闭环。
- 后续趋势落地建议：
  1. 设计 MySQL durable worker receipt index，按 commandId + tenant/project/run/session + replaySequence 建索引。
  2. 把 worker receipt、clarification fact、approval fact、outbox confirmation 统一建模为 OpenClaw-style resume gate 的条件节点。
  3. 将 MCP tools/call、A2A task action、模型 tool_call 的最终执行都收束到同一组 host facts，而不是各协议各自扫描事件。
- 参考资料：
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-17 落地补充：Human clarification should become host-verifiable resume facts

- 本轮趋势核验：
  - LangGraph Interrupts 官方文档把 HITL 建模为“图执行暂停、checkpointer 保存状态、同一 thread 后续 resume”，并强调生产环境应使用 durable checkpointer；这说明用户补充信息不能只停留在一次 HTTP 请求里，而应成为宿主控制面可验证的恢复事实。
  - OpenAI Agents SDK Human-in-the-loop 把敏感工具调用拆成 pending approval、decision 和恢复运行状态，提示 DataSmart 的工具恢复也应由 Java host 汇总事实，而不是让 Python Runtime 或外部 Agent 自报已经满足条件。
  - MCP Authorization 把受保护资源访问放在授权边界内；映射到 DataSmart，`tools/call`、A2A action、OpenClaw graph node 和模型 tool call 都必须经过租户/项目/actor/策略范围校验，不能靠一个 factId 字段绕过控制面。
- 对 DataSmart 的架构映射：
  - Java 5.79 新增服务端 `CLARIFICATION_FACT` 控制面，让用户澄清从“请求体字段”升级为“可登记、可过期、可撤销、可验范围的低敏事实”；
  - 登记 API 只接收 factId、run/session/command/tool/policy、租户/项目/actor 和低敏枚举码，不保存用户澄清原文；
  - fact bundle 查询会在恢复预检时回查澄清事实，并对跨租户/跨项目/跨 actor/跨 run/session/command/tool 的记录统一隐藏为 not found or not visible；
  - 过期、撤销、拒绝和策略版本漂移会进入 `REJECTED`，不会被 Python Runtime 误判为可恢复。
- 本轮落地到代码的能力：
  - 新增 `AgentToolActionClarificationFactController` 与登记 DTO/View；
  - 新增 `AgentToolActionClarificationFactRecord`、`AgentToolActionClarificationFactStore`、`InMemoryAgentToolActionClarificationFactStore`；
  - 新增 `AgentToolActionClarificationFactRegistrationService` 与 `AgentToolActionClarificationFactEvaluator`；
  - `AgentToolActionResumeFactBundleService` 接入 evaluator，`CLARIFICATION_FACT` 不再固定缺失；
  - 新增 evaluator/registration/fact bundle 集成测试，定向测试 15 个通过。
- 产品判断：
  - 这一步不是继续堆 Java 局部字段，而是在补 Codex/Claude Code/OpenAI Agents/LangGraph 类 Agent Host 的 HITL 安全恢复基础；
  - memory store 只解决本地闭环，商业化必须继续升级为 MySQL durable store、TTL/归档、管理员查询、低基数指标和审计导出；
  - 真实 resume 仍应等待 worker receipt persistent index、outbox 幂等、服务账号签名/mTLS、租户配额和 durable audit store 闭环。
- 后续趋势落地建议：
  1. 把 `CLARIFICATION_FACT` 也写成低敏 runtime event，便于管理员看到“用户已补充、已过期、已撤销”的状态演进。
  2. 设计 MySQL durable clarification fact table：factId 唯一索引、tenant/project/run/session/command/tool 组合索引、expiresAt 清理索引、状态变更审计。
  3. 把 LangGraph/OpenAI HITL 的 approval/edit/respond 模式映射为 DataSmart 的 approval fact、clarification fact、task draft edit fact 和 outbox confirmation fact。
  4. 把 MCP Authorization 的 resource/scope 思路继续映射到 DataSmart tool plan，保证外部协议层永远不能绕过 Java host 的事实验证。
- 参考资料：
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-17 落地补充：Resume fact checks should be replayable diagnostics before real tool resume

- 本轮趋势核验：
  - LangGraph 官方文档持续强调 thread/checkpoint、interrupt 和持久化对长任务、HITL 与故障恢复的重要性；对 DataSmart 来说，checkpoint/thread 不能只当作一次性请求字段，而应该进入宿主控制面可回放状态。
  - OpenAI Agents SDK Human-in-the-loop 把敏感工具调用建模为暂停、审批、序列化运行状态和恢复；这说明“恢复前事实验真”本身就是 Agent Host 的核心治理面，而不是 Python Runtime 的本地判断。
  - MCP Authorization 规范把受保护资源/工具访问放在授权边界下；DataSmart 映射到企业产品时，MCP tools/call、A2A action 或模型 tool_call 都不能绕过 Java host 的事实索引、审批、outbox 和 receipt 验真。
- 对 DataSmart 的架构映射：
  - Java 5.76/5.77 已把 checkpoint/thread 到 command/outbox/approval/clarification/tool/policy 的 locator index 做成 memory/mysql 可替换控制面索引；
  - Java 5.78 新增恢复事实包诊断事件，把 `locatorIndexHit`、`missingFactTypes`、`rejectedFactTypes`、`securityBoundary` 摘要写入 runtime event timeline；
  - 这让“暂停点能不能恢复、为什么不能恢复、缺哪个服务端事实”从单次响应升级为可回放诊断。
- 本轮落地到代码的能力：
  - 新增 `AgentToolActionResumeFactBundleDiagnosticPublisher`，写入 `agent.tool_action.resume_fact_bundle.diagnostics_recorded`；
  - 新增 `AgentToolActionResumeFactBundleDiagnosticEventDisplayBuilder`，展示 `REJECTED_BEFORE_RESUME`、`WAITING_RESUME_FACTS`、`FACTS_READY_FOR_PREVIEW_ONLY`；
  - 新增 `diagnostic-event-enabled` 配置开关；
  - `productionReadiness` 动态展示 memory/mysql locator index 与诊断事件模式；
  - `AgentToolGuardrailEventDisplayBuilder` 拆出 guardrail 展示逻辑，保持 display support 低耦合。
- 产品判断：
  - 这一步不是“继续堆 Java 小功能”，而是在为 Codex/Claude Code/OpenAI Agents/LangGraph 类 Agent Host 的真实暂停-审批-恢复链路补控制面可见性；
  - timeline 诊断事件仍是 metadata-only，不代表真实工具恢复已开放；
  - 真正开放 resume 前还需要 clarification fact store、worker receipt persistent index、服务账号签名/mTLS、租户配额和 durable audit store。
- 后续趋势落地建议：
  1. 把 clarification fact store 做成可验证事实源，避免用户补充信息长期停留在请求体自报。
  2. 把 worker receipt 从通用 runtime event 热窗口提升为可索引持久事实。
  3. 把 readiness、approval、budget、locator index、fact bundle、receipt 建模为 OpenClaw-style execution graph 条件节点。
  4. 在模型/工具网关侧继续跟进 MCP Tasks、HITL approval、agent memory 与长任务 checkpoint 的协议演进，但只吸收能增强 DataSmart 租户安全、审计和恢复能力的部分。
- 参考资料：
  - LangGraph Memory/Checkpoint 概念: `https://docs.langchain.com/oss/python/concepts/memory`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-17 落地补充：Checkpoint/thread locator index should become durable host control-plane state

- 本轮趋势核验：
  - LangGraph Persistence 明确把 checkpointer 定位为 thread-scoped graph state，用于对话连续性、HITL、time travel 和 fault tolerance；这说明 checkpoint/thread 不只是短期响应字段，而是恢复入口。
  - OpenAI Agents SDK Human-in-the-loop 将敏感工具调用建模为暂停、返回 interruption、序列化 RunState、审批后恢复；这要求宿主侧能从暂停点稳定找到审批、outbox、receipt 等服务端事实。
  - MCP Authorization 将受保护工具和资源访问纳入授权资源服务器边界；DataSmart 映射到产品能力时，外部 Agent 或协议 body 自报 locator 不应成为唯一事实来源，Java host 必须持久化并验真控制面定位符。
- 对 DataSmart 的架构映射：
  - Java 5.76 已完成内存版 checkpoint/thread locator index；
  - Java 5.77 新增 MySQL durable locator index，把 checkpointId/threadId 到 command/outbox/approval/clarification/tool/policy 的映射落到 `agent_tool_action_resume_locator_index`；
  - `locator-index-store=memory/mysql` 支持灰度切换，`database-enabled=true` 作为数据库链路总开关，避免本地开发被 MySQL 强耦合；
  - 该索引继续坚持 metadata-only/low-sensitive 策略，不保存 prompt、SQL、arguments、payload、模型输出、样本数据、密钥或内部 endpoint。
- 本轮落地到代码的能力：
  - 新增 `JdbcAgentToolActionResumeLocatorIndexRecordMapper` 与 `JdbcAgentToolActionResumeLocatorIndexStore`；
  - 新增 MySQL migration `20260617_agent_tool_action_resume_locator_index.sql`；
  - `AgentRuntimeJdbcPersistenceConfiguration` 纳入 locator index 的 MySQL 启用条件；
  - `InMemoryAgentToolActionResumeLocatorIndexStore` 改为仅在 memory 模式注册，防止双 Store Bean；
  - 新增 JDBC Store 单测，`agent-runtime` 全量测试 286 个通过。
- 产品判断：
  - 这是从“可恢复预检”走向“可恢复控制面事实库”的关键一步；
  - durable locator index 仍然只是恢复前置索引，不是工具执行器，也不是 checkpoint 正文库；
  - 不应因为有了 MySQL locator index 就直接开放真实 resume，下一步还需要 timeline/diagnostics、clarification fact store、worker receipt persistent index、服务账号签名和租户级配额。
- 后续趋势落地建议：
  1. 将 checkpoint securityBoundary、locatorIndexHit、missing/rejected fact 接入 Java timeline/diagnostics。
  2. 补 clarification fact store，让“用户补充信息”也成为服务端可验证事实。
  3. 补 worker receipt persistent index，减少对通用 runtime event projection 热窗口扫描的依赖。
  4. 设计 OpenClaw-style execution graph 条件节点，把 readiness、approval、budget、locator index、receipt 和 checkpoint 作为显式图条件，而不是让真实工具执行提前绕过控制面。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-17 落地补充：Checkpoint/thread should resolve host-side resume facts

- 本轮趋势核验：
  - LangGraph Persistence 把 checkpointer 定位为 thread 级 graph state，用于 conversation continuity、HITL、
    time travel 和 fault tolerance；这说明 checkpoint/thread 应该成为恢复入口，而不是只作为一次性响应字段。
  - OpenAI Agents SDK Human-in-the-loop 把敏感工具调用建模为暂停、返回 interruption、序列化 RunState、审批后恢复；
    这提醒 DataSmart 的恢复链路需要“从暂停状态找到审批/outbox/receipt 事实”，不能要求调用方每次重传全部事实定位符。
  - MCP Authorization 把受保护工具/资源访问建模为授权资源服务器请求；DataSmart 映射到产品能力时，需要由 Java host
    控制面补齐 resource/scope/approval/outbox/receipt 事实，而不是让外部 Agent body 自报。
- 对 DataSmart 的架构映射：
  - Python 5.75 已为 checkpoint query/resume-preview 增加可配置 gateway HMAC 保护；
  - Java 5.76 新增 checkpoint/thread 到 command/outbox/approval/clarification/tool/policy 的内存 locator index；
  - fact bundle 查询现在可以先学习 Python 派生的低敏 locator hints，再在后续 checkpoint-only 查询中补齐缺失字段；
  - 访问范围仍由 tenant/project/actor/run/session/tool scoped query 限定，避免 checkpoint/thread 跨范围补齐。
- 本轮落地到代码的能力：
  - 新增 `AgentToolActionResumeLocatorIndexRecord`、`AgentToolActionResumeLocatorIndexStore`、
    `InMemoryAgentToolActionResumeLocatorIndexStore`、`AgentToolActionResumeLocatorIndexService`；
  - `AgentToolActionResumeFactBundleService` 接入 locator enrichment；
  - `requestedLocator` 新增 locatorIndexHit 和 locatorIndexEvidenceCodes；
  - 主服务从 511 行拆到 460 行；
  - `agent-runtime` 全量测试 281 个通过。
- 产品判断：
  - 这是从“恢复预检依赖 Python 重传 hints”走向“Java host 控制面可学习/补齐恢复定位”的关键一步；
  - 当前仍是内存索引，不能替代 MySQL durable projection；
  - 后续应继续把 locator index 做成可审计、可迁移、可清理、可诊断的控制面事实表。
- 后续趋势落地建议：
  1. MySQL durable locator index：checkpointId/threadId/runId/commandId/outboxId 复合索引、TTL、归档与幂等 upsert。
  2. Java timeline/diagnostics：展示 checkpoint securityBoundary、locatorIndexHit、missing/rejected fact 状态。
  3. Clarification fact store：让用户补充信息也成为可验证事实，而不是请求体自报字段。
  4. Worker receipt persistent index：从 runtime event projection 走向更稳定的 receipt 查询面。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-17 落地补充：Checkpoint access should be protected before real resume

- 本轮趋势核验：
  - LangGraph Persistence 将 checkpointer 定位为 thread 级 graph state 持久层，用于 conversation continuity、
    human-in-the-loop、time travel 和 fault tolerance；这说明 checkpoint 不只是缓存，而是恢复控制面的关键入口。
  - OpenAI Agents SDK Human-in-the-loop 强调敏感工具调用可以暂停、等待审批、序列化运行状态并在决策后恢复；
    这提醒 DataSmart 的 resume-preview 不能只做业务判断，还必须受服务间认证、审计和重放保护约束。
  - MCP Authorization 规范把 HTTP transport 的受限资源访问放在授权边界下；对 DataSmart 来说，MCP tools/call、
    A2A action、模型 tool_call 最终都会汇入 checkpoint/resume surface，因此访问控制必须前置。
- 对 DataSmart 的架构映射：
  - 5.72 提供 Redis checkpoint store；
  - 5.73 提供 checkpoint-derived Java fact bundle locator hints；
  - 5.74 提供 checkpoint query/resume-preview runtime event 与低基数指标；
  - 5.75 将 checkpoint query/resume-preview 接入 gateway HMAC、nonce 防重放和可配置 fail-closed。
- 本轮落地到代码的能力：
  - 新增 `tool_action_checkpoint_security.py`，复用现有 gateway 签名协议，不引入第二套认证机制；
  - checkpoint route 响应新增低敏 `securityBoundary`；
  - runtime event attributes 新增 checkpointAuthMode、checkpointAuthResult、gatewaySignatureVerified 等低敏字段；
  - 新增 `DATASMART_TOOL_ACTION_CHECKPOINT_GATEWAY_SIGNATURE_REQUIRED`，让 checkpoint 控制面可先于其他诊断接口收紧；
  - 全量 Python 测试 476 个通过。
- 产品判断：
  - 这是从“checkpoint 可运营”继续推进到“checkpoint 可受控访问”的关键一步；
  - 但签名保护只解决调用入口身份问题，尚未解决 Java locator index、真实 outbox 幂等、worker receipt、
    租户配额、长期审计投影和管理员治理视图；
  - 因此下一步不应贸然开放真实 resume，而应把恢复定位和 Java 控制面事实索引做稳。
- 后续趋势落地建议：
  1. 把 checkpoint/thread 映射到 Java command/outbox/approval/clarification/receipt projection。
  2. 将 checkpoint 安全事件投影到 Java timeline，让管理员能查签名失败、scope mismatch、resume waiting/ready 历史。
  3. MCP tools/call 与 A2A action 进入真实执行前，应统一复用 `ToolPlan -> readiness -> checkpoint -> signed resume-preview`。
  4. 继续区分短期 checkpoint、长期记忆、审计投影和真实副作用 outbox，不把任一层扩成万能状态库。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-17 落地补充：Checkpoint resume surfaces need audit events and low-cardinality metrics

- 本轮趋势核验：
  - LangGraph Persistence 将 checkpointer 用于 thread 级 graph state、HITL、time travel 与 fault tolerance，说明暂停点不仅要能保存，还要能被检查和恢复。
  - OpenAI Agents SDK Human-in-the-loop 将工具调用审批建模为暂停、审批、恢复流程，说明恢复预检本身也是可审计控制面动作。
  - MCP Authorization 强调 bearer、resource、scope 等授权语义，提醒 DataSmart 的 checkpoint query/resume-preview 不能只依赖调用方自报字段，后续必须进入服务账号和 gateway 签名链路。
- 对 DataSmart 的架构映射：
  - 5.72 完成 Redis checkpoint store；
  - 5.73 完成 checkpoint-derived Java fact bundle locator hints；
  - 5.74 把 checkpoint query/resume-preview 接入 runtime event、live push、publisher 和低基数 Prometheus 指标；
  - 这让“读取暂停点、恢复预检 ready/waiting、scope mismatch、provider error、事实缺失”都成为可回放事实。
- 本轮落地到代码的能力：
  - 新增 `runtime_event_delivery.py` 复用事件投递；
  - 新增 checkpoint query/resume-preview 两类 runtime event；
  - 新增 `ToolActionCheckpointMetrics` 并接入 `/agent/metrics`；
  - checkpoint route 响应新增 `runtimeEvent`、`runtimeEventDelivery`、`runtimeMetricDelivery`；
  - 全量 Python 测试 473 个通过。
- 产品判断：
  - 这是从“checkpoint 可恢复”走向“checkpoint 可运营”的关键一步；
  - 指标刻意只使用 operation/result/severity/fact_state 等低基数标签，避免把 checkpointId、threadId、tenantId、
    requestId 或 runId 放入 Prometheus；
  - 单次业务对象定位继续走 runtime event、Java projection 和后续审计投影，不能用指标系统承载明细。
- 后续趋势落地建议：
  1. 对齐 MCP Authorization，给 checkpoint query/resume-preview 增加 service-account HMAC、resource/scope 和 nonce replay 防护。
  2. 增加 Java-side locator index，使 checkpoint/thread 能自动发现 command/outbox/receipt/approval。
  3. 将 checkpoint 事件接入 Java projection/timeline，让运维台可查询 checkpoint 访问和恢复预检历史。
  4. 继续区分短期 checkpoint、长期记忆和审计投影，不把 Redis checkpoint 扩展成万能状态库。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/draft/basic/authorization`

## 2026-06-16 落地补充：Checkpoint-derived locator hints make resume less client-dependent

- 本轮趋势核验：
  - LangGraph Persistence 把 checkpointer 定位为 thread 级短期图状态，把 store 定位为跨线程长期记忆，说明恢复执行应从 checkpoint/thread 出发，而不是依赖客户端重传完整上下文。
  - OpenAI Agents SDK Human-in-the-loop 展示了暂停、审批、序列化 RunState、恢复的工具调用流程，说明工具恢复需要稳定定位符和可验证事实，而不只是一次性 HTTP payload。
  - MCP Authorization 强调受保护资源请求需要 bearer token、scope 和 resource 语义，提醒 DataSmart 的 checkpoint/fact bundle 链路必须继续走宿主控制面，而不是把权限判断下放给调用方自报字段。
- 对 DataSmart 的架构映射：
  - 5.72 已把 checkpoint store 推进到可配置 Redis，但 Java fact bundle 查询仍需要更多显式定位符；
  - 5.73 新增 checkpoint-derived locator hints，让 Python 可以从低敏执行图摘要里派生 command、approval、clarification、outbox、tool、policy 线索；
  - 这让 `checkpoint/thread -> Java fact bundle -> resume-preview` 更接近真实 Agent Host 的恢复控制面；
  - 仍保持低敏边界，不把 payloadReference、graphId、prompt、SQL、arguments、样本数据、模型输出或内部 endpoint 放进 Java fact bundle 请求。
- 本轮落地到代码的能力：
  - 新增 `checkpoint_resume_fact_bundle_hints(...)`；
  - `JavaAgentRuntimeToolActionResumeFactBundleClient` 接入 checkpoint hints；
  - requiredFactTypes 结合 checkpoint requirements、请求事实和 checkpoint hints 推断；
  - checkpoint productionReadiness 修正为可配置 in-memory/Redis 短期 store；
  - 全量 Python 测试 469 个通过。
- 产品判断：
  - 该阶段降低了前端刷新、外部 Agent 重试、worker 补偿和跨实例恢复时的参数拼接成本；
  - 但它仍只是“恢复预检自动定位增强”，不是完整 durable resume runner；
  - 下一步应补服务账号授权、审计指标、Java locator index、clarification fact store 和 receipt 持久化，而不是贸然执行真实工具。
- 后续趋势落地建议：
  1. 对齐 MCP Authorization 风格，为 Python -> Java fact bundle 查询补服务账号签名、resource/scope 和 gateway 内部路由。
  2. Java 侧增加 checkpoint/thread 到 command/outbox/receipt/approval 的 locator index，让自动关联从 Python hint 过渡到控制面索引。
  3. 将 MCP tools/call、A2A action、模型 tool_call 统一接入 checkpoint-derived recovery surface。
  4. 保持 checkpoint 与长期记忆分层，避免把 Redis checkpoint 扩展成长期知识库或审计库。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/draft/basic/authorization`

## 2026-06-16 落地补充：Checkpoint store should be durable before real tool resume

- 本轮趋势核验：
  - LangGraph Persistence 明确区分 thread-scoped checkpointer 与 cross-thread store；前者用于对话连续性、HITL、time travel 和 fault tolerance，后者用于用户偏好、事实和共享知识。
  - LangGraph Interrupts 强调生产 interrupt/resume 需要 durable checkpointer，并且 resume 必须复用同一个 thread id；这说明暂停点不应只停留在单进程内存。
  - OpenAI Agents SDK Human-in-the-loop 将高风险工具调用建模为暂停、审批、恢复的状态流，强化了“执行前状态必须可恢复”的产品要求。
  - MCP Authorization 把受限资源与工具调用的授权边界放在协议层，提醒 DataSmart 的工具恢复不应绕过宿主控制面和服务账号边界。
- 对 DataSmart 的架构映射：
  - 5.71 已让 Python resume-preview 消费 Java fact bundle，但如果 checkpoint 本身仍是 in-memory，多实例和重启后仍无法稳定定位暂停点。
  - 5.72 新增 Redis checkpoint store，把短期执行图状态从本地内存推进到可选 durable store。
  - Redis 只承担短期 thread/checkpoint 状态，不承担长期记忆、审计正文或真实 payload 存储；长期事实仍应进入 Java 控制面、MySQL 投影或对象归档。
  - `control-flow-preview -> checkpoint query -> resume-preview -> Java fact bundle` 开始形成可恢复 Agent Host 的最小闭环。
- 本轮落地到代码的能力：
  - 新增 `RedisToolActionExecutionCheckpointStore`；
  - 新增 `ToolActionExecutionCheckpointStoreSettings` 与环境变量装配；
  - FastAPI 启动阶段统一创建 checkpoint store，并注入 control-flow-preview、checkpoint query、resume-preview；
  - 新增 `/agent/tool-actions/checkpoints/diagnostics` 低敏诊断；
  - 全量 Python 测试 466 个通过。
- 产品判断：
  - 这是从“能展示 checkpoint”走向“checkpoint 可被生产环境恢复链路消费”的关键一步；
  - 仍不建议开放真实 resume 执行，因为服务账号签名、审计事件、低基数指标、checkpoint/fact bundle 自动关联、worker receipt 持久化索引和租户配额尚未闭环；
  - 当前最佳路线是继续把真实副作用留在 Java outbox/worker/审批/审计链中，Python 负责低敏图状态、恢复预检和趋势适配。
- 后续趋势落地建议：
  1. 把 Redis checkpoint 的 thread/checkpoint 与 Java fact bundle 的 run/command/required facts 自动关联。
  2. 为 checkpoint 查询与恢复预检补服务账号签名、scope challenge、审计事件和低基数 Prometheus 指标。
  3. 将 Redis checkpoint 继续与 MCP tools/call、A2A action、模型 tool_call 的统一 `ToolPlan -> readiness -> checkpoint` 控制面打通。
  4. 后续再评估 MySQL 长期 checkpoint/audit 投影，避免把 Redis TTL 拉长成伪审计库。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/draft/basic/authorization`

## 2026-06-16 落地补充：Python resume provider should consume host fact bundles

- 本轮趋势核验：
  - LangGraph Interrupts 强调恢复执行时必须使用同一 thread/checkpoint，且 interrupt 之前的副作用需要可幂等；
  - LangGraph Persistence 区分 thread-scoped checkpointer 与 cross-thread store，说明恢复执行依赖持久状态定位，而不是重放原始 payload；
  - OpenAI Agents SDK Human-in-the-loop 将高风险工具调用建模为暂停、审批、恢复 RunState 的流程；
  - MCP Authorization 将 HTTP transport 的授权边界提升到协议层，说明工具/资源访问应由宿主控制面和服务账号链路保护。
- 对 DataSmart 的架构映射：
  - Java 5.70 已把 approval、outbox、worker receipt 聚合成 host-level fact bundle；
  - Python 5.71 开始优先消费 Java fact bundle，而不是继续分别直连 permission-admin、outbox 或 receipt；
  - Python 只处理 fact type、issue code、missing/rejected 状态，不处理事实值本身；
  - 请求侧自报的 approval/outbox/receipt 等服务端背书事实，只要 Java bundle 判定 missing/rejected，就会被 Python 视为 rejected，
    从而阻断“伪造一个 confirmationId 就进入 ready”的绕过风险。
- 本轮落地到代码的能力：
  - 新增 `JavaAgentRuntimeToolActionResumeFactBundleClient`；
  - 新增 `AgentRuntimeResumeFactBundleClientSettings` 和环境变量装配；
  - `build_tool_action_resume_fact_provider(...)` 改为 Java bundle 优先、旧 permission-admin provider 兜底；
  - 全量 Python 测试 459 个通过，关键文件均低于 500 行。
- 产品判断：
  - 这是从“Python provider 抽象”推进到“宿主控制面事实包消费”的关键一步；
  - 后续 DataSmart 的工具执行恢复应继续沿 `checkpoint/thread -> server-side fact bundle -> readiness/resume preview -> Java outbox/worker receipt`
    演进，而不是把真实工具参数、SQL、prompt 或审批凭证重新塞回 Python API；
  - 当前仍不建议开放真实 resume 执行，因为 durable checkpoint store、clarification fact、receipt persistent index、
    service-account signing、mTLS/gateway internal route 和审计指标尚未闭环。
- 后续趋势落地建议：
  1. 将 durable checkpoint store 作为下一阶段 P0，让 thread/checkpoint 成为 Java/Python 双侧可复用的恢复定位符。
  2. 将 MCP `tools/call`、模型 tool_call、A2A action 全部统一进入 DataSmart `ToolPlan -> readiness -> checkpoint -> fact bundle`
     控制面，而不是协议层直连执行器。
  3. 为内部 fact bundle 查询补 OAuth/MCP Authorization 风格的服务账号、资源范围和 scope challenge 语义。
  4. 继续保持模型栈可替换，把恢复事实、工具治理和审计链放在平台层，不绑定某个模型或框架。
- 参考资料：
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/draft/basic/authorization`

## 2026-06-16 落地补充：Resume fact bundle should be a host control-plane API

- 本轮趋势核验：
  - LangGraph Interrupts 明确图执行可以在节点中暂停，恢复时要复用同一 thread/checkpoint，并且生产环境应使用 durable checkpointer；
  - LangGraph Persistence 将 checkpoint 定位为 thread-scoped graph state，说明恢复执行不应依赖前端重传完整原始 payload；
  - OpenAI Agents SDK Human-in-the-loop 把敏感工具调用建模为 interruption，再通过审批/拒绝结果恢复 RunState；
  - MCP Authorization 把 HTTP transport 授权提升为协议级要求，说明工具/资源访问必须由宿主控制面校验，而不是只相信客户端字段。
- 对 DataSmart 的架构映射：
  - Python 5.68/5.69 已经具备 resume fact provider 与 permission-admin 审批事实验真；
  - Java 5.70 新增 `tool-action-resume-facts/bundles/query`，把 approval、outbox、worker receipt 聚合为 host-level fact bundle；
  - 该 API 只返回 fact type/status/evidence code/issue code，不返回事实值、工具参数、payload body、SQL、prompt、模型输出或内部 endpoint；
  - outbox 与 receipt 的摘要被刻意低敏化，避免 runtime/control-plane API 变成第二份敏感上下文缓存。
- 本轮落地到代码的能力：
  - 新增 `AgentToolActionResumeFactBundleService`；
  - 新增 `AgentToolActionApprovalFactEvaluator` 与 HTTP permission-admin 实现；
  - 新增恢复事实包 Controller、DTO、配置项和单元测试；
  - 定向 Java 测试 14 个通过，核心服务控制在 500 行。
- 产品判断：
  - 这是从“Python 自己串多个 provider”向“Java 企业控制面统一聚合事实”的关键一步；
  - 它符合 Codex/Claude Code 类 Agent Host 的方向：模型/协议只提出动作意图，宿主平台负责权限、审批、低敏事实、outbox、worker receipt 和审计；
  - 仍不应该直接开放真实 resume 执行，因为 durable checkpoint store、clarification fact、receipt persistent index、服务账号签名和审计指标还未闭环。
- 后续趋势落地建议：
  1. Python Runtime 下一步应消费 Java fact bundle API，而不是继续分别调用 permission-admin/outbox/receipt；
  2. Java 侧应建设 durable checkpoint store，让 checkpointId/threadId 能自动发现 run/command/required facts；
  3. MCP `tools/call`、A2A action、模型 tool_call 应统一落入 `ToolPlan -> readiness -> checkpoint -> fact bundle -> outbox/worker receipt`；
  4. 服务间调用需要引入 MCP Authorization 风格的服务账号签名、mTLS 或内部 gateway 策略。
- 参考资料：
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/draft/basic/authorization`

## 2026-06-15 落地补充：Client-supplied resume facts must be verified by the host control plane

- 本轮趋势核验：
  - LangGraph Interrupts/Persistence 强调暂停点由 checkpointer 保存，恢复时依赖同一 thread/checkpoint 的外部输入，但外部输入不应被无条件信任；
  - OpenAI Agents SDK Human-in-the-loop 将敏感工具调用建模为 interruption，并要求审批结果进入 RunState 恢复流程；
  - MCP Authorization 规范把 HTTP transport 的授权放在协议级能力中，说明工具/资源访问不能只靠客户端自报字段。
- 对 DataSmart 的架构映射：
  - 5.68 已经把恢复事实抽象成 provider；
  - 5.69 进一步接入 permission-admin 审批事实评估接口，让 approvalConfirmationId 经过 Java 控制面验真；
  - `rejectedFactTypes` 成为恢复预检的服务端否决机制，防止请求里出现 approval 字段就被当作事实齐备；
  - Python 仍然只做低敏 preflight，不返回 approvalId、审批意见、Java reason、SQL、arguments、payload body 或服务 token。
- 本轮落地到代码的能力：
  - 新增 `JavaPermissionAdminToolActionResumeFactClient`；
  - 新增 `build_tool_action_resume_fact_provider(...)` 启动装配函数；
  - FastAPI `create_app()` 将 resume fact provider 注入 checkpoint 子路由；
  - resume-preview 合并事实时会剔除服务端拒绝的事实类型；
  - 全量 Python 测试 454 个通过。
- 产品判断：
  - 这是从“可恢复预检”走向“可信恢复预检”的关键一步；
  - 当前仍不是完整 durable resume，因为 Java 还没有按 checkpointId/threadId 聚合所有恢复事实的 bundle API；
  - 正确下一步不是直接开放真实工具执行，而是让 Java 控制面提供 approval/clarification/outbox/worker receipt 的统一事实束，并补服务账号签名、RBAC 和审计。
- 后续趋势落地建议：
  1. 设计 Java resume-fact bundle API，让 Python 以 checkpointId/threadId 查询事实类型，而不是依赖调用方重传 ID；
  2. 将 MCP Authorization 的资源服务器/服务账号思路映射到 DataSmart gateway -> Python -> Java 的服务间签名和权限校验；
  3. 将 LangGraph/OpenAI HITL 的 RunState/interrupt 思路继续映射为 DataSmart 低敏 checkpoint + Java 事实控制面；
  4. 只有当 outbox、worker receipt、幂等、审计闭环完成后，再考虑真实 resume 执行。
- 参考资料：
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-15 落地补充：Resume should merge server-side facts before continuing a paused tool graph

- 本轮趋势核验：
  - LangGraph Interrupts 强调中断时由 persistence/checkpointer 保存图状态，并等待外部输入后继续；
  - LangGraph Persistence 将 checkpointer 用于 thread-scoped 短期状态，将 store 用于跨线程长期记忆，二者不能混淆；
  - OpenAI Agents Human-in-the-loop 将高风险工具调用表示为 interruption，并通过 RunState 在审批后恢复；
  - OpenAI Agents running guidance 同样强调审批暂停后应从 state 恢复，而不是开启一个新的用户轮次。
- 对 DataSmart 的架构映射：
  - 5.67 已经让 checkpointId/threadId 成为恢复预检入口；
  - 5.68 把恢复事实抽象为 provider，让审批、澄清、outbox confirmation、worker receipt、预算恢复等事实可以来自服务端；
  - API 响应只展示事实类型和缺失类型，不展示事实值，避免为了 resume 扩散 prompt、SQL、arguments、payload reference 或审批凭据。
- 本轮落地到代码的能力：
  - 新增 `ToolActionResumeFactProvider` 协议；
  - 新增 `EmptyToolActionResumeFactProvider` 与 `StaticToolActionResumeFactProvider`；
  - resume-preview 合并请求事实和服务端事实，并新增 `serverSideResumeFacts` 低敏摘要；
  - provider 异常 fail-closed，不把内部连接、异常消息或原始响应返回给调用方；
  - 定向 Python 测试 8 个通过。
- 产品判断：
  - 这是从“能恢复预检”走向“能被企业控制面安全恢复”的关键缝合点；
  - 它避免 checkpoint API 直接依赖 permission-admin/outbox/worker 的具体实现，后续可以按 provider 逐个接入；
  - 不应在这一阶段直接开放真实工具执行，因为 durable outbox、worker receipt、权限审计和幂等链路还没有闭环。
- 后续趋势落地建议：
  1. 实现 permission-admin approval/clarification provider，并要求服务间签名或服务账号授权；
  2. 实现 outbox confirmation / worker receipt provider，把 resume 从“预检通过”推进到“可证明 durable action 已就绪”；
  3. 把 checkpoint store 替换为 Redis/MySQL durable store，同时补 TTL、租户配额、审计事件和低基数指标；
  4. 继续跟踪 LangGraph durable execution 与 OpenAI Agents HITL，但只吸收能增强 DataSmart 权限、审计、恢复和低敏治理的机制。
- 参考资料：
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - OpenAI Running Agents: `https://developers.openai.com/api/docs/guides/agents/running-agents`

## 2026-06-15 落地补充：Resume should consume checkpoint locators and server-side facts, not replay raw payloads

- 本轮趋势核验：
  - LangGraph Persistence 把 checkpointer 定位为 thread-scoped graph state，`thread_id` 是恢复同一线程状态的关键指针；
  - LangGraph Interrupts 强调暂停后通过 checkpointer 保存位置，再由外部输入 resume，而不是重新提交完整原始请求；
  - OpenAI Agents SDK Human-in-the-loop 同样把高风险工具调用拆成 interruption、approval decision、RunState resume。
- 对 DataSmart 的架构映射：
  - 5.66 已经能保存低敏 checkpoint；
  - 5.67 新增 checkpoint 查询和 resume-preview，开始让 checkpointId/threadId 成为恢复入口；
  - resume-preview 只判断 approval、clarification、budget、outbox confirmation 等事实类型是否齐备，不回显事实值；
  - 这避免了“为了恢复执行而让前端或外部 Agent 重传 prompt/SQL/arguments”的反模式。
- 本轮落地到代码的能力：
  - 新增 `/agent/tool-actions/checkpoints/query`；
  - 新增 `/agent/tool-actions/checkpoints/resume-preview`；
  - 新增 checkpoint API helper 与独立 route registrar，保持主 routes 文件不过度膨胀；
  - 全量 Python 测试 443 个通过。
- 产品判断：
  - 查询和 resume-preview 是从 demo 走向商业化 Agent Host 的必要中间层：用户断线、审批延迟、worker 恢复、outbox 重放都需要能定位“暂停在哪”；
  - 但当前阶段不应该直接恢复执行，因为 Java graph/proposal/outbox/worker receipt 还没有完成完整 durable action 闭环；
  - 正确节奏是先让 checkpoint 被安全查询和预检消费，再接真实审批/澄清事实，再接 outbox/worker。
- 后续趋势落地建议：
  1. 将 approval/clarification fact 从手动 payload 升级为 permission-admin / fact store 服务端查询；
  2. 将 memory checkpoint store 替换为 Redis/MySQL，补齐 TTL、租户配额、scope authorization 和审计事件；
  3. 为 resume 后的 outbox writer 定义 idempotency key、receipt projection 和 replay protection；
  4. 继续保持低敏原则：checkpoint/resume 只处理定位符和事实类型，真实 payload 必须留在受控 payload store。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`

## 2026-06-14 落地补充：Checkpoint 是短期恢复状态，不是长期记忆仓库

- 本轮趋势核验：
  - LangGraph Persistence 明确区分 checkpointer 和 store：checkpointer 保存 thread 的 graph state，用于短期线程级记忆、human-in-the-loop、time travel 和 fault tolerance；store 保存应用自定义的跨线程长期记忆；
  - LangGraph Interrupts 强调执行图可以在关键节点暂停，持久化状态后等待外部输入，再通过 resume 继续；
  - OpenAI Agents SDK Human-in-the-loop 也把敏感工具调用设计为“暂停 -> 返回 interruption -> 序列化/恢复 RunState -> 审批后继续”。
- 对 DataSmart 的架构映射：
  - `ToolActionExecutionGraphRunner` 代表执行前图的节点推进；
  - 新增 checkpoint store 代表短期、线程级、低敏恢复状态；
  - 现有 long-term memory store 继续承担跨会话经验沉淀，二者不能混用；
  - prompt、SQL、工具参数、样本数据、模型输出、凭证和内部 endpoint 不进入 checkpoint，也不应通过 checkpoint 间接成为新的敏感上下文缓存。
- 本轮落地到代码的能力：
  - 新增 `ToolActionExecutionCheckpointStore` 协议，为 Redis/MySQL/durable workflow engine 预留替换点；
  - 新增 `InMemoryToolActionExecutionCheckpointStore`，提供单线程和全局容量上限；
  - `ToolActionExecutionGraphRunner` 可注入 checkpoint store，并在保存后通过 `checkpoint` 摘要返回 checkpointId、threadId、sequence、savedAt；
  - `/agent/tool-actions/control-flow-preview` 默认使用 memory checkpoint store，让工具动作 preview 具备短期可恢复定位能力；
  - 全量 Python 测试 439 个通过。
- 产品判断：
  - 这一步不是为了“记住用户说过什么”，而是为了让等待审批、等待澄清、等待预算恢复、等待 outbox 确认的执行前图可以被定位和恢复；
  - 它让 DataSmart 更接近 Codex/Claude Code 类 Agent Host 的运行时骨架：模型提出动作意图，宿主平台保存低敏状态，控制面事实决定是否继续；
  - 真正商业化前，还需要把 memory store 升级为 Redis/MySQL，并补齐 checkpoint 查询、权限过滤、TTL、租户配额和审计导出。
- 后续趋势落地建议：
  1. 用 checkpointId/threadId 串联 approval/clarification resume，而不是让调用方重复提交完整 payload；
  2. 将 outbox writer 和 worker receipt 接入 checkpoint 恢复链路，形成 durable action 证据；
  3. 把 checkpoint 指标做成低基数 Prometheus 统计，例如 saved_total、evicted_total、resume_required_count，避免记录 tenantId 或 toolName 高基数字段；
  4. 继续跟踪 LangGraph durable execution、OpenAI Agents RunState/HITL、MCP tools/call 以及 A2A task 恢复语义，但只吸收能强化 DataSmart 权限、审计、恢复和低敏治理的部分。
- 参考资料：
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`

## 2026-06-14 落地补充：Pre-execution graph runners are the bridge between preview and durable execution

- 本阶段继续对齐 Agent runtime 趋势：
  - LangGraph 官方把 durable execution、interrupt、persistence 放在运行时核心能力里，说明可恢复 Agent 不能只依赖一次同步响应；
  - OpenAI Agents SDK HITL 使用工具审批中断和 `RunState` 恢复，说明高风险工具调用应先停在可恢复状态；
  - MCP tool annotations 仍然只是风险提示，不能替代宿主平台的执行授权和审计事实。
- 对 DataSmart 的架构映射：
  - `ToolActionControlFlowService` 是静态控制流视图；
  - `ToolActionCommandProposalTemplates` 是进入 Java proposal 的低敏模板；
  - `JavaToolActionCommandProposalClient` 是 Python -> Java proposal 的受控桥；
  - 新增 `ToolActionExecutionGraphRunner` 是第一层执行前图节点，把 READY、审批、澄清、预算、草案、阻断等分支变成可恢复状态。
- 本轮落地到代码的能力：
  - `/agent/tool-actions/control-flow-preview` 新增 `toolActionExecutionGraphRun`；
  - READY 分支可以在证据完整且 client 启用时提交 Java proposal；
  - 默认 client 禁用，因此本地 preview 不会联网、不写 outbox、不执行工具；
  - 全量 Python 测试 436 个通过。
- 后续趋势落地建议：
  1. 下一步应补 checkpoint store，让 runner 的状态不只存在于一次 HTTP 响应；
  2. approval/clarification 应升级为服务端事实查询和 resume，而不是只靠调用方补字符串；
  3. 真正工具执行仍应由 Java outbox writer、task-management worker 和 receipt projection 承接，Python API 不应直接调用业务微服务。
- 参考资料：
  - LangGraph overview: `https://docs.langchain.com/oss/python/langgraph/overview`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - MCP Tool Annotations as Risk Vocabulary: `https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/`

## 2026-06-14 落地补充：Command proposal client keeps tool execution authority in the host control plane

- 本阶段继续沿用 5.63 的趋势判断：MCP tool annotations 可以描述 read-only、destructive、idempotent、open-world 等风险提示，但不能替代宿主平台自己的执行授权；OpenAI Agents SDK HITL 和 LangGraph interrupt 都把敏感工具调用设计成“暂停、持久化/保留状态、等待外部审批或输入后恢复”。
- 对 DataSmart 的架构映射进一步落到客户端契约：
  - Python Runtime 可以负责意图归一、readiness、graph 分支和 proposal 请求组装；
  - Java `agent-runtime` 仍持有 graph projection、payloadReference 复核、proposal 状态、outbox writer 和 worker receipt；
  - 模型、MCP Host、A2A 协议或 Python preview API 都不能因为 `READY` 就直接调用业务微服务；
  - proposal client 默认禁用，并在缺少可信 graph/payload/policy 证据时 fail-closed。
- 本轮落地到代码的能力：
  - 新增 `JavaToolActionCommandProposalClient`；
  - 新增 `tool_action_command_proposal_contract.py` 统一维护 Java 请求/响应低敏白名单；
  - 新增 `ToolActionCommandProposalEvidence` 和低敏 `ToolActionCommandProposalClientResult`；
  - 客户端只提交 Java DTO 白名单字段，并对疑似 URL/JSON/SQL/凭证的 payloadReference 做阻断与摘要脱敏；
  - 全量 Python 测试 432 个通过。
- 后续趋势落地建议：
  1. 把客户端接入 LangGraph/OpenClaw 风格最小 durable graph runner；
  2. 把 approval/clarification 从“外部补 evidence”升级为可查询、可回放的服务端事实；
  3. 后续如果引入完整 MCP Server，也应把 `tools/call` 转成 DataSmart proposal/outbox，而不是协议层直连执行器。
- 参考资料：
  - MCP Tool Annotations as Risk Vocabulary: `https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`

## 2026-06-14 落地补充：Tool annotations and HITL need host-owned command proposal contracts

- 本阶段继续核对 Agent 工具调用生态：
  - MCP 官方博客强调 tool annotations 是风险词汇与提示，例如 read-only、destructive、idempotent、open-world，但这些提示不能替代 Host 自身的执行治理；
  - OpenAI Agents SDK Human-in-the-loop 文档强调敏感工具调用要暂停，等待审批后从 `RunState` 恢复；
  - LangGraph interrupt/persistence 文档强调执行图可以在关键节点暂停、持久化并等待外部输入后恢复。
- 对 DataSmart 的架构映射：
  - `readOnlyHint/destructiveHint` 或 DataSmart 自己的 riskLevel 只能帮助生成提示，不能直接授予执行权；
  - 模型、MCP Host 或 A2A 协议都不应该自行决定“READY 就执行”；
  - READY 分支应该先生成 Java command proposal 模板，再由 Java `agent-runtime` 通过 graphId/contractId、payloadReference、policyVersion、approvalFact、workerReceiptMode 做服务端复核；
  - Python Runtime 不保存、不回显工具实参，只输出低敏模板，让控制面事实仍归 Java 所有。
- 本轮落地到代码的能力：
  - 新增 `tool_action_command_proposal_template.py`；
  - `ToolActionControlFlowReport` 新增 `toolActionCommandProposalTemplates`；
  - `/agent/tool-actions/control-flow-preview` 可返回 Java proposal 请求模板；
  - 全量 Python 测试 427 个通过。
- 后续趋势落地建议：
  1. 把 proposal 模板接到真实 Java proposal client，形成可恢复 graph runner 的下一跳；
  2. 继续让审批事实、payload store、outbox writer 和 worker receipt 保持 Java 服务端事实源；
  3. 后续 MCP Server 真正开放时，也只应把 tools/call 转换为 proposal/outbox，而不是让协议层直接执行业务服务。
- 参考资料：
  - MCP Tool Annotations as Risk Vocabulary: `https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`

## 2026-06-11 落地补充：Tool calls should enter a unified pre-execution control flow

- 本阶段重新核对当前 Agent 工具调用生态：
  - MCP Tools 规范把工具定义为模型可发现、可调用的外部能力，但真实 Host 仍需要在工具调用前做权限、确认和结果治理；
  - OpenAI Agents SDK Human-in-the-loop 文档强调敏感工具调用可以暂停，等待审批决定后再恢复；
  - LangGraph 的 interrupts 与 persistence 文档强调执行图可以在关键节点暂停、持久化、恢复，用于 human-in-the-loop、长期运行和故障恢复。
- 对 DataSmart 的架构映射：
  - 模型 `tool_call`、MCP `tools/call`、A2A `task/action` 不应该各自直连执行器；
  - 它们应该先进入统一 `tool_action_gate`：intake -> readiness -> readiness graph -> approval/clarification/outbox 分支；
  - READY 不代表已执行，只代表可进入下一跳；真实副作用必须继续由 Java outbox、permission-admin、task-management worker 和 runtime event receipt 承接；
  - 响应和事件必须坚持低敏策略，只保留工具名、字段名、决策码和计数，不泄露参数值、prompt、SQL、样本数据、模型输出、凭证或内部 endpoint。
- 本轮落地到代码的能力：
  - 新增 `ToolActionControlFlowService`，统一模型、MCP、A2A 三类入口的执行前控制流；
  - MCP 专用预检接口内部改为复用该服务，避免 readiness/graph/nextSteps 规则分叉；
  - 新增 `/agent/tool-actions/control-flow-preview`，给智能网关和后续图编排器提供统一 preview surface；
  - 全量 Python 测试 427 个通过。
- 后续趋势落地建议：
  1. 将该服务映射为 LangGraph/OpenClaw 风格的 `tool_action_gate` 条件节点；
  2. 把 WAITING_APPROVAL 分支接到 permission-admin 的审批事实和审批策略，而不是只依赖本地默认 policy；
  3. 把 READY 分支转为 Java `ToolActionCommand` outbox，而不是在 Python HTTP handler 内执行；
  4. 后续接入 MCP Server 时，仍应让 `tools/call` 先经过同一控制流，再决定是否创建审批、排队或拒绝。
- 参考资料：
  - MCP Tools specification: `https://modelcontextprotocol.io/specification/2025-06-18/server/tools`
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - LangGraph Interrupts: `https://docs.langchain.com/oss/python/langgraph/interrupts`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`

## 2026-06-11 落地补充：Agent Host API surfaces need capability-oriented packages

- 本阶段核对 FastAPI 官方 “Bigger Applications - Multiple Files” 文档：大型 API 很少适合长期放在单文件里，官方推荐用多个文件和包来组织应用，并通过类似 router 的结构保持灵活性。
- 对 DataSmart 的架构映射：
  - Python AI Runtime 正在承载智能网关、runtime event、长期记忆、模型网关、A2A、MCP 和工具治理，如果继续把入口散放成 `api_*.py`，后续会让协议、权限、路由、响应组装和服务实现互相缠绕；
  - 类 Codex/Claude Code Agent 的复杂度不只在推理和工具调用，也在“宿主控制面可维护”：每个入口都要能独立演进认证、审计、低敏响应、replay 和故障诊断；
  - 因此 API 层先按能力域拆包，是后续接入统一 intake、MCP/A2A 工具协议、长期记忆管理端和模型网关治理的基础工程。
- 本轮落地到代码的能力：
  - `datasmart_ai_runtime.api` 从单文件升级为兼容包；
  - `api/agent`、`api/gateway`、`api/events`、`api/memory`、`api/model_gateway` 分别承载对应 HTTP 边界；
  - 根聚合入口使用懒加载，避免导入子模块时提前装配整个 FastAPI app；
  - 迁移后全量 Python 测试 423 个通过。
- 后续趋势落地建议：
  1. API 分层完成后，不要长时间停留在目录治理，应回到 Agent 能力主线；
  2. 下一批更高价值方向是统一 MCP/A2A/model tool_call intake、Agent tool runtime、长期记忆检索/写入闭环和模型网关缓存治理；
  3. 如果继续分包，应把它绑定到真实能力落地，比如 `services/agent` 的执行图编排或 `services/integrations` 的 Java 控制面客户端，而不是单纯移动文件。
- 参考资料：
  - FastAPI Bigger Applications - Multiple Files: `https://fastapi.tiangolo.com/tutorial/bigger-applications/`

## 2026-06-11 落地补充：Approval ids must resolve to server-side facts before tool execution

- 当前 Agent Host 趋势继续从“模型/协议直接执行工具”转向“宿主控制面持有可恢复状态与审批事实”：
  - OpenAI Agents SDK HITL 文档强调工具调用可以暂停，审批决定进入 `RunState` 后再恢复执行；
  - LangGraph Persistence 文档强调 checkpoint 支撑 human-in-the-loop、memory、time travel 和 fault-tolerant execution；
  - MCP Authorization 规范强调 resource/audience 绑定、令牌不能透传，说明外部协议给出的凭据或 ID 不能被下游直接信任。
- DataSmart 本阶段把该趋势落成审批事实回查：
  - `confirmationId=approval:xxx` 只能作为线索，不能作为执行凭据；
  - permission-admin 必须能回查到 approval fact，且事实要绑定 tenant/project/actor/session/run/command/tool/policyVersion；
  - task-management dry-run 根据 APPROVED/PENDING/REJECTED/EXPIRED/SCOPE_MISMATCH 做 defer 或 fail-closed；
  - agent-runtime timeline 看到的是 `WAITING_APPROVAL_FACT` 等低敏状态，不会暴露审批意见、工具参数或 payload body。
- 产品映射：
  - 这一步让 DataSmart 更接近 Codex/Claude Code 类 Agent Host 的工具治理模型：模型提出动作，控制面登记引用，审批事实由宿主保存，worker 执行前回查；
  - 对企业客户来说，这比“前端传一个 approvalId 就执行”可靠得多，因为它支持过期、撤销、跨项目复用阻断和审计回放；
  - 后续 MySQL approval fact store、审批状态流转、管理员查询和 outbox 审计会把它从内存契约推进到生产事实源。
- 参考资料：
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - MCP 2025-11-25 Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-11 落地补充：Dry-run receipts turn execution gates into replayable host facts

- 本阶段继续跟随 Agent Host 领域的一个清晰趋势：工具调用的安全性不只来自“执行前拦截”，还来自“拦截结果可恢复、可回放、可审计”。
  - OpenAI Agents SDK Human-in-the-loop 文档强调，敏感工具可以暂停，审批决策进入 run state 后再恢复执行；
  - LangGraph Persistence 文档强调，checkpoint 不只是断点续跑，还支撑 human-in-the-loop、会话记忆、time travel 调试和故障恢复；
  - MCP 2025-11-25 Authorization 规范强调 protected resource server、token audience/resource 绑定和 token passthrough 边界，说明工具调用必须由宿主控制面保留清晰授权事实。
- 对 DataSmart 的架构映射：
  - `AGENT_TOOL_ACTION_CONTROLLED` 的 dry-run 不能只把 task 状态改成 defer/fail，还需要把“为什么被 defer/fail”变成 agent-runtime 可查询的低敏 timeline 事实；
  - receipt 是 Agent Host 控制面的可见性事实，不是工具结果正文，也不是下游业务执行凭据；
  - task-management 负责认领任务、执行前判断和任务状态变化，agent-runtime 负责 receipt 接收、projection 幂等、timeline 展示和后续 replay；
  - 这保持了微服务边界：任务状态机、Agent runtime event、权限事实、payload store 和真实 executor 各自拥有自己的事实源。
- 本轮落地到代码的能力：
  - task-management dry-run dispatcher 在 defer/fail 后调用 agent-runtime 内部 receipt endpoint；
  - agent-runtime 新增 `agent.tool_execution.controlled_dry_run_receipt_recorded` runtime event；
  - timeline 展示层新增 `WAITING_PAYLOAD_BODY`、`WAITING_CONTROLLED_EXECUTOR`、`BLOCKED_BEFORE_SIDE_EFFECT` 等状态；
  - 两侧都坚持低敏 payload policy，不写入工具实参、payload body、SQL、prompt、样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。
- 趋势落地建议：
  1. 将 receipt 从 best-effort HTTP 逐步升级为 outbox 驱动的最终一致事件，适合强审计客户场景；
  2. 把 permission-admin 审批事实接入 dry-run，让 HITL 决策不只是字符串 ID，而是可恢复、可过期、可授权校验的服务端事实；
  3. 真实 executor 应复用同一条 receipt/timeline 语义，继续输出 claimed、started、succeeded、failed、retry、dead-letter 等低敏事实；
  4. Python Runtime、MCP、A2A 和模型 tool_call 都应汇入统一 tool action control plane，避免各入口绕过同一套执行前治理。
- 参考资料：
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - MCP 2025-11-25 Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-11 落地补充：Controlled dry-run executors should gate side effects before real tool execution

- 本阶段重新核对的外部趋势：
  - OpenAI Agents SDK Human-in-the-loop 文档强调，敏感工具调用可以暂停，审批决策进入 run state 后再恢复执行；
  - LangGraph Persistence 文档强调，checkpoint 可以支撑 human-in-the-loop、会话记忆、time travel 调试和容错恢复；
  - MCP 2025-11-25 Authorization 规范强调受保护 MCP server 的 OAuth 资源服务器角色、`resource` 参数、access token audience validation，以及禁止 token passthrough 的安全边界。
- 对 DataSmart 的架构映射：
  - `AGENT_TOOL_ACTION_CONTROLLED` 不应从 task 入箱直接跳到真实业务工具调用；
  - 它应该先进入 host/control-plane 的 dry-run executor，由执行器在服务端复核 payload store 事实、审批事实、策略版本、worker 容量和幂等状态；
  - dry-run 结果必须是低敏 receipt 或任务状态变化，而不是把工具参数、SQL、prompt、样本数据、模型输出、凭证或内部 endpoint 泄露到 task.params、timeline 或 metrics；
  - 真实 executor 未来可以打开副作用，但必须在 dry-run 语义稳定之后再接入，并继续保留 fail-closed、defer、receipt、retry、dead-letter 和审计链路。
- 本轮落地到代码的能力：
  - 新增 task-management 侧 `AgentToolActionControlledPayloadResolver`，只解析低敏命令信封，不读取 payload body；
  - 新增 `AgentToolActionControlledDryRunDispatcherService`，只认领 `AGENT_TOOL_ACTION_CONTROLLED`，拒绝复用历史 `AGENT_ASYNC_TOOL` worker；
  - 新增内部 dry-run route，默认关闭，由配置灰度开启，防止调试接口悄悄改变生产任务台账；
  - 缺少 payload store 服务端证据时 fail-closed，payload body 或真实 executor 未就绪时 defer，而不是伪装成功。
- 趋势落地建议：
  1. 下一步补低敏 worker receipt event，把 dry-run 的 claimed/deferred/failed/precheck-passed 写回 agent-runtime timeline；
  2. 再接 permission-admin 审批事实，使 HITL 不只是字符串 ID，而是可恢复、可过期、可审计的服务端事实；
  3. payload body 物化时保持 host-owned payload store，不允许外部协议、模型输出或任务表直接携带真实参数正文；
  4. 智能网关/MCP/A2A/模型 tool_call 都应统一进入 intake -> readiness -> command -> dry-run -> executor receipt 链路，避免每个协议各自绕过治理。
- 参考资料：
  - OpenAI Agents SDK Human-in-the-loop: `https://openai.github.io/openai-agents-python/human_in_the_loop/`
  - LangGraph Persistence: `https://docs.langchain.com/oss/python/langgraph/persistence`
  - MCP 2025-11-25 Authorization: `https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`

## 2026-06-11 落地补充：Payload references must become host-owned facts before execution

- 当前 Agent Host 趋势继续从“模型或协议直接调用工具”转向“宿主控制面持有可恢复事实，再由受控执行器推进副作用”：
  - OpenAI Agents SDK Human-in-the-loop 文档强调敏感工具调用可以暂停，审批决定需要保存在 run state 中并恢复执行；
  - LangGraph persistence/durable execution 路线强调长任务、human-in-the-loop 和故障恢复需要持久化状态；
  - MCP 2025-11-25 Authorization 规范继续强化受限工具服务器的资源绑定、token audience validation 和禁止 token passthrough。
- DataSmart 本阶段把该趋势落成 `agent-payload:` 服务端登记：
  - writer 不再只相信 `agent-payload:{runId}/{payloadKey}` 字符串结构；
  - 服务端先登记 payload envelope 元数据，再由 verifier 回查登记事实；
  - verdict 只包含 reference、作用域、metadataDigest、payloadBodyAvailable、issueCodes 和 acceptedEvidence，不携带 payload body；
  - outbox payload 继续禁止 prompt、SQL、工具实参、样本数据、模型输出、凭证、内部 endpoint 和 artifact 正文。
- 产品映射：
  - payloadReference 是“指向服务端事实的句柄”，不是“执行权限”；
  - 真正执行前仍必须由专用 executor 回查 payload store、permission-admin/confirmation fact、worker capacity、幂等状态和过期时间；
  - 当前内存 store 只是端口和语义验证，生产版需要 MySQL/Redis/对象存储/KMS 加密与审计留存。
- 下一步趋势落地建议：
  1. 不要让 task-management 旧 worker 直接执行 `agent-payload:`，应新增 `AGENT_TOOL_ACTION_CONTROLLED` 专用 executor；
  2. executor 第一阶段先做 dry-run/pre-check，把 payload verdict、审批事实、预算/容量和幂等状态回写为低敏 receipt；
  3. MCP/A2A/模型 tool_call 的 payload 都应汇入同一套 host-owned payload store，而不是在各入口复制参数正文。
- 参考资料：OpenAI Agents SDK Human-in-the-loop：`https://openai.github.io/openai-agents-python/human_in_the_loop/`；LangGraph Persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；MCP 2025-11-25 Authorization：`https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization`。

## 2026-06-07 落地补充：Controlled command inbox separates approval from execution

- 最新 Agent Host 趋势继续强调：工具调用不应从“模型/协议请求”直接跳到“业务副作用执行”。更安全的路线是先形成可暂停、可恢复、可审批、可审计的控制面事实，再由执行器在服务端复核后推进。
- 本轮核对的外部参考：
  - OpenAI Agents SDK Human-in-the-loop 文档强调 function tool、hosted MCP 和 nested agent tool 都可能产生审批中断，运行状态需要保存并在审批后恢复；
  - LangGraph Durable Execution 文档强调长任务和 human-in-the-loop 场景需要持久化执行状态，避免中断、超时或重启后丢失流程；
  - MCP 官方 Authorization 规范强调受限 MCP server 的授权边界，并明确 token audience validation 与禁止 token passthrough 这类安全要求。
- DataSmart 本阶段把趋势落成代码：
  - `agent-runtime` writer 输出 task-management 可消费的低敏命令信封，但不携带内部 endpoint 和工具实参；
  - `task-management` Inbox 支持 `AGENT_TOOL_ACTION_CONTROLLED_COMMAND`，按 commandId/idempotencyKey 去重并创建 `AGENT_TOOL_ACTION_CONTROLLED` 任务；
  - 新任务类型不会被旧 `AGENT_ASYNC_TOOL` worker 认领，避免 `agent-payload:` 尚未接 payload store 时发生误执行；
  - 数据库 Inbox/Outbox 允许 targetEndpoint 为空，使“禁止外部携带内部端点”成为真实可落库的契约，而不是只停留在代码注释。
- 产品映射：
  - 这一步让 DataSmart 更接近 Codex/Claude Code 类 Agent Host 的“工具动作先进入宿主控制面，再进入受控执行器”模式；
  - 人工审批、策略版本、payloadReference 和 worker receipt 都应成为可回放事实，而不是临时 HTTP 请求字段；
  - 后续完整能力不应是“让 task-management 直接执行 agent-payload”，而是新增专用 tool-action executor，在执行前回查 payload store、permission-admin、confirmation store、worker capacity 和幂等状态。
- 下一步趋势落地建议：
  1. `agent-payload:` payload store/client 要先做服务端鉴权和低敏 verdict，不要让 writer 或 Inbox 读取真实参数正文；
  2. 专用 `AGENT_TOOL_ACTION_CONTROLLED` worker 应先接 pre-check/dry-run/receipt，再逐步开放真实副作用；
  3. MCP/A2A/模型 tool_call 都应汇入同一套 command inbox，而不是各自绕过权限、审批和审计。
- 参考资料：OpenAI Agents SDK Human-in-the-loop：`https://openai.github.io/openai-agents-python/human_in_the_loop/`；OpenAI Agents JS tools approval：`https://openai.github.io/openai-agents-js/guides/tools`；LangGraph Durable Execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；MCP Authorization：`https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization`。

## 2026-06-07 落地补充：Evidence replay turns verifier checks into host facts

- 现代 Agent Host 的工具执行安全正在从“校验请求字符串”走向“回放服务端事实证据”。模型 tool_call、MCP `tools/call`、A2A action 或前端确认页提交的 ID，都不应被直接视为可信执行凭据；它们必须被 host/control-plane 回查为真实存在、未过期、未越权、绑定当前 run/session/graph 的事实。
- DataSmart 本阶段把 5.54 的 writer 前 verifier 推进到部分 evidence replay：
  - `agent-tool-audit://.../plan-arguments` 会回查工具执行审计仓储，确认 auditId 与当前 proposal 和访问上下文一致；
  - `dag-confirmation:` 会回查 selected-node confirmation store，确认记录存在、已确认、未过期，并在当前 tenant/project/actor 范围内可读；
  - 普通 approval/clarification ID 暂不强行绑定 DAG store，避免把当前局部事实源误用成所有审批系统的统一来源。
- 趋势映射到产品能力时，关键不是“尽快执行工具”，而是“让每一次执行前证据都能被服务端重放和解释”：
  - readiness/proposal 负责说明当前是否具备最低条件；
  - verifier 负责把低敏引用回查为可接受事实；
  - outbox 负责创建 durable command；
  - dispatcher/inbox/worker 负责租约、幂等、执行和 receipt；
  - timeline/audit 负责低敏回放和故障诊断。
- 下一步趋势落地建议：优先把 `AGENT_TOOL_ACTION_CONTROLLED_COMMAND` 接入 task-management inbox，而不是继续只在 Java writer 附近加字段。消费侧 inbox 才能真正验证 durable execution 是否具备去重、租约、重试、死信和 worker receipt。

## 2026-06-07 落地补充：Server-side verifiers protect durable tool commands

- 当前 Agent Host 工具调用安全的一个明显趋势，是把“模型/协议给出的引用或确认 ID”当作线索，而不是当作已经可信的执行事实。真正进入 durable command 前，需要宿主平台做服务端 verifier：引用是否属于当前 run/session、是否是受控协议、是否可能是内联 payload、审批事实是否需要回查、是否过期或跨上下文。
- DataSmart 本阶段在 command outbox writer 前新增 payloadReference verifier 与 fact evidence verifier：
  - payloadReference verifier 校验 `agent-payload:`、`agent-tool-audit://`、`artifact-ref:`、`payload-ref:` 的结构边界和 run/session 绑定；
  - fact evidence verifier 校验 approval/clarification fact id 是否是安全短文本，阻断 URL、SQL、JSON、换行和疑似凭证；
  - writer 在 append outbox 前执行 verifier，不通过则返回 `BLOCKED_BY_SERVER_VERIFICATION`。
- 这一步继续遵守引用优先原则：verifier 不读取真实参数，不展开 payload，不把 prompt、SQL、工具实参、样本数据、模型输出、凭证或内部 endpoint 写入 outbox。它只把低敏 verification status、reference type、accepted evidence 写入命令信封。
- 趋势映射：
  - 这对应 Codex/Claude Code/LangGraph/OpenClaw 类 Agent Host 中“tool call before execution must be revalidated by host”的方向；
  - 工具动作即使已经通过 readiness/proposal，也必须在 writer、dispatcher、worker 三个阶段逐步复核；
  - 模型或外部协议不能通过传一个 payloadReference 或 approval id 直接取得执行权。
- 下一步应从“结构 verifier”进入“事实 verifier”：
  1. payloadReference verifier 接入真实 payload store/client，按 tenant/project/actor/use-case/policyVersion 做二次鉴权。
  2. approval/clarification verifier 接入 confirmation store 或 permission-admin，校验事实存在、未过期、已确认、绑定 graph/contract。
  3. task-management inbox 支持 `AGENT_TOOL_ACTION_CONTROLLED_COMMAND`，并在消费侧继续做 commandId/idempotencyKey 去重。

## 2026-06-07 落地补充：Durable command writers should create facts, not execute tools

- 当前先进 Agent Host 的可靠工具执行链路通常会把“命令事实创建”和“工具真实执行”拆开：先创建 durable command/outbox fact，再由 dispatcher、inbox、worker lease、receipt 和 retry/dead-letter 链路推进。这样可以在网络抖动、服务重启、多实例部署和人工补偿场景下保持可恢复。
- DataSmart 本阶段把 tool action command proposal 推进到 command outbox writer：
  - writer 会重新生成 proposal，不信任客户端缓存；
  - 只有 proposal 返回 `READY_FOR_OUTBOX_CONFIRMATION` 才写入 outbox；
  - 写入的是低敏 command envelope，包含 proposalId、graphId、contractId、payloadReference、policyVersion 和服务端复核要求；
  - writer 不读取 payloadReference、不投递 Kafka、不调用 worker，也不创建真实业务任务。
- 这对应 Codex/Claude Code/LangGraph/OpenClaw-style agent host 的一个重要趋势：工具调用需要 durable execution，但 durable 不等于“立刻执行”。正确分层应是 protocol intake -> readiness/proposal -> durable writer -> dispatcher/inbox -> worker lease -> receipt/event timeline。
- 安全边界继续保持：
  - command payload 不能包含 prompt、SQL、工具实参、样本数据、模型输出、凭证、内部 endpoint 或 task/artifact 正文；
  - targetEndpoint 不应由外部协议或前端推断；
  - payloadReference 只是引用，必须在 writer 之后由服务端 verifier 继续按租户、项目、actor、用途和策略版本回查。
- 下一步趋势落地建议：
  1. 增加 payloadReference verifier，把受控引用读取、权限复核和敏感字段策略独立成组件。
  2. 增加 approval/clarification fact verifier，避免客户端只提交 ID 就绕过人工确认。
  3. 在 task-management 侧建立 command inbox + idempotency table，让 `AGENT_TOOL_ACTION_CONTROLLED_COMMAND` 能被可靠消费。
  4. 将 worker receipt 回写 runtime event，形成 dispatched、accepted、succeeded、failed、retry、dead_letter 的完整低敏 timeline。

## 2026-06-07 落地补充：Command proposals separate readiness from durable writes

- 当前 Codex、Claude Code、LangGraph/OpenClaw 风格 Agent Host 的工具执行趋势，是把“执行前准备度通过”与“真实持久化命令写入”拆成两个阶段。前者回答“这个工具动作是否具备进入确认/写入的最低条件”，后者才真正创建 outbox、触发 worker 或产生副作用。
- DataSmart 本阶段新增工具动作 command proposal：
  - 它消费 execution graph、durable action contract、payloadReference、policyVersion、approval/clarification fact id 等低敏证据；
  - 它只判断是否可以进入正式 outbox writer 的复核，不写 outbox、不创建审批、不读取 payloadReference、不调用 worker；
  - 它把 proposalState、acceptedEvidence、missingEvidence、rejectedEvidence、guardrailNotes 和 recommendedActions 显式返回给控制面，便于前端确认页、审计台和智能网关共同解释下一步。
- Fail-closed 是本阶段的核心原则：
  - graph 已阻断或入口已拒绝时直接阻断；
  - 仍等待审批、澄清、预算或 worker 容量时不允许进入写入；
  - payloadReference 像内联 payload、HTTP endpoint、SQL 片段、凭证片段或过长引用时阻断；
  - 缺少 policyVersion、commandType 或 idempotencyKey 时阻断；
  - 即使传入 approvalConfirmationId，也只被视为低敏线索，正式 writer 仍必须服务端回查审批事实。
- 这条路线能避免一个常见架构误区：把 projection、execution graph 或 MCP `tools/call` intake 直接当作真实工具命令。projection 只能承载低敏治理事实，真实工具参数、SQL、样本数据、模型输出、内部 endpoint 和凭证都必须留在受控 payload 存储中，通过 payloadReference 与二次鉴权读取。
- 下一步趋势落地建议：
  1. 建立正式 outbox writer，由 writer 回查 proposal、graph、contract、payloadReference、审批事实和 worker 容量后再写 durable command。
  2. 建立前端/智能网关确认页，把 proposal 作为用户确认、审批补齐、澄清补齐和风险说明的统一入口。
  3. 将 task-management worker receipt 回写为低敏 runtime fact，让 dispatched、accepted、succeeded、failed、retry、dead_letter 都能被 timeline 和审计台追踪。
  4. 继续跟踪 MCP、A2A、OpenAI Agents SDK、Anthropic Tool Use、LangGraph durable execution、OpenClaw/NemoClaw 类项目，但落地时坚持协议层、治理层、写入层、执行层和审计层分离。

## 2026-06-07 落地补充：Execution graphs make tool governance explainable

- 现代 Agent Host 的工具治理不应只返回一个 `READY`、`WAITING_APPROVAL` 或 `BLOCKED` 字符串。更可商用的形态是把工具动作拆成可解释执行图：协议入口、readiness、审批/澄清/预算、durable contract、outbox command、worker receipt 和结果脱敏。
- DataSmart 本阶段新增工具动作执行图预览：
  - 将 durable action contract 转换为节点和边；
  - 每个节点都解释状态、证据引用、缺失要求和下一步建议；
  - 窗口响应提供 graphState、nodeType 和 missingRequirement 聚合，帮助运营台判断瓶颈集中在哪个阶段。
- 趋势映射：
  - 这对应 LangGraph/OpenClaw-style condition node 的方向，但当前刻意保持只读控制面预览；
  - 图中 outbox 和 worker receipt 节点不是执行器，而是提醒真实副作用必须走可恢复命令和回执；
  - 这种设计能避免 MCP `tools/call`、A2A action、模型 tool_call 三套入口各自绕过治理。
- 安全原则继续不变：
  - 执行图只能保存低敏治理事实和引用；
  - 不保存 prompt、SQL、工具实参、样本数据、模型输出、凭证或内部 endpoint；
  - payload、结果正文和记忆正文都应通过受控引用和二次鉴权读取。
- 下一步应把执行图从“可解释预览”推进到“受控命令生成”：
  1. 由 command builder 校验图状态和 contract 缺口；
  2. 由审批/确认页补齐人类确认事实；
  3. 由 task-management outbox 负责持久化命令；
  4. 由 worker receipt 回写接单、成功、失败、重试和死信事实。

## 2026-06-07 落地补充：Durable action contracts should precede outbox writes

- 当前 Codex、Claude Code、LangGraph/OpenClaw 风格 Agent 的关键趋势，是把“模型或外部协议提出工具动作”与“系统真正执行副作用”拆成多个可审计阶段：意图接收、准备度判断、人工确认、持久化命令、worker 回执、结果脱敏与审计回放。
- DataSmart 本阶段把 `tool_action_intake_recorded` 进一步转换为只读 Durable Action 契约预览：
  - 它不是执行器，也不是 outbox writer；
  - 它是控制面 evidence mapper，用来判断某个工具动作是否具备进入 outbox 的最低条件；
  - 它会显式列出缺失项，例如 payload 引用、幂等键、真实 outbox command、人工审批事实、用户澄清事实和 worker receipt。
- 趋势映射到产品设计时，需要坚持一个很重要的边界：低敏 runtime event 只能证明“发生过什么治理事实”，不能替代真实命令载荷。真实工具参数、SQL、样本数据、模型输出和内部 endpoint 不能为了方便回放而复制到 projection、timeline 或 contract DTO。
- 这条路线能避免两个常见误区：
  - 不把 MCP `tools/call` preview 直接做成真实工具执行入口，防止外部协议绕过 DataSmart 的权限、审批、预算和审计；
  - 不让 Java projection 无限膨胀成万能对象，而是把 projection、contract、outbox、worker receipt 分层，形成可演进的商业化执行链路。
- 下一步趋势落地建议：
  1. 将 durable action contract 接入正式 outbox command builder，并强制要求 payloadReference、idempotencyKey、approvalFact 和 workerReceipt 语义齐备。
  2. 将 contract state 作为执行图条件节点，支持等待审批、等待澄清、等待预算恢复、阻断和入队执行的显式分支。
  3. 继续跟踪 MCP、A2A、OpenAI Agents SDK、Anthropic Tool Use、LangGraph durable execution 与 OpenClaw/NemoClaw 类项目，把工具能力拆成协议层、治理层、执行层和审计层。
  4. 后续若引入 KV cache、长短期记忆或模型路由优化，也应遵守相同原则：缓存和记忆保存低敏决策、索引和引用，不保存 prompt、工具实参或完整模型输出。

## 2026-06-07 落地补充：MCP tools/call intake should become control-plane evidence

- 当前 Agent 工具体系的趋势不是“收到 MCP `tools/call` 就直接执行”，而是让 Agent Host 先把外部工具动作意图转成可治理事实：协议识别、工具可见性、参数形态、readiness、人工审批、预算/队列、outbox 和 worker receipt 都应在真实副作用前发生。
- DataSmart 本阶段把 Python 5.48 的 `tool_action_intake_recorded` 接入 Java `agent-runtime` projection 与 timeline：
  - Java 控制面可以查询外部工具动作入口是否被接收、是否在 readiness 前拒绝、是否等待审批/澄清/预算或是否可进入受控执行；
  - Timeline 可以直接显示“工具动作入口已在准备度前拒绝”等可理解状态，前端不需要自己解析 attributes map；
  - 投影和 display 继续坚持低敏边界，不携带 `arguments`、prompt、SQL、样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。
- 趋势映射：
  - MCP 官方 schema 当前 latest 页面为 `2025-11-25`，schema 覆盖 JSON-RPC、`tools/list`、`tools/call`、任务和通知相关类型；这意味着完整 MCP 能力不是一个 HTTP wrapper，而是一组协议生命周期、能力发现、调用、取消、进度、任务和授权协作。
  - 对 DataSmart 来说，正确演进顺序应是“preview/intake evidence -> readiness graph -> approval/outbox/worker receipt -> controlled execution”，而不是直接把 MCP tools/call 映射成 Java/Python 工具执行器。
- 下一步趋势落地建议：
  1. 把 tool action intake 与 readiness 作为 execution graph condition node，靠图分支决定等待审批、澄清、排队、阻断或进入 outbox。
  2. 设计 durable action/outbox contract，要求每个真实副作用都有 command、幂等键、worker receipt、结果脱敏和审计回放。
  3. 在实现完整 MCP Server 前，先补 tools/list 可见工具目录、authorization scope、session lifecycle、cancel/progress 和低敏错误模型。
  4. 继续跟踪 MCP、A2A、LangGraph/OpenClaw、OpenAI Agents SDK 和 Claude Code 类 Agent Host 的工具治理实践，把“工具调用能力”拆成协议、治理、执行、审计四层，而不是做成单个执行函数。

## 2026-06-06 落地补充：agent hosts should inject signed policy before tool execution

- 最新 Agent 平台实践越来越强调 tools、guardrails、tracing 和 durable execution 的组合：工具调用不能只是模型输出一个函数名，还需要宿主平台在执行前注入权限、预算、风险、审批和可回放证据。
- DataSmart 本阶段把 `X-DataSmart-Tool-Policy-Envelope` 从“Python 可解析的 Header 契约”推进到“gateway `/api/agent/plans` 主链路生成的签名控制面事实”。
- 这一步减少同一请求内的重复策略调用：gateway 可以一次性评估 `toolCallBudget + toolExecutionReadinessPolicy`，Python 远程 provider 在发现 `trustedControlPlane` 已有 signed policy 后直接本地解析，不再回源 permission-admin。
- 该实现继续遵守低敏边界：Header 只放策略版本、角色/套餐/风险/backlog、预算数字、布尔开关和 influenceCodes，不放 prompt、SQL、工具实参、样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。
- 趋势映射到 DataSmart 的下一步不是继续增加 Header 字段，而是把 readiness 变成 OpenClaw/LangGraph-style 执行图条件节点，再把 MCP `tools/call`、A2A action 和模型 tool_call 汇入同一套 durable action/outbox/worker receipt 链路。
- 参考资料：OpenAI Agents SDK Guardrails：`https://openai.github.io/openai-agents-python/guardrails/`；OpenAI Agents SDK Tracing：`https://openai.github.io/openai-agents-python/tracing/`；Anthropic Tool Use：`https://platform.claude.com/docs/en/docs/agents-and-tools/tool-use/overview/`；LangGraph Durable Execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；LangGraph Human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`。

## 2026-06-06 落地补充：policy envelopes reduce duplicate control-plane calls

- 商业化 Agent Host 不能让模型请求体直接决定工具预算、审批、风险阻断或队列策略；这些策略应由受信控制面生成，并通过可验证的边界进入 Agent Runtime。
- DataSmart 本阶段把 `toolCallBudget + toolExecutionReadinessPolicy` 设计为一个 gateway 签名保护的低敏 policy envelope：Java 控制面负责评估，Python Runtime 只负责验签后裁剪、归一化和消费。
- 这一步解决了 5.40 的一个性能与一致性问题：预算 provider 与 readiness provider 不必长期分别同步调用 permission-admin，同一请求可以共享同一次控制面评估结果。
- 低敏边界继续是硬约束。Envelope 只允许策略版本、角色/套餐/风险/backlog 枚举、预算数字、布尔策略开关和 influenceCodes；不能携带 prompt、SQL、工具实参、样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。
- 后续趋势跟进应进入“执行图条件节点”和“统一工具入口”：MCP `tools/call`、A2A action、模型 tool_call 都应该先落到 DataSmart `ToolPlan/readiness`，再由权限、审批、outbox、worker receipt 和审计链路决定是否执行。

## 2026-06-06 落地补充：policy centers should emit execution-readiness contracts

- 当前 Agent Host 的执行前治理正在从“运行时本地判断”演进为“控制面策略合同”：权限中心、租户套餐、队列压力、风险等级和人工确认策略应共同决定工具是否可执行。
- DataSmart 本阶段让 `permission-admin` 输出标准 `toolExecutionReadinessPolicy`，不再只输出旧的 `toolCallBudget`。这让 Python Runtime 可以优先消费 Java 控制面策略，而不是从预算字段里猜测 readiness 语义。
- 该合同将 execution gate 的关键维度固定为 source、policyVersion、actorRole、tenantPlanCode、workspaceRiskLevel、workerBacklogLevel、maxAutoSyncTools、maxAsyncTools 和 influenceCodes。
- 低敏边界仍是核心：permission-admin 返回的是策略摘要，不是权限表达式、prompt、工具参数、SQL、模型输出或凭证。真实动作授权仍要由 action-level evaluate、outbox 和 worker pre-check 再次确认。
- 下一步趋势跟进应把该策略注入 Python `trustedControlPlane`，再进入 LangGraph/OpenClaw-style 条件节点；不要让策略合同停留在只读 API 响应里。

## 2026-06-06 落地补充：execution gates need policy sources, not only local defaults

- 当前 Agent 工具链的发展趋势不是“模型能调用工具就直接执行”，而是先经过 execution gate：权限、风险、预算、队列、人工确认、参数完整性和审计边界都要参与判断。
- DataSmart 本阶段把 readiness policy 从 Python 本地默认值推进到受控策略快照：`trustedControlPlane.toolExecutionReadinessPolicy` 可以下发角色、租户套餐、workspace 风险、worker backlog 和策略版本。
- 这更接近 Codex/Claude Code 类 Agent Host 的生产形态：工具执行前要根据宿主平台的实时治理状态决定是执行、排队、等待审批、请求澄清、仅生成草案还是阻断。
- 低敏边界仍然保持：策略事件只暴露 source、policyVersion、tenantPlanCode、workspaceRiskLevel、workerBacklogLevel 和 influenceCodes，不暴露 prompt、工具参数、SQL、模型输出、凭证或内部 endpoint。
- 下一步趋势跟进应进入“策略中心标准化”和“执行图条件节点”：让 permission-admin 输出稳定策略合同，并让 LangGraph/OpenClaw-style 图把 readiness 作为显式分支，而不是在 API 响应层做隐式判断。

## 2026-06-06 落地补充：execution gates should be queryable by the control plane

- 成熟 Agent Host 不只要在运行时知道 execution readiness，还要让控制面、审计台和 timeline 能查询这层 execution gate。否则用户刷新页面或从 Java 管理台排查时，仍然看不到工具为什么等待审批、澄清或预算恢复。
- DataSmart 本阶段把 Python 5.36 的 `tool_execution_readiness_recorded` 接入 Java `agent-runtime` projection 和 display，新增专用查询入口与 timeline builder。
- 这一步让 readiness 从“Python 响应字段/事件”推进到“Java 控制面事实”，为后续 MCP `tools/call`、A2A action、LangGraph 节点条件和 task-management outbox 提供统一前置治理证据。
- 低敏边界继续保持：Java projection 只解析白名单字段，即使事件 attributes 里意外出现 arguments、payload、SQL、internalEndpoint，也不会返回给 DTO 或 display。
- 下一步趋势跟进应进入策略来源和执行图：让 readiness policy 消费 permission-admin/tool budget/worker backlog，或者把 readiness 作为 LangGraph/OpenClaw-style 节点条件，而不是继续堆 projection 字段。

## 2026-06-06 落地补充：execution readiness must be visible in the agent timeline

- Agent 工具调用治理如果只停留在内部对象里，用户体验仍然像“黑盒”：用户不知道为什么 Agent 没有继续执行，也不知道是等待审批、缺参数、草案展示还是预算限流。
- DataSmart 本阶段把 `ToolExecutionReadinessReport` 接入 `/agent/plans` 响应和 runtime event，新增 `tool_execution_readiness_recorded`，让 HTTP snapshot、WebSocket replay、event publisher 和未来 Java projection 都能看到同一份执行前事实。
- 这一步贴近当前代码 Agent/企业 Agent Host 的趋势：不仅要有 tool call，还要有可解释的 execution gate。Agent 应该能告诉用户“我可以执行哪些，只能展示哪些，哪些要审批，哪些需要你补充信息”。
- 低敏边界继续保持：响应和事件只暴露字段名、工具名、风险等级、执行模式、目标服务、issue/reason code，不暴露参数值、SQL、prompt、样本数据、payload 明细、模型输出、凭证或内部 endpoint。
- 下一步应让 Java 控制面消费 readiness event，或把 readiness 作为 LangGraph/OpenClaw-style 执行图的条件节点；不建议绕过控制面直接在 Python 执行工具。

## 2026-06-06 落地补充：tool calls need execution readiness before execution

- Codex、Claude Code 和 OpenClaw 风格 Agent 的关键能力不是“模型能输出工具名”这么简单，而是运行时能在执行前判断工具是否可自动执行、是否缺参数、是否要审批、是否该入队、是否被预算或风险策略阻断。
- DataSmart 本阶段新增 Python `services/tools` 能力包和 `ToolExecutionReadinessService`，把现有 `ToolPlan` 转换为低敏准备度报告，形成 `READY_TO_EXECUTE`、`DRAFT_ONLY`、`WAITING_APPROVAL`、`NEEDS_CLARIFICATION`、`QUEUED_ASYNC`、`THROTTLED`、`BLOCKED` 等状态。
- 这一步让模型 tool_call 治理从“候选是否接受”推进到“进入执行器前还差什么”。它也为后续 MCP `tools/call`、A2A task action、LangGraph 节点执行、Java outbox worker 共享一套前置判断打基础。
- 低敏边界依然重要：准备度只记录参数字段名、敏感字段名、风险等级、执行模式、issue code 和 reason code，不记录参数真实值、SQL、prompt、样本数据、payload 明细、模型输出、凭证或内部 endpoint。
- 下一步趋势跟进应把 readiness 接入 `/agent/plans` 与 runtime event，让用户看到“Agent 为什么等待审批/澄清/预算恢复”，而不是直接开放 Python 侧真实工具执行器。
- 参考资料：OpenAI Agents tools：`https://openai.github.io/openai-agents-python/tools/`；Anthropic tool use：`https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/overview`；LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`。

## 2026-06-06 落地补充：A2A scheduling evidence should be visible in projections and timeline

- 成熟 Agent Host 的体验不只是“后端知道状态”，还要让前端 timeline、审计台和控制面能解释状态。A2A task planning 如果只存在于 Python `/agent/plans` 响应中，用户刷新页面、WebSocket replay 或 Java 审计查询时仍然看不到“为什么等待授权/用户/诊断”。
- DataSmart 本阶段把 Python 5.33 的 A2A scheduling attributes 接入 Java `AgentSessionSchedulingProjectionService`，新增 A2A 子视图与 mode/state 聚合，让控制面能按 `WAIT_FOR_AUTHORIZATION`、`WAIT_FOR_USER_INPUT`、`REJECTED_OR_DIAGNOSTIC` 等规划模式查询。
- 同时新增 `AgentSessionSchedulingEventDisplayBuilder`，让通用 runtime event timeline 显示 “A2A 任务等待授权” 等人读状态。这样 WebSocket/HTTP replay 不需要前端自己解析自由 Map attributes。
- 低敏边界仍是关键：projection 和 display 都不返回 task id、artifactRef、prompt、工具参数、SQL、artifact 正文、模型输出、凭证或内部 endpoint。timeline 要解释状态，不应该变成第二份任务载荷缓存。
- 下一步趋势跟进可以进入 task fact/task-management 对接，或者把 A2A 子视图接入 handoff DAG 解释节点；仍不建议在缺少幂等、权限、outbox 和 worker pre-check 前开放真实 A2A `message/send`。
- 参考资料：A2A Core Protocol Specification：`https://agent2agent.info/specification/core/`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`。

## 2026-06-06 落地补充：A2A task decisions should shape scheduling before execution

- 当前 Agent Host 趋势不是把外部协议 endpoint 直接接到工具执行，而是让协议状态先进入调度、trace、checkpoint 和 human-in-the-loop 控制面。A2A task 的 submitted、working、input-required、auth-required、completed 等状态，应该先影响 Master Agent 的下一步策略，而不是直接触发 worker。
- DataSmart 本阶段把 Python A2A `planningDecision` 接入 `agentSessionScheduling`：授权等待态会激活权限 Agent，未知状态会激活运维诊断 Agent，用户输入等待态会阻断自动推进。这让外部 Agent 委派任务真正进入 DataSmart 的多 Agent 会话编排。
- 该落地继续坚持低敏 trace 原则。runtime event 只记录 mode、状态、内部阶段、guardrail code 和计数，不记录 taskPublicId、artifactRef、prompt、工具参数、SQL、样本数据、artifact 正文、模型输出或内部 endpoint。
- 这一步也让 DataSmart 的路线更接近 Codex/Claude Code 类 Agent：用户看到的是会话与任务进度，系统内部保留的是可回放、可暂停、可审批、可诊断的调度事实。真实执行仍必须回到 permission-admin、task-management、confirmation/outbox、worker pre-check 和 artifact 二次鉴权。
- 下一步趋势跟进应把 A2A scheduling evidence 接入 Java projection/WebSocket timeline，并设计 task fact 持久化；不要在缺少权限、幂等和 outbox 的情况下开放真实 `message/send`。
- 参考资料：Google ADK A2A Introduction：`https://adk.dev/a2a/intro/`；A2A Core Protocol Specification：`https://agent2agent.info/specification/core/`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

## 2026-06-06 落地补充：A2A planning previews should expose decisions without becoming task endpoints

- A2A 生态的关键不只是 Agent Card 发现，还包括 task 状态、history、artifact、streaming 和 push 的可靠恢复。DataSmart 当前选择继续把 A2A task 当成“受治理的持久工作单元”，而不是把协议入口直接接到工具执行。
- 本阶段新增 Python `POST /agent/protocol-adapters/a2a/task-planning-preview`，让 Java task query preview 或未来真实 task 低敏合同可以在 Python Runtime 中转换为 planning decision。
- 该接口有意只暴露 decision，不回显原始 payload，也不创建、取消或执行 task。这一点很重要：企业 Agent Host 的联调入口如果复制 prompt、工具参数、SQL、artifact 正文或内部 endpoint，很快会变成第二份敏感上下文缓存。
- 从演进顺序看，现在已经有 Java Agent Card/状态机/事件契约/查询预览、Python planning adapter 和 Python planning preview API。下一步更应该把 decision 接入会话调度与 runtime event，而不是直接开放 `message/send`。
- 参考资料：Google ADK A2A Introduction：`https://adk.dev/a2a/intro/`；A2A Core Protocol Specification：`https://agent2agent.info/specification/core/`。

## 2026-06-06 落地补充：A2A task states should become planning inputs before execution endpoints

- A2A 最新实践继续强调跨服务 Agent 协作、Task 生命周期、history 恢复、artifact 引用、streaming 和 push notification。对 DataSmart 这类企业数据治理 Agent Host 来说，最危险的路径不是“不会开放 A2A”，而是过早把 A2A task endpoint 直接接到工具执行。
- 本阶段没有继续在 Java A2A preview 层增加字段，而是把 Java 5.30 已经形成的 task 查询合同交给 Python Runtime 消费：`A2aTaskPlanningAdapter` 会把 submitted、working、input-required、auth-required 和终态状态映射为 Master Agent 可理解的规划模式。
- 这种顺序更接近商业化 Agent 平台：先让编排层理解远程 task 的状态，再决定是否进入权限预检、等待用户输入、等待授权、worker 预检或终态展示；最后才考虑真实 `message/send`、`tasks/get`、stream 或 push。
- DataSmart 当前继续坚持低敏边界：规划决策只保留 task id、context id、状态、阶段、序号、原因码、history 事件摘要和 artifact 引用；不会保留原始消息、工具参数、SQL、样本数据、artifact 正文、模型输出、凭证或内部端点。
- 这一步也回应了 Python Runtime 目录治理趋势：外部协议合同进入 `domain/protocols` 子包，而不是继续平铺到根目录。后续 MCP resource/prompt、A2A Agent Card、task fact、LangGraph 节点输入都可以按同一结构扩展。
- 下一步趋势跟进不应马上打开真实 A2A task 副作用端点。更稳的路线是把 planning decision 接入智能网关会话调度和 runtime event，再设计 task fact 持久化、task-management 对接、幂等、限流、审批和 worker receipt。
- 参考资料：Google ADK A2A Introduction：`https://adk.dev/a2a/intro/`；A2A Core Protocol Specification：`https://agent2agent.info/specification/core/`。

## 2026-06-06 落地补充：model routing decisions should become control-plane projections

- 本阶段把 Python `model_gateway_routed` runtime event 接入 Java 强类型 projection 和 display。这个方向贴近当前 Agent tracing 趋势：成熟 Agent Host 不只记录最终回答，还要能回放 LLM 选择、工具调用、handoff、guardrail 和自定义事件。
- DataSmart 当前没有让 Java 重新计算模型路由，而是把 Python 已经产生的低敏事实转换成控制面视图：selectedProvider、selectedModel、health status、fallback、budget、cache plan 和 route scoring 摘要。这样可以避免 Java/Python 双写两套路由判断。
- 该投影继续遵守低敏边界：不返回 prompt、messages、工具参数、SQL、URL、API Key、模型输出、真实 cache key、isolationKey 或 KV cache。Provider 名称和模型名称属于控制面事实，但仍经过租户/项目/本人范围收口。
- 这一步让模型网关能力从“Python 内部 runtime event”进入 Java 查询面，为后续 WebSocket timeline、审计导出、Grafana 排障跳转和 provider health 诊断联动打基础。
- 下一步不建议继续无限增加 projection 字段。更高价值路线是转向 MCP/A2A adapter 草案，或者只补一小步 provider health diagnostics 只读快照，然后切到多 Agent 会话调度与外部协议兼容。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-js/guides/tracing/`；OpenAI Agents Python tracing：`https://openai.github.io/openai-agents-python/tracing/`。

## 2026-06-06 落地补充：provider health probes need aggregate metrics, not provider labels

- 本阶段把模型 Provider 主动健康探测接入 Python Runtime `/agent/metrics`。这对应 Agent Host 生产化趋势：模型网关不只要能 fallback，还要让运维能看到健康探测是否持续运行、失败率是否升高、最近一轮是否出现大量 unavailable。
- DataSmart 当前选择把指标做成低基数聚合：累计 runs/outcomes、最近一轮 candidate/probed/truncated、按 `status` 的 Provider 数量、探测配置开关和限额。没有把 providerName、URL、tenantId、projectId、runId、traceId 放进 label。
- 这个边界很重要。Prometheus 官方实践强调标签不应承载高基数或无界值，指标名称和标签也应保持稳定；单个 Provider 的诊断明细应留在诊断接口、runtime event、审计或日志中，而不是变成时序数据库的标签。
- 本阶段继续保持 Python Runtime 默认零依赖，没有引入 `prometheus_client`。这适合当前学习和单元测试环境；未来如果接 Histogram、multiprocess collector 或 OpenTelemetry bridge，可以保持 `/agent/metrics` 契约不变，替换内部渲染器。
- 下一步不建议继续只堆 Provider 探测字段。更高价值路线是把 `MODEL_GATEWAY_ROUTED` v2、provider health diagnostics 接入 Java projection/WebSocket timeline，或者切到 MCP/A2A adapter 与多 Agent 会话调度，让模型网关能力回到整体 Agent 平台闭环。
- 参考资料：Prometheus metric and label naming：`https://prometheus.io/docs/practices/naming/`；Prometheus instrumentation best practices：`https://prometheus.io/docs/practices/instrumentation/`。

## 2026-06-05 落地补充：semantic memory adapters should enforce metadata filters first

- 本阶段新增 Chroma-compatible semantic memory adapter，把 `semantic + vector` 同步任务从 no-op 推进到可注入真实 collection 的端口实现。它不强依赖 Chroma SDK，而是定义 `ChromaCollectionPort` 和 `AgentMemoryEmbeddingProvider`，让 Chroma Open Source、Chroma Cloud、pgvector 或企业内部向量平台都能接入。
- 关键工程点不是“能不能 upsert 一段文本”，而是 metadata filter 边界。adapter 强制写入 tenantId、projectId、sessionId、workspaceKey、memoryNamespace、memoryType、scope 和 payloadPolicy，避免向量库后续跨 workspace 或跨项目召回。
- 当前仍未启用真实 Chroma 查询，也没有批量 embedding worker。DataSmart 选择先落同步 adapter 和 metadata 契约，是为了避免常见误区：向量库接得很快，但忘记审批、namespace、遗忘、重建、指标和审计。
- 这一步完成后，长期记忆主线已经有候选治理、正式 store、物化 runner、审计 outbox、二级索引路由、同步 worker 和 semantic vector adapter 端口。下一步更应切到多 Agent 协作/智能网关会话调度，把项目从 memory 局部带回整体 Agent 平台。

## 2026-06-05 落地补充：secondary memory indexes need durable sync tasks

- 本阶段把长期记忆二级索引从“路由契约”推进到“同步任务契约”。成熟 Agent memory 不是把正式记忆写入 store 就结束了，还要把同一条受治理记忆同步到 vector、graph、resource 或 keyword 索引，并能观察哪些索引已经同步、哪些失败、哪些进入 DLQ。
- DataSmart 当前新增 `AgentMemorySecondaryIndexSyncScheduler` 和 `AgentMemorySecondaryIndexSyncWorker`。materializer 写入正式 store 后会创建二级索引同步任务；worker 默认使用 no-op adapter 验证状态机，后续可以替换为 Chroma、Neo4j、MinIO 或企业内部索引服务。
- 这一步继续保持安全边界：同步任务只保存 memoryId、candidateId、tenant/project/session、workspaceKey、memoryNamespace、indexKind 和 action，不保存记忆正文、prompt、SQL、样本数据或工具原始输出。
- 当前不是完整向量库上线，而是先把 upsert/delete/expire、pending/synced/failed/dead_letter、退避和诊断固定下来。这样后续真实 adapter 出问题时，不会推翻 materializer 或 retriever 主链路。
- 下一步如果继续 memory 主线，建议接一个真实 semantic memory adapter，并补 ready task、sync failure、DLQ、index latency 等低基数指标；随后应切到多 Agent 协作或智能网关会话调度，保持平台整体节奏。

## 2026-06-05 落地补充：long-term memory needs routed secondary indexes

- 本阶段把长期记忆检索从“正式 store 候选窗口 + 关键词排序”推进到“二级索引路由契约”。成熟 Agent 记忆系统不会只靠一种检索方式：语义知识适合 vector，流程和依赖适合 graph，事件经验适合 keyword/event index，报告和日志适合 resource index。
- DataSmart 当前新增 `AgentMemorySecondaryIndexRouter`，让每个 `AgentMemoryRetrievalTarget` 都能表达首选索引、实际索引、fallback 原因和候选窗口大小。这样 API 诊断和检索报告能明确告诉运维：本次到底是走了 vector、graph、resource，还是因为索引不可用退回 keyword。
- 这一步刻意不直接连接 Chroma、Neo4j 或 MinIO。原因是索引同步、namespace metadata filter、删除/遗忘、重建和观测指标都需要先有稳定契约；先把路由和诊断放进主链路，后续替换具体索引实现风险更低。
- 关键安全边界没有放松：即使将来使用向量相似度或图谱 traversal，索引层也必须先执行 tenant/project/session/memoryNamespace 过滤，不能先召回全局结果再交给模型“自行忽略”。
- 下一步建议做二级索引同步 worker 契约和 Chroma/pgvector semantic adapter；完成一个真实索引通道后，应切到多 Agent 协作或智能网关会话调度主线，避免长期记忆局部继续吞掉全部节奏。

## 2026-06-05 落地补充：cache admission decisions, not user prompts or full plans

- 本阶段把 gateway 会话级 READY Skill cache 从规划建议推进到主路径契约：gateway 为 `/api/agent/plans` 生成低敏缓存上下文，Python Runtime 验签后只缓存 Skill admission decision。
- 这个落地点对应当前 Agent host 的一条重要趋势：性能优化不能只理解成“缓存模型回答”。Codex、Claude Code 类工具更需要把工具/Skill 暴露边界、权限事实、会话能力集、模型前缀和工具 schema 分层缓存；其中用户 prompt、完整计划和工具结果通常不适合跨请求复用。
- DataSmart 当前选择缓存“控制面准入判断”，而不是缓存完整 `AgentPlan`。语义评分仍按每次 objective 重新执行，避免用户目标变化后复用错误能力排序；准入判断则按 gateway key、project、session、Skill Manifest 指纹、skillCode 和策略版本短期复用。
- gateway 生成的 cache key 被纳入 Java/Python HMAC 签名协议，Python 不相信终端自报缓存 key。这是多租户 Agent 平台必须具备的基本边界，否则高并发缓存会变成权限绕过入口。
- 下一步如果继续沿性能主线，建议优先做 Redis/分布式缓存、命中率指标和真实策略版本回填；但从项目整体节奏看，更推荐切到长期记忆二级索引或多 Agent 协作主线，避免 Skill 可见性局部继续过度扩张。

## 2026-06-04 落地补充：session capability facts should be materialized into dedicated indexes

- 本阶段把 Java `agent-runtime` 的 Skill 可见性查询从“扫描通用 runtime event 热窗口”推进到“专用索引端口 + 内存物化”。成熟 Agent host 里，工具/Skill 暴露边界不应永远停留在自由 attributes 扫描，否则 Marketplace、审计台和前端治理卡片都会被事件内部结构绑死。
- 新增 `AgentSkillVisibilitySnapshotIndexStore` 和内存实现后，consumer 首次接收 `skill_visibility_snapshot_recorded` 会把低敏快照物化到专用索引；查询服务优先读专用索引，并通过 `indexSource` 告诉调用方当前来自专用索引还是 projection fallback。
- 这一步仍然保持克制：没有马上把表结构固化到 MySQL，也没有引入 ClickHouse/OpenSearch。原因是 Skill 可见性事件还在快速演进，先稳定端口、查询语义、权限收口和 DTO，后续再接持久化 store 更稳。
- 从产品角度看，专用索引是从“运行时可回放”走向“运营可分析”的中间层。后续可以按 tenant/project/actorRole/permissionFactSource/manifestFingerprint/hiddenAdmissionStatus 聚合，回答灰度、套餐、权限缺口和策略漂移问题。
- 下一步建议落 MySQL 或 ClickHouse 索引实现，并补低基数诊断指标：索引物化成功数、重复数、fallback 查询数、按 Manifest 指纹的窗口分布。完成后应考虑切到 gateway 会话级 Skill cache，而不是继续只堆查询字段。

## 2026-06-04 落地补充：agent control planes should index session capability facts

- 本阶段把 Python Runtime 的 `skill_visibility_snapshot_recorded` 事件接入 Java `agent-runtime` 专用查询视图。成熟 Agent host 不只在运行时生成事件，还需要控制面能按产品语义查询这些事件，否则前端、审计台和运营报表会被迫解析自由 attributes。
- DataSmart 当前没有急着新建持久化表，而是先复用现有 runtime event projection store，新增强类型 `AgentSkillVisibilitySnapshotProjectionView` 和 `/runtime-events/skill-visibility-snapshots` 只读入口。这是一种渐进路线：先稳定跨语言事件语义，再落 SQL/ClickHouse/OpenSearch 索引。
- 该视图保留低敏字段：可见/隐藏数量、Skill code、权限事实来源、Manifest 绑定状态、Manifest 指纹、风险/领域/隐藏状态分布、策略版本和 replaySequence。它仍不返回 prompt、SQL、工具参数、完整权限清单或长期记忆正文。
- Java display 层现在能把该事件解释为 `SKILL_VISIBILITY`，而不是泛化系统事件；BASIC 用户可以看到进度但属性被脱敏。这让 HTTP replay、WebSocket 断线恢复和前端 timeline 的体验更接近 Codex/Claude Code 类 Agent host 的“可解释工具/Skill 暴露边界”。
- Manifest `contentFingerprint` 已进入 Python 事件与 Java 专用投影；下一步应把热窗口查询升级为持久化 replay index。只有跨实例、跨重启保留这些 Manifest 版本事实，平台才能在灰度、回滚和事故复盘时长期回答“会话当时使用的是哪版能力目录”。

## 2026-06-04 落地补充：session skill visibility should be replayable, not only returned once

- 本阶段把 `intelligentGatewayGovernance.skillVisibility` 写入 `SKILL_VISIBILITY_SNAPSHOT_RECORDED` runtime event。这个变化看似小，但它把“当前会话可见能力集”从一次性 HTTP 展示字段推进为可 replay 的运行时事实。
- 成熟 Agent host 不只需要“实时规划”，还需要用户刷新、WebSocket 断线、Java 控制面补索引、审计员回放和运营报表都能还原当时的能力边界。否则 Skill Marketplace 越丰富，事故复盘时越难回答“模型当时是否真的看见了某个能力”。
- DataSmart 当前选择只写低敏聚合属性：Skill code、数量、风险/领域/隐藏状态分布、权限事实来源、策略版本和推荐动作数量；不写 prompt、SQL、工具参数、完整权限清单或记忆正文。这符合 Agent runtime event 的产品定位：公共时间线保存事实摘要，敏感排障走受控详情接口。
- 实时事件可见性策略已允许普通用户看到该事件的进度存在，但 BASIC 级别仍会脱敏 attributes。项目负责人、审计员和管理员可以按既有策略看到低敏聚合字段，用于治理卡片、回放和排障。
- 当前事件已接入 Java 热窗口 replay/index 查询视图，并携带 Manifest `contentFingerprint`；下一步应落地持久化索引。这样才能支持灰度对比、租户能力包版本追踪和策略漂移排查。

## 2026-06-04 落地补充：agent hosts need session-level skill visibility, not only global catalogs

- 本阶段把 `intelligentGatewayGovernance` 从 Skill 准入摘要继续推进到会话级 Skill 可见性快照。成熟 Agent host 不只需要知道“平台有哪些 Skill”，还要知道“当前会话、当前角色、当前权限和当前预算下，哪些 Skill 真正可见”。
- 这个方向贴近 Codex、Claude Code 类 Agent host 的工程体验：工具和 Skill 暴露给模型前，宿主需要先按身份、workspace、预算、风险和确认策略过滤，再把可见集合交给规划器或 UI。否则全局目录越丰富，越容易把用户当前不能用的能力泄露给模型。
- DataSmart 当前选择复用本轮 `AgentSkillPlan` 生成快照，而不是响应阶段重新拉 Manifest 或重新调用 permission-admin。这是为了避免二次决策导致“计划实际使用的 Skill”和“网关展示的 Skill”不一致。
- 快照显式标记事实来源：`trusted-control-plane`、`legacy-request-variables` 或 `missing`。这能帮助迁移旧联调路径，同时提醒生产环境必须由 gateway 注入可信控制面事实。
- 当前 `skillVisibility` 已写入 runtime event，并与 Manifest `contentFingerprint` 绑定后进入 Java 专用查询视图；下一步应把它从内存热窗口推进到持久化索引或会话 cache，形成跨实例可查询、可排障、可灰度对比的会话能力事实。

## 2026-06-04 落地补充：agent runtimes should expose the skill catalog they actually see

- 本阶段把 Skill Publication Manifest 接入 Python Runtime 启动诊断。成熟 Agent host 不只是“有一个能力目录”，还必须能回答当前运行时实际看见了哪版能力目录、READY 能力有多少、哪些能力因为审批/审计/隔离问题不能进入规划。
- `manifestFingerprint` 是运行时可解释性的关键锚点。后续如果同一个租户在多个 Python Runtime 实例、多个 gateway 会话或灰度批次中出现规划差异，指纹可以帮助快速判断是不是能力目录版本不一致。
- 当前诊断显式区分 `REMOTE_READY`、`REMOTE_UNAVAILABLE_FALLBACK`、`REMOTE_NOT_REFRESHED` 和 `LOCAL_DEFAULT_ONLY`。这比静默回退更适合商业化产品：开发环境可以 fallback，生产环境必须知道自己是否偏离 Java 控制面的发布事实源。
- 本阶段没有直接改变 Agent 规划主路径，是刻意渐进：先观测 Manifest 健康，再接会话级能力快照、缓存刷新、runtime event 和 Prometheus。这样不会让发布目录新能力上线时立刻改变模型规划结果，降低迁移风险。
- Manifest 指纹已经写入 runtime event 与智能网关会话快照；下一步建议把 Skill 可见性从单次响应事实推进到租户、项目、角色、权限包和预算策略过滤后的可缓存会话能力集。

## 2026-06-04 落地补充：skills need publishable manifests before full MCP adapters

- 本阶段把 Agent Skill 从普通 descriptor 列表推进到 `Skill Publication Manifest`。这对应当前 Agent 工程趋势：能力不能只散落在本地 prompt、工具函数和配置文件里，而应该先形成可发现、可版本化、可缓存、可诊断的目录事实源。
- MCP 最新规范继续围绕 tools、resources、prompts、elicitation、sampling 和 task support 演进。DataSmart 当前没有直接宣称“已经实现完整 MCP Server”，而是先落地内部 MCP-style Manifest：Java 控制面负责发布事实，Python Runtime 和智能网关负责消费、缓存和后续协议适配。
- `contentFingerprint` 是这一步的关键工程点。成熟 Agent host 不应每次用户消息都全量刷新能力目录，而应能判断“远端 Skill 目录是否真的变化”，并在启动诊断、灰度对比、缓存复用和事故排查中给出稳定证据。
- `publicationState` 把 Skill 治理变成机器可读状态：禁用、缺审批、缺审计、缺隔离和 READY 被明确区分。这样未来前端市场、Python 规划器和网关策略不会各自发明一套判断逻辑。
- 下一步不建议马上堆一个完整 MCP Server 外壳。更稳的商业化路线是先把 Manifest 接入 Python 启动诊断和智能网关会话能力快照，再推进数据库发布流、租户级可见性、灰度/回滚，最后由适配层转换为 MCP tools/resources/prompts 或 A2A Agent Card。

参考资料：MCP 最新规范：`https://modelcontextprotocol.io/specification/2025-11-25`；MCP Tools：`https://modelcontextprotocol.io/specification/2025-11-25/server/tools`；MCP Resources：`https://modelcontextprotocol.io/specification/2025-11-25/server/resources`；MCP Prompts：`https://modelcontextprotocol.io/specification/2025-11-25/server/prompts`；MCP Elicitation：`https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation`。

## 2026-06-04 落地补充：guardrails need aggregate metrics after timeline facts

- 本阶段把 dispatcher pre-check 的 `ALLOW_EXECUTION/BLOCKED/DEFERRED` 和 issueCode 分布接入 Micrometer/Prometheus，并新增 Alertmanager 规则草案。这是对上一阶段 runtime event 的补齐：timeline fact 解释单次动作，aggregate metrics 判断平台趋势和运营风险。
- Codex、Claude Code 类 Agent 的成熟体验并不只是“模型能调用工具”，而是工具调用宿主能持续回答：最近有多少动作被允许，多少被阻断，多少因容量/熔断暂缓，多少是用户确认过期导致。没有聚合指标，平台只能事后查日志，无法做值班告警和容量治理。
- DataSmart 当前延续低基数纪律：指标标签只保留 `decision/issueCode/targetService`，未知值归并 `OTHER`；`commandId/runId/sessionId/traceId/tenantId/projectId` 继续留给 runtime event、outbox 诊断和审计链路，而不是进入 Prometheus。
- 本阶段还修正了 Java Agent Runtime 与 Python AI Runtime 的默认端口边界：Java 控制面使用 `8091/actuator/prometheus`，Python 运行时使用 `8090/agent/metrics`。这类部署细节看似小，但如果不治理，会让本地联调和 Prometheus 告警长期产生误报。
- 下一步不建议继续沿着 pre-check 无限制细化。更合理的产品路线是把注意力切到 MCP/Skill 发布流、长期记忆二级索引 worker 或智能网关多 Agent 协作；如果短期继续运维闭环，则只补 outbox 积压和最老 pending 年龄这类关键平台信号。

## 2026-06-04 落地补充：pre-check decisions should become timeline events

- 本阶段把 dispatcher pre-check 的 `BLOCKED/DEFERRED` 结果写入 runtime event，并增强 display 解释。这对应成熟 Agent host 的趋势：工具调用治理不能只存在于后端状态机、日志或开发者控制台，而应成为用户和运营可见的 timeline fact。
- 对 Codex、Claude Code 类 Agent 来说，工具调用能力的关键不只是“会调用工具”，还包括：调用前能确认、调用中能限流/沙箱、调用后能审计、异常时能清楚解释为什么没有发生副作用。DataSmart 当前把这条链路推进到了“执行前复核结果可 replay”。
- 当前事件只写低敏摘要：`toolCode/targetService/commandId/preCheckDecision/issueCodes/currentState`。这延续了生产化监控的低基数原则：Prometheus、审计台和前端时间线需要稳定事实，不应把 prompt、SQL、工具参数或 payload 放进公共事件流。
- `DEFERRED` 与 `BLOCKED` 的展示差异很重要：前者暗示自动退避重试和容量/熔断恢复，后者暗示重新确认、人工补偿或管理员治理。把这两者混成一个“失败”会直接降低商业产品的可运营性。
- 下一步建议先补低基数指标和告警，然后不要继续只在 Java Agent Runtime 局部扩展，应切换到 MCP/Skill 发布流、长期记忆二级索引 worker 或智能网关多 Agent 协作，把 Agent 平台从“工具执行控制面”推进到更完整的智能工作台。

## 2026-06-04 落地补充：pre-check should gate dispatch, not only document intent

- 本阶段把异步 command pre-check 从只读契约推进到 dispatcher 投递前安全闸门。这个变化很关键：如果 pre-check 只存在于文档或诊断接口里，真实消息仍可能绕过确认、沙箱和容量判断进入 task-management。
- DataSmart 当前选择“默认关闭、配置开启”的灰度策略。成熟企业系统里，历史 command、本地学习环境和生产 selected-node 主路径成熟度不同，直接全局 fail-closed 可能造成迁移事故；可配置开关让团队可以先在集成环境验证，再逐步推向生产。
- `BLOCKED -> BLOCKED`、`DEFERRED -> FAILED + nextRetryAt` 的映射体现了 Agent durable action 的两个不同恢复语义：确认过期/沙箱拒绝需要人工或重新确认；容量满额/熔断暂缓适合退避重试。
- 下一步应把 pre-check verdict 事件化和指标化。没有 runtime event，前端时间线看不到“为什么 worker 没执行”；没有低基数指标，运营也无法区分确认过期率、策略漂移率、容量暂缓率和沙箱拒绝率。

## 2026-06-04 落地补充：workers need a final pre-check, not just an outbox record

- 本阶段新增异步 command worker pre-check 契约。它对应成熟 Agent 平台的 durable action 思路：命令已经入箱只代表“曾经被确认过”，不代表 worker 领取时仍然可以执行真实副作用。
- DataSmart 的 pre-check 把 selected-node confirmation、当前 execution-policy、sandbox verdict、runtime-protection verdict 和 payload 中的 policyVersions 合并成一个 verdict。这比在 worker 中散落多个 if 判断更适合商业化产品，因为所有拒绝、暂缓、审计和告警都能共享同一套 issueCodes。
- `DEFERRED` 与 `BLOCKED` 的区分很重要：容量满额、目标服务熔断、策略服务暂不可用适合退避重试；确认过期、缺 confirmation、沙箱拒绝、策略不再允许则应阻断并等待重新确认或管理员处理。
- 本阶段没有直接启动真实 worker，是刻意控制节奏。先固定 pre-check 契约和测试，再接 worker、runtime event、Prometheus 和补偿入口，可以避免一边执行副作用一边重构安全语义。
- 下一步趋势落地应把 pre-check 接入 dispatcher/worker，并让每次 `BLOCKED/DEFERRED` 成为可 replay 事件和低基数指标；随后切换到 MCP/Skill 发布流、长期记忆二级索引或智能网关多 Agent 协作，保持项目整体均衡。

## 2026-06-04 落地补充：guardrail issue codes should become replayable facts

- 本阶段把 sandbox/runtime-protection 的 issueCodes 接入 DAG dry-run runtime event 和 display。这个方向对应成熟 Agent host 的一个关键趋势：工具调用保护不能只在当次 HTTP 响应里解释，必须进入可 replay、可审计、可运营聚合的事件事实层。
- DataSmart 当前没有把完整 reasons、工具参数、executionPath 或 payload 写入事件，而是只写 `sandboxRejectedCount`、`runtimeProtectionRejectedCount`、去重 issueCodes、熔断数和容量拒绝数。这是有意的低基数设计：看板和告警需要稳定枚举与计数，敏感排障需要受控详情接口，不应把两者混在一起。
- display 层现在能把“被阻断”进一步解释成“沙箱拒绝、容量拒绝、目标服务熔断”。这比单一 blockedCount 更接近 Codex/Claude Code 类 Agent host 的运营体验：模型可以规划工具，但宿主平台需要解释为什么动作暂缓、应由谁处理、是否适合自动重试。
- 下一步趋势落地有两条分支：
  - 短期继续 Agent Runtime：把这些低基数摘要接入 Prometheus、告警和真实 DAG worker pre-check；
  - 平衡项目整体：切换到 MCP/Skill 发布流、长期记忆 Chroma/Neo4j 二级索引 worker 或智能网关多 Agent 协作。
- 关键边界：当前仍是事件展示和内存投影，不是强审计 outbox，也不是全局分布式限流。生产化还需要持久化事件、指标、告警、服务网格/mTLS、全局配额和运维手动摘除。

## 2026-06-03 落地补充：runtime backpressure must be visible before DAG execution

- 本阶段把 runtime-protection verdict 从真实 execute 入口继续传播到 execution-policy、DAG preview 和 dry-run。这对应成熟 Agent host 的一个关键趋势：工具执行的容量、熔断和配额不能只在最后一刻失败，而应该在计划、预览、dry-run 和 worker pre-check 阶段就变成可解释的控制面事实。
- DataSmart 新增 `inspectExecutionAdmission(...)`，用于只读预判“如果现在启动一次执行是否会超限”。这和当前状态诊断不同：当前状态可能没有超过上限，但再启动一次就会超过。Agent 批量策略必须使用准入预判，否则会在满额边界把不可执行节点展示成候选。
- Run/DAG 批量响应现在同时携带 sandbox verdict 与 runtime-protection verdict。前者解决 tool safety，后者解决 host-side backpressure 与 target service health，两者组合后才接近 Codex/Claude Code 类 Agent 的真实工具治理形态。
- 当前仍保持 JVM 内存级实现，适合单实例学习和早期联调；生产趋势应继续迁移到 Redis 原子令牌桶、租户配额中心、服务网格熔断、低基数 Prometheus 指标、runtime event display 和运维手动摘除/恢复。
- 下一步不建议继续无限扩展本地保护细节；更合理的路线是先把 issueCodes 接入可观测事件与指标，再进入真实 DAG worker pre-check，随后切换到 MCP/Skill 发布流、长期记忆索引 worker 或智能网关多 Agent 协作。


## 2026-06-03 落地补充：tool execution needs runtime backpressure and circuit breakers

- 本阶段把 `agent-runtime` 工具执行链路从 sandbox verdict 继续推进到 runtime-protection verdict。前者回答“工具计划是否安全”，后者回答“当前是否因为容量、租户压力或目标服务故障而应该暂缓执行”。
- 这对应成熟 Agent 工程里的 host-side backpressure 思路：模型可以规划工具，但 host 必须控制工具执行速率、并发、超时、失败冷却和下游健康，避免自动化能力把小故障放大成平台事故。
- DataSmart 当前落地点：
  - 新增 `AgentToolRuntimeProtectionService`，使用 `inspect(...)` 输出低敏 verdict，使用 `beginExecution(...)` lease 控制真实执行入口；
  - `AgentToolExecutionService` 在 sandbox guard 后申请 lease，执行结束后记录 success/failure，并按 targetService 维护连续失败熔断；
  - 新增 `runtime-protection-policy` 只读接口，让前端、Python Runtime 和运维台可以解释容量拒绝与熔断拒绝；
  - 配置上把全局并发、租户并发、目标服务并发、连续失败阈值和熔断冷却期显式暴露。
- 当前实现刻意不直接引入复杂中间件：JVM 内存计数适合当前单实例学习与联调；生产多实例应继续迁移到 Redis 原子令牌桶、租户配额中心、服务网格熔断、Prometheus 指标和管理台手动摘除/恢复。
- 趋势映射：MCP Tools 规范强调工具调用必须由服务端控制访问、校验输入、限流和清洗输出；OpenAI Agents SDK guardrails 强调 tool/agent 调用链路应具备 guardrail。DataSmart 本次把这些思想转化为“host-side runtime verdict + lease”，让工具执行不是单纯的函数调用，而是受控运行时资源。
- 下一步技术路线：
  - 将 runtime-protection verdict 传播到 Run/DAG 批量策略；
  - 将容量拒绝、熔断打开、连续失败计数映射为低基数指标；
  - 真实 DAG worker 上线前，把 runtime-protection、sandbox、permission-admin evaluate 和 confirmation evidence 合并为 worker pre-check；
  - 随后应切换一部分精力到 MCP/Skill 发布流、长期记忆索引 worker 或智能网关多 Agent 协作，避免只在工具保护局部无限加码。

参考资料：MCP Tools specification：`https://modelcontextprotocol.io/specification/draft/server/tools`；OpenAI Agents SDK guardrails：`https://openai.github.io/openai-agents-js/guides/guardrails`。

## 2026-06-03 落地补充：sandbox verdicts must propagate into batch planning

- 本阶段把工具调用沙箱从“单工具执行前检查”推进到“Run/DAG 批量策略面共享 verdict”。这对应成熟 Agent 工程的一个关键点：安全判断不能只存在于最后 execute 入口，否则 preview、dry-run、前端按钮和 Python Runtime 仍可能把危险动作显示成候选。
- DataSmart 当前让 execution-policy、DAG preview、DAG dry-run 共同透传 `sandboxAllowed/isolationMode/issueCodes/reasons/recommendedActions`。这让调用方在一次 Run 级响应中同时看到依赖、审批、参数、服务授权和沙箱阻断。
- MCP Tools 规范强调服务端必须做访问控制、输入校验、限流和输出清洗；OpenAI Agents SDK 也把 guardrails 放在 agent/tool 调用链路中。DataSmart 的本次落点是把这些理念转成“host-side verdict 作为批量调度输入”，而不是只在 UI 上做提示。
- 这一步仍不等同于容器沙箱。它先解决控制面事实一致性：只要沙箱拒绝，Run policy 就降级为 `BLOCKED_BY_POLICY`，DAG preview/dry-run 就不会继续把该节点当成可执行候选。
- 下一步趋势落地应把沙箱 issueCodes 接入 runtime event、低基数指标和真实 DAG worker pre-check；随后再推进 SQL dry-run、HTTP egress allow-list、工具级限流和执行器隔离。
- 参考资料：MCP Tools specification：`https://modelcontextprotocol.io/specification/draft/server/tools`；OpenAI Agents SDK guardrails：`https://openai.github.io/openai-agents-js/guides/guardrails`。

## 2026-06-03 落地补充：tool execution needs host-side sandbox verdicts

- 本阶段把 `agent-runtime` 的工具执行前治理推进到“host-side sandbox verdict”。这对应当前 Agent 工程趋势：工具调用不能只靠模型自觉，也不能只靠 prompt 约束；真正执行工具的宿主/控制面必须在每次工具调用前后执行 guardrail、权限、参数、超时、输出和审计治理。
- MCP Tools 官方规范把工具定位为模型可自动发现和调用的能力，同时要求服务端验证输入、实现访问控制、限流、清洗输出；客户端也应对敏感操作提示确认、展示工具输入、校验结果、设置超时并记录审计。DataSmart 当前把这些要求转成 Java 控制面沙箱：注册表一致性、targetService 准入、参数体量、审批事实和幂等重试先落地。
- OpenAI Agents SDK 文档也明确区分 agent 级 guardrails 与 tool guardrails：tool guardrails 会围绕每次 function-tool invocation 执行。DataSmart 的落点不是照搬 SDK，而是把同类思想放进 `AgentToolExecutionGuard + AgentToolSandboxPolicyService`，让 Python Runtime、前端、DAG worker 和手动 execute 都共享 Java 控制面的执行前 verdict。
- 当前实现刻意保持低敏：诊断接口返回 issueCodes、中文 reasons、推荐动作和参数体量，不返回完整 tool arguments、prompt、SQL、样本数据或密钥。成熟 Agent 平台的可解释性不能以泄露上下文为代价。
- 这一步也把“工具沙箱”从未来大词拆成可递进能力：当前是控制面策略沙箱；下一步可以接工具级限流、下游健康熔断、SQL dry-run、HTTP egress allow-list、容器级隔离、输出脱敏和审计 outbox。
- 参考资料：MCP Tools specification：`https://modelcontextprotocol.io/specification/2024-11-05/server/tools`；OpenAI Agents SDK guardrails：`https://openai.github.io/openai-agents-js/guides/guardrails/`；OpenAI Agents SDK tracing：`https://github.com/openai/openai-agents-python/blob/main/docs/tracing.md`。

## 2026-06-03 落地补充：agent model gateways need health-aware routing

- 本阶段把模型网关从“静态路由 + dry-run/openai-compatible provider 抽象”继续推进到“健康感知路由”。这对应 Codex、Claude Code 类 Agent 的真实工程趋势：模型调用层必须具备 provider health、fallback、熔断、预算、缓存和工具调用治理，而不是把所有请求无条件发给一个默认模型。
- DataSmart 新增的 Provider Health Registry 先从真实调用结果出发：成功、失败、错误码和延迟会影响下一次路由选择。连续失败会打开熔断窗口；错误率需要最小样本数才会参与状态判定，避免一次偶发 503 造成过度 fallback。
- 这一步没有直接伪造外部 `/health` 调用，也没有引入具体供应商 SDK。这样的边界更稳：未来接 OpenAI-compatible、vLLM、SGLang、LiteLLM 或企业内部模型网关时，只需要把健康事件回灌到同一 registry。
- `/agent/models/provider-health/diagnostics` 只返回低敏诊断，不返回 prompt、工具参数、模型正文或密钥。成熟 Agent 平台的诊断面板必须解释“为什么 fallback”，但不能变成上下文泄漏面。
- 趋势落地建议：下一阶段应把健康摘要接入 Prometheus 和告警，并继续推进 KV/prefix cache 命中率、provider quality score、tenant package、灰度路由和工具调用沙箱。不要把模型网关停留在“能调通一个模型”的 demo 层。

## 2026-06-03 落地补充：memory materialization needs audit outbox

- 本阶段把长期记忆物化从“有 runtime event、指标和告警”继续推进到“有审计 outbox”。这对应成熟 Agent durable action 的关键趋势：模型或 worker 产生副作用后，平台不仅要能看见和告警，还要能把动作事实交给审计系统、客户归档或合规复盘。
- DataSmart 新增的审计 outbox 不替代 Runtime Event，也不替代 Prometheus。Runtime Event 负责 replay 和时间线，Prometheus 负责聚合告警，audit outbox 负责“谁在什么时间对哪个候选/批次做了什么，是否 dry-run，是否留下可派发审计事实”。
- 当前支持 in-memory、SQLite、MySQL，并把 `enabled`、`required`、`store_fail_open` 分开配置。这是商业化产品常见的渐进式安全姿态：本地学习环境默认不被数据库绑定，生产强合规环境可以选择 fail-fast/fail-closed。
- 本阶段也明确承认边界：`required=true` 能让 worker/API 把审计写入失败显式暴露，但还不是同库事务级 outbox，不能自动回滚已经提交的 lease/formal memory 状态。下一步应做 Java audit dispatcher、outbox claim/ack/retry 指标，以及事务 outbox。
- 趋势落地建议：后续批量补偿应围绕 outbox 设计为“批量 dry-run -> 风险分组 -> 二次确认 -> 真实 requeue -> 审计 outbox -> dispatcher 派发”的产品流程，而不是只加一个批量 POST 接口。

## 2026-06-03 落地补充：memory materialization needs actionable alerts

- 本阶段把长期记忆物化从“有低基数指标”继续推进到“有可执行告警规则”。这对应 Codex、Claude Code 类 Agent 平台的生产化要求：长期记忆、工具副作用和后台恢复链路不能只写日志，必须能在异常扩大前主动提醒运维。
- DataSmart 当前新增的告警不追求覆盖所有明细，而是覆盖关键运营问题：Python Runtime 指标入口不可用、Prometheus target 缺失、DLQ 增长、finalize/fencing 错误、失败率升高、retry cooldown 积压、worker 长时间无批次、补偿重排激增和 dry-run-only 停滞。
- 规则保持低基数，继续禁止把 tenantId、projectId、candidateId、leaseId、traceId、workspaceKey 等业务主键作为 Prometheus 标签。单条候选仍通过 Runtime Event replay、lease/receipt 查询和审计日志定位。
- dry-run-only 告警采用 `sum(...) unless sum(...) > 0` 的聚合语义，而不是直接比较 `dry_run=true/false` 两组标签不同的时间序列。这类细节看似小，但会决定告警在真实 Prometheus 中是否能触发。
- Python 审计 outbox 已在后续小批次落地。下一步趋势落地建议：把告警接入 Alertmanager/Grafana，再补 Java 审计派发、事务强一致、批量补偿二次确认、租户级恢复 SLA 和 Chroma/Neo4j 二级索引 worker；不要继续只在单条告警阈值上无限打磨。

## 2026-06-03 落地补充：memory materialization worker should be controlled, not hidden

- 本阶段把长期记忆物化从“Runner 可显式调用”推进到“受控后台 worker 可选启动”。这一步对应成熟 Agent durable action 的核心要求：副作用不能只存在于一次同步请求中，而要能被后台恢复、周期执行、诊断和熔断。
- DataSmart 当前没有把 worker 默认打开，而是要求显式配置 `DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_ENABLED=true`。这是商业化产品很重要的安全姿态：长期记忆会影响未来模型上下文，未准备好 SQL lease store、审计和指标前不应自动写入。
- Worker 每轮复用 Runner、Runtime Event 和 Prometheus 指标，而不是在后台线程里重写一套逻辑。这样“人工触发、CLI、后台循环、未来 Java task-management 调度”可以共享同一套低敏事实和观测链路。
- 当前仍保持单线程循环，并通过 `maxConsecutiveErrors` 做连续异常熔断。多线程与租户并发并非没有价值，但应等 Prometheus 告警、SQL lease store、下游向量库容量、审计 outbox 和配额策略稳定后再扩展。
- Prometheus 告警规则和 Python 审计 outbox 已在后续小批次落地。下一步趋势落地建议：把审计 outbox 派发到 Java 审计中心，再进入 Chroma/Neo4j 二级索引 worker 和遗忘/归档任务。

## 2026-06-03 落地补充：memory materialization metrics must stay low-cardinality

- 本阶段把长期记忆物化 Runtime Event 进一步映射为 Prometheus 低基数指标，覆盖 Runner 批次、候选处理数量、跳过原因、fencing finalize error、批次耗时和管理员补偿重排次数。
- 这对应生产级 Agent 平台的 observability 纪律：runtime event/tracing 适合保留单次 run 和单条候选的上下文，Prometheus 适合聚合告警和趋势判断。二者不能混用，否则指标系统会被业务 ID、高频 trace 和候选明细拖垮。
- DataSmart 当前明确禁止把 `tenantId/projectId/candidateId/leaseId/requestId/runId/sessionId/workspaceKey` 作为 Prometheus 标签，只保留 `result/severity/reason/action/dry_run/after_status` 等有限枚举。单条候选排障继续走 Runtime Event replay、lease/receipt 查询和审计日志。
- 这一步仍保持 Python Runtime 默认零依赖，没有直接引入 `prometheus_client`。后续如果进入生产级指标体系，可以在保持 `/agent/metrics` 路径不变的前提下，替换为官方 client、Histogram、multiprocess collector 或 OpenTelemetry bridge。
- 受控常驻 worker、Prometheus 告警规则和 Python 审计 outbox 已在后续小批次落地。下一步趋势落地建议是设计 Java 审计派发和事务强一致，满足强合规客户对补偿动作不可丢失、对后台异常可告警且可追溯的要求。

## 2026-06-03 落地补充：memory materialization needs traceable recovery events

- 本阶段把长期记忆物化从“管理员可以恢复”继续推进到“恢复与后台批次事实可以进入 runtime event 时间线”。新增的 `memory_materialization_run_completed` 和 `memory_materialization_requeue_recorded` 分别覆盖 Runner 批次汇总和管理员补偿重排。
- 这对应当前 Agent 平台的 durable execution / tracing / human-in-the-loop 趋势：LangGraph persistence 强调 checkpoint 支撑 human-in-the-loop、memory、time travel 和 fault-tolerant execution；OpenAI Agents SDK tracing 强调 agent run 中的步骤、工具调用和自定义事件应可追踪；MCP Tools 规范也强调工具调用需要清晰的可见性和受控交互。
- DataSmart 当前选择先进入 Runtime Event，而不是直接上常驻 worker 或 Prometheus，是为了先稳定事实契约：哪些字段低敏、哪些计数可聚合、哪些事件属于审计、哪些失败只影响旁路投递而不回滚补偿主流程。
- Runner 事件聚合 `DLQ/retry cooldown/active lease/fencing finalize error`，避免未来 Prometheus 直接使用 candidateId、tenantId、traceId 等高基数字段；管理员重排事件只记录 operatorId、状态变化和 namespace，不记录候选正文、正式记忆正文、SQL、工具输出或 lease token。
- 这些事件已经在后续小批次映射为低基数 Prometheus 指标，并进一步写入 Python 审计 outbox。下一步趋势落地建议是引入 Java 审计派发与事务 outbox；随后再推进批量补偿、租户级恢复 SLA 和 Chroma/Neo4j 二级索引同步。
- 参考资料：LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

## 2026-06-03 落地补充：long-term memory needs operator recovery loops

- 本阶段把长期记忆物化从“失败退避 + DLQ”推进到“管理员可 dry-run、可查询、可重排”的恢复闭环。
- 这对应当前 Codex、Claude Code 类 Agent 工程中的 durable action recovery 思路：工具调用、记忆写入、索引构建和后台副作用不能只依赖一次进程内执行，必须具备失败证据、可解释状态、人工补偿和后续自动恢复窗口。
- DataSmart 当前选择单候选补偿而不是立即做批量重放，是为了先稳定安全语义：只允许 failed/dead_letter，保留 attemptCount，不绕过审批，不直接写正式记忆，不暴露 lease token 或候选正文。
- 这一步也把“管理员操作”纳入 Agent 产品主线：成熟 Agent 平台不仅要会调用工具和记忆，还要能被运维人员安全地恢复、审计和解释。
- 后续趋势落地建议：把补偿动作接入 runtime event / Prometheus / 审计表，再启动受控常驻 worker；批量补偿、错误类型聚合、租户级恢复 SLA 和 Chroma/Neo4j 二级索引 worker 应建立在这些可观测事实之上。

## 2026-06-02 落地补充：model gateway should own provider and tool-call governance

- 本阶段把模型网关能力迁入 `services/model_gateway/`，让 provider、route registry、budget guard、tool-call schema/planner/aggregator、feedback、result context filter 和 OpenAI-compatible provider 形成独立包边界。
- 这对应当前 Agent 工程趋势：模型调用不应只是 `call_llm(prompt)`，而应具备 provider abstraction、routing policy、tool-call governance、context safety、cache planning、budget control 和未来 provider health/tenant package 策略。
- DataSmart 当前仍采用渐进迁移，不改类名、不重命名测试，只先把模型网关能力域从大 `services` 目录中拆出来，降低结构治理对功能稳定性的影响。
- 后续趋势落地建议优先把智能网关服务间认证、长期记忆持久化和工具能力市场继续落到产品主线；如果继续结构治理，则迁移 `tools/skills` 与 `api/routes`，不要让目录整理变成新的无限循环。

## 2026-06-02 落地补充：runtime event pipelines need their own package boundary

- 本阶段把 runtime event 能力迁入 `services/runtime_events/`，让事件 store、session、ack/checkpoint、outbox、live push、publisher、replay source、WebSocket frame、visibility 和 authorization 形成独立包边界。
- 这对应前沿 Agent 产品的核心工程事实：用户看到的“正在思考、调用工具、等待确认、执行失败、恢复连接后继续显示历史事件”不是日志附属品，而是一条独立的 runtime event pipeline。
- DataSmart 当前选择渐进迁移，不改类名、不重命名测试，只先把能力域从大 `services` 目录中拆出来。这样既改善可读性，也避免结构治理破坏已经稳定的事件流测试。
- 后续趋势落地建议把智能网关服务间认证、事件审计导出、分布式 WebSocket 连接管理和低敏 display policy 都继续放在这个能力域下演进，而不是重新散落到 API 或 Agent orchestrator 文件中。

## 2026-06-02 落地补充：AI Runtime package layout should follow capability domains

- 本阶段把 Python Runtime 的长期记忆相关文件迁入 `services/memory/`，开始从“按技术层 services 平铺”转向“按 Agent 能力域分包”。
- 这对应成熟 Agent 工程项目的常见趋势：memory、tools、runtime events、model gateway、skills、integrations 应该各自形成可理解、可测试、可替换的包边界，而不是在一个 services 目录中靠文件名前缀维持秩序。
- DataSmart 当前采用渐进迁移：先移动 memory 能力域并保持文件名稳定，避免目录治理变成高风险大重构；后续再迁移 runtime events、model gateway、tools、skills 和 API routes。
- 对 Codex/Claude Code 类 Agent 目标而言，这不是“好看一点”的整理，而是未来支持长期记忆、MCP 工具、模型 provider、事件回放、服务间认证、策略审计和多租户运营时必须提前建立的工程秩序。

## 2026-06-02 落地补充：Agent API surface needs modular route ownership

- 本阶段没有盲目继续叠加长期记忆或工具执行新功能，而是先治理 Python Runtime 的 API surface，避免 Agent 规划、事件回放、WebSocket 和诊断能力全部堆在一个 bootstrap 文件中。
- 这对应 Codex、Claude Code 类 Agent 平台的工程趋势：随着工具调用、长期记忆、会话事件、权限策略、人工确认和模型网关不断增长，API 入口必须按能力面拆分，否则每次扩展都会造成局部文件膨胀和隐性耦合。
- DataSmart 当前新增 `api_agent_routes.py` 作为 Agent 规划与 runtime event 路由注册模块，`api.py` 继续作为可选 FastAPI app 装配入口。这个边界为后续智能网关服务间认证、WebSocket 会话治理、事件审计导出和长期记忆上下文注入留下了更干净的挂点。
- 后续趋势落地建议：继续把 memory-write API、diagnostics API、model-gateway API 拆成独立 route module，并引入统一的内部认证/审计 dependency，避免在每个 handler 里重复实现权限和观测逻辑。

## 2026-06-02 落地补充：long-term memory needs governed materialization

- 本阶段从 Java worker 收口切换到长期记忆主线，把已存在的“候选生成、审批、SQL 候选仓储、分页 API”继续推进为“APPROVED 候选可以幂等落成正式记忆，并被后续请求召回”。
- 这对应当前 Agent memory 的重要工程趋势：长期记忆不是无差别聊天历史，也不是工具成功后直接写向量库；它需要 namespace、范围隔离、写入治理、幂等、TTL、检索边界和遗忘策略。
- DataSmart 当前只把低敏 `contentSummary` 写入正式记忆正文。原始工具输出、SQL、样本和 outputRef 不会直接进入模型上下文；敏感候选即使审批通过，也会继续等待脱敏流水线。
- 正式 store 与 retriever 都保留 tenant/project/session 隔离。后续接 Chroma、Neo4j 或 MySQL 时，相关性搜索可以下沉，但范围过滤不能删除。
- 当前 materializer 支持 PROJECT/TENANT，暂缓 SESSION 与 GLOBAL：SESSION 需要稳定 session 字段和短期记忆 TTL 策略，GLOBAL 需要更严格的组织级审批、prompt-injection 防护和只读发布流程。
- 参考资料：LangGraph Memory：`https://docs.langchain.com/oss/python/langgraph/memory`；LangGraph Persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`。
- 下一步趋势落地建议在两条路线中择一：一是补正式记忆持久化 receipt、workspace namespace、后台 outbox worker 和遗忘任务；二是切入智能网关会话编排，把正式记忆召回接入上下文预算和模型 provider 路由。

## 2026-06-02 落地补充：guardrail metrics need bounded cardinality

- 本阶段把 worker-side guardrail 从“时间线可解释”推进到“可聚合告警”：claim 前容量不足、claim 后执行复核阻断、confirmation 或 permission-admin 暂不可用退避，都进入 Micrometer 计数指标。
- 这对应生产级 Agent 平台的 observability / admission control / action guardrail 思路：系统不仅要能解释单个工具为什么没有执行，还要能判断某类阻断是否在实例范围内持续升高。
- DataSmart 刻意没有把 tenantId、projectId、sessionId、taskId、traceId 和原始 toolCode 直接用作指标标签。Agent 会话和工具任务增长很快，直接使用业务主键会造成 Prometheus 高基数时序，反过来损害监控平台可靠性。
- 当前 outcome、scope、decision、reasonCode 都采用有限白名单；未知动态值归并为 `OTHER`。单条任务定位继续走 runtime event、任务审计和结构化日志。
- 后续若要建设租户运营看板，应使用受控租户分组、工具类别、Top-N 聚合、日志分析或 exemplar 关联 trace，而不是无限扩展基础指标标签。
- 这一批完成后，不再继续围绕 Java worker 做无止境局部优化。下一轮趋势落地应进入更大的 AI Agent 能力面，例如长期记忆写入闭环、智能网关会话调度、Skill/工具注册市场或可替换模型 provider 网关。

## 2026-06-01 落地补充：guardrail events must be explainable

- 本阶段把 worker-side guardrail 从“能阻断”继续推进到“能解释”：权限拒绝、策略漂移、确认不可用、权限中心不可用等不再只是一段日志或泛化错误码，而是进入 Agent runtime event display。
- 这对应 Codex、Claude Code 类 Agent 工具链路里的 action trace / failure taxonomy / explainable guardrail 思路：用户和运维需要知道工具为什么没有执行，是被安全策略阻断、等待审批、控制面不可用，还是下游业务失败。
- DataSmart 当前采用低敏摘要：只写错误码、状态、工具编码、目标服务和建议动作，不写 prompt、SQL、token、完整工具参数或样本数据。
- 下一步趋势落地应把同类 guardrail 事件接入指标维度：按 tenant、project、toolCode、reasonCode 聚合阻断率、退避次数、平均恢复时间和高频误配置。

## 2026-06-01 落地补充：local admission control before tool execution

- 本阶段把 Agent worker-side guardrail 从“确认与权限复核”继续推进到“容量入场保护”：worker 在 claim 任务前先检查本地并发和调度节流。
- 这对应前沿 Agent 工具平台中的 admission control / rate limiting / backpressure 思路：工具执行不仅要被允许，还必须在系统容量允许时执行。
- DataSmart 当前选择先落本地 JVM 级保护，是为了快速防止手动 dispatch、后台 scheduler 或未来多线程 worker 在单实例内过度并发；这不是最终形态。
- 后续趋势落地应演进到多维度配额：tenant、project、workspace、toolCode、targetService、queue backlog、downstream health，并与 runtime event stream、指标告警和管理员控制台联动。

## 2026-06-01 落地补充：delegated authorization re-check before side effects

- 本阶段把 Agent worker-side guardrail 继续推进到 permission-admin 实时授权复核：即使 command 已入箱、任务已创建、confirmation 已回查通过，worker 在真实工具副作用前仍要重新 evaluate。
- 这对应企业 Agent 平台里的 delegated authorization / capability lease renewal 思路：服务账号不是永久通行证，机器身份代表用户执行工具时，需要在执行瞬间重新确认策略仍然有效。
- DataSmart 当前把入箱动作 `ENQUEUE_SELECTED_ASYNC_TOOL` 与执行动作 `EXECUTE_CONFIRMED_ASYNC_TOOL` 拆开，避免“能确认节点入箱”被误解释为“能执行所有后续副作用”。
- 下一步趋势落地应进入 quota/rate/concurrency guardrail：Agent 工具执行不仅要被授权，还要受租户配额、项目配额、工具级限流、下游容量与队列积压保护约束。

## 2026-06-01 落地补充：worker-side confirmation revalidation

- 本阶段把 worker-side guardrail 从本地字段校验推进到跨服务确认回查：task-management worker 在调用真实工具适配器前，会按 `confirmationId` 查询 agent-runtime 的 DAG selected-node confirmation。
- 这对应 Codex、Claude Code 等 Agent 工具体系中越来越重要的 action confirmation / capability lease / durable approval evidence 思路：确认不是一行日志，而是 worker 执行前可以重新读取和核对的行动凭证。
- 当前已核对 confirmation 所属的 session/run、选中 auditId、关联 commandId、策略版本和委托证据摘要；如果控制面暂时不可用，任务退避重试，而不是执行副作用或永久失败。
- 这仍不是完整智能网关执行治理。下一步要把 permission-admin 最新策略 evaluate、租户/项目配额、工具级限流、payloadReference 版本和运行时指标纳入同一条 preflight 链路。

## 2026-06-01 落地补充：worker-side guardrail 先于真实工具副作用

- 本阶段继续把“工具执行不是函数直调，而是受控行动链路”的 Agent 产品原则落到 task-management worker。
- worker 当前在调用真实工具适配器前新增本地二次复核：任务必须已被认领为 RUNNING，Agent 审计状态必须仍可执行或可补偿，payloadReference 必须仍是受控 plan-arguments，toolCode 必须存在白名单适配器，confirmation/policy/delegation 证据必须保持低敏短文本。
- 这对应前沿 Agent 平台常见的 worker-side guardrail / execution preflight / capability lease 思路：即使计划已确认、命令已入箱、任务已创建，真正副作用发生前仍要做最后一跳安全判断。
- 后续应把本地 pre-check 升级为跨服务 pre-check：远端反查 confirmation、permission-admin 最新策略、租户/项目配额、工具级限流、payloadReference 版本和幂等键。

## 2026-06-01 落地补充：执行证据进入任务消费预检链路

- 本阶段继续把类 Codex / Claude Code Agent 的“工具执行必须可解释、可复核、可审计”原则落到 Java 控制面，而不是贸然扩大自动执行范围。
- `agent-runtime` 已把 selected-node confirmation 的 `confirmationId`、权限策略版本和服务账号委托摘要写入异步 command payload；`task-management` 消费命令时会先做低敏证据预检，再把安全摘要写入任务参数。
- 这对应前沿 Agent 工具体系中的 policy snapshot、approval evidence、capability lease 和 worker-side guardrail 思路：模型提出计划，控制面确认计划，任务中心保留证据，真正 worker 执行前还要再次复核。
- 后续雷达落地应优先推进“worker 执行前二次复核”的完整闭环：回查 agent-runtime confirmation、permission-admin 最新策略、工具 schema、payloadReference、租户配额、任务状态和幂等键，而不是让任务创建成功就等价于工具可执行。

## 2026-06-01 落地补充：工具执行确认需要绑定授权证据版本

- 本阶段没有新增外部趋势扫描，而是把前期“类 Codex/Claude Code Agent 必须可审计地执行工具”的路线继续落到 Java 控制面。
- `agent-runtime` selected-node outbox enqueue 现在要求调用方带回 dry-run 时看到的 `policyVersion`，服务端确认前重新 dry-run 并对比当前 permission-admin 策略版本。
- 这对应前沿 Agent 工具执行治理中的一个核心原则：模型或人类确认的是“某个时间点的行动预案”，真实副作用发生前必须重新绑定权限、策略和审计证据。
- 后续技术雷达应继续关注 MCP/A2A/tool-use 生态里的 tool approval、capability lease、policy snapshot、signed tool plan 等方向，并优先转化为 DataSmart 的租户安全、确认记录、worker pre-check 和可回放事件能力。

## 2026-06-01 落地补充：工具确认事实需要可恢复持久化

- 本阶段把 selected-node confirmation 增加 MySQL/JDBC 仓储，继续贴近前沿 Agent 产品中的 durable action、checkpoint、human-in-the-loop 和 tool trace 思路。
- 对 Codex/Claude Code 类 Agent 来说，“用户确认过”不能只存在于一次 HTTP 响应或进程内存里；它应能在服务重启、网关重试、多实例切换和审计回放时被恢复。
- DataSmart 当前把确认事实落成独立 evidence store，而不是直接扩大自动执行范围，这符合企业工具执行治理的路线：先可追踪、可恢复、可证明，再逐步提高自动化。
- 后续应继续把 confirmation evidence 接入 runtime event display、审计查询 API、worker pre-check 和权限中心策略版本复核，形成端到端行动证据链。

## 2026-05-25 智能网关 WebSocket 路由校准

本次在继续推进 Agent 实时事件能力时，先按项目 skill 要求校准了 Spring Cloud Gateway 的 WebSocket 转发方式。
结论是：网关侧应优先使用 Spring Cloud Gateway 原生 WebSocket Routing Filter，而不是手写 Java WebSocket 代理。
原因是 Gateway 已经能根据 `ws://`、`wss://` 或 `lb:ws://` URI 识别 WebSocket Upgrade 并转发，下游 Python Runtime 只需要暴露
`/agent/events/ws` 协议入口即可。

已转化为 DataSmart 能力：
- Java gateway 新增 `/api/agent/events/ws` 对外统一入口，默认转发到 `ws://localhost:8090/agent/events/ws`。
- 该路由放在通配 `/api/agent/**` 之前，避免被 Java `agent-runtime` 普通 HTTP 控制面误接收。
- 授权语义固定为 `AI_RUNTIME + SUBSCRIBE`，不把 WebSocket 握手误解释成普通 `GET + VIEW`。
- Python AI Runtime 默认端口建议使用 `8090`，避免与本地 Chroma 的 `8000` 冲突。

后续路线：
- 短期补连接配额、租户/项目级订阅限制、心跳超时清理、关闭审计。
- 中期把会话状态和 live outbox 从内存升级为 Redis/Kafka 支撑，解决多实例和断线续传窗口问题。
- 长期对齐 A2A streaming/push notification 思路，把 Agent 运行进度、工具审批、任务状态变化统一成可审计事件流。

> 本文档用于记录 DataSmart Govern 后续在 AI Agent、工具调用、Skill、记忆、模型网关、推理缓存和多智能体协作方向上的持续跟踪结果。
> 它不是一次性的调研报告，而是项目长期演进的“趋势雷达 + 落地路线”。

## 1. 维护原则

- **持续跟踪，而不是一次性选型。** AI Agent 生态变化非常快，后续涉及 `agent-runtime`、`python-ai-runtime`、智能网关、模型 provider、工具调用、记忆、推理服务时，都应先做一次最新趋势校准。
- **优先参考一手来源。** 包括官方文档、协议规范、GitHub 开源项目、Hugging Face Papers、arXiv、主流框架文档；二手文章只作为线索，不作为最终技术依据。
- **不盲目追热点。** 新技术必须映射到本项目的商业目标：数据治理、权限审计、长任务可恢复、工具调用可控、模型可替换、可观测、可部署。
- **所有趋势必须转成架构能力。** 例如 MCP 不只是“接入协议”，要转成工具注册、权限审批、审计回放和服务隔离；KV Cache 不只是推理优化，要转成模型网关的成本与延迟治理。
- **每次重要落地都更新本文档与 `current-repo-state.md`。** 这样长期任务不会只依赖聊天历史。

## 2. 2026-05-23 趋势扫描摘要

本次扫描结合了官方文档、GitHub 开源项目、Hugging Face Papers 和本项目当前实现状态。

### 2.1 工具调用协议正在走向标准化：MCP

**趋势判断：**

MCP 已经从“某个客户端的插件机制”演进为 AI 应用连接外部系统、工具、数据源和工作流的开放标准。官方文档把 MCP 类比为 AI 应用的 USB-C 接口，强调用统一协议连接数据库、文件、搜索、工具和工作流。

**对 DataSmart 的意义：**

- `agent-runtime` 不应该只维护一套内部工具调用 DTO，后续应设计成“内部工具注册表 + MCP 兼容适配层”。
- 数据源元数据读取、质量规则生成、同步任务创建、权限查询、审计导出都可以逐步变成受控工具。
- 工具调用必须绑定租户、项目、角色、审批、风险等级和审计流水，不能只做“函数名 + 参数”。

**建议落地：**

- 近期：扩展 `AgentToolRegistryService` 的工具 schema，增加 `required`、`sensitive`、`approvalRequired`、`riskLevel`、`tenantScoped`、`projectScoped`。
- 中期：新增 MCP-style tool descriptor 导出接口，让 Java 工具注册表可以被 Python Runtime 或智能网关消费。
- 后期：评估是否实现 MCP server，把 DataSmart 的治理能力暴露给兼容 MCP 的客户端。

### 2.2 Agent-to-Agent 协作正在协议化：A2A

**趋势判断：**

A2A 关注的是不同框架、不同公司、不同服务器上的 agent 之间如何发现能力、协商交互方式、处理长任务和协作，而不是把对方当作普通工具调用。官方仓库强调 agent discovery、task lifecycle、streaming、push notification、安全和可观测性。

**对 DataSmart 的意义：**

- DataSmart 的多个治理智能体不应长期写成一个巨大的 orchestrator。
- 数据接入 Agent、质量 Agent、合规 Agent、运维 Agent、审计 Agent 可以逐步具备独立能力描述、任务协议和协作边界。
- A2A 的“Agent Card”思想很适合 DataSmart：每个 Agent 暴露能力、输入输出、风险等级、权限要求、支持的任务状态。

**建议落地：**

- 近期：为 `agent-runtime` 增加 `AgentCapabilityDescriptor` / `AgentCard` 风格的领域模型。
- 中期：把 Agent 会话、run、tool execution、runtime event 和 task-management 的任务状态做协议对齐。
- 后期：评估 A2A 兼容网关，让外部 agent 或企业内部 agent 能安全调用 DataSmart agent。

### 2.3 记忆能力正在从“聊天历史”演进为多层记忆系统

**趋势判断：**

最新 agent memory 方向已经明显超出传统 conversation summary / vector RAG。Hugging Face Papers 中 MIRIX 将记忆拆为 Core、Episodic、Semantic、Procedural、Resource Memory、Knowledge Vault 等类型，并用多 agent 协调更新和检索。LangGraph 文档也将 short-term memory、long-term memory、checkpoint/store 区分开。

**对 DataSmart 的意义：**

- DataSmart 需要的不是“把所有聊天记录塞进向量库”，而是面向治理任务的分层记忆。
- 短期记忆：当前会话、当前任务、当前工具调用参数、最近事件。
- 语义记忆：数据源元数据、业务术语、质量规则、血缘、指标定义。
- 情节记忆：某次同步失败、某次质量异常处理、某次审批链路。
- 程序记忆：成功执行过的治理流程、常用修复步骤、客户环境偏好。
- 资源记忆：报告、SQL、截图、配置文件、运行日志引用。

**建议落地：**

- 近期：在 `agent-runtime` 中明确 `AgentSessionMemoryStore` 的记忆类型枚举，不只保存 session 文本。
- 中期：为 Python AI Runtime 增加 `MemoryRetrievalPlan`，根据意图选择短期、语义、情节、程序记忆。
- 后期：引入记忆压缩、过期策略、重要性评分、隐私级别和审计可回放。

### 2.4 Skill 正在成为 Agent 的“程序记忆”和能力封装方式

**趋势判断：**

Agent skill 的主流方向是把复杂工作流、工具使用说明、领域偏好和操作原则封装成可发现、可按需加载的能力包，而不是把所有提示词塞进一个大 prompt。

**对 DataSmart 的意义：**

- 数据治理领域天然适合 Skill 化：数据源接入 Skill、质量规则 Skill、同步诊断 Skill、审计导出 Skill、合规脱敏 Skill。
- Skill 不应只是 prompt 文件，还应包含工具依赖、权限需求、输入输出 schema、示例、风险说明和回滚策略。

**建议落地：**

- 近期：设计 `AgentSkillDescriptor`，字段包含 `skillCode`、`domain`、`requiredTools`、`riskLevel`、`memoryPolicy`、`approvalPolicy`。
- 中期：把项目 skill 机制与 Java `AgentToolRegistryService` 对齐，形成“Skill 选择工具，工具执行业务”的链路。
- 后期：提供 Skill 市场或租户级 Skill 开关，支持企业客户按需启用。

### 2.5 模型网关正在从“转发请求”演进为推理治理层

**趋势判断：**

OpenAI Agents SDK、LiteLLM、vLLM、SGLang 等生态都说明模型访问层正在承载更多治理能力：工具调用、handoff、guardrails、tracing、虚拟 key、budget、fallback、routing、prefix cache、KV cache。

**对 DataSmart 的意义：**

- `ModelGatewayService` 不应只是 provider 枚举和 URL 转发。
- 它应该逐步承担模型路由、成本预算、延迟监控、fallback、熔断、prompt/response 审计、缓存策略、租户配额。
- 对数据治理产品来说，模型网关还要理解“任务类型”：意图识别、SQL 生成、规则生成、异常解释、报告摘要、Agent 编排。

**建议落地：**

- 近期：扩展 `ModelRouteRegistry` 的路由维度，增加 workload、latencyTier、costTier、privacyLevel、cachePolicy。
- 中期：在 Python Runtime 的 `model_router.py` 中补充 provider health、fallback、超时和模型能力标签。
- 后期：引入 prefix cache / KV cache 观测指标，尤其是多轮 Agent 和重复系统提示场景。

### 2.6 KV Cache / Prefix Cache 正在成为 Agent 推理性能关键点

**趋势判断：**

vLLM 官方文档已经把 automatic prefix caching 作为明确特性；近两年的论文和服务框架也在围绕多 agent 工作流的 KV cache 复用、预测式缓存管理和跨上下文缓存通信进行优化。

**对 DataSmart 的意义：**

- Agent 系统提示、工具 schema、租户策略、项目上下文往往在一次长任务中重复出现，非常适合 prefix cache。
- 如果不做缓存策略，工具 schema 越丰富，prompt 越长，成本和延迟越容易失控。
- 缓存策略必须与权限边界绑定，不能把一个租户或项目的上下文缓存错误复用到另一个租户。

**建议落地：**

- 近期：为模型请求增加 `cacheKeyScope` 概念，例如 `GLOBAL_SAFE`、`TENANT_SAFE`、`PROJECT_SAFE`、`SESSION_ONLY`。
- 中期：在模型网关指标中加入 prompt token、cache hit、prefill latency、decode latency。
- 后期：按工作负载拆分缓存策略：工具 schema 和系统提示可跨会话复用，用户数据和项目上下文只能在严格范围内复用。

## 3. DataSmart 下一阶段落地路线

### P0：技术雷达制度化

- 新增本文档并持续维护。
- 在 skill 中加入“前沿 AI Agent 趋势跟踪”工作规则。
- 后续涉及 agent、模型、工具、记忆、智能网关时，默认先做最新资料校准。

### P1：Agent 工具与 Skill 治理

- 扩展 Java `AgentToolRegistryService` 的工具 schema。
- 新增 Skill 描述模型，绑定工具、权限、风险、审批和记忆策略。
- Python Runtime 的 tool planner 使用统一 schema，不再依赖自由字典。

**2026-05-23 落地进展：**

- Java `agent-runtime` 已新增 `/agent-runtime/tools/descriptors`，输出 DataSmart 自定义的 MCP-style tool descriptor。
- Python AI Runtime 已优先消费 descriptor，并兼容旧 `list_tools(...)` 工具目录。
- `ToolDefinition` 已纳入 `protocol_hint`、`tenant_scoped`、`project_scoped`、`sensitive_fields`、`memory_write_policy`、`cache_policy`。
- `ToolPlan.governance_hints` 已能把敏感字段、项目范围、记忆策略和缓存策略透传给审批、审计、前端确认页和后续模型网关。
- 当前仍不实现完整 MCP Server；下一步先把 Skill、记忆和模型网关治理字段内部打稳，再决定对外协议兼容深度。

**2026-05-24 追加落地进展：**

- Java `agent-runtime` 已新增 AgentPlan 接入口 `/agent-runtime/plan-ingestions`，开始承接 Python AI Runtime 生成的 `AgentPlan`。
- Python ToolPlan 进入 Java 后会生成受控工具审计计划，而不是直接执行工具，符合 MCP/A2A 趋势中“能力描述、计划、执行控制分离”的企业治理思路。
- Java 工具目录成为工具白名单事实源，未知 toolCode 会在接入阶段被拒绝，避免模型幻觉工具进入真实执行链路。
- 工具审计已保存 `planReason`、`planArguments`、`governanceHints`、`parameterValidation`，为后续审批解释、参数 schema 强化、Agent eval 和事故回放提供数据基础。
- 高风险计划会同步推动 Run 进入 `WAITING_HUMAN`、工具审计进入 `WAITING_APPROVAL`，让“人类在环”不只是前端文案，而是控制面状态机事实。

**2026-05-24 入口治理追加落地：**

- `/api/agent/plan-ingestions` 已被 gateway 明确纳入内部服务端点保护，默认只允许 `SERVICE_ACCOUNT` 调用。

**2026-05-24 工具链上下文与任务草稿追加落地：**

- Java `agent-runtime` 已从单点工具执行推进到多工具链编排：成功工具输出会进入 Run 内上下文仓储，后续工具可以读取前序工具结果。
- `quality.rule.suggest` 已能消费 `datasource.metadata.read` 的元数据输出，减少模型在 ToolPlan 中复制大 JSON 的需求。
- `task.create.draft` 已实现为无副作用草稿工具，可以消费质量规则草案并生成可审批任务草稿，但不调用 task-management 创建真实 `PENDING` 任务。
- 这对应 Agent 生态中“工具调用可审计、结果可引用、写操作需审批”的趋势，也为后续显式 `outputRef/jsonPath`、MCP-style resource 引用、A2A 长任务状态对齐打基础。

**2026-05-24 显式工具输出引用追加落地：**

- Java `agent-runtime` 已新增 `AgentToolOutputReference` 与 `AgentToolOutputReferenceResolver`，把工具链上下文从“最近一次输出”推进到“显式来源 + 审计 ID + 路径”的可复现引用。
- 当前引用语法支持 `toolCode/fromTool`、`auditId/fromAuditId`、`jsonPath/path`，并支持 `metadata.tables[0]` 这类轻量路径。
- 该设计贴近 MCP resource reference 和工作流 DAG node output 的思想，但先保持 DataSmart 内部协议，避免过早实现完整外部 MCP Server。
- 下一步应让 Python ToolPlan 主动生成 `metadataRef/suggestionRef`，并让前端审批页展示引用关系图。
- gateway 在调用 permission-admin 前先执行本地角色白名单、可选内部 Token 和固定窗口限流，普通用户直连会被快速拒绝。
- permission-admin 已新增 `INGEST_PLAN` 动作，服务账号允许提交计划，人类角色默认拒绝直接提交计划。
- 这一步对应 Agent 平台商业化趋势中的“工具/计划入口必须经过企业网关治理”：模型或 Python Runtime 可以生成计划，但不能绕过服务账号、限流、权限策略和审计事实源。
- 当前本地限流仍是 MVP 形态，后续需要演进为 Redis/分布式限流，并配合 mTLS、JWT client credential 或服务账号密钥轮换。

**2026-05-24 幂等与可回放追加落地：**

- `AgentPlan` 接入请求已新增 `idempotencyKey`，并兼容 `pythonRequestId` 作为兜底去重键。
- Java `agent-runtime` 已能在重复提交相同计划时回放首次 run/audit 结果，而不是创建重复审批单。
- 同一个幂等键提交不同 payload 会被拒绝，避免审计事实、审批单和真实工具执行链路被混淆。
- 这对应长任务 Agent 系统的可靠性趋势：HTTP 重试、Kafka 重复投递、ack 丢失、consumer rebalance 都必须通过幂等键和可回放结果治理，而不能假设上游永远只调用一次。

**2026-05-24 工具执行安全追加落地：**

- Java `agent-runtime` 已新增工具执行前置守卫，在真实调用适配器前校验 session/run/audit 链路、租户/项目/actor 边界、参数缺失和非只读审批事实。
- 工具执行顺序已调整为先守卫、再进入 `EXECUTING`，避免校验失败时留下错误执行中状态。
- 这对应 MCP/Agent 工具体系的企业化落地方向：工具 descriptor 和 ToolPlan 只是计划层，真正执行前必须有平台控制面做二次治理。

**2026-05-24 真实工具契约追加落地：**

- `datasource.metadata.read` 已从裸 `Map` 调用升级为“本地请求契约 + 参数裁剪 + 响应摘要 + 稳定错误码”的受控工具适配模式。
- Agent Runtime 侧会读取 Python ToolPlan 的 `planArguments`，但不会盲目信任模型参数；`maxTables`、`maxColumnsPerTable` 会被 Java 控制面裁剪，`includeSampleRows` 当前强制关闭。
- 工具输出区分 `summary` 与 `metadata`，让前端、审计、后续编排和模型上下文可以优先消费摘要，避免所有场景都解析完整元数据。
- 这一步把 MCP-style 工具治理从“工具描述符与计划审计”推进到了“真实业务工具调用契约”，后续 `data-quality`、`task-management`、`data-sync` 工具都应沿用该模式。

**2026-05-24 草案型业务工具追加落地：**

- `quality.rule.suggest` 已接入真实 data-quality 草案接口，Agent 可以基于元数据生成质量规则建议。
- 当前工具被定位为 DRAFT_ONLY：只生成草案，不落库、不启用、不调度任务，避免模型直接影响生产规则。
- data-quality 第一版使用确定性元数据规则引擎作为兜底，后续 Python AI Runtime 可以叠加模型语义、数据画像、历史质量报告和业务术语。
- 这对应企业 Agent 工具趋势中的“模型给建议，控制面管边界，业务域保事实”：Agent 生成建议，但规则生命周期仍属于 data-quality。

**2026-05-24 工具链上下文追加落地：**

- Java `agent-runtime` 已新增 Run 内工具输出仓储，工具成功输出可以被后续工具读取。
- `quality.rule.suggest` 已能在 ToolPlan 不显式携带 metadata 时，读取同一 Run 中 `datasource.metadata.read` 的成功输出。
- 这把工具调用从“单个函数调用”推进到“可编排工作流上下文”，对应 MCP/Agent Runtime 生态中 tool result、resource reference、工作流 DAG 和事件回放的趋势。
- 当前仍是内存实现和隐式最近输出引用，后续应升级为显式 `outputRef/jsonPath`、对象存储引用和可回放事件日志。

### P2：Agent 记忆分层

- 明确短期记忆、语义记忆、情节记忆、程序记忆、资源记忆的存储和检索职责。
- 将任务执行事件、质量异常、同步事故、审批历史沉淀为可检索记忆。
- 增加记忆写入审批、隐私级别、过期和压缩策略。

**2026-05-23 落地进展：**

- Python AI Runtime 已新增 `AgentMemoryType`，把短期、语义、情节、程序、资源记忆写成稳定枚举。
- `AgentMemoryPlan` 已进入 `AgentPlan`，用于表达本次请求应该检索哪些记忆、允许写入哪些记忆、默认范围、保留期、审批要求和隐私说明。
- `AgentMemoryPlanner` 已根据治理域、风险标签、上下文和工具 descriptor 的 `memoryWritePolicy` 生成记忆计划。
- 敏感数据、跨范围、跨租户、导出类风险会把默认记忆范围收紧为 `SESSION`，并要求写入审批保护。
- 这一步暂不绑定 Chroma/Neo4j/Redis，先保留存储可替换抽象。

**2026-05-24 追加落地进展：**

- Python AI Runtime 已新增 `AgentMemoryRecord`、`AgentMemoryRetrievalResult`、`AgentMemoryRetrievalReport`，把“应该检索什么”和“实际检索到了什么”拆成两个契约。
- 新增 `AgentMemoryRetriever` 协议与 `InMemoryAgentMemoryRetriever`，先用内存实现验证 tenant/project/session 隔离、关键词排序和 `maxItems` 行为。
- `AgentOrchestrator` 已新增 `retrieve_memory` 状态节点，并通过 `MEMORY_RETRIEVED` 事件记录目标数量、实际召回数量和检索器类型。
- 当前设计继续遵守“先接口、后存储”的原则：短期记忆可接 Redis，会话/事件可接 MySQL 或 Kafka 审计，语义记忆可接 Chroma，关系记忆可接 Neo4j，资源记忆可接 MinIO 索引。

### P2.5：Agent Skill 能力包治理

- Skill 已被建模为 `AgentSkillDescriptor`，包含工具依赖、权限要求、记忆依赖、风险等级、审批策略、触发关键词和示例。
- Python AI Runtime 已新增 `AgentSkillRegistry`，根据治理域、候选工具和关键词选择 Skill。
- `AgentPlan` 已返回 `skill_plan`，为前端解释、Java 控制面审批、未来 Skill 市场和 A2A Agent Card 映射预留字段。
- 下一步建议把 Skill descriptor 同步到 Java `agent-runtime`，让 Java 成为 Skill 治理事实源。

**2026-05-23 追加落地进展：**

- Java `agent-runtime` 已新增 `AgentSkillRegistryProperties`，独立配置 Skill 注册表。
- Java 已新增 `/agent-runtime/skills/descriptors` 与 `/api/agent/skills/descriptors`，输出 `datasmart.agent.skill.v1` descriptor。
- Skill descriptor 已包含工具依赖、权限依赖、记忆依赖、风险等级、审批策略、租户/项目范围、审计要求、示例目标和触发关键词。
- 默认 Skill 已覆盖数据源画像分析、质量规则设计、受控任务创建、权限边界解释。
- 当前采用 `AGENT_CARD_STYLE` 协议提示，先形成内部 Agent Card 风格描述，不急于实现完整 A2A Server。

**2026-05-24 追加落地进展：**

- Python AI Runtime 已新增 Java Skill descriptor 客户端，优先消费 `/agent-runtime/skills/descriptors`，失败后回退本地默认 Skill。
- `build_default_orchestrator(...)` 已可注入远程 Skill registry client，说明 Skill 治理链路已形成“Java 控制面事实源 -> Python 编排层消费”的闭环。
- 当前仍不急于实现完整 Skill 市场或 A2A Server，下一步更适合补模型网关治理和记忆写入审批边界。

### P3：模型网关与推理缓存治理

- 将模型 provider、workload、fallback、budget、latency、cachePolicy 纳入统一路由模型。
- 为 prefix cache / KV cache 预留指标与配置，不直接绑定单一推理框架。
- 未来在 vLLM/SGLang 部署路径中验证 prefix cache 对长 agent 工作流的收益。

**2026-05-24 落地进展：**

- Python AI Runtime 已新增模型网关治理契约，覆盖 Provider 健康状态、预算策略、预算决策、路由决策和缓存范围。
- `ModelRoute` 已纳入 `fallback_group`、`latency_tier`、`cost_tier`、`cache_key_scope`、`health_check_path`，默认模型路由开始具备延迟/成本/缓存治理元数据。
- 新增 `ModelGatewayGovernanceService`，支持预算预评估、跳过不可用 Provider、fallback 候选选择、交互式延迟偏好和显式缓存范围覆盖。
- 当前实现为内存健康表与内存预算台账，主要用于固定契约和测试；后续可替换为 Prometheus/Redis/MySQL/Java 控制面。
- 设计继续保持供应商中立，不直接锁定 LiteLLM、vLLM、SGLang 或某个云厂商，先让 DataSmart 自己的治理语义稳定。

**2026-05-24 追加落地进展：**

- 模型网关治理已接入 `AgentOrchestrator` 主链，Agent 状态流新增 `route_model_gateway`，并在模型调用前完成预算、健康、fallback 与缓存范围决策。
- `AgentPlan` 已返回 `model_gateway_decision`，运行事件新增 `MODEL_GATEWAY_ROUTED`，为前端展示、智能网关观测和审计回放提供模型治理依据。
- 模型调用成功后已可通过 `record_invocation_usage(...)` 回写 usage，形成“调用前预算评估、调用后 token 记账”的最小成本闭环。
- 模型网关上下文构建已拆到独立 helper，避免 Agent 编排器继续膨胀。

**2026-05-24 API 展示闭环：**

- `build_plan_response(...)` 顶层响应已新增 `modelGatewayGovernance`，把内部模型网关决策转换为前端/Java 控制面可直接消费的扁平摘要。
- 摘要已覆盖预算状态、fallback、健康状态、缓存范围、候选 Provider、展示摘要和推荐动作，适合进入 Agent 计划确认页与审计流水。
- 模型网关阶段当前可以暂时收口，后续重点转向 Agent 工具执行安全与 Java 控制面联动。

**2026-05-27 OpenAI-compatible tool_calls 契约追加落地：**

- 参考 OpenAI Chat Completions 当前 tool/function calling 语义，Python AI Runtime 已支持解析非流式 `message.tool_calls` 与流式 `delta.tool_calls`。
- streaming tool call 会按 `index` 输出 `ModelToolCallDelta`，保留 `id/type/name/arguments` 增量，符合“参数分片返回、运行时再聚合”的 Agent 执行模式。
- Provider 只负责解析模型意图，不直接执行工具；权限、审批、参数 schema 校验、工具沙箱和审计仍由 Agent loop 与 Java 控制面治理。
- `model_provider.py` 已拆出 `openai_compatible_provider.py`，避免后续模型网关、工具调用、指标和连接池能力堆进同一个文件。
- 这一步让 DataSmart 从“模型能输出文本/流式文本”推进到“模型能提出结构化工具调用意图”，是对齐 Codex、Claude Code 类 Agent 工具能力的关键前置。

**2026-05-27 工具 schema 暴露治理追加落地：**

- Python AI Runtime 新增 `model_tool_schema.py`，把 DataSmart `ToolDefinition` 转换为 OpenAI-compatible `tools` 请求体。
- 工具暴露不再是“把全量工具塞给模型”，而是先经过 `ModelToolSchemaExposurePolicy` 控制工具数量、CRITICAL 风险隐藏、审批工具提示和 strict schema 开关。
- 工具函数名会从 DataSmart 原始点号命名转换为 OpenAI-compatible 更稳妥的下划线命名，例如 `quality.rule.suggest` -> `quality_rule_suggest`；模型回传 tool_calls 时再映射回原始工具名，避免 Java 工具执行链路失配。
- 工具描述中会写入风险等级、执行模式、只读/草案/审批、租户/项目边界、敏感字段等治理提示，让模型选择工具时能看到边界。
- 这一步补上了“模型看见哪些工具”的治理面，后续才能安全进入 tool call 聚合、参数校验、审批和执行闭环。

**2026-05-27 Agent 编排器候选工具暴露追加落地：**

- `ToolPlanner` 新增 `model_visible_tools(...)`，按 IntentAnalysis、Skill required tools 和规则式计划结果合并本轮模型可见工具。
- `AgentOrchestrator` 的 `invoke_model_intent` 节点已把候选工具写入 `ModelInvocationRequest.available_tools`，让模型节点不再只看自然语言 messages。
- 工具暴露仍分两层：编排器先按意图/Skill 裁剪候选工具，Provider 前再按风险策略转换为 OpenAI-compatible `tools`。
- `test_model_provider.py` 已拆分，工具 schema 和 tool_calls 测试移入 `test_model_tool_schema.py`，为后续 tool call 聚合器与执行闭环测试预留空间。
- 这一步让工具 schema 从“Provider 单测能力”进入“Agent 主编排链路”，下一步可以做 streaming tool call delta 聚合和工具执行前置治理。

**2026-05-27 streaming tool call delta 聚合追加落地：**

- Python AI Runtime 新增 `model_tool_call_aggregator.py`，把 OpenAI-compatible streaming `ModelToolCallDelta` 按 `index` 聚合为完整 `ModelToolCall`。
- 聚合器支持一次性 replay 聚合，也支持实时 `accept_chunk(...)` 增量聚合，适配 WebSocket/SSE 实时 UI 与断线回放。
- 聚合报告会输出 `tool_calls`、`issues`、source chunk/delta 数量；缺少 name、arguments、call_id 时不抛异常，而是形成可审计 issue。
- 这一步对齐当前 function calling streaming 模式：模型参数片段按 index 累加，但执行前仍必须做参数 schema 校验、权限判断、审批和审计。
- 下一步可把聚合后的 `ModelToolCall` 映射到 DataSmart `ToolPlan` 或独立“模型工具调用候选”，再接 Java agent-runtime 执行链路。

**2026-05-27 模型工具调用候选治理追加落地：**

- Python AI Runtime 新增 `model_tool_call_planner.py`，把聚合后的 `ModelToolCall` 转换为 DataSmart `ToolPlan` 或治理拒绝候选。
- 治理问题覆盖未知工具、未在本轮暴露的工具、非法 JSON 参数、非 object 参数、需审批工具、CRITICAL 风险工具。
- 模型回传的下划线函数名会通过工具 schema alias 映射回 DataSmart 点号工具名，继续复用 Java agent-runtime 工具注册命名。
- 通过治理的候选会生成 `ToolPlan`，并复用 `ToolParameterValidator` 做参数完整性校验；参数缺失时不会执行，只保留可解释问题。
- 这一步把“模型提出工具调用”接入了 DataSmart 平台治理语义，为后续 runtime event、审批和 Java 工具执行链路打通前置桥梁。

**2026-05-27 模型工具调用治理事件追加落地：**

- Python AI Runtime 新增 `model_tool_call_events.py`，把 `ModelToolCallPlanningReport` 转换为 runtime events。
- 事件契约新增 `MODEL_TOOL_CALL_PROPOSED`、`MODEL_TOOL_CALL_ACCEPTED`、`MODEL_TOOL_CALL_REJECTED`、`MODEL_TOOL_CALL_APPROVAL_REQUIRED`。
- 事件只记录工具名、callId、风险等级、执行模式、参数字段名和 issue code，不记录真实 arguments 值，避免 WebSocket/replay/audit 扩大敏感参数泄露面。
- 需审批候选会生成 `AUDIT` 级事件，被治理拒绝候选会生成 `WARNING` 级事件，便于前端实时展示、审计回放和运维诊断区分不同状态。
- `RuntimeEventVisibilityPolicy` 已把模型工具调用事件纳入 BASIC 可见进度集合；普通用户只能看到脱敏进度，管理员/审计员可按角色看到更完整治理摘要。
- 这一步让“模型提出工具调用 -> 平台治理判断”不仅停留在内存对象里，而是进入实时事件通道，为后续 Agent loop、审批单、Java 工具执行和回放诊断提供统一轨迹。

**2026-05-27 Agent 主链非流式 tool_calls 治理接入：**

- Python AI Runtime 新增 `agent_model_intent_node.py`，把模型意图调用从 `AgentOrchestrator` 中拆出，避免编排器继续膨胀。
- `AgentModelIntentNode` 负责构造模型消息、传入候选工具、调用 Provider、接收非流式 `tool_calls`、治理候选并写入 runtime events。
- `AgentOrchestrator` 现在会把模型生成的 `ToolPlan` 与规则式安全基线计划合并；合并策略为“规则顺序保依赖、模型参数优先”。
- `ToolPlanner` 新增 `registered_tools()`，让模型工具调用治理能够同时检查“平台注册工具全集”和“本轮模型可见工具集”。
- 主链状态新增 `govern_model_tool_calls`，表示模型确实返回了工具调用并已经进入平台治理。
- 这一步让 DataSmart 从“Provider 可以解析 tool_calls”推进为“Agent 主流程能接住并治理 tool_calls”，但仍不直接执行工具，继续把执行、审批、审计和幂等留给 Java agent-runtime。

**2026-05-27 Agent 主链 streaming tool_call_deltas 聚合治理接入：**

- `AgentModelIntentNode` 已优先尝试 Provider `stream(...)`，并在流式路径中聚合 `ModelInvocationChunk.tool_call_deltas`。
- streaming 工具调用复用 `ModelToolCallDeltaAggregator`，按 `index` 聚合 name、arguments、callId 和 type，符合 OpenAI-compatible streaming tool calls 的分片返回模式。
- 聚合后的 `ModelToolCall` 继续走与非流式相同的 `_govern_model_tool_calls(...)`，确保流式和非流式不会产生两套权限、参数、审批或事件规则。
- `AgentModelIntentNodeResult` 新增 streaming chunk/delta/assembly issue 计数，为后续模型工具调用可观测性指标预留入口。
- 支持通过请求变量 `streamModelIntent` / `stream_model_intent` 显式开关 streaming，便于灰度、排障和私有模型网关兼容。
- 这一步让 DataSmart 的 Agent 主链从“只能治理完整 tool_calls”升级为“能治理 streaming 片段聚合后的 tool_calls”，更接近实时 Agent 产品形态。

**2026-05-27 工具执行结果回填模型消息契约追加落地：**

- `ModelMessage` 已扩展 `tool_call_id`、`name`、`tool_calls`，支持 assistant tool_calls 历史消息与 role=tool 工具结果消息。
- Python AI Runtime 新增 `model_tool_result_feedback.py`，定义 `ToolExecutionFeedbackStatus`、`ToolExecutionFeedback`、`ToolExecutionFeedbackMessageBundle` 和 `ModelToolResultFeedbackBuilder`。
- 构建器会生成 `assistant(tool_calls)` + `tool(tool_call_id=result)` 的下一轮模型消息包，并检测缺失或多余的 tool_call_id。
- 工具结果回填只使用 Java 控制面返回的摘要、审计 ID、runId、outputRef 和允许进入模型的 result 字段；敏感字段会被脱敏。
- OpenAI-compatible Provider 已支持把 assistant `tool_calls` 与 tool `tool_call_id` 序列化进 Chat Completions 请求体。
- 这一步固定了 `tool_call_id -> tool result message -> next model turn` 的协议基础，但仍不在 Python 内执行工具；真实执行继续由 Java agent-runtime 控制。

**2026-05-27 Agent 主链模拟工具反馈二轮推理闭环追加落地：**

- Python AI Runtime 新增 `model_tool_feedback_provider.py`，把“工具执行反馈来源”抽象为可替换 Provider，并提供 `SimulatedModelToolExecutionFeedbackProvider` 作为最小闭环占位。
- `AgentModelIntentNode` 已在模型提出 tool_calls 并完成平台治理后，构造模拟的 Java 控制面反馈，再通过 `ModelToolResultFeedbackBuilder` 生成 assistant/tool 消息包进入第二轮模型调用。
- 第二轮模型调用显式设置 `tool_choice="none"` 且不暴露工具列表，避免当前最小闭环阶段出现无限工具调用循环；后续如果要支持多轮工具链，应升级为有最大步数、预算、状态机和人工接管策略的 Agent loop。
- runtime events 新增 `TOOL_RESULT_FEEDBACK_BUILT` 与 `MODEL_SECOND_TURN_COMPLETED`，用于让 WebSocket、审计回放和调试面板看见“工具结果已回填、二轮推理已完成”的关键节点。
- 模拟反馈遵守治理边界：需要人工审批的工具只返回 `WAITING_APPROVAL`，参数非法的工具返回 `REJECTED`，只有低风险且参数有效的候选才返回模拟成功摘要；Python 仍不直接执行真实业务工具。
- 这一步让 DataSmart 从“模型能提出工具调用并生成回填消息契约”推进到“Agent 主链具备最小二轮推理 loop”，更接近 Codex、Claude Code 类 Agent 的工具使用体验，同时保留 Java agent-runtime 作为真实执行、审批、审计和幂等的控制面事实源。

**2026-05-27 Java 控制面工具结果查询与 Python 反馈 Provider 追加落地：**

- Java `agent-runtime` 新增按 `sessionId/runId/auditId` 查询工具执行结果快照的只读入口：`GET /agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/result`。
- 查询结果返回 `audit + output`，不触发执行、不推进状态，适合 Python Runtime 二轮模型推理、前端刷新、审计回放和轮询补偿。
- Python AI Runtime 新增 `agent_runtime_tool_feedback_client.py`，将 Java `AgentToolExecutionResultView` 映射为 `ToolExecutionFeedback`，并区分 `SUCCEEDED/FAILED/WAITING_APPROVAL/SKIPPED`。
- `JavaAgentRuntimeToolFeedbackProvider` 会在 ToolPlan 带有 Java 控制面引用时优先查询真实结果；缺少引用或 Java 暂不可用时回退模拟反馈，支持渐进式集成。
- 这一步贴合 OpenAI-compatible tool result 与 MCP tool result 的共同方向：工具结果可以是结构化 JSON，但业务失败、审批等待和跳过状态必须作为工具结果语义返回给模型，而不是伪造成成功输出。
- 当前仍未实现完整“Python Plan -> Java ingestion -> Java execute -> Python poll/callback -> multi-step loop”的自动闭环，但真实结果查询和 Provider 替换点已经具备。

### P4：A2A / 多智能体协作协议

- 为内部治理 agent 建立 Agent Card 风格的能力描述。
- 将 DataSmart 的长任务、runtime event、WebSocket replay 与 agent task lifecycle 对齐。
- 后续再评估外部 A2A 兼容，而不是现在就过早实现完整协议。

## 4. 当前参考来源

- MCP 官方文档：<https://modelcontextprotocol.io/docs/getting-started/intro>
- A2A 官方 GitHub：<https://github.com/a2aproject/A2A>
- LangGraph Memory 文档：<https://docs.langchain.com/oss/python/langgraph/memory>
- OpenAI Agents SDK 文档：<https://openai.github.io/openai-agents-python/>
- vLLM Automatic Prefix Caching：<https://docs.vllm.ai/en/latest/features/automatic_prefix_caching/>
- LiteLLM Virtual Keys / Proxy：<https://docs.litellm.ai/docs/proxy/virtual_keys>
- Hugging Face Paper - MIRIX：<https://huggingface.co/papers/2507.07957>

## 5. 后续扫描清单

- GitHub：LangGraph、AutoGen、CrewAI、MCP servers、A2A、mem0、vLLM、SGLang、LiteLLM、OpenHands、OpenDevin 类项目。
- Hugging Face Papers：agent memory、tool learning、multi-agent workflow、KV cache、inference serving、agent evaluation。
- 协议与标准：MCP、A2A、AGENTS.md、Agent Skill、tool schema、workflow event protocol。
- 推理栈：vLLM、SGLang、TensorRT-LLM、LMDeploy、llama.cpp、TGI。
- 模型栈：Qwen、DeepSeek、Mistral、embedding、reranker、vision-language、small agent model。

## 6. 最新落地记录

**2026-05-28 AgentPlan 接入 Java 控制面与 auditId 映射：**

- Python AI Runtime 新增 AgentPlan ingestion client，可以把模型规划出的 `AgentPlan` 提交到 Java `agent-runtime`，并解析 Java 返回的 session/run/toolAudits。
- 通过 `modelToolCallId -> auditId` 映射，Python 可以把 `agentRuntimeSessionId`、`agentRuntimeRunId`、`agentRuntimeAuditId` 写回 ToolPlan governance hints。
- 这一步让 DataSmart 的 Agent 架构更贴近当前主流工具型 Agent：模型只提出工具调用意图，控制面负责审计、审批、幂等和真实执行，工具结果再以结构化消息回到模型上下文。
- 当前仍不实现完整 MCP Server 或外部 A2A 协议，而是优先把内部控制面契约做稳，避免为了追热点牺牲产品闭环。
- 下一步应把计划接入、Java 执行结果查询、Kafka/WebSocket 状态事件和受控多步 loop 串起来，并持续补充最大步数、预算、超时、循环检测和人工接管等商业化保护。

**2026-05-28 Java 控制面反馈快照追加落地：**

- Python AI Runtime 新增 `AgentControlPlaneFeedbackCollector`，在 AgentPlan 接入 Java 并拿到 auditId 后，可以读取 Java 当前工具反馈状态。
- 反馈快照会输出成功、失败、等待审批、跳过、缺失反馈等状态统计，并给出 `secondTurnEligible`，用于判断是否具备进入二轮模型推理的基础条件。
- 这一步吸收的是当前工具型 Agent 的关键经验：工具调用不是一次函数执行，而是有状态、有审批、有失败、有回放的控制面工作流。
- DataSmart 目前仍保持克制：反馈快照只读取事实，不触发执行、不推进审批、不自动继续模型循环；后续再把它接入受控多步 loop 和实时事件流。

**2026-05-28 受控 Agent loop 策略追加落地：**

- Python AI Runtime 新增 `AgentLoopControlPolicyEvaluator`，在控制面反馈快照之后判断是否允许自动进入二轮模型推理。
- 策略覆盖最大工具步数、最大二轮次数、工具数量上限、token 预算、全局超时、控制面等待超时、等待审批人工接管、失败/跳过反馈是否允许进入二轮等保护。
- API 响应新增 `agentLoopControl` 摘要，输出 allowed、action、reasons、recommendedActions，让前端和审计能看到 Agent 为什么继续、等待、停止或转人工。
- 这一步对齐当前 Agent 工具体系的发展方向：自治能力必须和 budget、guardrail、approval、human takeover、traceability 绑定，而不是把工具循环写成模型自由递归。

**2026-05-28 AgentPlan 响应组装解耦追加落地：**

- Python AI Runtime 新增 `api_plan_response.py`，把 AgentPlan HTTP 响应组装从 FastAPI 路由层拆出。
- 拆分后的响应组装模块集中处理事件 envelope、事件存储/推送、Java plan ingestion 摘要、控制面反馈快照和 loop 控制决策摘要。
- `api.py` 只保留路由与依赖装配职责，避免后续二轮推理、长期记忆写入、实时事件或智能网关能力继续挤进单个 API 文件。
- 这一步对应当前 Agent Runtime 工程化趋势：真实 Agent 系统需要把 route、orchestrator、control-plane adapter、event protocol、loop policy 分层，而不是把所有副作用藏在一个 HTTP handler 里。

**2026-05-28 受控二轮推理编排器追加落地：**

- Python AI Runtime 新增 `AgentSecondTurnOrchestrator`，只有在 Java 控制面反馈完整且 `AgentLoopControlDecision.action=allow_second_turn` 时才调用模型二轮。
- 二轮请求基于 assistant(tool_calls) + tool(tool_call_id=result) 消息契约构造，但不再暴露工具，并强制 `tool_choice=none`，避免当前阶段出现无限工具循环。
- 新增 `agent_loop_control_decided` 与 `model_second_turn_skipped` 事件，二轮允许、跳过和完成都进入可 replay 的 runtime event 流。
- 这一步进一步贴近 Codex / Claude Code 类 Agent 的关键体验：模型可以基于工具结果继续推理，但每次继续都必须经过预算、审批、状态和完整性守卫。

**2026-05-28 Java 工具执行状态事件契约追加落地：**

- Java `agent-runtime` 新增 `AgentToolExecutionEventPublisher` 端口与 `AgentToolExecutionStateChangedEvent` 契约，开始把工具执行状态变化沉淀为可订阅事实流。
- 状态事件覆盖初始计划、审批通过、审批拒绝、执行中、成功、失败等关键节点，为 Python Runtime、智能网关、前端 WebSocket、审计中心和 observability 提供统一上游事实。
- 默认发布器为 Noop，Kafka 发布器通过 `datasmart.agent-runtime.tool-execution-events.enabled=true` 显式启用，避免本地无 Kafka 时破坏开发体验。
- 事件 payload 默认不发布完整工具入参和审批备注原文，只发布参数键、状态、摘要、是否存在审批备注和治理元数据，贴合当前 Agent 工具事件“可观察但不扩散敏感上下文”的安全趋势。
- 这一步把 DataSmart 从同步反馈快照继续推向事件驱动 Agent loop：后续可以按 runId 订阅/replay 工具状态，再驱动二轮推理、前端进度和人类审批体验。

**2026-05-28 Java 工具状态事件投影/replay 追加落地：**

- Java `agent-runtime` 将工具状态事件发布改造成 `DefaultAgentToolExecutionEventPublisher + AgentToolExecutionEventSink` 组合模式，同一条状态事实可以同时进入 Kafka、runtime-event 投影、未来 outbox、WebSocket 和审计中心。
- 新增 `AgentToolExecutionEventProjectionSink`，把 Java 工具状态事件写入已有 `AgentRuntimeEventProjectionStore`，让 Python runtime event 与 Java tool execution event 可以共享同一套查询、脱敏、replay 和诊断入口。
- Kafka 发布器被重构为 `KafkaAgentToolExecutionEventSink`，Kafka 不再是唯一发布实现，而是可选下游出口；这更贴近真实 Agent 平台的多通道事件总线设计。
- runtime-event 投影只保留工具状态、风险、审批、参数键名、治理提示键名和参数校验摘要，不写完整工具入参、审批备注或完整输出，继续坚持“事件可回放但敏感上下文不扩散”的安全边界。
- 这一步为后续智能网关 `/api/agent/events/ws`、断线 replay、Python 事件驱动 loop、长期审计 outbox 和前端实时工具进度提供了统一事件底座。

**2026-05-28 Python WebSocket/HTTP replay 接入 Java runtime-event 投影：**

- 本次实现前再次对照了当前 Agent 工程趋势：MCP 强调 AI 应用与工具/数据/工作流的标准化连接，OpenAI Agents SDK 强调 agent loop、tool invocation、sessions、human-in-the-loop 与 tracing，A2A 强调跨 agent 应用互操作。这些方向都指向一个共同结论：工具型 Agent 必须有可恢复、可追踪、可治理的事件时间线。
- Python AI Runtime 新增 `RuntimeEventReplaySource` 与 `RuntimeEventReplayCoordinator`，将 Java runtime-event 投影视为可插拔外部 replay source，而不是硬编码进 WebSocket handler。
- 新增 `JavaAgentRuntimeEventReplayClient`，把 Java `AgentRuntimeEventProjectionView` 映射为 Python `AgentRuntimeEvent`，使 WebSocket subscribe/reconnect 和 HTTP replay 都能合并 Python 编排事件与 Java 工具状态事件。
- `create_app()` 通过 `DATASMART_AGENT_RUNTIME_EVENT_REPLAY_ENABLED` 控制是否启用 Java replay，保持本地开发默认轻量，同时给集成/生产环境预留 timeout、path、limit 配置。
- 对 DataSmart 的产品意义：智能网关的实时事件通道不再只是“Python 自己讲述模型做了什么”，而开始恢复“模型如何规划 + Java 工具如何真实审批/执行/失败”的统一运行轨迹。
- 当前仍保持克制：没有盲目实现完整 MCP Server、A2A 协议或复杂 agent framework，而是先把 DataSmart 内部事件可恢复链路做稳。下一阶段应把 replay 事件用于驱动受控二轮 loop，并继续补全统一 sequence、事务 outbox、长期记忆和模型网关缓存治理。

**2026-05-28 runtime-event 参与受控 Agent loop 决策：**

- 当前主流 Agent Runtime 正在把 “agent loop + tool invocation + sessions/tracing” 组合成可观测执行轨迹，而不只是一次模型调用。DataSmart 本次落地选择把 replay 到的 Java 工具状态事件接入 loop policy 前置快照，正是对这个趋势的产品化吸收。
- 新增 `AgentRuntimeEventFeedbackBridge`，让 Java runtime-event replay 能补齐同步结果查询缺失的反馈，或刷新等待审批/跳过等较旧状态，再由 `AgentLoopControlPolicyEvaluator` 判断是否继续二轮推理。
- 执行中的工具事件不会被转换成工具结果，避免模型提前基于未完成工具继续推理；这与企业 Agent 的安全趋势一致：自治 loop 必须受状态、审批、预算和人工接管保护。
- API 新增 `runtimeEventFeedback` 摘要，帮助前端和运维判断本次二轮允许/阻断是否来自 replay 事件，而不是黑盒策略。
- 这一步仍然没有追完整 MCP Server 或 A2A task runtime，而是先把内部事件事实、工具反馈和 loop 策略串稳。下一阶段更适合补事务 outbox、统一 sequence 和后台事件触发 loop 恢复，然后再切到长期记忆、模型网关 KV/prefix cache 和 Skill 工具市场。

**2026-05-28 Java 工具执行事件 outbox 底座追加落地：**

- 本次落地对齐的是工具型 Agent 的可靠运行趋势：agent loop、tool invocation、tracing、human-in-the-loop 只有在事件可恢复、可诊断、可补偿时，才适合进入商业化生产场景。
- Java `agent-runtime` 新增工具执行事件 outbox 配置、领域记录、状态枚举、内存 store、优先级 sink、查询服务和诊断 API，让工具状态事件先进入可查询事件箱，再面向 Kafka/WebSocket/审计中心等下游演进。
- outbox payload 复用统一的 `AgentToolExecutionStateChangedEvent`，继续坚持不发布完整工具入参、审批备注原文和完整输出；超大 payload 会进入 `BLOCKED`，避免通用事件通道扩散异常上下文。
- 新增 MySQL 迁移脚本 `20260528_agent_runtime_tool_event_outbox.sql`，明确后续生产路线：工具审计状态更新与 outbox INSERT 同事务提交，后台 dispatcher 负责异步投递与失败补偿。
- 当前仍保持克制，没有急着实现完整 dispatcher 或数据库 store；原因是工具审计本身仍是内存仓储。下一阶段应先把工具审计状态落 MySQL，再把 outbox 从热窗口升级为真正事务 outbox。

**2026-05-28 Java 工具执行审计持久化契约追加落地：**

- 本次落地把工具执行审计从 `AgentToolExecutionAuditMemoryStore` 具体实现抽象为 `AgentToolExecutionAuditStore` 端口，开始为生产级可恢复工具状态机做存储解耦。
- `AgentToolExecutionAuditService` 现在在审批、拒绝、执行中、成功、失败等状态变更后，先保存审计状态，再发布工具状态事件，避免下游看到系统事实库中不存在的“幽灵事件”。
- 新增 `agent_tool_execution_audit` MySQL 表迁移脚本，覆盖租户、项目、工作空间、工具、风险、审批、状态、参数摘要、治理提示、输出摘要和错误码，支撑后续审计中心、WebSocket replay、长期记忆追溯和 outbox 对账。
- 配置层新增 `datasmart.agent-runtime.persistence`，默认继续使用 memory，避免本地开发被数据库依赖卡住；生产化路线则预留 `audit-store=mysql + database-enabled=true` 的双开关灰度策略。
- 这一阶段仍然没有直接接完整数据库 store，是有意控制节奏：先固定仓储端口、保存顺序、SQL 表和测试语义，再实现 MySQL store 与事务 outbox，降低重构风险。

**2026-05-28 Java MySQL 工具审计仓储追加落地：**

- Java `agent-runtime` 新增条件化 Hikari/JDBC 持久化配置，只有 `audit-store=mysql` 且 `database-enabled=true` 时才创建连接池，默认 memory 模式不受影响。
- 新增 `JdbcAgentToolExecutionAuditStore`，用 `audit_id` 做幂等 upsert，支持单条保存、批量事务保存、按 auditId 查询和按 session/run 查询。
- 本次刻意没有引入 MyBatis-Plus starter，避免默认本地环境触发 DataSource 自动配置；这是一种面向快速演进阶段的稳妥过渡，后续可在 agent-runtime 整体数据库化后再迁移 mapper。
- 该能力让工具执行审计开始具备真实可恢复路径，进一步靠近当前 Agent 工程趋势中的 durable tool invocation、traceable human-in-the-loop 和 event replay。
- 下一步应补数据库版 outbox store 与同事务边界，否则审计状态虽然可落库，但事件箱仍不能保证与状态同提交。

**2026-05-28 Java MySQL 工具事件 outbox 仓储追加落地：**

- Java `agent-runtime` 新增 `outbox-store` 持久化配置与 `JdbcAgentToolExecutionEventOutboxStore`，让工具状态事件箱具备 MySQL 可恢复路径。
- 数据库 outbox store 支持唯一键幂等 append、按 run/status 查询、按 retry 时间查询可投递记录、PUBLISHING/PUBLISHED/FAILED 状态流转和 diagnostics 聚合。
- 默认仍使用 memory，不会影响本地学习；只有 `outbox-store=mysql + database-enabled=true` 才启用数据库 store 和 Hikari 连接池。
- 这一步使 DataSmart 的 Agent 工具链路更接近 durable event replay：工具状态可落库，待投递事件也可落库，下一步可以进入真正事务 outbox。
- 仍未实现 dispatcher 和同事务提交，因此还不能把它称作完整生产闭环；下一阶段应优先补事务边界与后台投递器。

**2026-05-28 Java 工具审计与 outbox 同事务边界追加落地：**

- Java `agent-runtime` 新增轻量 JDBC 连接管理器，让 MySQL audit store 与 MySQL outbox store 可以在同一同步调用链内复用同一条事务连接。
- `AgentToolExecutionAuditService` 在 `database-enabled=true + audit-store=mysql + outbox-store=mysql` 同时满足时，把审计状态保存与状态事件发布包进同一个事务；outbox sink 写入失败会触发 rollback。
- 新增必达 sink 语义：普通 Kafka/WebSocket/projection 仍保持 fail-open，事务 outbox 在双 MySQL 条件下自动 fail-closed，避免“状态已提交但事件凭据丢失”。
- 这一步把 DataSmart 的工具型 Agent 可靠性推进到 durable tool invocation 的关键工程点：工具状态、事件凭据、后续 replay/恢复不再依赖脆弱的内存窗口。
- 当前仍未实现 dispatcher 和统一 sequence；下一步应把 outbox 中的 PENDING/FAILED 事件投递到 Kafka/WebSocket/审计中心，并开始设计跨 Python/Java 的稳定游标。

**2026-05-28 Java outbox dispatcher 最小闭环追加落地：**

- Java `agent-runtime` 新增 outbox dispatcher，从 PENDING/FAILED 记录中领取可投递事件，投递成功后标记 PUBLISHED，失败后写回 FAILED 和 nextRetryAt。
- dispatcher 使用独立 `AgentToolExecutionEventOutboxDispatchTarget`，不复用同步 publisher/sink 链路，避免从 outbox 读出的事件再次写回 outbox。
- 新增 Kafka dispatch target，直接投递 outbox 中已持久化的安全 payloadJson，并等待 broker ack 后才允许标记发布成功。
- 本阶段参考 Spring 官方 scheduling 模型，采用 fixedDelay 的周期轮询语义，适合“上一轮投递完成后再启动下一轮”的补偿投递任务。
- 当前仍是最小单实例闭环，尚未实现多实例抢占锁、stale PUBLISHING 恢复、dead-letter 和统一 sequence/cursor；这些是下一阶段把工具型 Agent 事件链路推向生产的重点。

**2026-05-28 长期记忆写入候选与审批治理追加落地：**

- 本阶段再次对齐当前 Agent 工程趋势：MCP 等协议强调工具/资源/上下文的标准化连接，OpenAI Agents SDK 等运行时强调 sessions、tool invocation、human-in-the-loop 与 tracing，LangGraph 等框架强调短期/长期记忆分层。共同结论是：长期记忆不应只是向量库写入，而应是带权限、审批、审计、保留期和遗忘策略的治理资产。
- Python AI Runtime 新增 `AgentMemoryWriteGovernanceService`，把工具结果沉淀拆成“候选 -> 审批/拒绝 -> 后续持久化”的分层流程。
- 记忆候选只记录摘要、scope、memoryType、auditId/runId/outputRef、resultKeys 和敏感字段名称，不把完整工具结果扩散到 runtime event 或 API 摘要中。
- 高风险工具、敏感字段、人工审批工具和收紧 scope 的场景默认进入 `PENDING_APPROVAL`，低风险结果也只进入 `DRAFT`，不会绕过控制面直接写长期存储。
- API 响应层通过显式注入返回 `memoryWriteProposal`，保持默认路径无隐藏副作用；候选生成会进入 runtime events，便于未来 WebSocket、审计回放和前端审批面板消费。
- 下一步不应急于“接一个向量库就算长期记忆完成”，而应优先补持久化候选表、permission-admin 审批权限、遗忘/归档策略，再分别接 Chroma 语义记忆、Neo4j 关系记忆、MySQL 事件记忆和 MinIO 资源记忆。

**2026-05-28 记忆写入候选存储与审批 API 追加落地：**

- 当前 Agent 生态里，长期记忆越来越接近“可治理的运行时资源”，而不是模型供应商或框架内部的临时功能。DataSmart 本阶段把记忆候选抽成 store 协议，并补最小查询/审批 API，正是为了让候选可以进入审批台、审计台、异步写入 worker 和未来 Java 控制面。
- Python AI Runtime 新增 `AgentMemoryWriteCandidateStore` 与内存实现，先固定 `save/get/list/update` 最小接口，避免治理服务直接依赖内存字典。
- 新增 `api_memory_write.py`，把候选列表、详情、审批通过、拒绝接口从 `api.py` 拆出，防止 FastAPI 入口重新膨胀。
- 当前路由仍不直接承担最终权限判断，生产访问应由 gateway/permission-admin 注入数据范围和操作权限；这与企业 Agent 的趋势一致：模型运行时负责能力和状态，企业控制面负责权限、审计和策略。
- 下一步应把该 API 契约映射到 Java gateway route metadata 与 permission-admin action，再补 MySQL 候选表和操作审计表。
**2026-05-28 记忆写入候选权限治理追加落地：**

- 当前主流 Agent 产品正在把 memory 从“模型上下文增强”升级为“可治理资产”：需要 scope、审批、保留期、遗忘、审计和跨工具来源追踪。
- DataSmart 本阶段将长期记忆候选 API 接入 gateway/permission-admin 权限语义，新增 `VIEW_MEMORY_WRITE_CANDIDATES`、`APPROVE_MEMORY_WRITE`、`REJECT_MEMORY_WRITE` 三类动作。
- 这一步刻意先做权限闸口，而不是直接把候选写入 Chroma/Neo4j。原因是企业数据治理场景中的记忆可能包含工具结果、事故经验、字段线索和审批上下文，一旦进入长期存储就会影响后续 Agent 决策。
- 默认策略采用“审计/运营可查看、项目负责人可决策、服务账号不可人工决策”的责任链设计，贴近 human-in-the-loop Agent 的安全趋势。
- 下一步技术路线应继续关注可持久化 memory store、memory write worker、retrieval permission、forgetting policy，以及与 KV/prefix cache 的边界隔离。

**2026-05-28 记忆写入候选持久化契约追加落地：**

- 当前 Agent 记忆趋势正在从“向量库检索能力”演进为“可审批、可恢复、可遗忘、可审计的记忆治理系统”。
- DataSmart 本阶段新增候选主表、候选审计表、候选版本号和幂等键，先保证长期记忆写入前的治理事实可恢复。
- Python Runtime 新增 DB-API SQL store，并用 sqlite3 验证语义；生产方向仍是 MySQL/Java memory-service 可替换实现。
- 这一步避免了常见误区：直接把工具结果写入 Chroma 看似完成了长期记忆，实际缺少审批、审计、并发保护和遗忘入口。
- 下一步应跟进 MySQL store 启用配置、写入 worker、遗忘策略、检索权限，以及 semantic/episodic/procedural/resource 分层落库。

**2026-05-28 记忆写入候选 Store 运行时配置追加落地：**

- 当前 Agent 记忆工程正在从“框架内部 memory”走向“可部署、可审计、可切换的企业记忆基础设施”。DataSmart 本阶段把候选 SQL store 接入 Runtime 配置，避免长期记忆治理只停留在单测或 demo 层。
- Python AI Runtime 现在支持 `in-memory`、`sqlite`、`mysql` 三种候选 store 模式，默认仍为内存，保持本地学习和离线规划零依赖。
- MySQL 驱动采用动态导入，生产环境可启用 PyMySQL/mysqlclient，本地环境不被数据库客户端强绑定。
- fail-open / fail-closed 被显式建模：开发环境可回退内存继续联调，生产环境可选择快速失败，避免“配置了持久化但实际未持久化”的隐性事故。
- 新增诊断接口只返回 store 类型、实现、持久化状态、fallback 原因和脱敏 DSN，不返回候选内容。这延续了 Agent 运行时“可观察但不扩散敏感上下文”的安全原则。
- 下一步应补候选 API 错误语义、分页 cursor 和 APPROVED 候选异步写入 worker；同时保持全局节奏，继续推进智能网关模型路由、工具/Skill 执行闭环和工作区隔离，不让长期记忆局部吞掉全部演进资源。

**2026-05-28 记忆写入候选 API 分页与结构化错误追加落地：**

- 企业 Agent 的长期记忆管理界面需要像审计系统一样可翻页、可筛选、可诊断，而不是只暴露一个临时列表。DataSmart 本阶段为候选列表增加 cursor 分页契约。
- cursor 采用 `createdAt + candidateId`，先保证 API 语义稳定；后续 MySQL store 可把相同契约下沉到 SQL 条件，避免深分页性能问题。
- 错误响应增加 `errorCode/message/statusCode`，让前端、gateway、日志统计和告警规则可以基于机器可读码工作。
- 这一步继续把 memory 当作治理资产处理：候选不存在、非法游标、审批冲突和版本冲突都必须可解释、可观测、可自动化处理。
- 长期记忆候选链路至此已有候选生成、审批/拒绝、权限动作、SQL 持久化地基、运行时配置、诊断、分页和错误语义。下一阶段应在异步写入 worker 与智能网关/工具 Skill 主线之间做节奏切换，不宜继续只围绕候选 API 做局部扩展。

**2026-05-28 Agent 工作空间上下文追加落地：**

- 当前 Agent 工程趋势正在把 sessions、tracing、tool invocation、MCP 工具/资源连接和 A2A 互操作组合成可持续运行的 Agent Runtime。对 DataSmart 来说，落地这些能力前必须先有稳定 workspace 边界。
- Python Runtime 新增 `AgentWorkspaceContextBuilder`，把 tenant/project/workspace/session 转换为统一 `workspaceKey`，并派生 cache、memory、artifact namespace。
- `/agent/plans` 响应新增 `agentWorkspace`，使前端、gateway、Java 控制面和后续 Python 工具/Skill 执行都能看到同一隔离语义。
- 当前只生成逻辑 namespace，不创建真实目录、MinIO bucket、Chroma collection 或 Neo4j 子图，是为了先固定契约，再渐进接入真实资源。
- 下一步应把 workspace 写入 ToolPlan governance hints，并设计 `workspace://`、`memory://`、`minio://` 等输出引用规范；随后再把 workspace namespace 用于模型 prefix/KV cache、长期记忆写入和工具执行沙箱。

**2026-05-28 ToolPlan 工作空间治理提示追加落地：**

- DataSmart 本阶段把 workspace 从顶层响应字段推进到每个 `ToolPlan.governance_hints`，让工具执行链路不必依赖顶层响应回查。
- 这符合当前 Agent Runtime 趋势：tool invocation、sessions、tracing 和人类审批都需要在单条工具审计记录上保留足够上下文，否则 replay、恢复、审批和二轮推理会丢失隔离边界。
- workspace hints 只保留机器字段，例如 `workspaceKey`、`cacheNamespace`、`memoryNamespace`、`artifactNamespace`，不扩散人读推荐动作。
- 下一步应设计工具输出引用协议，并把 `cacheNamespace` 用于 prefix/KV cache key，把 `memoryNamespace` 用于长期记忆写入边界，把 `artifactNamespace` 用于 MinIO 或本地沙箱产物路径。

**2026-05-28 Agent 资源引用协议追加落地：**

- 当前 Agent 生态中的工具/资源连接趋势强调资源不能只是文本：MCP 的 resources、工具输出、运行事件和长期记忆都需要可定位、可审计、可授权的引用结构。
- DataSmart 新增 `AgentResourceReference`，把工具输出、workspace 产物、memory、MinIO 对象和 agent-runtime 审计引用统一成结构化协议。
- 规则式 ToolPlan 仍保留 Java 兼容的 `fromTool/path`，同时附加 `resourceReference`；模型工具结果回填仍保留 `outputRef`，同时附加 `outputReference`。
- 这一步为后续资源 resolver、模型上下文准入过滤、工作空间产物管理和长期记忆写入边界打基础。
- 下一步应把 `contextPolicy` 接入二轮推理和模型回填，防止审计专用或下载专用资源被错误放进模型上下文。

**2026-05-28 Agent 资源引用治理解析器追加落地：**

- 当前 Codex、Claude Code、MCP 生态和企业 Agent 平台都在把“工具结果”升级为“受治理资源”：资源可以被引用、下载、审计、摘要进模型或进入长期记忆，但这些路径必须有明确准入条件。
- DataSmart 新增轻量 `AgentResourceReferenceResolver`，先执行 workspace 边界、资源类型和 `contextPolicy` 校验，而不是直接读取 MinIO、Chroma、Neo4j、Java audit 或外部 URL。
- 这与主流 Agent 工程趋势一致：把 resource discovery、permission gate、context selection 和 physical fetch 解耦。模型上下文选择器只应该看到被治理准入的摘要或安全结果，不能直接根据 URI 自行读取。
- Resolver 输出 `resolverHint`，为后续 `java_tool_output_store`、`workspace_artifact_store`、`agent_memory_service`、`minio_object_store` 等读取器预留扩展点，避免形成一个巨大且高耦合的“全能资源读取类”。
- 下一步应把 `model_context_allowed` 接入二轮推理消息构建，再逐步支持 Java `resourceReference/outputReference` 与真实资源读取器。资源读取器落地前，应优先补齐权限、脱敏、大小限制、超时、缓存和审计事件策略。

**2026-05-28 模型工具结果回填接入资源准入过滤：**

- 当前前沿工具型 Agent 的一个核心趋势是“context engineering”：工具结果、文件、记忆和事件不是越多越好，而是必须按权限、任务、token 预算和安全策略选择进入模型上下文。
- DataSmart 本阶段把 `AgentResourceReferenceResolver` 接入 `ModelToolResultFeedbackBuilder`，让 role=tool 消息在二轮推理前执行 workspace 和 `contextPolicy` 判断。
- 默认 `audit_only` 的策略符合企业 Agent 安全实践：如果 Java 控制面或资源生产方没有明确声明“这个摘要可以给模型看”，Python Runtime 就不会把结构化 result 放进模型。
- 该设计也贴近 MCP resources 和现代 Agent Runtime 的分层思想：引用可以进入消息，真实内容是否进入模型要经过独立 context gate，后续还可以叠加字段级脱敏、大小限制、引用摘要、缓存和审计。
- 下一步应让 Java 控制面在工具结果查询中返回 `workspaceKey/contextPolicy`，并把资源准入决策写入 runtime event，形成可解释、可审计、可调试的上下文选择链路。

**2026-05-29 资源准入决策事件化追加落地：**

- 当前 Agent 工程趋势不只强调“能不能调用工具”，也强调工具上下文选择过程要可解释、可追踪、可回放。Codex/Claude Code 类产品里的工具调用体验，背后需要稳定事件流来解释每一步为什么发生。
- DataSmart 本阶段把资源准入判断写入 `TOOL_RESULT_FEEDBACK_BUILT` runtime event，记录 decision、issue code、modelContextAllowed、resolverHint、referenceKind 和 contextPolicy。
- 这让 context engineering 从“内部策略”变成“可观察事实”：如果模型没有看到某个工具 result，前端和审计台可以知道是 `audit_only`、workspace 越界、外部引用阻断，还是 URI 缺失。
- 事件摘要不包含 result 原文，只包含引用和治理结论。这符合企业 Agent 的安全趋势：trace 要足够可诊断，但不能把敏感工具输出扩散到 tracing/event 系统。
- 下一步应继续向字段级/JSONPath 级 context selection 演进，并让 Java 控制面返回真实 `outputReference/contextPolicy`，再把这类事件接入 WebSocket/Kafka replay。

**2026-05-29 工具结果字段级上下文过滤追加落地：**

- 当前前沿 Agent 产品的重点正在从“长上下文堆更多内容”转向“context engineering”：选择哪些工具结果、哪些字段、以什么粒度进入模型，比盲目塞入全部结果更重要。
- DataSmart 本阶段新增 `ModelResultContextFilter`，支持 include/exclude/sensitive 路径、列表通配、字符串长度限制、列表长度限制和深度限制。
- 这让工具结果可以按字段进入模型：例如表数量、字段名、规则数量可见，样本值、连接串、原始 SQL、大日志和长列表被遮蔽、删除或截断。
- `resultFilterReport` 和 runtime event 的 `resultFilters` 只记录路径和过滤动作，不记录字段值，符合企业 Agent tracing 的安全实践。
- 下一步应让 Java 控制面、工具 schema、permission-admin 字段权限和数据分级分类共同生成字段级策略；同时把过滤事件接入 WebSocket/Kafka replay，使上下文选择过程可回放、可审计。

**2026-05-29 智能网关 prefix/KV cache 治理计划追加落地：**

- 当前 Agent 基础设施趋势正在把模型网关从“Provider 转发器”升级为“路由、预算、fallback、上下文、缓存和审计的策略中枢”。对长上下文 Agent 来说，prefix/KV cache 能明显降低 prefill 成本，但如果没有租户、项目、工作空间和会话边界，就会变成新的上下文泄露风险。
- DataSmart 本阶段新增 `ModelGatewayCachePlan` 与 `ModelGatewayCachePlanner`，在模型网关路由决策中输出缓存治理计划，而不是直接把 prompt 哈希交给推理服务复用。
- 缓存范围显式区分 `GLOBAL_SAFE`、`TENANT_SAFE`、`PROJECT_SAFE`、`SESSION_ONLY`、`NO_CACHE`。其中会话级缓存必须携带 `sessionId`，缺失时禁用缓存并给出诊断。
- `cachePlan` 已进入 API 响应和 `MODEL_GATEWAY_ROUTED` runtime event，前端、Java 控制面、审计台和运维面板后续都可以解释缓存启停原因、namespace、TTL 和治理 issue。
- 下一步不建议继续在缓存本身无限深挖；更合理的节奏是把 `cachePlan` 透传给 Provider metadata，并并行推进工具执行闭环、Skill/Tool 市场、工作空间沙箱和长期记忆写入 worker，形成更接近 Codex/Claude Code 类 Agent 的整体能力。

**2026-05-29 模型 Provider metadata 透传 cachePlan 追加落地：**

- 当前前沿 Agent 网关正在从“模型 API 代理”演进为“上下文工程控制面”：网关不仅要知道请求发往哪个模型，还要知道租户、工作负载、缓存边界、trace、预算和审计标签。
- DataSmart 本阶段新增 `provider_metadata` 契约，并把 `cachePlan` 从 Agent 主链透传到 OpenAI-compatible Provider 的请求体 `metadata.datasmart` 与 `X-DataSmart-*` Header。
- 这一步让 LiteLLM/vLLM/SGLang/企业内部智能网关可以在不理解 Python 内部 dataclass 的情况下读取缓存策略，例如是否启用、namespace、keyPrefix、TTL 和 scope。
- metadata 使用白名单裁剪，不携带 prompt、工具结果、样本数据、SQL 或密钥，符合企业 Agent tracing 与 context engineering 的安全原则。
- 下一步应补“受信内部网关开关”和模型网关指标，而不是直接把复杂缓存存储塞进 Python Runtime；同时应把开发重心转向工具执行闭环、Skill/Tool 市场和工作空间沙箱，形成更完整的类 Codex/Claude Code Agent 能力。

**2026-05-29 Java 工具结果批量反馈查询追加落地：**

- 当前 Codex/Claude Code 类 Agent 的关键能力不是只调用一个工具，而是多工具链路、状态可见、失败可恢复、结果可回填。多工具链路如果每个工具结果都单独轮询，会很快形成 N+1 控制面请求。
- DataSmart 本阶段新增 Java run 级批量结果查询接口，并让 Python Java-feedback Provider 优先批量读取同一 session/run 的工具反馈。
- 这一步保持“查询不执行”的边界：批量接口只返回 audit/output 快照，不审批、不执行、不推进状态，避免反馈收集成为隐藏副作用。
- 批量优先、失败回退逐个查询的策略更适合渐进式生产部署：Java 新旧版本混合、网络波动或部分引用缺失时，Python Agent loop 不会直接中断。
- 下一步应把批量反馈与 ToolPlan DAG、runtime-event replay 和 run 级执行策略结合，而不是简单做“自动执行所有工具”。真正商业化 Agent 需要明确哪些工具能自动执行、哪些等待审批、失败后是否继续、并行组如何回滚。

**2026-05-29 Run 级工具执行策略预检追加落地：**

- 当前类 Codex、Claude Code、OpenClaw 方向的 Agent 产品越来越强调“工具调用不是函数调用，而是受治理的行动”。模型可以提出工具计划，但执行前必须有策略层判断审批、人类介入、参数完整性、执行模式、幂等性和失败恢复。
- DataSmart 本阶段新增 Java Run 级 execution policy preflight，把审计事实翻译成 `AUTO_EXECUTABLE`、`WAITING_APPROVAL`、`WAITING_PARAMETER_COMPLETION`、`WAITING_ASYNC_EXECUTOR`、`FAILED_BLOCKS_RUN` 等决策。
- 这一步符合前沿 Agent runtime 的趋势：先建立 tool invocation control plane，再做自动执行器；否则一旦把模型计划直接变成下游写操作，后续再补审批、回滚、审计和限流会非常痛苦。
- 策略接口保持只读，没有隐藏副作用。它可以被前端、Python Runtime、自动执行 worker、审计台和运维面板共同使用，成为工具行动的统一解释层。
- 下一步应沿两条线推进：一条是低风险同步工具的受控自动执行器，另一条是 `ASYNC_TASK` 到 task-management/Kafka command 的任务化执行。与此同时，需要尽快补 ToolPlan DAG，让多工具计划具备依赖、并发组、失败策略和结果回填顺序。

**2026-05-29 受控同步工具自动执行器追加落地：**

- 当前前沿 Agent 产品的工具执行能力正在从“人工点击每个工具”走向“可治理的自动行动”。但成熟产品不会直接把模型计划全部执行，而是先开放低风险、只读、幂等、可审计的同步工具。
- DataSmart 本阶段新增 `auto-execute-sync`，在 Run 级 policy 之后再次筛选 LOW/readOnly/idempotent/requiresApproval=false 的工具，形成第一条安全自动执行路径。
- `dryRun`、auditId 白名单和单批次数量上限，是面向生产的必要控制点：前端可以先预览，Python Runtime 可以缩小范围，平台可以防止一次请求打爆下游服务。
- 这一步仍然复用统一 `AgentToolExecutionService` 执行和审计状态推进，没有复制第二套状态机，符合 Agent Runtime 控制面解耦趋势。
- 下一步应让 Python Runtime 接入该闭环，并尽快设计异步工具任务化、ToolPlan DAG、权限动作、租户开关和多实例幂等执行表。否则自动执行能力会停留在单机低风险同步工具，无法支撑真实复杂 Agent 工作流。

**2026-05-29 Python 接入 Java 工具策略与自动执行追加落地：**

- 当前类 Codex/Claude Code Agent 的核心体验是“模型规划、工具执行、结果回填、继续推理”形成闭环，而不是让用户手工逐个触发工具。DataSmart 本阶段让 Python Runtime 在二轮推理前具备触发 Java 安全同步候选的能力。
- Python Provider 现在可以按需执行 `policy -> auto-execute-sync -> batch results`，这让低风险只读工具可以进入自动行动路径，同时仍由 Java 控制面掌握最终执行边界。
- 自动执行默认关闭，必须通过环境变量灰度启用。这符合企业 Agent 落地趋势：先可观测、再 dryRun、再小范围自动执行，最后才进入租户级策略自动化。
- 本阶段也拆分了 client/provider/contracts，避免工具反馈能力膨胀成单文件巨石。Agent 工具链后续会继续增加 policy、DAG、async、event、permission，如果现在不拆，后续会很难演进。
- 下一步应把 auto execution 摘要事件化，并启动异步工具任务化与 ToolPlan DAG。否则同步自动执行会变成新的局部极限，无法承载长耗时、多依赖、可恢复的真实 Agent 工作流。

**2026-05-29 同步自动执行事件化追加落地：**

- 当前前沿 Agent Runtime 的关键趋势之一是“行动可见性”：Agent 不只是执行工具，还要让用户、审计员和运维系统知道它什么时候、为什么、执行了哪些动作。
- DataSmart 本阶段将 Java `auto-execute-sync` 的批次摘要写入 Python runtime event，新增 `tool_auto_execution_sync_completed`，记录 dryRun、limit、executed/failed/skipped 计数和每个工具 action/reason。
- 这让自动执行从隐藏副作用变成可审计事件。对类 Codex/Claude Code 的交互体验来说，用户需要看到“我刚才让 Agent 继续，它自动跑了哪些安全工具”，否则工具行动会变成黑盒。
- 事件不记录工具 output 原文，只记录动作摘要，符合企业 Agent 的安全实践：trace 要解释动作，不应复制敏感数据。
- 下一步技术重心应从同步低风险工具转向 `ASYNC_TASK` 任务化、ToolPlan DAG、WebSocket replay 和 permission-admin 策略来源，避免同步自动执行链路继续无限细化。

**2026-05-31 ASYNC_TASK 异步命令草案规划追加落地：**

- 当前成熟 Agent Runtime 不会让长耗时工具继续占用同步请求线程，而是把它们转换为可恢复、可暂停、可重试、可回放的任务。DataSmart 本阶段开始建立 `agent-runtime -> Kafka/outbox -> task-management` 的异步行动边界。
- Java 新增只读 `async-command-plans` preflight：它只为 `ASYNC_TASK + WAITING_ASYNC_EXECUTOR` 工具生成稳定 commandId、幂等键、topic、消费者、隔离边界和审计上下文，不会偷偷投递消息或创建任务。
- Kafka 通常是至少一次投递，因此默认要求异步工具声明幂等。非幂等工具会保守阻断，避免重复同步、重复导出或重复写入等副作用。
- 草案只暴露参数名和敏感参数名，不暴露连接密钥、SQL、文件路径或参数值。未来 dispatcher 应按 auditId 在服务内部重新读取受控参数快照，再完成权限、schema、脱敏和密钥引用校验。
- 下一步应切换到 task-management 消费侧，落地 command 幂等表和任务创建契约，再回到 agent-runtime 做 outbox + Kafka dispatcher。这样可以避免继续在单一模块里局部优化，也降低跨服务契约反复推翻的成本。

**2026-05-31 Task Management Agent Command Inbox 追加落地：**

- 当前前沿 Agent 工具执行正在从“同步函数调用”演进为“可靠行动流水线”：计划、审批、投递、任务化、执行、回写、可见性和回放都要有明确边界。
- DataSmart 本阶段在 task-management 侧新增 Agent async command Inbox，用 commandId 与 idempotencyKey 唯一索引承接 Kafka 至少一次投递风险，避免同一 Agent 工具重复创建多个任务。
- 消费侧只把合法 command 转成通用 `AGENT_ASYNC_TOOL` 任务，复用任务中心已有队列、租约、重试、死信和运营干预能力，避免 agent-runtime 变成第二个任务系统。
- Inbox 和 task.params 只保存 payloadReference、参数名和敏感参数名，不保存原始工具参数值。这符合企业 Agent 的“引用优先、按需解析、权限复核”趋势。
- 下一步应回到 agent-runtime 增加 outbox + dispatcher，并在 task-management 增加 Kafka listener 传输适配器；业务去重和任务创建逻辑继续保持在同一个 ConsumerService 中。

**2026-05-31 Agent Command Outbox 与 Dispatcher 追加落地：**

- 当前成熟 Agent 平台越来越强调 durable action：模型规划出的行动不能只停留在内存对象或同步请求里，而要进入可恢复、可诊断、可重放、可人工补偿的执行轨道。
- DataSmart 本阶段在 agent-runtime 侧新增 ASYNC_TASK command outbox，把“准备下发异步工具命令”固定为 outbox record，再由 dispatcher 投递到 task-management。这样与 task-management Inbox 形成了“生产者 outbox + 消费者 inbox”的双端可靠性组合。
- command payload 继续坚持引用优先，只传播 `payloadReference`、参数名、敏感参数名和治理上下文，不传播真实工具参数值。这与当前 Agent 安全趋势一致：工具执行上下文应在执行边界按权限和 schema 重新解析，而不是在消息总线上到处复制。
- dispatcher 支持 PENDING/PUBLISHING/PUBLISHED/FAILED/BLOCKED 状态、失败退避、最大尝试阻断、无投递目标防误吞和 stale publishing 恢复；这些能力是未来 Kafka、WebSocket replay、死信治理和运维补偿台的基础。
- 当前仍然保持克制：HTTP target 默认关闭，后台 dispatcher 默认关闭，MySQL 表已准备但默认使用内存 store。下一步应补 Kafka target、task-management Kafka listener、MySQL command outbox store 和 payloadReference resolver，再把任务状态回写到 agent-runtime 工具审计。

**2026-05-31 Task Management Kafka Listener 追加落地：**

- 当前 Agent 工具执行趋势不是简单“发一个 HTTP 请求”，而是把行动变成可恢复的消息流水线。DataSmart 本阶段在 task-management 侧新增 Agent async command Kafka listener，开始承接 `agent-runtime outbox -> Kafka -> task-management Inbox` 的真实消息入口。
- listener 只做传输适配，真正的协议校验、幂等去重和任务创建继续复用 `AgentAsyncTaskCommandConsumerService`。这符合商业化系统的关键原则：多入口共享同一业务事实源，避免 HTTP、Kafka、重放工具各写一套逻辑。
- Kafka payload 明确采用字符串 JSON，而不是 Java 类型序列化，降低跨语言消费和运维排障成本。未来 Python Runtime、审计服务或命令行工具都可以直接查看同一消息契约。
- 默认关闭 listener、默认非法消息 fail-fast，是当前没有 DLQ 阶段的安全选择：本地不被 Kafka 依赖拖住，生产也不会静默确认坏消息。下一步应尽快补 DLQ、消费指标和积压告警。
- 到这里，异步工具任务化链路已经有 command plan、producer outbox、consumer inbox 和 Kafka listener。下一段最关键的是补 agent-runtime Kafka dispatch target 与 MySQL command outbox store，然后进入 payloadReference resolver、异步 worker、状态回写和 ToolPlan DAG。

**2026-05-31 Agent Command Kafka Dispatch Target 追加落地：**

- DataSmart 本阶段补齐 agent-runtime 侧 Kafka dispatch target，让 command outbox 可以把 ASYNC_TASK 命令真正投递到 task-management Kafka listener。异步工具链路从“生产者 outbox 与消费者 listener 分别存在”推进到“传输通道可以闭合”。
- Kafka target 仍然由 outbox dispatcher 驱动，不把发送动作塞回业务线程。这与当前 Agent 平台的 durable action 趋势一致：行动先进入可恢复 outbox，再由后台投递器完成至少一次传输。
- 发送时等待 broker ack，失败、超时和中断都会让 dispatcher 写回 FAILED 并按退避重试。命令消息会触发下游任务创建，因此不能像普通日志一样 fire-and-forget。
- topic、partitionKey 和 payload 都来自 outbox record，说明路由决策仍在 command plan/outbox 层，Kafka target 只是传输适配器。后续按租户、风险等级或工具类型拆 topic 时，不需要改投递器主流程。
- 下一步应优先把 command outbox 从内存升级为 MySQL store，并补 task-management DLQ/指标；否则传输通道虽然打通，但生产事故恢复仍会受限于单实例内存窗口。

**2026-05-31 Agent Command MySQL Outbox Store 追加落地：**

- 当前 Agent 平台的 durable action 趋势强调“行动事实先持久化，再异步投递”。DataSmart 本阶段新增 MySQL command outbox store，让 ASYNC_TASK 命令不再只停留在 JVM 内存窗口。
- Store 使用唯一索引承接 outboxId、commandId、idempotencyKey 幂等，重复写入返回既有事实，而不是制造重复任务。这与 Kafka 至少一次投递和 Agent retry 行为天然匹配。
- `PENDING/FAILED -> PUBLISHING` 通过条件 UPDATE 领取，`PUBLISHING` stale 恢复为 FAILED，形成最小可用的多实例恢复语义。后续可以进一步演进为 workerId/lockedAt/lockExpireAt 显式租约模型。
- payload 仍坚持引用优先，只保存 payloadReference、参数名和治理上下文，不在 outbox 表中复制真实工具参数值。这是企业 Agent 安全边界的关键设计。
- 下一步不宜继续只优化 outbox 内部机制，应尽快进入 payloadReference resolver、异步 worker、状态回写和 ToolPlan DAG，让“命令能可靠到达”升级为“工具能安全执行、进度能回放、失败能补偿”。

**2026-05-31 Agent payloadReference Resolver 与执行预检追加落地：**

- 当前 Codex/Claude Code 类 Agent 的工具执行链路强调“引用优先、按需解析、执行前再校验”。模型计划和消息队列不应到处复制真实参数，尤其是 SQL、密钥引用、文件路径、同步配置和数据样本。
- DataSmart 本阶段新增 Agent Runtime 内部 `plan-arguments` 快照接口，并在 task-management 侧新增 payloadReference parser、Agent Runtime client、payload resolver 和 worker 预检接口。
- resolver 会校验 task.params 摘要与 Agent 审计快照是否一致，包括 session/run/audit/tool/target/tenant/project/workspace、参数名、敏感参数名和 payload 大小。这让任务执行前具备“命令没有错配、参数没有漂移”的安全闸门。
- 预检仍然 dry-run，不直接调用 targetEndpoint。这个边界符合成熟 Agent 平台趋势：先做 tool invocation control plane，再做工具适配器和执行器；否则自动行动会变成无法审计、无法回滚的黑盒。
- 下一步应把 resolver 连接到真正 worker 的认领/心跳/执行/回写流程，并接 permission-admin 服务间策略、工具 schema 校验、密钥引用解析和 ToolPlan DAG。这样 DataSmart 的 Agent 才能从“消息能到”升级为“行动可信、进度可见、失败可恢复”。
**2026-05-31 Agent 异步工具白名单执行与 data-sync.execute 受控适配追加落地：**

- 当前类 Codex/Claude Code Agent 的工具执行能力不是“模型输出 URL，系统直接调用”，而是“模型提出工具计划，控制面按白名单、权限、幂等、审计和状态机执行”。DataSmart 本阶段把这个原则落到 `task-management` worker：只允许明确适配器执行 `data-sync.execute`。
- `data-sync.execute` 没有复用公开两步 API，而是新增内部幂等入口，把创建同步任务和提交入队合并为一个可重试动作。这对生产 Agent 很关键：worker 网络超时、Kafka 重放或手动补偿都不能产生重复同步任务。
- `dispatch-once` 先作为手动入口而不是后台无限循环，符合渐进式自动化策略：先把执行边界、幂等、失败语义和结果摘要跑通，再开启并发 worker、租户配额、心跳续租和调度器。
- 下一步应把 task-management 的执行结果回写到 agent-runtime 工具审计与 runtime event，让 Python Runtime、前端和审计台看到 TASK_RUNNING/TASK_SUCCEEDED/TASK_FAILED；随后再扩展 ToolPlan DAG、permission-admin 服务间授权和更多工具适配器。
**2026-05-31 Agent 异步工具状态回写追加落地：**

- 当前 Codex/Claude Code 类 Agent 的工具能力趋势已经不是“能调用工具”这么简单，而是要求 durable action 具备状态可见性、trace 可回放、失败可补偿、人类可审计和模型二轮推理可消费。
- DataSmart 本阶段新增 agent-runtime 内部 `async-task-status` 回调，把 task-management worker 的 RUNNING、SUCCEEDED、FAILED、DEFERRED 映射回工具审计状态和既有 runtime event。
- 这一步没有让 task-management 复制 Agent event 模型，也没有跨库写 agent-runtime 状态；所有 Agent 侧事实仍由 agent-runtime 发布，符合成熟 Agent 控制面的边界设计。
- 回写失败时 worker 会 defer 当前任务，特别是业务成功但 SUCCEEDED 回写失败时，不会静默 complete。这对应前沿 Agent 工程里的可靠行动原则：实际副作用、审计事实和用户可见状态必须最终一致。
- DEFERRED 暂不扩展状态枚举，而是作为 EXECUTING 的进度说明处理。后续等 worker 调度、DLQ、指标和前端状态视图稳定后，再引入 RETRYING/DEFERRED 细分状态更稳妥。
- 下一步技术重点应转向后台 worker 调度、DLQ/指标、ToolPlan DAG 和 permission-admin 服务间授权，避免只在单个 data-sync 适配器上继续局部深挖。

**2026-05-31 Agent 后台 worker 调度骨架追加落地：**

- 当前前沿 Agent 平台正在把 tool invocation 从同步函数调用升级为 durable action pipeline：计划、审批、入队、后台执行、状态回写、trace、重试、补偿和指标都必须成为平台能力。
- DataSmart 本阶段新增 task-management 后台 worker scheduler，但默认关闭，并要求 `enabled=true + dryRunOnly=false + schedulerEnabled=true` 三个条件同时满足才会自动消费任务。
- 这符合成熟 Agent 产品的渐进自动化思路：先手动验证执行链路，再小流量自动调度，最后才进入并发池、租户配额、工具限流和自愈补偿。
- 调度器采用 fixed-delay 和单轮上限，避免长耗时工具导致调度堆积，也避免单实例一次性吞掉过多任务压垮 data-sync 等下游服务。
- 下一步需要把 worker 变成可观测组件：Micrometer 指标、DLQ、积压告警、坏消息处理台和权限策略，比继续新增更多工具适配器更关键。

**2026-05-31 Agent Kafka 命令消费诊断与 DLQ 基础追加落地：**

- 当前类 Codex/Claude Code 的 Agent 行动链路越来越接近“可恢复消息流水线”：模型计划不是一次性函数调用，而是进入 outbox、Kafka、Inbox、worker、状态回写和事件回放的可靠轨道。
- DataSmart 本阶段补 task-management Kafka listener 的失败诊断，按 `EMPTY_PAYLOAD`、`PAYLOAD_TOO_LARGE`、`INVALID_JSON`、`CONSUMER_REJECTED`、`CONSUMER_EXCEPTION` 分类记录坏消息。
- 这一步对齐 Agent runtime 的生产趋势：工具行动失败不能只靠日志猜测，必须能被指标、告警、DLQ、人工补偿台和审计系统理解。
- 当前只做 DLQ 候选标记，不直接写真实 DLQ topic，是安全的渐进路线。真实 DLQ 必须同时包含 payload 脱敏、重放幂等、权限审批、审计记录和租户隔离，否则会变成另一个危险执行入口。
- 下一步应把诊断能力接入 Micrometer 和 Kafka record 元数据，再进入 ToolPlan DAG 与服务间授权。这样 Agent 从“能执行单个异步工具”继续升级为“多工具可编排、失败可解释、行动可恢复”的商业化平台能力。

**2026-05-31 Agent Kafka 指标与 record 元数据诊断追加落地：**

- 当前前沿 Agent 平台越来越强调 durable action observability：工具行动不只是“被调用”，还必须具备指标、trace、事件、失败分类、可定位消息和人工补偿入口。
- DataSmart 本阶段让 task-management Kafka command 入口具备 Micrometer 指标，记录 accepted/rejected、failureType、duplicate、taskCreated、DLQ candidate 和 handle duration。
- 同时 listener 开始读取 Kafka `ConsumerRecord`，把 topic、partition、offset、timestamp、keyHash 和 traceId 写入诊断样本与安全日志。
- 这一步保持了指标低基数原则：offset、traceId、commandId 不进入指标标签，避免 Prometheus 时序爆炸；单条定位交给诊断快照。
- 下一步不应继续只优化 listener，而应进入 ToolPlan DAG、服务间授权和 worker 指标。类 Codex/Claude Code Agent 的核心竞争力来自多工具可编排、行动可审计、失败可恢复，而不是单个 Kafka 消费器越来越复杂。

**2026-05-31 ToolPlan DAG 只读预检追加落地：**

- 当前前沿 Agent Runtime 的主线不是“更多工具函数”，而是“可恢复的图执行”：工具节点需要依赖边、拓扑顺序、人工中断、状态检查点、失败策略和结果回填。LangGraph 的持久化/检查点文档强调图状态、human-in-the-loop、fault-tolerance 与 replay，这与 DataSmart 先做只读 DAG preflight 再做真实执行器的路线一致。
- MCP 工具规范强调工具是模型可发现、可调用的外部能力，但也明确需要 trust & safety 与 human-in-the-loop。DataSmart 的 ToolPlan DAG 预检继续坚持“模型可规划，控制面解释与治理，执行前再授权”的边界，而不是让模型直接变成任意 HTTP 调用器。
- 本阶段新增 `dag-plan` 视图，用节点、边、并行组、失败策略、ready/blocked 解释，把 Agent 行动从线性列表提升为可审计图谱。
- `LEGACY_SEQUENCE` 兼容旧线性计划，`EXPLICIT` 支持 `dependsOn` 显式依赖，这让 Python Runtime 可以渐进升级，不需要一次性推翻现有 ingestion 契约。
- 参考资料：LangGraph Persistence / checkpointing：`https://docs.langchain.com/oss/python/langgraph/persistence`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。
- 下一步应把 DAG hints 前移到 Python AgentPlan schema，并把 Java ready 节点选择接入现有 execution-policy、permission-admin、worker 指标和 runtime event replay。否则 DAG 只会停留在展示层，无法形成类 Codex/Claude Code 的真实多工具行动闭环。

**2026-05-31 Python ToolPlan DAG hints 追加落地：**

- 当前前沿 Agent 工具链正在从“模型调用单个函数”转向“计划图 + 检查点 + 工具治理 + 人类介入”的组合。DataSmart 本阶段让 Python Runtime 在计划阶段就输出 DAG hints，而不是让 Java 运行时事后猜测依赖。
- `ToolPlanDagAnnotator` 会从 `fromTool/resourceReference` 中解析依赖，并对模型生成计划补最小业务依赖兜底。这样即使模型只生成了某个业务工具调用，平台也能保留“先读元数据、再生成规则、再创建任务草稿”的治理链条。
- DAG hints 仍放在 `governanceHints`，属于兼容式演进。短期有利于快速贯通 Python -> Java；中期应升级为强类型 ToolPlan DAG schema，避免工具越来越多后 hint key 失控。
- 下一步应优先做 DAG-aware execution preview 与 permission-admin 授权，而不是直接打开并发自动执行。类 Codex/Claude Code 的体验看似自动，底层必须有权限、审计、状态、重放和失败补偿托底。

**2026-05-31 Java DAG-aware execution preview 追加落地：**

- 当前 Agent 工程趋势强调“自动行动前必须可解释”。DataSmart 本阶段新增 DAG execution preview，不执行工具，只解释每个 ready/blocked 节点下一步应走同步自动执行、异步 command、人工审批、参数补齐还是等待依赖。
- Preview 的价值是把前端、人类审批、Python Runtime loop policy 和未来 DAG worker 对齐到同一套行动语义。没有这层，真实 worker 很容易变成隐藏副作用黑盒。
- 同步候选继续要求 LOW/readOnly/idempotent，异步候选继续要求 command plan dispatchable，说明系统并没有因为 DAG ready 就放弃工具治理。
- 下一步技术重点应转向 permission-admin 服务间授权和执行 dry-run request。真正进入并发 DAG worker 之前，还需要租户配额、工具限流、worker 指标、失败补偿和 runtime event 可视化。

**2026-05-31 Agent 服务间授权预检追加落地：**

- 当前 Codex、Claude Code、MCP 工具生态和企业 Agent 平台都在强化“工具调用不是模型自由行动，而是受控委托执行”。DataSmart 本阶段在 DAG execution preview 中新增 `serviceAuthorization`，把 SERVICE_ACCOUNT 代表 actor 推进工具节点这件事显式建模。
- 预检支持 `LOCAL_PREVIEW` 与 `PERMISSION_ADMIN_EVALUATE` 两条路线：前者适合本地学习和结构检查，后者面向生产权限中心。这个分层符合成熟 Agent 平台的落地节奏：先让行动可解释，再接真实授权，再进入自动执行。
- 远端权限中心不可用被建模为 `PERMISSION_ADMIN_UNAVAILABLE`，而不是简单当作 false 或吞掉异常。这样前端、审计台和运维可以区分“权限真的拒绝”和“权限基础设施故障”。
- 该能力保持微服务边界，agent-runtime 通过本地客户端契约调用 permission-admin，而不是编译期依赖权限模块 DTO。后续可以平滑替换为 OpenFeign、gRPC、服务网格或带缓存的授权代理。
- 下一步应把 permission-admin evaluate 契约升级为支持 representedActorId、serviceAccountId、delegationReason 和 policyVersion，并在真实 DAG worker 前打开 fail-closed enforcement、租户配额、工具限流和执行指标。
**2026-05-31 DAG-aware execution dry-run 追加入地：**

- 当前前沿 Agent 平台正在把“工具调用”从一次性函数调用演进为可观察、可审批、可恢复的行动流水线。LangGraph 文档强调持久化 checkpoint 能支撑 human-in-the-loop、time travel debugging 和 fault-tolerant execution；OpenAI Agents SDK 文档也把 tool calls、handoffs、guardrails 和 custom events 纳入 trace。DataSmart 本阶段新增 DAG execution dry-run，正是把 Agent 行动先变成可解释预案，再进入受控执行。
- dry-run 与 preview 的职责分离很重要：preview 解释全量 DAG 节点当前状态，dry-run 解释“本次选择的节点准备走哪条执行入口”。这比直接让模型输出 `targetEndpoint` 后由系统调用更接近商业级 Agent：模型负责提出计划，控制面负责选择入口、批量上限、权限、审计和副作用边界。
- MCP 工具生态强调工具可发现和可调用，但真正落地到企业内部系统时，还需要额外的权限、审计、幂等、重放、限流和人类确认层。DataSmart 的 dry-run 不写 outbox、不投递 Kafka、不创建任务，保留了“工具协议层”和“企业执行治理层”的边界。
- 下一步应把 dry-run 结果进入 runtime event/WebSocket，并让智能网关展示“Agent 即将执行哪些节点、为什么阻断、需要谁确认”。这比立即做高并发 DAG worker 更稳，因为真实自动化前用户必须先信任行动路径。
- 参考资料：LangGraph Persistence / checkpointing：`https://docs.langchain.com/oss/python/langgraph/persistence`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-js/guides/tracing`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-05-31 DAG dry-run runtime event 摘要追加入地：**

- 当前前沿 Agent 产品的关键体验不是单纯“模型能调工具”，而是工具行动必须能被观察、回放、审批和补偿。OpenAI Agents SDK tracing 文档把 tool calls、handoffs、guardrails 和 custom events 纳入 trace；LangGraph persistence 文档强调 checkpoint 支撑 human-in-the-loop、time travel debugging 与 fault-tolerant execution；MCP tools 规范也明确工具调用需要 trust & safety 和人类介入能力。DataSmart 本阶段把 DAG dry-run 结果写入 runtime event，正是在补“行动预案可见性”这一层。
- 本阶段没有把 dry-run 事件设计成完整工具日志，而是只写摘要：选择器、计数、actionCounts、节点安全摘要和 payload 策略。这样做是为了避免把 SQL、密钥引用、业务参数、样本数据或工具结果扩散到事件流，继续坚持企业级 Agent 的最小暴露原则。
- `agent.dag_execution.dry_run.completed` 事件把“本次 Agent 准备如何推进 DAG”变成可查询事实，为智能网关动作审批面板、WebSocket replay、审计回放和二轮推理反馈奠定基础。
- 当前仍不直接进入真实 DAG worker，是刻意的节奏控制：先让行动透明，再补授权和确认，再进入可恢复执行。否则自动化能力越强，越容易形成用户看不见、审计追不回的黑盒副作用。
- 下一步趋势跟进重点应落在三条线上：一是 WebSocket/replay 的人机可见性；二是 permission-admin 的服务账号委托授权；三是 selected-node outbox dispatcher 与 checkpoint/replay。三者合在一起，才接近 Codex/Claude Code 类 Agent 的“可解释计划 -> 受控工具调用 -> 可恢复后台行动”闭环。
- 参考资料：LangGraph Persistence / checkpointing：`https://docs.langchain.com/oss/python/langgraph/persistence`；LangGraph fault tolerance：`https://docs.langchain.com/oss/python/langgraph/timeout-and-error-handling`；OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-js/guides/tracing`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2024-11-05/server/tools`。

**2026-05-31 runtime event display 展示解释层追加入地：**

- 当前 Agent 工程趋势正在从“系统内部有 trace”升级为“用户可理解的行动时间线”。OpenAI Agents SDK tracing 强调完整记录 tool calls、handoffs、guardrails 与 custom events；LangGraph 的 checkpoint/replay 思路强调状态可以恢复、可以人工介入；MCP tools 规范强调工具调用需要 human-in-the-loop。DataSmart 本阶段在 runtime event 查询响应中加入 `display`，就是把机器 trace 向人类可读行动时间线推进。
- 这一步刻意没有直接做复杂 WebSocket，因为通道不是最难的，最容易长期变乱的是“事件应该如何被解释”。先固定 category/title/status/replayPolicy/recommendedActions，可以让未来 HTTP replay、WebSocket replay、审计导出和智能网关审批面板共用同一套语义。
- `display` 在权限脱敏之后生成，这一点非常关键。成熟 Agent 产品不能出现 attributes 已脱敏、但 UI 摘要又泄露 prompt、SQL、payload 或工具参数的情况。
- dry-run display 把同步候选、异步预案、阻断项、未命中 selector 与批量上限变成低风险指标，帮助用户先信任行动路径，再进入授权确认和后台执行。
- 下一步应进入 WebSocket/replay 最小通道，但仍要保持节奏：只做 session/run 订阅、afterSequence 增量回放和 ack cursor，不急着做复杂 UI；同时继续推进 permission-admin 服务账号委托授权和 selected-node outbox dispatcher。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；OpenAI Agents SDK running agents tracing/sensitive data：`https://openai.github.io/openai-agents-python/running_agents/`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2024-11-05/server/tools`。

**2026-05-31 runtime event replay 与 ack cursor 契约追加入地：**

- 当前 Agent 工程趋势不是只把事件实时推给前端，而是要求事件、状态、工具行动和审批点都能恢复、回放、确认和排障。OpenAI Agents SDK tracing 文档强调 Agent run 中的 LLM generation、tool call、handoff、guardrail 与 custom event 都应进入 trace；LangGraph persistence 文档强调 checkpoint 支撑 human-in-the-loop、memory、time travel 和 fault-tolerant execution；LangGraph streaming 文档也把 updates、custom、checkpoints、tasks、debug 等多种流模式作为运行时可见性能力；MCP tools 规范强调工具调用需要清晰 UI、调用提示和 human-in-the-loop。
- DataSmart 本阶段新增 HTTP replay 与 ack cursor，是对这些趋势的工程化收敛：先让 Java 控制面成为可信 replay source，再让 WebSocket 或前端用 `clientId + run/session + replaySequence` 做恢复，而不是只依赖一次长连接不掉线。
- 这一步刻意不把 WebSocket 连接管理塞进 Java，因为 gateway 目前已经有 `/api/agent/events/ws -> Python Runtime` 路由。更稳妥的路线是：Java 先提供 replay/ack 控制面，Python 桥接和未来 Java WebSocket 都复用它，避免两个实时通道各自定义 cursor。
- `ACK_EVENTS` 独立于 `VIEW_EVENTS` 体现了企业 Agent 的权限细分趋势：事件查看、消费确认、诊断、审批、执行和补偿应该逐步拆成独立动作，不能长期用一个泛化读权限覆盖所有行为。
- 下一步应把 Python Runtime WebSocket 桥接接入 Java replay/ack，并准备 Redis/MySQL cursor store、慢消费者诊断和 live push 协议。这样 DataSmart 的事件层才能从“能展示”继续走向“可恢复、可确认、可运营”。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；LangGraph streaming：`https://docs.langchain.com/oss/python/langgraph/streaming`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-01 Python WebSocket 接入 Java replay/ack 追加入地：**

- 当前 Agent 平台的实时体验正在从“服务端持续推消息”升级为“可追踪、可恢复、可确认的运行时间线”。OpenAI Agents SDK tracing 文档强调一次 agent run 中 LLM generation、tool call、handoff、guardrail 和 custom event 都应进入 trace；LangGraph streaming 文档把 updates、custom、messages、checkpoints、tasks、debug 等模式作为运行可见性接口；LangGraph persistence 则强调 checkpoint 对 human-in-the-loop、memory、time travel 和 fault-tolerant execution 的支撑。
- DataSmart 本阶段让 Python `/agent/events/ws` 使用 Java 4.64 的 replay/ack 契约：subscribe/reconnect 可以按 Java source cursor 补拉控制面事件，ack/heartbeat 可以把 Java `replaySequence` 回写到 Java 控制面。
- 这一步的关键不是又加一个 WebSocket，而是统一两套坐标系：前端看到的是 envelope `lastSequence`，Java 控制面保存的是 `replaySequence`，未来 Redis Stream/Kafka 还会有自己的 offset。把这些放进 `sourceCursors`，比强行维护一个临时全局序号更稳。
- 外部 ack 失败不打断本地 ack，是贴近生产体验的折中：用户连接不能因为 Java cursor 短暂写失败就卡住，但诊断必须暴露 `externalAckErrors`，否则运维会以为消费位置已经可靠落库。
- 下一步趋势跟进应从“replay/ack 协议”进入“主动 live bridge 与持久 checkpoint”：Java runtime event 应通过 Kafka/bridge 进入 Python live hub，cursor store 应迁移到 Redis/MySQL，ack 失败应有重试与慢消费者指标。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph streaming：`https://docs.langchain.com/oss/python/langgraph/streaming`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-01 DAG selected-node outbox 确认入箱追加入地：**

- 当前 Agent 工具治理正在从“模型可调用工具”演进为“模型提出动作、控制面解释动作、用户或策略确认动作、后台可靠执行动作”。LangGraph 官方 human-in-the-loop 文档强调 interrupt 会暂停图执行、保存状态并等待外部输入；LangChain HITL middleware 也明确支持 approve、edit、reject 等决策。DataSmart 的 selected-node 入箱正是在 Java 控制面落地确认边界。
- MCP Tools specification 明确建议工具调用具备清晰 UI、调用可见性、敏感操作确认，同时要求服务端校验输入、实现访问控制和限流。DataSmart 本阶段没有让模型回传 `targetEndpoint`，而是只接收节点选择、指纹和确认标志，再由服务端按 auditId 重新读取可信路由，这与该安全原则一致。
- `selectionFingerprint` 不是为了做复杂密码协议，而是作为执行预案乐观锁：用户看到 dry-run 后，如果依赖、权限、审批、幂等声明或候选集合变化，旧确认必须失效。未来可以把它与 checkpoint、interrupt payload、审批记录和 runtime event timeline 关联。
- 整批拒绝是刻意的产品决策。面向普通用户的动作确认不应该悄悄部分成功；运营后台如果需要大批量修复，应单独提供带逐项结果、权限审批、配额和补偿能力的管理接口。
- gateway 已把推荐 selected-node 入箱与兼容 Run 级批量入箱拆成 `ENQUEUE_SELECTED_ASYNC_TOOL`、`ENQUEUE_RUN_ASYNC_TOOLS` 两个动作，避免真实副作用入口退化为通用 `CREATE`。下一步需要在 permission-admin 给两种动作配置不同角色、服务账号和审计策略。
- 下一步趋势跟进应进入 delegated authorization 与 durable execution policy：SERVICE_ACCOUNT 代表 actor 的委托授权、策略版本、租户配额、工具限流、并发池和积压保护，比继续扩展更多工具适配器更重要。
- 参考资料：LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；LangChain HITL middleware：`https://docs.langchain.com/oss/python/langchain/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。
**2026-06-01 SERVICE_ACCOUNT 委托授权契约追加落地：**

- 当前前沿 Agent 工具链正在从“模型可以调用工具”演进为“模型提出动作，控制面按身份、权限、审批、审计和恢复策略托管动作”。MCP Tools specification 强调工具调用可见、输入校验、访问控制、限流和敏感操作确认；LangGraph / LangChain HITL 强调 interrupt、approve/edit/reject 与 checkpoint；OAuth 2.0 Token Exchange/RFC 8693 所代表的 delegated authorization 思路也说明生产系统需要明确“代表谁执行”。
- DataSmart 本阶段把这种趋势落到 `permission-admin + agent-runtime` 契约：Agent Runtime 不再只以 `SERVICE_ACCOUNT` 身份询问“能不能执行”，而是携带 `serviceAccountCode`、`representedActorId`、`delegationType` 和 `delegationReason`，让权限中心记录机器身份与上游主体之间的责任链。
- `policyVersion` 和 `delegationEvidence` 是后续 durable agent action 的关键拼图：dry-run、human confirmation、outbox enqueue、worker execute 和 runtime event timeline 都可以引用同一份授权证据，避免事后只看到“服务账号执行了某动作”，却无法解释用户、策略和确认链。
- 本阶段刻意没有把 SERVICE_ACCOUNT 设计为超级权限。它仍然必须命中具体 route policy，Run 级粗粒度入箱也默认拒绝服务账号，仅 selected-node 入口按 dry-run 指纹和选中节点收口开放。这符合企业 Agent 安全趋势：机器身份需要最小权限、可审计委托、可撤销策略，而不是全局万能 token。
- 下一步趋势跟进应聚焦四件事：确认记录持久化、策略版本执行前复核、租户/工具配额与限流、以及把 delegated authorization evidence 接入 runtime event / WebSocket 时间线。这样 DataSmart 的 Agent 能继续朝 Codex/Claude Code 类“可解释计划、受控工具、可恢复后台行动”的产品形态演进。
- 参考资料：MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；LangChain HITL middleware：`https://docs.langchain.com/oss/python/langchain/human-in-the-loop`；OAuth 2.0 Token Exchange RFC 8693：`https://www.rfc-editor.org/rfc/rfc8693`。
**2026-06-01 selected-node 确认记录持久化契约追加落地：**

- 当前前沿 Agent 工具链正在把“工具调用”升级为“可暂停、可审批、可恢复、可审计的行动事务”。LangGraph human-in-the-loop 与 persistence/checkpointing 强调在执行图中暂停、保存状态并等待外部确认；MCP Tools specification 也强调工具调用可见性、敏感操作确认、输入校验、访问控制和限流。DataSmart 本阶段把 selected-node 确认事实独立持久化，正是把这些趋势转成企业数据治理产品里的执行证据层。
- 本阶段新增的 `confirmationId` 采用稳定摘要而不是随机 ID，这一点贴近 durable agent action 的工程需求：用户刷新、网关重试或 Python Runtime 重放同一确认时，系统应识别为同一确认事实，而不是把一次决策膨胀成多次人工审批。
- 确认记录刻意不保存 prompt、SQL、工具参数或样本数据，只保存 nodeId、auditId、outboxId、commandId 和治理边界。这延续了 Agent tracing 与 tool safety 的重要原则：trace 和审计必须足够可解释，但不能成为敏感上下文的二次扩散通道。
- 下一步应把 permission-admin 的 `policyVersion` 与 `delegationEvidence` 接入 confirmation，再在真实 worker 执行前复核策略版本。如果策略版本变化，就要求重新 dry-run/重新确认。这样才能把“human confirmation”从一次 UI 操作升级为可验证、可恢复、可撤销的企业执行契约。
- 参考资料：LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-01 selected-node durable action 同事务边界追加落地：**

- 当前 Codex、Claude Code、LangGraph HITL、MCP 工具调用等前沿 Agent 工程趋势都在强调“工具调用不是一次普通函数调用，而是可确认、可恢复、可审计的 durable action”。DataSmart 本阶段把 selected-node confirmation 与 async command outbox 在双 MySQL 配置下收敛到同一 JDBC 事务边界，正是把这一趋势落到企业数据治理场景中。
- 这一步没有追求分布式事务，而是选择本地事务 + outbox/inbox 的工程路线：模型或用户确认某批 DAG 节点后，Java 控制面先把 command 与 confirmation 作为同一服务内的事实提交，再由 dispatcher、Kafka、task-management Inbox 和 worker pre-check 承接跨服务可靠性。这比把 Kafka、下游任务和确认记录强塞进一个大事务更符合微服务演进。
- 事务边界刻意避开 dry-run 和 policyVersion 校验，只包裹真正产生副作用的两次写入。这个选择贴近高并发 Agent 产品的现实要求：预检可能被频繁刷新、重放和并发查看，而数据库事务应尽量短，避免连接池成为 Agent 行动确认链路的瓶颈。
- 下一步趋势跟进不应继续无限细化 selected-node 内部实现，而应走向三条商业化主线：confirmation 审计查询与权限动作、租户/工具配额与并发保护、worker 执行前二次复核。这样 DataSmart 的 Agent 能力才能从“能确认入箱”演进到“能被审计、能被限流、能被恢复、能被安全执行”。

**2026-06-01 selected-node confirmation 审计查询与权限动作追加落地：**

- 当前 Agent 工具调用的前沿方向不是只强调“模型能调工具”，而是强调工具行动必须可见、可确认、可恢复、可审计。DataSmart 本阶段把 selected-node confirmation 暴露为只读审计 API，并用 `VIEW_TOOL_CONFIRMATIONS` 独立权限保护，正是在把 durable action 证据从内部状态推进到产品可运营界面。
- confirmation 与 runtime event 被刻意拆成两类读模型：event 解释执行时间线，confirmation 解释 human-in-the-loop 确认事实和授权证据。这个拆分可以避免权限过粗，也方便未来智能网关、审计导出、管理员补偿台从不同角度复用同一证据。
- 查询 DTO 继续遵循 tool safety 原则：只返回 nodeId、auditId、outboxId、commandId、policyVersion、delegationEvidence 和范围字段，不返回工具参数、SQL、prompt 或样本数据。成熟 Agent 产品需要 trace 和审计，但不能让 trace 变成敏感上下文扩散通道。
- 下一步趋势跟进应转向“执行前复核 + 配额限流”：worker 在真正执行前必须检查确认是否存在、策略版本是否仍有效、服务账号委托是否匹配、租户/工具配额是否足够、outbox 是否重复消费。否则确认查询做得再好，也只能解释历史，不能防止未来高并发执行风险。

**2026-06-01 Agent outbox 入箱前容量保护追加落地：**

- 前沿 Agent 产品正在从“可以自动调用工具”走向“可以安全地持续调用工具”。安全不只包括权限和确认，也包括资源治理：一个 Agent loop 不能无限制造后台任务，单租户也不能占满全平台 worker。DataSmart 本阶段在 command outbox append 前增加 run/tenant backlog 保护，正是把工具调用治理从授权证据推进到执行容量。
- 这一步没有直接引入复杂分布式 quota-center，而是先在本服务内用 outbox store 做可验证保护：单次批量、单 run 活跃积压、单租户活跃积压。这个选择适合当前项目阶段，既能阻止明显的压力放大，又不会让 Java 控制面陷入过度平台化。
- 活跃积压只统计 PENDING/PUBLISHING/FAILED，体现了 outbox 状态机的工程语义：这些记录仍会消耗 dispatcher 和下游处理能力；PUBLISHED 已交给下游，BLOCKED/IGNORED 则进入人工治理，不应被混入自动执行压力。
- 下一步趋势跟进应把容量保护推到 worker 执行前：按 toolCode、targetService、tenant、project、priority 做并发池和租约，避免 data-sync 大任务、data-quality 扫描任务、导出任务和记忆写入任务互相抢资源。

**2026-06-02 长期记忆 workspace namespace 隔离追加落地：**

- 当前 Agent 记忆工程正在从“跨会话保存一些内容”升级为“按 namespace、key、权限和安全边界组织长期上下文”。LangChain 长期记忆文档说明其长期记忆基于 LangGraph store，以 namespace/key 组织 JSON 文档；LangGraph memory 文档也区分短期记忆和跨 session 的长期记忆。DataSmart 本阶段把 `workspaceKey/memoryNamespace` 贯穿候选、正式 store 和检索，就是把这一趋势转成企业数据治理场景里的可审计隔离边界。
- MCP Tools specification 继续强调工具调用的可见性、访问控制、限流和 human-in-the-loop。长期记忆本质上也是“工具结果对未来模型上下文的延迟注入”，因此它不能只靠项目 ID 粗过滤；候选写入、人工审批、正式落成和后续召回都必须携带同一份 workspace 证据。
- 本阶段选择 fail-closed 处理历史空 workspace 候选：没有命名空间证据就不落成正式记忆。这个策略比自动猜测更适合商业化产品，因为一旦错误记忆进入模型上下文，后续 Agent 可能在任务创建、数据同步、质量规则生成或权限审批中持续复用错误经验。
- 下一步趋势跟进应补 materialization receipt/outbox 和向量库 metadata filter。长期记忆不只是 store.save，还需要记录 worker 尝试、失败原因、重试次数、耗时、命名空间、召回命中和遗忘结果，才能变成可运营的企业 Agent 记忆系统。
- 参考资料：LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；LangGraph memory：`https://docs.langchain.com/oss/javascript/langgraph/add-memory`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 长期记忆 materialization receipt 追加落地：**

- 前沿 Agent 记忆能力的生产化重点不是“能把内容存起来”这一句，而是能解释内容从哪里来、为什么允许存、哪个 worker 何时写入、失败后如何重试、后续如何遗忘。LangGraph persistence/checkpointing 强调可恢复执行和 time travel，MCP 工具规范强调工具调用需要可见、可控、可审计；长期记忆写入本质上是工具结果对未来模型上下文的延迟影响，因此同样需要执行证据。
- DataSmart 本阶段新增 materialization receipt，把 approval fact、materialization fact、retrieval fact 拆开。这个拆分比给候选 status 增加 `MATERIALIZED/FAILED` 更稳，因为审批台、worker 补偿台、审计导出和 observability 未来会有不同查询模型。
- receipt 的 `attemptCount/workerId/errorMessage` 是面向真实生产事故的字段：它能回答是 Chroma/MySQL 抖动、worker 重启、消息重复投递，还是候选本身不满足安全边界导致失败。
- 下一步不宜继续无限细化长期记忆内部。更有产品价值的方向是智能网关：把模型路由、工具调用预算、provider fallback、workspace 上下文预算、runtime event 和 memory retrieval 接到一个统一治理入口，让 Agent 能像 Codex/Claude Code 一样在受控边界里持续行动。
- 参考资料：LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 智能网关工具调用预算守卫追加落地：**

- 当前前沿 Agent 产品越来越强调“模型可以提出工具调用，但平台必须在执行前做可见性、审批、限流、预算和审计”。OpenAI Agents SDK tracing 把 tool calls、handoffs、guardrails、custom events 纳入 trace；LangGraph human-in-the-loop 强调在敏感动作前暂停并等待人工决策；MCP tools 规范也要求工具调用具备清晰 UI、访问控制、输入校验和限流。DataSmart 本阶段新增 tool-call budget guard，就是把这些趋势转成智能网关的第一层准入策略。
- `ModelToolCallPlanner` 已经能校验工具是否存在、是否本轮可见、arguments 是否 JSON object、参数是否满足 schema；但这仍不足以应对商业化场景。一个合法工具调用集合仍然可能太多、太大、太高风险。budget guard 用 `maxProposedToolCalls/maxAutoExecutableToolCalls/maxHighRiskToolCalls/maxArgumentsBytes` 把容量与风险前置到模型工具调用阶段。
- 本阶段选择不直接改 Java gateway，也不把 guard 融入模型路由服务，是为了保持智能网关的职责分层：模型路由管 provider 与成本，工具 planner 管工具意图合法性，tool budget guard 管同轮动作规模。后续可以由统一 gateway response 汇总这三类治理结果。
- 下一步应把 guarded report 写入 runtime event，并接入 AgentOrchestrator 主流程。这样前端和审计能看到“模型提出 5 个工具，网关因预算只允许 3 个”的过程，而不是只看到最终少了几个工具。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 工具调用预算守卫接入 Agent 主链追加落地：**

- 前沿 Agent 工具治理的关键不只是“有 guardrail 组件”，而是 guardrail 必须位于真实执行链路上。DataSmart 本阶段把 tool-call budget guard 接入 `AgentModelIntentNode`，让 streaming 和 non-streaming tool_calls 都先经过预算守卫，再生成最终 ToolPlan。
- 这一步对 Codex/Claude Code 类体验很重要：用户需要看到模型的行动建议被平台治理过，而不是黑盒地消失。runtime event 现在会记录 `guard_model_tool_call_budget`，说明 proposed/accepted before/after、预算策略和阻断 issue codes。
- 当前仍复用 `MODEL_TOOL_CALL_REJECTED` 事件类型承载预算 guard summary，这是小步快跑的折中。后续更成熟的智能网关应新增独立事件类型和 gateway governance response，把模型路由、工具预算、缓存计划、记忆召回和 workspace namespace 放在同一张治理视图中。

**2026-06-02 智能网关统一治理摘要追加落地：**

- 前沿 Agent 平台的治理体验正在从“内部有多个 guardrail”走向“用户和控制面能看到统一治理解释”。OpenAI Agents SDK tracing 强调 tool calls、guardrails 和 handoffs 的可追踪性；LangGraph human-in-the-loop 强调动作暂停与恢复；MCP Tools specification 强调工具调用可见、输入校验、访问控制和限流。DataSmart 本阶段新增 `intelligentGatewayGovernance`，正是把这些分散治理事实合成一个响应视图。
- 该摘要不重新做决策，而是汇总已经发生的模型路由、工具预算、workspace 和记忆检索事实。这个边界很重要：API 展示层不应绕过模型网关、预算守卫、记忆检索器或 Java 控制面，避免“展示结果”和“真实治理结果”出现两套逻辑。
- 下一步更适合进入 Agent skill 能力或策略来源抽象：让 tool budget policy 可以来自租户套餐、项目等级、角色和实时 backlog，而不是长期停留在静态默认值。

**2026-06-02 工具调用预算策略来源抽象追加落地：**

- 当前前沿 Agent 工程趋势已经不满足于“模型能调用工具”，而是要求工具调用在 trace、guardrail、human-in-the-loop、访问控制和限流策略下运行。OpenAI Agents SDK tracing 把 tool calls、guardrails、handoffs 和 custom events 纳入端到端追踪；LangGraph interrupts/human-in-the-loop 强调敏感动作前的暂停、恢复和外部决策；MCP Tools specification 明确提到工具调用需要可见性、访问控制、输入校验和限流。DataSmart 本阶段把 `ModelToolCallBudgetPolicyProvider` 抽象出来，就是为了让预算不再是 Python 代码里的固定常量，而能逐步接入企业控制面策略。
- 这次落地选择“环境变量 + 请求变量”作为最小策略来源，并不是最终生产形态。它的价值在于先固定 provider 契约：部署级默认值由环境决定，本次请求可由可信上游控制面覆盖，非法配置自动忽略，预算执行仍由 guard 统一完成。后续替换为 Java permission-admin、tenant plan、Redis quota 或实时 backlog provider 时，不需要重写预算守卫算法。
- 商业化场景里，工具预算应该按租户套餐、项目等级、角色、workspace 敏感级别、工具成本权重、worker backlog 和审批压力动态变化。例如只读元数据分析可以允许更多低风险工具；数据同步、质量修复、权限变更、导出下载等高风险动作应收紧自动推进数量，并要求人工确认或服务间授权证据。
- 下一步趋势跟进应优先补独立 `MODEL_TOOL_CALL_BUDGET_GUARDED` 事件类型和 Java 策略中心接入，然后再推进 Agent skill 能力。这样 DataSmart 的 Agent 形态会更接近 Codex/Claude Code 类产品：模型可以提出行动，但平台用可追踪、可授权、可限流、可审计的智能网关决定行动边界。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop/interrupts：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 工具调用预算治理事件一等化追加落地：**

- 前沿 Agent 平台的 trace 设计正在把工具调用、guardrail、handoff、人工确认和自定义事件拆成可检索的结构化事实，而不是混在一条普通日志里。DataSmart 本阶段新增 `MODEL_TOOL_CALL_BUDGET_GUARDED`，把“预算收缩动作”从普通 rejected 事件中拆出来，贴近这种可追踪、可回放、可审计的工程趋势。
- 这个拆分的产品意义很实际：模型候选因未知工具、不可见工具或非法 JSON 被拒绝，和模型候选本身合法但因租户/项目/角色/容量预算被收缩，是两类不同事实。前者更多指向模型工具生成质量或工具 schema 暴露问题，后者更多指向智能网关策略、容量保护和运营配额。
- 统一治理摘要仍兼容旧 stage，是为了让历史运行记录、旧 replay 事件和测试夹具在迁移期可读。商业化产品做事件契约演进时，向后兼容比“一次性干净重命名”更重要，因为审计回放、客户工单和运营看板通常会跨版本查询。
- 下一步应从两条线推进：短期接 Java permission-admin/tenant plan，让预算 policy 具备真实控制面来源；中期把该事件写入 Java replay/index，使前端治理卡片、告警规则、审计导出和运维指标都能直接消费 `model_tool_call_budget_guarded`。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 permission-admin Agent 工具预算策略控制面追加落地：**

- 前沿 Agent 平台的工具治理正在从“运行时内部 guardrail”走向“企业控制面可配置策略”。这意味着工具预算不应只在 Python Runtime 里以默认常量存在，而应逐步由租户套餐、项目等级、角色、workspace 风险、实时容量和审批压力共同决定。DataSmart 本阶段在 `permission-admin` 新增 Agent 工具预算策略评估接口，就是把智能网关预算从 Python 局部能力推向 Java 企业控制面的第一步。

**2026-06-02 Python Runtime 远程接入 permission-admin 工具预算策略：**

- DataSmart 本阶段把 Java 控制面的预算策略接回 Python Runtime：`JavaPermissionAdminToolBudgetPolicyClient` 负责 HTTP 契约，`RemoteThenLocalModelToolCallBudgetPolicyProvider` 负责远程优先和本地回退，`AgentModelIntentNode` 继续负责执行预算守卫。这个分层与当前主流 Agent 产品演进一致：模型运行时负责推理与工具候选，企业控制面负责策略、审计和容量保护。
- 这一步让“智能网关”开始具备真实跨服务治理闭环：同一个 Agent 请求在不同租户套餐、角色、workspace 风险或 worker backlog 下，可以得到不同 `toolCallBudget`，而不是所有环境共用一套默认阈值。它为后续服务间认证、策略版本、指标驱动降载、审计回放和前端治理卡片打基础。
- 当前仍应控制扩展节奏。预算策略已经具备远程来源后，下一步更适合进入 Agent skill 能力或 Java 业务 worker 串联，而不是继续只在预算阈值上无限细化。真正类 Codex/Claude Code 的能力还需要 skill registry、tool permission、memory、workspace、runtime event replay 和后台可恢复执行共同推进。
- 这次实现没有直接做数据库策略表，而是先用内存规则稳定 API 契约。这个顺序更稳：先让 gateway/agent-runtime/Python Runtime 都知道 `toolCallBudget` 如何由 Java 控制面生成，再把规则迁移到 tenant plan、策略发布版本、审计记录和缓存失效。否则过早建表容易把尚未稳定的策略维度固化成数据库债务。
- 策略维度同时包含 actorRole、tenantPlanCode、workspaceRiskLevel、workerBacklogLevel 和 requestedToolRiskLevel，体现了商业化 Agent 的关键现实：同一个模型工具调用，在普通用户、项目负责人、服务账号、平台管理员之间，在低风险 workspace 与高敏 workspace 之间，在 worker 空闲与积压之间，应该得到不同预算。
- 下一步趋势跟进应接 Python 远程 provider 或 gateway 注入链路，让 Java 评估结果真正进入 Python Runtime；再接入真实 worker backlog 和租户套餐表。完成这两步后，DataSmart 的智能网关会更接近 Codex/Claude Code 类产品的受控行动模型：模型提出动作，企业控制面决定预算、权限和恢复边界。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 Agent Skill 准入治理追加落地：**

- 前沿 Agent 工具正在从“函数调用列表”升级为“能力包 + 工具 + 记忆 + 权限 + 审计”的组合。Codex/Claude Code 类体验看起来像模型自主行动，但底层必须知道哪些 Skill 可以被当前用户、workspace、租户和风险策略启用。DataSmart 本阶段把 Skill 选择拆成语义命中与准入判断两步，正是为了避免“模型理解了需求”被误当成“平台允许执行能力”。
- `selected_skills/rejected_skills` 的拆分有明显产品价值：前端可以解释“已推荐启用质量规则设计 Skill”，也可以解释“任务创建 Skill 命中但因普通用户角色被拒绝”。这比简单不返回 Skill 更接近企业级 Agent 的治理体验，因为用户和管理员能看到被挡住的原因。
- 当前准入策略仍是 Python 本地基线，生产化方向应是 permission-admin 远程 evaluate、gateway 注入可信权限事实、runtime event 记录 Skill admission，以及 Skill Marketplace 的租户级启停和版本发布。不要急着堆更多默认 Skill；先把 Skill 能力包的权限、工具 schema 暴露、记忆依赖和审计闭环做稳。

**2026-06-02 Skill Admission 事件化与智能网关摘要追加落地：**

- Agent 的可解释性不能只停留在最终工具计划。真正商业化的 Agent 平台需要解释“为什么选择这个能力包、为什么另一个能力包被挡住”。DataSmart 本阶段新增 `SKILL_ADMISSION_EVALUATED` runtime event，并把 `skillAdmission` 纳入 `intelligentGatewayGovernance`，让 Skill 准入成为可订阅、可回放、可审计的一等事实。
- 这一步与当前 Agent 工程趋势一致：OpenAI Agents SDK tracing 强调 guardrails 与 tool calls 的 trace，LangGraph 强调 interrupt/human-in-the-loop 的状态可恢复，MCP 强调工具调用的访问控制和可见性。Skill admission 位于工具 schema 暴露之前，是比单个 tool call 更高一层的能力包级 guardrail。
- 下一步不宜继续扩大 Python 本地规则，而应设计 permission-admin Skill evaluate 契约，把 `actorRole/grantedPermissions/tenantSkillSwitch/policyVersion` 变成可信控制面事实。随后再补 Java replay/index 和前端治理卡片，使 Skill Marketplace 不只是“列表页”，而是可授权、可灰度、可审计的企业能力市场。

**2026-06-02 permission-admin Skill Admission evaluate 契约追加落地：**

- DataSmart 本阶段把 Skill admission 从 Python 本地 policy 推进到 Java permission-admin 控制面：`/permissions/agent/skill-admissions/evaluate` 能返回 allowed、admissionStatus、policyVersion、matchedPolicy、rejectionReason 和权限摘要。这让 Skill Marketplace 的关键安全问题先有统一控制面答案，而不是让 Python Runtime 和前端各自解释“为什么这个 Skill 能不能用”。
- 这与前沿 Agent 产品趋势一致：能力包不只是 prompt 或函数集合，而是带权限、记忆、工具 schema、风险等级和审计语义的组合资产。真正类 Codex/Claude Code 的企业形态，需要在模型看到工具之前，就完成 Skill 级准入。
- 当前实现仍是内存规则，刻意不先建表。下一步应把 Python Skill Registry 接入远程 provider，再补 gateway 可信注入、Java replay/index 和前端治理卡片。等字段语义稳定后，再建设租户 Skill 开关、Marketplace 版本发布、灰度与审计表。

**2026-06-02 Python Runtime 远程接入 permission-admin Skill Admission：**

- DataSmart 本阶段把 Skill admission 远程 provider 接入 Python Runtime：Skill 仍由 Python 根据意图语义命中，但准入结果可以来自 Java permission-admin。这使能力包治理从“Python 本地判断”升级为“企业控制面可替换策略”，更符合 Agent 工具/Skill 快速演化下的商业化边界。
- 该能力的架构价值在于分层：Skill Registry 负责适配目标，permission-admin 负责准入策略，runtime event 和 `intelligentGatewayGovernance.skillAdmission` 负责解释。这个模式比把权限规则直接写进 prompt 或工具 planner 更稳，也更适合后续接 Skill Marketplace、租户开关、策略版本和审计。
- 下一步重点不是继续加本地 Skill，而是补可信事实链路：gateway/agent-runtime 注入 `actorRole/grantedPermissions/tenantSkillEnabled/policyVersion`，远程调用增加服务间认证和 fail-closed 策略，再把 `policyVersion/matchedPolicy` 结构化进入 Skill selection 与 Java replay/index。
**2026-06-02 Skill Admission 可信控制面命名空间追加落地：**

- 企业 Agent 的准入事实不能和用户输入混放。DataSmart 新增 `trustedControlPlane.skillAdmission` 保留命名空间，远程 Skill admission 默认只从该命名空间读取角色、权限集合、租户开关和 workspace 风险。
- 这一步不是完整认证方案，而是 Python Runtime 的安全收口：普通 variables 不再能伪造管理员角色或扩大权限。下一步仍需由 gateway/agent-runtime 根据 JWT、服务账号和 permission-admin 结果注入可信快照，并用服务间认证保护内部调用。
- 该设计与工具调用、长期记忆和 runtime replay 的治理方向一致：模型和终端可以提出目标，但影响授权、隔离和执行边界的事实必须来自受控控制面，而不是 prompt 或客户端自报字段。
**2026-06-02 工具预算可信控制面命名空间追加落地：**

- 智能网关预算不是客户端偏好，而是资源治理策略。DataSmart 将远程 tool budget provider 迁移到 `trustedControlPlane.toolBudget`，避免终端自报管理员、企业套餐或空闲 backlog 来放宽自动执行额度。
- Skill admission 与 tool budget 现在共享保留根命名空间，但保持独立快照对象。这样后续可以分别演进权限版本、容量版本、审计事件和降载策略，而不是把所有控制面事实揉成一个难以治理的大字典。
- 下一步需要完成 gateway/agent-runtime 注入桥接：身份 Header 来自认证链路，权限集合来自 permission-admin，backlog 来自运行时指标或容量服务，Python Runtime 只消费受控快照。
**2026-06-02 Gateway Agent Plan 可信 Header 桥接追加落地：**

- DataSmart 新增 `/api/agent/plans` 专用 gateway 路由与 Python API 边界装配器。终端提交的 `trustedControlPlane` 会先被删除，只有 gateway 转发 Header 可以重建最小可信快照。
- 该分层保持了 OpenClaw 风格智能网关的职责：gateway 管统一入口与身份上下文，Python Runtime 管模型规划与 Skill/tool budget 消费，Java agent-runtime 管可审计执行控制面。
- 当前 Header 来源标记仍不是密码学认证。下一步应增加内部服务凭证，并把权限快照、策略版本、容量快照时间戳与过期策略纳入可信上下文。
**2026-06-02 Gateway 到 Python Runtime 签名信任链追加落地：**

- DataSmart 本阶段把 `/api/agent/plans` 的 gateway Header 桥接升级为 HMAC-SHA256 可验证信任链。Java gateway 在权限、开发期身份和数据范围 Header 都写入后，对可信 Header 快照生成版本、timestamp、nonce、keyId 和签名；Python Runtime 启用后会复算签名，拒绝只伪造 `X-DataSmart-Source-Service` 的直连请求。
- 这符合当前企业 Agent 网关演进方向：模型运行时不直接相信客户端自报身份，影响工具预算、Skill 准入、workspace 隔离和长期记忆写入的事实必须来自可验证控制面。它让 DataSmart 从“迁移期来源标记”迈向“智能网关服务间认证”的第一阶段。
- 当前签名保护 Header 快照而不是 body，是为了避免 Spring Cloud Gateway reactive 链路缓存请求体导致背压和大 payload 风险；请求体中的 `trustedControlPlane` 仍由 Python API 边界删除。后续更成熟形态应叠加 mTLS、Secret Manager 密钥轮换、Redis nonce 去重、服务网格策略和统一 401/403 异常映射。
**2026-06-02 Gateway 签名失败 API 错误语义追加落地：**

- DataSmart 本阶段继续收口智能网关服务间认证体验：Python Runtime 的 `/agent/plans` 在 gateway 签名失败时返回 401 + `GATEWAY_SIGNATURE_INVALID`，而不是让内部异常表现为 500。对企业 Agent 产品来说，这个细节很重要，因为 500 会误导运维去排查模型服务故障，而 401 能明确指向内部调用凭证、签名、时钟或网关路径问题。
- 路由层新增安全日志，但只记录 code、reason、traceId、sourceService 和 path，不记录密钥、签名值、签名原文或完整 Header。这个边界贴近真实安全运营：日志要足够定位误配置和攻击尝试，又不能成为新的凭证泄漏面。
- 下一步应把这类安全失败从日志升级为一等审计事件和指标：例如按 reason 统计 missing-signature、signature-mismatch、timestamp-out-of-window，接入 Prometheus/告警和 Java replay/index，帮助平台管理员区分攻击、时钟漂移、密钥不一致和灰度发布异常。
**2026-06-02 Gateway 签名 nonce 去重与安全诊断追加落地：**

- DataSmart 本阶段把 gateway HMAC 签名链补上 nonce 短 TTL 去重。HMAC 能证明请求来源，但不能单独阻止窗口期重放；现在同一个 keyId + nonce 只能登记一次，重复请求会得到 `nonce-replayed`，这让智能网关信任链具备第一层防重放能力。
- 工程上保持轻依赖：本地默认进程内 store，生产多实例显式切 Redis，并通过 `SET NX EX` 原子语义共享 nonce。这个选择符合企业 Agent 平台渐进式落地：开发体验不被外部中间件绑死，生产安全边界又能通过配置升级。
- 新增安全诊断快照，把签名失败 reason 聚合为进程内统计，展示 nonce store 类型、TTL 和集群安全提示。后续应继续把这些 reason 指标接入 Prometheus、告警和 Java 审计链路，而不是长期停留在 Python 进程内快照。

**2026-06-02 Agent 正式长期记忆 SQL Store 追加落地：**

- 当前 Agent 长期记忆趋势正在从“把聊天历史塞回上下文”走向“跨会话、跨任务、按 namespace 隔离、可搜索、可更新、可过期的记忆资产”。LangChain/LangGraph 长期记忆文档强调 long-term memory 会跨 conversations/sessions 保留，并通过 namespace/key 组织；OpenAI Agents sandbox memory 文档也把 memory 与会话消息历史区分开，强调从历史运行中沉淀经验、偏好和可用记忆。
- DataSmart 本阶段新增 `SqlAgentMemoryStore`，不是为了把 SQL 当成最终语义检索引擎，而是为了给长期记忆建立可审计事实源：候选审批通过后，低敏摘要、workspace namespace、幂等键、来源候选、过期时间和 materializedAt 必须落到可恢复存储。向量库、图谱和对象存储后续可以围绕同一 `memoryId` 做二级索引，但不能替代控制面事实。
- namespace 隔离是商业化 Agent 长期记忆的关键。一个企业租户内可能同时存在项目默认空间、客户现场专题空间、临时诊断 session、敏感质量治理空间和跨项目知识空间。如果没有 `memoryNamespace` fail-closed，模型很容易把 A 工作空间的治理经验错误注入 B 工作空间。
- 这一步也延续了 Agent tracing 与敏感数据治理原则：正式记忆表只保存低敏 `contentSummary`，不保存完整工具输出、样本数据、原始 SQL、文件正文或敏感日志。长期记忆要能帮助未来 run 降低探索成本，但不能成为敏感上下文的二次扩散仓库。
- 下一步趋势跟进不应只是接向量库，而是建立“记忆写入后台 worker -> SQL receipt/outbox -> 二级索引 -> namespace 检索 -> 过期/遗忘 -> 审计导出”的完整闭环。这样 DataSmart 才能从“能记住一点东西”走向 Codex/Claude Code 类 Agent 所需要的可恢复经验层。
- 参考资料：LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；LangGraph memory overview：`https://docs.langchain.com/oss/javascript/langgraph/memory`；OpenAI Agents sandbox memory：`https://openai.github.io/openai-agents-python/sandbox/memory/`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

**2026-06-03 Agent 正式长期记忆 Runtime Builder 追加落地：**

- LangChain/LangGraph 的长期记忆文档把 memory store 作为 Agent 运行时可读写组件，而不是单独存在的离线表；OpenAI Agents sandbox memory 也强调 memory 与 conversational session memory 分离，用于让未来 run 减少重复探索。DataSmart 本阶段把正式记忆 store 注入 `StoreBackedAgentMemoryRetriever`，让 `/agent/plans` 真正消费正式 store，而不是只停留在建表和持久化类。
- 本阶段新增 runtime builder 的意义在于“部署形态可替换”：本地可以继续 in-memory，联调可以 SQLite，生产可以 MySQL，并用 fail-open/fail-fast 控制开发体验与生产安全之间的取舍。这与成熟 Agent 平台的渐进式落地一致：能力默认可学习、生产可收紧、故障可诊断。
- 共享 `memory_sql_connection.py` 也是一个重要工程信号。长期记忆后续会有候选 store、正式 store、receipt store、outbox store、二级索引同步任务，如果每个组件各自解析 DSN 和脱敏密码，后续接 Secret Manager、TLS、连接池或 PostgreSQL 时会快速失控。
- 新增 `/agent/memory/diagnostics` 把候选 store、正式 store、retriever 和 materializer 放在同一张低敏诊断视图中，帮助运维判断“配置的 MySQL 是否真的参与召回”。当前不做 schema 探测，是为了避免诊断接口引入额外数据库副作用；后续可以做管理员受控 health check。
- 下一步趋势跟进应进入后台 materialization worker：长期记忆不能只靠同步调用落成，必须具备 outbox、租约、失败退避、DLQ、补偿重放和指标。完成后再接向量库/图谱二级索引，才接近 Codex/Claude Code 类 Agent 的可恢复经验层。
- 参考资料：LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；OpenAI Agents sandbox memory：`https://openai.github.io/openai-agents-python/sandbox/memory/`；LangGraph/Deep Agents memory：`https://docs.langchain.com/oss/python/deepagents/long-term-memory`。

**2026-06-03 长期记忆落成 Receipt SQL Store 追加落地：**

- 当前 Agent 工程趋势越来越强调 durable action：模型提出动作只是开始，真正生产化还需要执行证据、重试、补偿、审计和可观测性。长期记忆写入也是一种 durable action，不能只在内存里“写过就算”；DataSmart 本阶段把 materialization receipt 推进到 SQL store，就是给长期记忆后台写入建立执行证据。
- 这一步把三类事实拆开：candidate 表示审批决策，formal memory 表示可召回经验，receipt 表示写入尝试。这个拆分贴近成熟 Agent 平台的控制面设计：审批、执行、结果三件事各有状态机，避免一个 status 字段同时承担多种含义。
- receipt store 的 attempt_count、worker_id、status、error_message 是后续 outbox worker 的基本观测面。没有这些字段，平台无法回答“为什么这条记忆没写进去”“是不是重复消费”“哪个 worker 一直失败”“是否需要管理员补偿”。
- 本阶段刻意没有直接启动 worker，是为了保持节奏：先把执行证据持久化，再做有界扫描和租约，再做失败退避和 DLQ，最后再接向量库二级索引。否则向量库先上线、写入链路不可靠，会让长期记忆出现难以补偿的幽灵状态。
- 下一步趋势跟进应做最小 materialization runner：从 APPROVED 候选中有界取数，调用 materializer，receipt 记录成功/失败，并输出低敏指标。之后再扩展租约、多实例竞争控制、管理员补偿和审计导出。
- 参考资料：LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`。

**2026-06-03 长期记忆最小 Materialization Runner 追加落地：**

- 当前 Agent 工程中的 durable action 不只是“状态持久化”，还要求可恢复执行：动作被批准后，需要有一个可重复运行、失败可隔离、结果可观测的执行器。DataSmart 本阶段新增最小 materialization runner，把 APPROVED 候选从静态审批事实推进到可批次落成的执行事实。
- Runner 的设计刻意采用有界窗口和至少一次语义：每轮只取有限数量候选，重复执行依赖 formal memory store 的幂等键与 receipt store 的 attempt_count 证明。这与 LangGraph durable execution/persistence 强调的“重放时不重复副作用”方向一致，也与 OpenAI Agents tracing 对 tool/action 过程可追踪的趋势一致。
- 单条失败不阻塞同批其他候选，是商业化 Agent 的必要行为。真实环境中坏候选、历史配置缺字段、外部存储抖动都很常见；如果 worker 因一条数据失败而整体中断，长期记忆会产生隐性 backlog，用户会感觉 agent “没有学习能力”。
- 当前没有自动后台循环，是有意的产品节奏控制。下一步应先补 lease/outbox claim、失败退避、DLQ、管理员补偿和指标，再考虑自动调度。否则多实例环境下可能重复抢同一候选，反而让长期记忆链路不可解释。
- 参考资料：LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

**2026-06-03 长期记忆 Materialization Lease Store 与 token fencing 追加落地：**

- DataSmart 本阶段把长期记忆落成从“有界 Runner 可用”推进到“多 worker 领取语义可验证”。这与当前 Agent 平台的 durable action 趋势一致：长期记忆写入、工具副作用、异步任务和二级索引同步都不能只靠“调用过一次”证明成功，而要有 claim、receipt、幂等键、审计和补偿事实。
- 独立 lease store 的意义在于分层清晰：candidate 负责审批，formal memory 负责可召回经验，receipt 负责执行证据，lease 负责短时并发控制。把这些事实拆开，后续才能分别演进审批台、补偿台、Prometheus 指标、DLQ、管理员重放和向量/图谱二级索引，而不是把所有语义塞进一个候选状态字段。
- token fencing 是多实例 worker 的关键保护。慢 worker、旧 worker 或网络抖动后的迟到回写，如果没有 token 条件更新，很容易覆盖新 worker 的结果。现在 `succeed/fail` 都必须携带当前领取 token，过期 worker 会被拒绝，这让长期记忆链路更接近真实生产队列的安全语义。
- 当前 SQL 实现优先采用跨 SQLite/MySQL 可验证的条件 UPDATE + INSERT 冲突恢复；高吞吐阶段可以再按压测结果引入 MySQL `SELECT ... FOR UPDATE SKIP LOCKED` 风格的批量 claim。也就是说，本阶段先保证语义正确和测试可覆盖，再做数据库专用性能优化。
- 下一步趋势跟进不应急着启动常驻后台线程，而应先补失败退避、最大尝试次数、DLQ、管理员补偿重放和指标。等“坏候选不会热循环、好候选不会重复消费、失败原因可观测”之后，再把 Runner 包成后台 worker，并接 Chroma/Neo4j 二级索引同步。
- 参考资料：LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；MySQL locking reads：`https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

**2026-06-03 长期记忆失败退避与 DLQ 基础语义追加落地：**

- 成熟 Agent 平台的 durable action 不只是“能重试”，而是要能控制重试节奏、隔离毒性任务、解释跳过原因并给管理员补偿入口。DataSmart 本阶段把长期记忆 materialization 失败从“立即可重试”升级为 `nextRetryAt` 冷却窗口与 `dead_letter` 终态，避免坏候选在多 worker 环境里热循环。
- 这一步让长期记忆链路更接近真实生产队列治理：失败不再只是日志，而会变成可查询、可统计、可告警的状态事实。`retry_cooldown`、`dead_letter`、`active_lease`、`already_succeeded` 等跳过原因，后续可以直接映射到 Prometheus 指标、runtime event 和管理员控制台。
- DLQ 对企业 Agent 尤其重要。工具输出可能缺字段，历史候选可能来自旧版本 schema，下游向量库或图谱可能暂时不可用；如果没有 DLQ，自动 worker 会把这些异常伪装成“系统一直很忙”，用户和运维很难判断 agent 到底是没学会、没权限、没写入，还是一直在失败重试。
- 当前退避策略采用简单指数退避，是刻意的最小闭环：先让行为稳定、可解释、可测试，再引入更复杂的错误分类、租户套餐、下游容量和维护窗口策略。对 DataSmart 来说，下一步不是马上启动常驻 worker，而是把 DLQ 查询、dry-run 重放、解除 dead_letter 和补偿审计接起来。
- 参考资料：LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`；MySQL locking reads：`https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html`。
## 2026-06-03 落地补充：Skill Marketplace needs governance summary before editable publishing

- 本阶段从长期记忆主线切回 Agent Skill 能力市场主线，避免项目长期只在 memory 局部继续打磨。
- DataSmart 在 `agent-runtime` 新增 Skill Marketplace 治理摘要接口，把当前配置式 Skill descriptor 聚合为市场可消费的统计事实：领域、风险、审批、记忆依赖、启用/禁用、强制审计、租户/项目隔离。
- 这对应当前 Agent 工具生态的一个关键趋势：Skill/能力包不应只是 prompt 片段，而应成为可发现、可筛选、可授权、可审计、可灰度发布的产品对象。
- 本阶段没有急着做可编辑 Skill 市场或数据库发布流，是刻意控制风险。成熟产品中，读侧摘要契约应先稳定，随后再引入版本、租户开关、灰度、审批和使用统计；否则很容易先做出一堆编辑入口，再反复推翻字段语义。
- `includeDisabled=true` 与 `includeDisabled=false` 的区分很重要：前者服务运营市场，后者服务 Python Runtime/智能网关规划诊断。一个成熟 Agent 平台必须区分“市场里存在过的能力”和“当前允许模型规划的能力”。
- 技术落地建议：下一阶段不要继续增加大量默认 Skill，而应把 Skill Marketplace 与 permission-admin admission、工具 schema 暴露策略、长期记忆依赖、审计 outbox、前端治理卡片和 A2A Agent Card 导出串起来。

## 2026-06-04 落地补充：Skill visibility needs durable evidence, not only runtime display

- 当前 Codex、Claude Code、OpenClaw/MCP 风格 Agent host 的核心能力之一，是能解释“当前为什么能看到这些工具/Skill，又为什么看不到另一些工具/Skill”。这不是单纯 UI 问题，而是安全、审计、灰度、套餐和事故复盘共同需要的能力边界事实。
- DataSmart 本阶段把 `skill_visibility_snapshot_recorded` 专用索引从内存端口推进到 MySQL Store。它让 Skill 可见性快照不再依赖单个 JVM 的热窗口，而可以跨实例、跨重启恢复，并按 tenant/project/run/session/replaySequence、Manifest 指纹和权限事实来源查询。
- 表结构没有把所有 runtime event 统统落库，而是只保存低敏聚合字段：可见/隐藏数量、Skill code 摘要、Manifest 绑定状态、权限事实来源、风险/领域/隐藏状态分布。这一点很重要，因为商业化 Agent 的可观测性不能变成新的敏感数据扩散面。
- `memory/mysql` 双实现是成熟产品常见的演进手法：本地开发不被 MySQL 绑死，生产环境又能通过配置切到可恢复事实库。后续接 ClickHouse/OpenSearch 时，也应继续保持同一端口，而不是让 controller 或 consumer 直接依赖某个数据库方言。
- 下一步趋势跟进应补指标和缓存，而不是继续无限扩表：物化成功/失败/重复、fallback 查询、Manifest 绑定状态分布应该进入 Prometheus；gateway 会话级 READY Skill cache 应按租户、项目、角色、套餐、workspace 风险和 Manifest 指纹生成缓存键。完成后再推进长期记忆二级索引或多 Agent 协作。

## 2026-06-05 落地补充：Agent capability evidence also needs low-cardinality observability

- 当前前沿 Agent host 不只追求工具调用“能执行”，也越来越强调工具/Skill 可见性、权限、记忆写入和副作用执行都能被观测、解释和恢复。没有指标的 durable evidence 很容易变成“数据库里也许有，但没人知道链路是否健康”。
- DataSmart 本阶段为 Skill 可见性索引增加低基数 Micrometer 指标和只读诊断 API：物化成功、重复、跳过、失败、dedicated/fallback 查询、返回记录数和 Manifest 绑定状态分布都可以被运维看到。
- 低基数约束是关键：指标标签只允许 store、source、outcome、bindingStatus 这类有限枚举，不把 runId、sessionId、tenantId、projectId、traceId 或 Manifest 指纹放进 Prometheus。真实企业部署里，高基数指标会比没有指标更危险。
- 本阶段还补了 projection duplicate 下的幂等补物化：如果首次消费时通用 projection 成功但 MySQL 索引失败，Kafka 重放时即使 projection 判重，也会再次尝试写专用索引。这让能力边界事实更接近至少一次、可补偿的控制面语义。
- 下一步趋势跟进应进入 gateway 会话级 Skill cache 或 outbox 式补偿队列。前者提升 `/agent/plans` 高频路径性能，后者进一步解决“Kafka 不再重放但索引漏写”的长尾可靠性问题。

## 2026-06-05 落地补充：Agent Host needs session-level multi-agent scheduling evidence

- 当前 Codex、Claude Code、OpenClaw/NemoClaw、MCP/A2A 方向的 Agent 产品正在从“单模型对话 + 工具调用”走向“会话级 Agent Host”：主控 Agent 负责规划，专家 Agent 负责领域能力，工具/Skill/记忆/预算/审批由网关统一治理。
- DataSmart 本阶段新增 `agentSessionScheduling`，把 Master Agent、数据源 Agent、质量 Agent、任务 Agent、权限 Agent、记忆 Agent、运维 Agent 放进同一会话策略视图。它不是为了炫技式并发，而是为了让产品能解释“本轮为什么由这些 Agent 参与，为什么有的只能观察或防护，为什么要 handoff”。
- 调度视图借鉴了当前 Agent 生态的几个趋势：A2A 强调 Agent 间任务和能力协作；MCP 强调工具、资源、提示的标准化上下文边界；Agents SDK 类框架强调 tracing、sessions、handoff 和可观测运行。DataSmart 的落地选择是先保留低敏控制面证据，再接具体协议或框架。
- 企业数据治理场景不能把多 Agent 协作做成“互相聊天”。每个 Agent 的可见 Skill、工具名、记忆依赖、审批 handoff、模型网关状态和预算结论都必须可审计，并且不能暴露 prompt、SQL、工具参数、样本数据或记忆正文。
- 下一步趋势跟进应把该策略视图事件化，并逐步升级为可执行 handoff 图：Master Agent -> 专家 Agent -> 工具控制面 -> 反馈/二轮推理。完成事件化后，再评估 A2A Agent Card、MCP resource/tool adapter、KV cache/prefix cache 治理和 provider health 路由，而不是把当前视图扩成又一个无止境的本地摘要。

## 2026-06-05 落地补充：Multi-agent scheduling must become replayable runtime evidence

- 当前 Agent 生态越来越强调 session、trace、handoff 和事件回放。多 Agent 调度如果只显示在响应里，就无法支撑断线恢复、异步投影、审计复盘和运行时优化。
- DataSmart 本阶段把 `agentSessionScheduling` 事件化为 `agent_session_scheduling_recorded`，让“本轮哪些 Agent 参与、哪些角色需要 handoff、哪些策略轴导致降级”进入统一 Runtime Event 链。
- 事件化后的设计重点不是保存更多文本，而是保存更稳定的控制面事实：角色、参与模式、状态、Skill code、工具名、记忆类型、预算/模型/审批布尔值和计数。prompt、工具参数、SQL、样本数据、记忆正文继续禁止进入事件。
- 这一步让 DataSmart 更接近 Codex/Claude Code 类 Agent Host 的运行观测方式：用户看到的是会话，系统内部保留的是可排序、可回放、可投递、可索引的状态事实。
- 下一步趋势跟进可以进入 Java projection、handoff DAG 和 A2A/MCP 适配。优先级上应先保证企业审计、租户隔离、workspace 边界和失败恢复，再做更复杂的跨 Agent 通信协议。

## 2026-06-05 落地补充：Agent scheduling evidence needs a control-plane view, not only raw events

- 事件化只是第一步。成熟 Agent Host 需要把 runtime event 转换为控制面可查询对象，否则管理台和审计台仍要理解自由 Map attributes，长期会形成脆弱耦合。
- DataSmart 本阶段新增 Java `AgentSessionSchedulingProjectionService`，把 `agent_session_scheduling_recorded` 解析为强类型视图，并按状态、handoff、主控 Agent、参与角色、治理域、工具名和 Skill code 做窗口聚合。
- 这对应 Agent 平台从“trace 可以看”走向“trace 可以治理”的趋势：trace 原始事件服务排障，projection 视图服务产品运营、权限审计和后续自动化策略。
- 当前仍先使用通用 runtime event projection 热窗口，而不是直接上 MySQL 专用表。这是刻意的节奏控制：先稳定字段和查询语义，再根据真实流量决定是否增加 dedicated index、遥测、告警和持久化。
- 下一步更有价值的是 handoff DAG：把 Master Agent、专家 Agent、工具控制面、审批、反馈和二轮推理连接成可解释状态机，而不是继续只扩展查询字段。

## 2026-06-05 落地补充：Handoff should be governed as a DAG before becoming autonomous execution

- 当前 Agent 生态里的 handoff、tool call、trace、MCP tool/resource 边界和 A2A agent task/card 都指向同一个工程事实：跨 Agent 协作不能只是“多个模型互相聊天”，而应该是可解释、可暂停、可审批、可回放的控制面图。
- DataSmart 本阶段新增 `AgentSessionHandoffDagService`，把会话调度投影翻译为 Master、Specialist、Guardrail/Approval、Tool Control、Feedback、Second Turn 节点，先形成只读 DAG，再考虑真实执行。
- 该设计避免过早绑定某个协议或框架。未来接入 MCP 时，可把 tool/resource/prompt 边界映射到 Tool Control 节点；接入 A2A 时，可把 Agent Card/Task 映射到 Specialist 或 Handoff 节点；接入 LangGraph/OpenClaw 时，可把节点映射为图状态。
- `DEGRADED/BLOCKED/APPROVAL_REQUIRED` 默认不可执行，是面向商业化产品的保守选择。企业数据治理场景里，工具副作用、权限越界、记忆泄露和审批缺失的风险高于“多跑一步”的收益。
- 下一步趋势跟进不应只围绕 DAG 继续膨胀字段，而应把图接到 durable action：dry-run、human confirmation、outbox、worker pre-check、runtime event timeline 和失败恢复。

## 2026-06-05 落地补充：Handoff-to-tool execution needs a preview bridge, not a second executor

- OpenAI Agents SDK 的 handoff 文档把 handoff 表达为可被模型选择的工具形式，并强调目标 Agent、输入 schema、过滤和回调；这说明 handoff 本身需要契约，而不是直接等同于最终业务副作用。
- MCP Tools 规范强调工具调用的可见性、输入校验、访问控制、限流与敏感操作确认；因此 DataSmart 不能让 handoff DAG 直接调用工具，而应先回到 Java Tool DAG dry-run 和 selected-node confirmation 链路。
- A2A 协议关注 Agent Card、Task、Message、Artifact 等跨 Agent 协作对象；DataSmart 当前先用 bridge preview 固定“handoff 节点如何映射到工具预检”的内部控制面语义，后续再考虑对外协议适配。
- 本阶段新增 `AgentSessionHandoffDagExecutionBridgeService`，只把 `tool-control` 映射到现有 Tool DAG dry-run；其他会话级节点不会被误当成可执行工具节点。
- 下一步趋势跟进应把 bridge preview 写入 runtime event，并让 selected-node confirmation 记录 bridge 来源证据。这样才能把 handoff、dry-run、human confirmation、outbox 和 worker pre-check 串成可回放 durable action，而不是只靠 UI 跳转。
## 2026-06-05 落地补充：Handoff bridge preview should be replayable but still low-sensitive

- 当前 Codex、Claude Code、OpenAI Agents SDK、LangGraph、MCP/A2A 等 Agent 工程趋势都在把 handoff、tool call、guardrail、
  human confirmation 和 durable execution 放进统一 trace 或 checkpoint 链路。DataSmart 本阶段把
  `agent.handoff_dag.execution_bridge.previewed` 写入 Java runtime event projection，正是在补齐“handoff 图进入工具预检”的可回放证据。
- 事件化并不等于暴露更多明细。成熟 Agent 产品的 trace 必须能解释状态，但不能把 prompt、SQL、工具参数、targetEndpoint、
  executionPath、样例数据或完整 request template 扩散到通用事件层。本阶段只保存 bridgeAction、bridgeReady、selectionFingerprint、
  节点选择摘要和计数，保持最小暴露原则。
- 这一步让 handoff DAG 不只是 UI 跳转图，而是接近 durable agent action 的证据链：Master/Specialist 协作图 -> Tool Control
  bridge preview -> Tool DAG dry-run -> human/policy confirmation -> selected-node outbox -> worker pre-check。
- 下一步趋势跟进重点不应继续无限增加 Java preview 字段，而应把 bridge 来源证据接入 confirmation，并转向 MCP/A2A adapter、
  模型 provider health、KV/prefix cache 和 Python Runtime 真实 Agent 编排，让项目从控制面证据继续走向可执行 Agent Host。

## 2026-06-05 落地补充：Handoff bridge evidence should follow durable action into confirmation and outbox

- 本阶段把 `bridgeSourceEvidence` 从 handoff bridge preview 模板推进到 selected-node confirmation、JDBC 持久化和 command
  outbox payload。这个变化的重点不是“多传一个字段”，而是把 Agent 行动链中的来源事实从同步响应延伸到真实副作用前的 durable action 记录。
- OpenAI Agents SDK 的 handoff 设计强调 handoff 是受控转交契约，而不是随意把上下文交给另一个执行体；MCP Tools 继续强调工具调用需要服务端访问控制、输入校验、限流和敏感操作确认；A2A 的 Agent Card/Task 也把跨 Agent 协作建模为可发现、可管理的任务对象。DataSmart 当前的落点是：内部先用低敏 evidence 固定“handoff 图如何进入工具确认链路”，未来再映射到 MCP/A2A。
- `AgentHandoffDagBridgeSourceEvidenceValidator` 是本阶段最关键的工程拆分。它让 selected-node outbox 主服务继续专注 dry-run、confirmation 和 outbox 写入，把 sourceType、bridgeAction、bridgeReady、tool-control、fingerprint、audit/node 子集校验独立出来。这样后续增加 MCP adapter、A2A task、人工补偿来源时，可以扩展来源治理层，而不是继续膨胀一个 500 行以上的 Impl。
- evidence 仍然不是授权令牌。真正执行前仍要重新 dry-run、校验 selectionFingerprint、校验 permission-admin policyVersion、检查 async outbox candidate、通过 worker pre-check、容量保护和下游健康判断。这一点对企业数据治理产品非常重要：来源可解释不能替代权限和副作用治理。
- 本阶段继续坚持低敏策略：confirmation/outbox 可以保存 `sourceType`、`bridgeAction`、`selectionFingerprint`、节点 ID、auditId、traceId 和事件类型，但不能保存 prompt、SQL、工具参数、targetEndpoint、executionPath、样例数据或完整 request template。公共审计证据越接近真实副作用链路，越要避免成为敏感上下文扩散面。
- 下一步不宜继续在 Java bridge evidence 上无限加字段。更高价值路线是切到模型 provider health、KV/prefix cache、MCP/A2A adapter 或 Python Runtime 真实 Agent 编排；如果必须继续 Java，则只补 WebSocket/live timeline 或 worker pre-check 消费 evidence 这种小闭环。
- 参考资料：OpenAI Agents SDK Handoffs：`https://openai.github.io/openai-agents-js/guides/handoffs/`；MCP Tools：`https://modelcontextprotocol.io/specification/draft/server/tools`；A2A Core Concepts：`https://agent2agent.info/docs/concepts/`；LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`。

## 2026-06-06 落地补充：Model gateways should route by health and safe prefix-cache affinity

- 当前开源推理栈正在快速把“模型调用”升级为“模型网关运行时”：vLLM automatic prefix caching、SGLang prefix/RadixAttention、NVIDIA Dynamo KV-cache-aware routing 都在强调减少重复 prefill、降低 TTFT、提升长上下文吞吐。
- DataSmart 本阶段没有直接绑定某个推理框架，而是先把 health-aware 与 cache-aware scoring 放进 Python `ModelGatewayGovernanceService`。这一步让现有 provider health registry 与 cache planner 不再只是诊断信息，而是真正影响候选路由排序。
- 商业化 Agent 不能只追求 cache hit。企业数据治理上下文包含租户、项目、workspace、数据源、审批和权限语义，cache-aware routing 必须先满足隔离边界。本阶段继续按 `GLOBAL_SAFE/TENANT_SAFE/PROJECT_SAFE/SESSION_ONLY/NO_CACHE` 生成 namespace，并且只有能生成安全 cache plan 的候选才获得 cache 亲和优势。
- 健康状态优先于缓存：`UNAVAILABLE` 或熔断 Provider 即使拥有更好的 cache scope，也会被排序到最后并跳过；`HEALTHY` 优先于 `UNKNOWN/DEGRADED`。这对应真实 Agent Host 的体验要求：模型服务抖动时，应该先保住可用性，再考虑缓存收益。
- API 响应新增低敏 `routeScoring`、`orderedCandidateProviders`、`configuredPrimaryProvider` 和 `cacheAwareRouting`，方便前端和 Java 控制面解释 fallback 与缓存选择。这里不保存 prompt、工具参数、用户输入、模型输出或真实 KV cache 内容。
- 下一步趋势跟进应把 route scoring 写入 runtime event，并增加低基数指标：provider 状态、fallback 次数、熔断打开数、平均延迟、cachePlan 启用率、cache 禁用原因。等控制面可观测稳定后，再接真实 vLLM/SGLang/Dynamo 的 cache hit、TTFT、prefill tokens 和 worker utilization。
- 参考资料：vLLM Automatic Prefix Caching：`https://docs.vllm.ai/en/v0.13.0/features/automatic_prefix_caching/`；SGLang 文档：`https://docs.sglang.io/index.html`；NVIDIA Dynamo KV Cache Routing：`https://docs.nvidia.com/dynamo/user-guides/kv-cache-aware-routing`。

## 2026-06-06 落地补充：Model gateway route scoring should be replayable runtime evidence

- 5.20 已经让 health-aware/cache-aware scoring 参与模型路由，但如果评分只存在于同步 API 响应中，前端断线重连、Java 控制面回放、审计台复盘和运维诊断仍然缺少统一事件事实。本阶段把 route scoring 写入 `MODEL_GATEWAY_ROUTED` runtime event，让模型路由决策成为 Agent trace 的一部分。
- 这一步对应当前 Agent Host 的工程趋势：模型调用不应只是“发起一次请求并返回文本”，而应形成可解释的运行轨迹，包括 provider 健康、fallback、预算、缓存计划、候选排序和降级原因。用户看到的是对话，平台内部要保留可排序、可回放、可索引的低敏状态事实。
- DataSmart 的落地选择是新增 `model_gateway_runtime_event.py`，由 helper 负责 allow-list 裁剪 attributes，而不是把字段拼装继续塞进 `AgentOrchestrator`。这样主编排器保持状态机职责，事件安全策略也可以独立演进。
- 事件明确采用 `SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT_NO_KV_CACHE` 策略：只记录 selectedProvider、configuredPrimaryProvider、orderedCandidateProviders、cachePlan、routeScoring 等摘要；不记录 prompt、messages、工具参数、SQL、模型输出、真实 KV cache、cache keyPrefix、isolationKey 或 reusableContextHint。
- 下一步趋势跟进不应回到“继续往事件里塞更多明细”，而应进入两条路线：一是 provider health 主动探测和低基数 Prometheus 指标，二是 Java projection/WebSocket timeline 消费 `MODEL_GATEWAY_ROUTED` v2，让用户和管理员真正看见模型路由与 fallback 的过程。

## 2026-06-06 落地补充：Provider health should have an active probe source, not only passive invocation feedback

- 真实 Agent Host 的模型网关不能只在模型调用失败后才知道 Provider 出问题。低流量租户、刚启动的服务、备用 Provider 或恢复中的路由都可能长期没有真实调用样本，因此需要主动健康探测、被动调用回写和外部指标回灌三类健康事实来源。
- DataSmart 本阶段新增 `ModelProviderHealthProbeService`，按 providerName 去重探测路由 endpoint，并把结果回灌到同一个 `InMemoryModelProviderHealthRegistry`。这样 route scoring、runtime event、API diagnostics 后续都消费同一份健康事实，不产生平行状态源。
- 探测默认不在启动时自动访问网络，必须显式调用 `POST /agent/models/provider-health/probe`，或配置 `DATASMART_AI_MODEL_PROVIDER_HEALTH_PROBE_ON_STARTUP=true`。这符合学习环境和生产环境的差异：本地 dry-run 不应被外部 Provider 拖慢，生产环境则可以按需启用冷启动探测。
- 低敏策略继续保持严格：probeUrl 会移除 query/fragment，响应不包含 API Key、prompt、messages、工具参数、模型输出或真实 KV cache；低基数计数只按 outcome 聚合，不把完整 URL、tenantId、projectId、runId 或 traceId 放进指标维度。
- 下一步趋势跟进应把 probeRun/probeSuccess/probeFailure/probeSkipped 等低基数计数接入 Prometheus 文本导出，并把 Provider health 与 `MODEL_GATEWAY_ROUTED` v2 一起接入 Java projection/WebSocket timeline；等控制面稳定后，再接 TTFT、TPOT、cache hit、queue length 和 GPU worker utilization。

## 2026-06-06 落地补充：MCP/A2A adapters should start as governed protocol projections

- 当前 Agent 生态正在把“模型能调用工具”升级为“Agent Host 能治理工具、资源、Prompt、记忆、模型路由和跨 Agent 委派”。MCP 最新规范继续围绕 tools/resources/prompts 三类 Server Feature 展开，并强调工具调用的人类确认、输入校验、访问控制、限流和审计；A2A 1.0.0 则强调 Agent Card 发现、任务生命周期、传输协商、认证授权和扩展能力。
- DataSmart 本阶段没有直接实现完整 MCP Server 或 A2A Server，而是新增 Java `AgentExternalProtocolAdapterPreviewService`，先把内部 Skill Manifest、Tool Registry、Handoff DAG、Runtime Events 和 Model Gateway 映射成外部协议预览。这是更适合商业化产品的节奏：先固化暴露边界，再实现协议传输。
- MCP 映射采用“metadata-only”策略：Tool 只暴露 toolCode、schema 引用、风险等级、审批要求和参数数量；Resource 只暴露目录，不返回正文；Prompt 只暴露模板目录，不返回 messages/text。这样既能支持外部 Agent 发现能力，又不会把 prompt、工具实参、资源正文、模型输出或内部 endpoint 扩散出去。
- A2A 映射只把 `publicationState=READY` 的 Skill 放入 Agent Card 预览。非 READY 或禁用 Skill 即使在管理诊断视图中可见，也不能进入外部 Agent 可委派名片。这个边界对应真实 A2A 的风险：Agent Card 一旦被外部 Agent 使用，就可能触发任务委派和自动协作。
- 本阶段把 MCP/A2A 定位为协议投影层，而不是权限替代层。真实 tools/call、resources/read、prompts/get 或 A2A task endpoint 后续仍必须回到 DataSmart 的 permission-admin、tool preflight、human confirmation、outbox、worker pre-check、runtime event 和模型网关治理链路。
- 下一步趋势跟进应优先落两个只读端点：MCP `tools/list` 与 A2A public Agent Card；再为真实协议调用设计 runtime event 类型和签名/限流/授权策略。不要直接把 tools/call 接到下游微服务，否则会让协议适配层成为新的绕权入口。
- 参考资料：MCP Tools：`https://modelcontextprotocol.io/specification/2025-11-25/server/tools`；MCP Resources：`https://modelcontextprotocol.io/specification/2025-11-25/server/resources`；MCP Prompts：`https://modelcontextprotocol.io/specification/2025-11-25/server/prompts`；A2A Specification：`https://a2a-protocol.org/latest/specification/`。

## 2026-06-06 落地补充：Protocol discovery should be public, execution should remain governed

- MCP `tools/list` 和 A2A Agent Card 都属于“能力发现”协议面：它们帮助外部 Agent 理解可用工具、输入结构、Agent 身份、Skill 和认证方式；但发现不等于授权，也不等于执行。
- DataSmart 本阶段新增 Java `AgentExternalProtocolDiscoveryService`，把 5.25 的总览预览拆成更标准的发现端点：MCP `tools/list` 风格响应和 A2A `/.well-known/agent-card.json`。
- MCP tools/list 输出 JSON Schema 风格 inputSchema，但不输出 example、真实参数值、内部 endpoint、targetService、Prompt、资源正文或模型输出。高风险、审批型、异步型和草稿型工具通过 `taskSupport=required` 提醒后续必须走任务化执行。
- A2A public Agent Card 只暴露 READY Skill，并且公开根路径不支持 domain/riskLevel 参数，避免外部 Agent 通过枚举过滤条件推断内部能力目录。
- 这一步对应当前 Agent 生态的产品化趋势：Agent Host 可以开放发现入口，但必须把真实副作用放在受治理的 durable action 链路中处理，包括权限、审批、审计、outbox、worker pre-check、限流和 runtime event。
- 下一步不应急着做 MCP tools/call 或 A2A task 直连，而应先记录协议发现事件、设计 task 状态机和签名/缓存/租户可见性策略；同时把部分精力切回 Python Runtime 编排、长期记忆和模型网关真实推理遥测。
- 参考资料：MCP Tools：`https://modelcontextprotocol.io/specification/2025-11-25/server/tools`；A2A AgentCard：`https://a2a-protocol.org/latest/specification/`。

## 2026-06-06 落地补充：Capability discovery should be traceable without copying capability catalogs

- 当前 Agent Host 趋势正在把“工具目录/Agent Card 可发现”继续推进到“发现行为可审计”。原因很直接：企业环境不仅关心谁调用了工具，也关心谁读取过能力目录、是否有异常扫描、是否有未授权 Agent 频繁探测 public well-known 入口。
- DataSmart 本阶段新增 `AgentExternalProtocolDiscoveryEventPublisher`，把 MCP `tools/list` 与 A2A Agent Card 读取写入 runtime event timeline，但只保存协议、入口、分页、计数和是否启用调用等摘要。
- 这一步没有把 tools/list 返回的工具名、inputSchema、Agent Card URL、Skill ID、Prompt、资源正文或模型输出复制进事件。发现资源本身可以公开或受控读取，runtime event 则应该是审计摘要，不应该成为第二份能力目录缓存。
- 对 public well-known Agent Card 来说，匿名读取是正常场景；事件上下文要允许 actor 缺失，但仍记录 `endpointKind=PUBLIC_WELL_KNOWN`。这为后续 WAF/网关限流、异常扫描识别、缓存策略和签名校验提供了事实入口。
- 下一步趋势跟进可以有两条：一是为 discovery 增加低基数指标和异常读取告警；二是先设计 A2A task 状态机和 MCP tools/call 治理契约，继续避免把协议适配层变成绕过 permission-admin 和 outbox 的副作用入口。
- 参考资料：MCP Tools：`https://modelcontextprotocol.io/specification/2025-11-25/server/tools`；A2A Specification：`https://a2a-protocol.org/latest/specification/`。

## 2026-06-06 落地补充：A2A task endpoints need a governed state machine before execution

- A2A 的 Agent Card 解决“外部 Agent 如何发现我”，Task 生命周期解决“外部 Agent 委派给我之后如何安全推进”。这两者不能混为一谈：Agent Card 可以相对公开，Task 则需要权限、审批、取消、幂等、worker receipt、stream/push 和审计。
- DataSmart 本阶段新增 `AgentA2aTaskStateMachinePreviewService`，先把 A2A 标准 TaskState 与 DataSmart 内部治理阶段映射起来，再考虑真实 `message:send`。这种节奏更接近商业化 Agent Host：先固定协议和状态合同，再开放副作用入口。
- 状态机把 `submitted/working/input-required/auth-required/completed/failed/canceled/rejected` 保持为对外标准状态，同时把 `POLICY_PRECHECK`、`APPROVAL_WAITING`、`OUTBOX_PENDING`、`WORKER_PRECHECK`、`RUNNING`、`DEAD_LETTER` 等内部阶段作为控制面解释。外部状态稳定，内部治理足够细，后续才能兼容 SDK、前端、审计台和 worker。
- 取消语义是本阶段的关键趋势点。成熟 Agent 工具调用不能把 cancel 写成“把状态改成 canceled”这么简单：如果任务已越过副作用边界，取消必须等待 worker receipt 或补偿证据。这与当前 durable execution、outbox、human confirmation 的工程方向一致。
- 本阶段仍然坚持低敏策略：状态机预览只解释状态和策略，不返回消息正文、工具实参、资源正文、模型输出、查询样例、凭证或内部 endpoint。Agent 产品的 trace 和 preview 必须可解释，但不能变成第二份敏感上下文缓存。
- 下一步不应直接跳到 A2A `message:send`。更稳的路线是先设计 task runtime event schema、只读 task preview 或 MCP/A2A 共用 durable action contract，再接 permission-admin、task-management、confirmation/outbox、worker pre-check、限流和幂等。
- 参考资料：A2A Specification：`https://a2a-protocol.org/latest/specification/`；MCP Tools：`https://modelcontextprotocol.io/specification/2025-11-25/server/tools`；LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`。

## 2026-06-06 落地补充：Task streaming and push need one low-sensitive event contract

- A2A 的 task 不只是一次同步请求。规范中 task status、artifact、streaming、push notification、cancel 和 history 都指向同一个工程要求：任务状态变化必须成为可排序、可回放、可投递的事实，而不是只存在于某一次 HTTP 响应里。
- DataSmart 本阶段新增 `AgentA2aTaskRuntimeEventContractPreviewService`，把未来 task 生命周期事件先定义成只读契约：submitted、working、input-required、auth-required、cancel-requested、artifact-announced、completed、failed、rejected 和 push-delivery-attempted。
- 这一步的重点不是“多列一些事件名”，而是把事件字段白名单、敏感级别、投递通道、排序回放、持久化分层同时固定下来。否则后续 streaming、push、task history、runtime timeline、metrics 和 audit 会各自拼字段，最终导致用户看到的状态、审计保存的状态和 worker 真实处理状态不一致。
- 事件契约采用低敏策略：保存 taskPublicId、contextPublicId、a2aState、internalPhase、sequence、reasonCode、artifactRef、idempotencyKeyHash 等摘要字段；不保存原始消息正文、工具输入正文、artifact 正文、模型输出正文、查询语句、样例数据、凭证或内部端点。
- 对商业化 Agent Host 来说，streaming 是实时体验，不能当作可靠历史；push 是通知通道，必须有签名、重放保护、退避和幂等；runtime projection 是热窗口，不能替代长期 task fact。DataSmart 当前把这些边界写入契约，是为了后续真实 task endpoint 不被单一路径绑死。
- 下一步仍不建议直接实现真实 `message:send`。更稳的路线是先做只读 task preview/query 草案，展示 event contract 如何恢复 task history；随后再接 permission-admin、task-management、confirmation/outbox、worker pre-check、限流和幂等。
- 参考资料：A2A Specification：`https://a2a-protocol.org/latest/specification/`；LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

## 2026-06-06 落地补充：Task query is the durable recovery surface for Agent collaboration

- A2A 里的 streaming 和 push 更像实时体验，真正支撑断线恢复、前端刷新、外部 Agent 重试和审计复盘的是 task query/history。一个成熟 Agent Host 不能只会把状态推给客户端，还必须能在任意时刻从 task fact 与事件历史恢复当前状态。
- DataSmart 本阶段新增 `AgentA2aTaskQueryPreviewService`，用 mock task fact 演示未来 `GetTask/ListTasks/SubscribeToTask` 如何消费 5.29 的 runtime event contract，恢复 task 当前状态、history、artifact 引用、stream replay cursor 和 push 边界。
- `historyLength` 是非常重要的产品约束：它让调用方可以请求最近 N 条历史，但不能把任务查询变成无界审计导出。真实实现还需要分页、sequence cursor、保留期、归档查询和租户级权限。
- artifact 只返回 metadata-only 引用。这一点对数据治理产品尤其重要：质量报告、同步诊断、合规结果、数据样例和模型输出都可能包含敏感信息，task query 只能返回受控引用，正文必须走 artifact 服务和二次鉴权。
- 本阶段也明确了一个节奏边界：A2A 控制面草案已经覆盖 Agent Card、发现事件、状态机、事件契约和查询预览。继续在 Java 协议层加字段收益会下降，下一步更应该把契约交给 Python Runtime 编排、长期记忆治理或模型网关遥测去消费。
- 参考资料：A2A Specification：`https://a2a-protocol.org/latest/specification/`；LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

## 2026-06-06 落地补充：Execution readiness policy should come from the enterprise control plane

- 当前 Agent 工程趋势不是让模型直接“决定是否执行工具”，而是让模型提出行动候选，再由控制面在执行前统一做权限、预算、审批、限流、队列压力和审计判断。DataSmart 本阶段把 Java `permission-admin` 的 `toolExecutionReadinessPolicy` 接入 Python `/agent/plans` 主链路，就是把 readiness 从本地默认策略推进到企业控制面策略来源。
- 这一步与可治理 Agent Host 的方向一致：工具调用、human-in-the-loop、guardrails、runtime event 和权限策略必须成为同一条可解释链路。模型规划出的 `ToolPlan` 只是候选动作；真正进入 READY、THROTTLED、WAITING_APPROVAL、NEEDS_CLARIFICATION 或 BLOCKED，需要读取角色、租户套餐、workspace 风险和 worker backlog。
- 本阶段采用“远程优先、本地回退”的工程节奏：本地学习环境不需要启动完整 Java 微服务即可工作；生产环境则可以关闭 fallback，让 permission-admin 策略中心异常显式暴露。这种设计比硬编码默认值更接近商业化部署，也为灰度开关和故障策略保留空间。
- 低敏边界继续保持严格：远程策略只解析 source、policyVersion、actorRole、tenantPlanCode、workspaceRiskLevel、workerBacklogLevel、预算数字、布尔开关和 influenceCodes，不透传 prompt、SQL、工具实参、样本数据、模型输出、凭证或内部 endpoint。Agent trace 应该解释治理结果，而不是复制敏感上下文。
- 当前还有一个性能与架构改进点：`toolCallBudget` 与 `toolExecutionReadinessPolicy` 可能分别触发同一策略接口。下一步更推荐由 gateway/agent-runtime 一次性注入完整 policy envelope，或在 Python Runtime 增加请求级缓存，再将 readiness 接入 LangGraph/OpenClaw-style 条件节点。
- 参考资料方向：OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop / durable execution：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-11-25/server/tools`。
## 2026-06-06 落地补充：execution readiness should become graph condition nodes

- 当前前沿 Agent Host 的趋势不是让模型 tool_call 直接触发副作用，而是把工具调用放进可检查、可暂停、
  可追踪的执行图中：工具 guardrail 负责执行前/后的安全检查，tracing 负责让状态可回放，durable
  execution 负责让中断、审批和 worker 恢复有稳定 checkpoint。
- DataSmart 本阶段把 `ToolExecutionReadinessReport` 推进为 `toolExecutionReadinessGraph`：
  - 每个 readiness decision 都映射到稳定分支，例如 `READY_TO_EXECUTE`、`WAITING_APPROVAL`、
    `NEEDS_CLARIFICATION`、`QUEUE_ASYNC_COMMAND`、`WAIT_FOR_TOOL_BUDGET` 和 `BLOCKED_BEFORE_EXECUTION`；
  - 图谱根节点是 `readiness-gate`，表示所有真实工具执行都必须先经过宿主平台控制面判断；
  - `durableActionBoundary` 明确图谱阶段不会执行工具、不会写 outbox、不会创建审批单。
- 低敏边界仍是硬约束：图谱只保存工具名、分支、reason/issue code、风险等级、执行模式、目标服务和敏感字段名，
  不保存 prompt、SQL、工具参数真实值、样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。
- 趋势映射到后续路线：MCP `tools/call`、A2A action 和模型 tool_call 应统一落到 DataSmart
  `ToolPlan -> readiness -> readiness graph -> approval/clarification/outbox/worker receipt`，不要各自实现一套执行前治理。
- 参考资料：OpenAI Agents SDK Guardrails `https://openai.github.io/openai-agents-python/guardrails/`；
  OpenAI Agents SDK Tracing `https://openai.github.io/openai-agents-python/tracing/`；
  Anthropic Tool Use `https://platform.claude.com/docs/en/docs/agents-and-tools/tool-use/overview/`；
  LangGraph Durable Execution `https://docs.langchain.com/oss/python/langgraph/durable-execution`；
  LangGraph Human-in-the-loop `https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`。

## 2026-06-06 落地补充：执行前图谱需要跨运行时事件事实，而不只是同步响应字段

- 当前 Agent Host 的演进方向不是让模型直接“决定并执行工具”，而是把工具意图放进宿主平台可治理的执行前控制面：
  模型提出候选动作，平台根据权限、审批、预算、worker backlog、风险等级、参数完整性和租户策略决定下一跳。
- DataSmart 本阶段把 `toolExecutionReadinessGraph` 的低敏摘要写入 runtime event，并由 Java projection/timeline 消费。
  这让 Python 的规划结果和 Java 的控制面展示第一次围绕同一个 graph summary 对齐，而不是各自维护一套解释逻辑。
- 这一步对应前沿趋势中的三个关键词：
  guardrail：工具执行前必须先经过策略闸口；
  durable execution：真正副作用必须有 outbox、worker receipt 和可重放证据；
  human-in-the-loop：审批与参数澄清应是图谱分支，而不是散落在 UI 或临时状态里的特殊判断。
- 事件化时只保留 graph 摘要和 durable boundary，是为了避免 runtime event 变成第二份敏感上下文缓存。完整 graph view
  如果未来用于 UI，也应通过受控只读接口返回低敏视图，而不是从事件里回填原始节点、边、参数或业务正文。
- 下一阶段技术路线应从“展示 graph”走向“消费 graph”：MCP `tools/call`、A2A action、模型 tool_call 都先进入
  `ToolPlan -> readiness -> graph`，再由分支路由到审批、澄清、outbox 或阻断。这样 DataSmart 才会更接近
  Codex/Claude Code 类 Agent 的宿主治理能力，而不是只有一个可调用工具列表。

## 2026-06-06 落地补充：多协议工具意图需要 host-level intake，而不是协议直连执行

- 当前 Agent 工具体系正在从单一模型 tool_call 走向多入口并存：模型原生 tool_call、MCP `tools/call`、
  A2A task/action、前端确认页重放、后台 worker 补偿都可能表达“我想推进一个动作”。
- DataSmart 本阶段新增 `ToolActionIntakeService`，把这些入口先归一到 host-level intake。
  这与 Codex/Claude Code 类 Agent 的关键趋势一致：宿主平台负责工具可见性、策略闸口、低敏摘要、
  human-in-the-loop 和 durable action 证据，而不是让协议适配层直接连到业务微服务。
- MCP `tools/call` 复用 ToolPlan 校验链路，但会被重标记为 `mcp_tools_call` 来源；这让后续审计可以区分
  “模型自发建议调用”与“外部 Agent 请求调用”，也为不同限流、鉴权、幂等键和审批策略保留空间。
- A2A task/action 不被扁平成 ToolPlan，是一个重要边界：A2A 更像跨 Agent 任务事实，可能处于 submitted、
  input-required、auth-required、working 或 terminal 状态。把它保留为控制面决策，可以避免 task 状态与工具执行状态混淆。
- 下一阶段趋势跟进应进入“intake 被真实消费”：orchestrator、MCP preview endpoint、permission-admin 策略、
  task-management outbox 和 worker receipt 都应围绕同一入口演进，避免每个入口各自发明一套执行前治理。

## 2026-06-07 落地补充：统一 intake 必须进入真实编排链路

- Agent Host 的控制面能力不能只停留在“有一个统一入口类”。如果模型 tool_call 主链路仍然直接调用旧 planner，
  那么 MCP/A2A 接入时仍会出现多套工具意图治理规则。
- DataSmart 本阶段把 `AgentModelIntentNode` 改为消费 `ToolActionIntakeService`，让模型原生 tool_call 先进入
  host-level intake，再进入预算守卫、事件记录、readiness graph 和后续反馈链路。
- 这一步的趋势意义在于“入口治理被真实主链路采用”：后续 MCP `tools/call` preview、A2A action、前端确认页重放
  可以复用同一条 intake 语义，而不是只在文档或独立测试中保持一致。
- 同时拆出工具反馈二轮服务，符合成熟 Agent 平台的模块边界：工具意图 intake、预算守卫、执行前 readiness、
  工具反馈回填、模型二轮总结应是可替换节点，而不是一个不断膨胀的编排类。

## 2026-06-07 落地补充：MCP tools/call 应先成为治理事实，而不是直接执行入口

- MCP 2025-11-25 规范中，`tools/call` 是 JSON-RPC 请求，`params` 包含 `name` 和可选 `arguments`。
  这说明外部 Agent 生态正在把工具调用标准化，但标准化协议本身并不会自动提供租户权限、审批、幂等、
  outbox、worker receipt、结果脱敏或企业审计。
- DataSmart 本阶段新增 MCP `tools/call` intake preview，选择先把请求归一为 `ToolActionIntake -> ToolPlan ->
  readiness -> readiness graph`，而不是直接实现真实工具执行。这符合 Codex/Claude Code 类 Agent Host 的关键设计：
  协议入口只表达动作意图，宿主平台负责治理、确认、持久化执行证据和可回放时间线。
- 该 preview 支持标准 JSON-RPC 和本地包装形态，但响应只返回低敏摘要：工具名、字段名、状态、风险等级、
  执行模式、issue/reason code 和数量统计。不返回 prompt、SQL、工具参数值、样本数据、模型输出、凭证或内部 endpoint。
- 技术趋势映射：
  - MCP 提供生态互联入口；
  - readiness graph 提供执行前条件节点；
  - permission-admin 应提供权限、审批、预算和租户策略；
  - outbox/worker receipt 应提供真实副作用的 durable action 证据；
  - runtime event/projection 应提供外部 Agent 工具意图的回放与审计。
- 下一阶段更推荐“事件化 MCP intake”或“durable action contract”，而不是立即写完整 MCP Server。完整 MCP Server 必须同步补齐
  会话生命周期、认证、能力协商、工具列表分页、任务化长执行、取消、进度、限流和结果脱敏，否则容易形成 demo 式直连执行器。
- 参考资料：MCP 2025-11-25 Specification `https://modelcontextprotocol.io/specification/2025-11-25`；
  MCP Schema tools/call `https://modelcontextprotocol.io/specification/2025-11-25/schema`。

## 2026-06-07 落地补充：外部工具意图要成为可回放事实

- 成熟 Agent Host 的关键不是“能收到 MCP tools/call”，而是能在刷新、断线、审批等待、worker 恢复和审计复盘时
  解释这个外部工具意图曾经如何被平台治理。也就是说，intake 结果必须进入事件事实层，而不应只停留在同步 HTTP 响应。
- DataSmart 本阶段新增 `tool_action_intake_recorded` runtime event，把 MCP preview 的低敏摘要写入 replay/live/publisher 三条旁路。
  这让未来 Java projection、WebSocket timeline、审计台和 observability 可以围绕同一条入口事实演进。
- 事件采用通用命名而非 MCP 专用命名，是为了后续 A2A action、前端确认页重放和后台补偿都能复用同一事件类型，
  只通过 `source/protocolFamily/boundaryCounts` 等 attributes 区分来源。
- 事件化时保持比同步响应更克制的字段白名单：只保存数量、枚举、工具名、issue/reason code、readiness 计数、
  graph 分支和 durable boundary；不保存参数字段值、prompt、SQL、样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。
- 趋势映射到下一步：MCP/A2A/模型 tool_call 应统一落到
  `tool action intake event -> readiness graph -> approval/clarification/outbox/worker receipt -> result feedback`，
  而不是让每个协议入口各自实现状态机、审计和执行器。

## 2026-06-19 落地补充：模型层应收敛为成熟接入治理，而不是算法/推理内核研发

- 本轮趋势判断：
  - DeepSeek V4 Pro、Qwen3.7、GLM-5.2 等新一代 Agent/长上下文模型继续强化 OpenAI-compatible/Anthropic-compatible、工具调用、结构化输出、上下文缓存和长任务能力；
  - vLLM/SGLang 等成熟推理框架仍是自托管模型服务的重点方向，但 DataSmart 不应在当前阶段自己实现底层 KV cache 调度、CUDA kernel、量化算法或后训练流程；
  - 对商业化数据治理平台来说，最重要的是“模型能力可替换、工具调用可治理、缓存复用不越权、成本与健康可观测、灰度与 fallback 可控”。
- 本轮落地到代码的能力：
  - 新增 `model_capability_registry.py`，把模型能力画像从文档建议落为运行时控制面契约；
  - 能力画像覆盖 DeepSeek V4 Pro、Qwen3.7 Agent 家族、GLM-5.2、Qwen Embedding、Qwen Reranker 和 dry-run placeholder；
  - 新增 `/agent/models/capabilities/diagnostics`，让智能网关、管理台和运维可以低敏查看路由适配状态；
  - 诊断明确区分 `production_candidate`、`needs_provider_validation`、`development_only`、`incompatible` 和 `unknown_model_profile`。
- 低敏与安全边界：
  - 诊断只返回模型名、工作负载、provider 类型、能力支持状态、生产缺口和推荐动作；
  - 不返回 endpoint、API Key、prompt、SQL、工具参数、样本数据、模型输出或内部服务地址；
  - 对 long context/prefix cache/KV cache 只表达治理边界和推荐 cache scope，不保存或暴露真实缓存内容。
- 产品判断：
  - 当前项目不应该转向模型训练平台，也不应该为了“推理优化”自研推理引擎；
  - 更优路线是先把成熟模型、成熟推理服务和成熟 Provider API 纳入 DataSmart 的模型网关治理，再用 benchmark/eval、provider health、预算、缓存和工具调用事件闭环持续优化；
  - 后续模型层工作应以“适配、评估、路由、降级、成本、缓存治理”为主，服务于 OpenClaw-style Agent，而不是发散到算法研发。
- 参考资料：
  - DeepSeek V4 Preview Release: `https://api-docs.deepseek.com/news/news260424`
  - DeepSeek API Quick Start / Context Caching: `https://api-docs.deepseek.com/`、`https://api-docs.deepseek.com/guides/kv_cache`
  - Qwen3.7 Agent Frontier: `https://qwen.ai/blog?id=qwen3.7`
  - Alibaba Cloud Model Studio OpenAI-compatible API: `https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope`
  - GLM-5.2 Docs: `https://docs.z.ai/guides/llm/glm-5.2`
  - vLLM OpenAI-compatible server / tool calling: `https://docs.vllm.ai/en/stable/serving/openai_compatible_server/`、`https://docs.vllm.ai/en/latest/features/tool_calling/`

## 2026-06-19 落地补充：Agent Host 能力必须进入产品闭环，而不是继续堆 preview

- 本阶段的关键判断不是新增某个前沿协议字段，而是把 DataSmart 的 Agent、模型、网关、权限、任务、数据同步和部署一起纳入收敛视图。
- 成熟 Agent Host 的价值不只是“能规划工具”或“能展示事件”，而是能把外部工具意图、人工审批、任务事实、outbox、worker receipt、checkpoint、模型路由和观测指标收束到可恢复、可审计、可运营的产品闭环。
- DataSmart 新增 `/agent/platform/convergence/diagnostics`，把 gateway、permission-admin、task-management、datasource/data-sync、agent-runtime、python-ai-runtime、model-gateway、observability 和 deployment/middleware 的缺口放在同一张低敏地图中。
- 这会成为后续路线的护栏：
  - MCP/A2A/model tool_call 统一 adapter 优先于继续扩展单个 preview；
  - data-sync 真实任务生命周期优先于继续只优化 datasource CRUD；
  - data-quality 规则生命周期优先于继续只做规则建议；
  - 模型 benchmark/eval 与灰度优先于继续扩展模型画像字段。
- 低敏边界仍保持一致：该诊断不读取业务数据、不调用模型、不执行工具、不返回用户输入、工具实参、模型响应、凭证、真实租户数据或内部服务地址。

## 2026-06-19 落地补充：多协议工具入口需要显式 Adapter Contract

- 官方 MCP tools 文档说明，工具可以让模型与外部系统交互，并建议工具调用场景保留 human-in-the-loop 的拒绝能力；这与 DataSmart 的审批、澄清和 readiness gate 设计一致。
- A2A 官方项目强调 Agent 可以安全协作长任务，并且不需要暴露内部状态、记忆或工具实现；因此 DataSmart 不应把 A2A task/action 直接扁平成普通工具调用。
- OpenAI Agents 文档也把“应用拥有编排、工具执行、审批和状态”作为使用 Agent SDK 的重要边界；DataSmart 作为企业 Agent Host，必须承担这些 Host-owned 责任。
- 本阶段新增 `ToolActionAdapterContractRegistry`，把三类入口映射为稳定契约：
  - `MODEL_TOOL_CALL`：模型只提出候选动作，Host 负责工具可见性、参数校验、readiness、审批、outbox 和 worker receipt；
  - `MCP_TOOLS_CALL`：外部 JSON-RPC 工具意图只进入 ToolPlan/readiness，不允许 handler 内直连业务微服务；
  - `A2A_TASK_ACTION`：保留为 A2A 控制面决策，只有显式派生工具动作时才进入 ToolPlan。
- 这个实现把趋势转化为 DataSmart 产品能力：协议互联不等于授权执行，所有副作用都必须经过租户安全、审批、幂等、resume gate、durable outbox、worker receipt 和可回放事件。
- 参考资料：
  - MCP Tools specification: `https://modelcontextprotocol.io/specification/2025-11-25/server/tools`
  - A2A official repository/spec entry: `https://github.com/a2aproject/A2A`
  - OpenAI Agents SDK guide: `https://developers.openai.com/api/docs/guides/agents`
  - OpenAI Agents SDK tracing: `https://openai.github.io/openai-agents-python/tracing/`
