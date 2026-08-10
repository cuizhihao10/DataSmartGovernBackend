# DataSmart Govern 最终收敛交付清单

## 1. 文档目标

本清单用于把当前项目从“持续扩展功能”推进到“可验收、可演示、可继续生产化”的收敛状态。它不是新的功能规划，也不是要求继续无限补模块；它的核心作用是固定当前项目的交付边界，让后续每一次变更都能先判断自己属于“闭环缺口修复”“生产化加固”还是“暂缓的新能力扩展”。

当前仓库已经具备完整的本地容器化闭环：基础中间件、Keycloak 认证中心、gateway、8 个 Java 微服务、Python AI Runtime、Prometheus、Grafana、Chroma、Neo4j、MinIO 都可以在同一套 Compose 栈中启动，并通过只读 smoke 验证关键控制面。这个结论只代表“本地单机集成闭环已经成立”，不等于“生产发布完全完成”。

## 2. 已闭环能力

### 2.1 平台入口与认证授权

- Keycloak 已作为本地 OIDC 身份中心接入，支持服务账号 token 获取、issuer 校验和 gateway 资源服务器验签。
- Keycloak 的 realm、用户、client、角色和服务账号已切换到 PostgreSQL-backed 存储；本地 `start-dev` 只表示开发启动模式，不再表示身份事实保存在容器文件卷。
- Gateway 已实现 OIDC issuer 与容器内 JWKS 地址拆分：宿主机 token 的 `iss` 继续保持 `http://localhost:18080/realms/datasmart`，gateway 容器通过 `http://keycloak:18080/.../certs` 拉取签名公钥，避免容器内误连自身 `localhost`。
- Gateway 已具备基于权限元数据的路由授权、内部服务账号入口保护、Agent Runtime 控制面授权、Python Runtime 指标与诊断入口授权。
- Gateway 已补齐 Reactive LoadBalancer，能够通过 Nacos 服务发现执行 `lb://` 路由，不再只停留在静态直连或发现注册层。

### 2.2 Java 微服务闭环

- `permission-admin` 已承担授权中心、角色权限、服务账号、工具预算策略和网关授权决策支撑职责。
- `task-management` 已承担任务控制、DataSync worker command outbox、执行回执投影和任务状态诊断职责。
- `datasource-management` 已承担数据源连接、连接测试、元数据与受控执行面职责。
- `data-sync` 已承担同步连接器能力、模板执行契约、worker loop 受控开关、task-management 回执 outbox 和诊断职责。
- `data-quality` 已承担质量规则、执行器诊断、质量报告导出和质量闭环控制面职责。
- `agent-runtime` 已承担 Agent 会话、工具描述符、Skill Manifest、模型路由、runtime event 投影、Skill 可见性投影、工具事件 outbox 和异步命令 outbox 只读控制面职责。
- `observability` 已承担平台闭口就绪度、服务健康快照、告警覆盖视图和容器服务寻址职责。
- `platform-common` 已作为跨模块契约和共享类型层存在，不承担业务流程编排，避免公共模块膨胀成隐式业务中心。

### 2.3 Python AI Runtime 与多智能体闭环

- Python Runtime 已具备 Agent plan 入口、LangGraph 工作流、长期记忆检索节点、执行门禁图、Skill Manifest 消费、模型路由诊断、推理优化诊断和低基数 Prometheus 指标。
- 多智能体能力已按交付分层收敛：必做 Agent 进入核心闭环，应做 Agent 保持控制范围，暂缓 Agent 以轻量能力矩阵记录，不继续无边界扩张。
- LangGraph 已参与复杂流程编排、状态观察、长期记忆检索、执行门禁、事件 envelope 和多智能体协作图，不再只是普通函数调用包装。
- Agent 运行时事件已经具备 request/run/session/sequence 语义，HTTP snapshot、WebSocket replay 契约和 Kafka audit envelope 均已有协议基础。

### 2.4 容器化交付闭环

- 8 个 Java 服务和 Python Runtime 均已有可执行构建、应用镜像、健康检查、非 root 运行用户和 Compose 应用层。
- 基础 Compose 负责 PostgreSQL、MySQL、Redis、Kafka、Nacos、Keycloak、Prometheus、Grafana、Neo4j、Chroma、MinIO、Alertmanager 等依赖。
- 应用 Compose overlay 负责 Java/Python 服务容器、内部服务 DNS、OIDC/JWKS 分离、Prometheus 容器抓取配置和安全默认开关。
- DaoCloud 已作为默认国内镜像加速路径，其中 Docker Hub 体系使用 `docker.m.daocloud.io`，Quay 体系 Keycloak 使用 `quay.m.daocloud.io`。

## 3. 受控关闭能力

这些能力不是“没实现”，而是为了本地闭环、安全演示和避免误写业务数据，在默认 Compose 或 smoke 中保持关闭或只读。

- 真实任务 worker 默认关闭，避免容器启动后自动消费历史任务或修改本地数据。
- Agent 工具真实提交默认关闭，当前通过工具描述符、执行门禁、受控 dry-run、outbox 诊断和恢复事实验证控制面。
- Agent outbox dispatcher 默认关闭，避免本地 smoke 把控制面事件误投递成真实业务动作。
- DataQuality executor 的高风险写入链路通过诊断和报告导出闭环，默认 smoke 不触发真实清洗写操作。
- DataSync run-once 与 worker loop 具备控制面和回执投影，但最终写入/迁移类动作不作为只读 smoke 的通过条件。
- WebSocket live 推送、Kafka audit 真实发送和事件持久化回放已有协议基础，但当前最终验收以 HTTP snapshot、只读 replay 和诊断入口为主。

## 4. 当前验收基线

截至 2026-07-01，当前仓库在本机完成以下验收：

```powershell
mvn test "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
```

结果：Java reactor 10 个模块全部通过，`BUILD SUCCESS`。

```powershell
python -m pytest python-ai-runtime\tests -q
```

结果：`597 passed`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\containerized-delivery-check.ps1 -SkipMaven
```

结果：可执行 jar、Compose 配置、gateway OIDC/JWKS 静态合同、9 个应用镜像的非 root 与 healthcheck 契约全部通过。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-e2e-smoke-check.ps1 `
  -CheckServiceAccountToken `
  -CheckAgentGatewayDiagnostics
```

结果：真实只读 E2E smoke `PASS=89, WARN=0, FAIL=0`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\production-readiness-check.ps1
```

结果口径：默认模式用于收敛阶段，已闭环和已文档化的生产加固契约应通过；生产环境值与 Secret 管理说明见 [production-environment-values.md](production-environment-values.md)。容量基线与故障演练已经具备静态门禁和计划输出能力；真实发布仍需在预生产环境执行真实压测与人工批准的故障注入。若进入真实发布门禁，可追加 `-StrictProductionGates`，把所有 `WARN` 提升为失败。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\helm-delivery-check.ps1
```

结果口径：默认模式只验证 Kubernetes/Helm 交付边界，不连接集群、不读取 Secret、不创建 namespace、不部署服务；它会检查 [kubernetes-helm-deployment.md](kubernetes-helm-deployment.md)、`helm/datasmart-govern` chart、Secret 契约、安全上下文、探针、RollingUpdate、资源限制和高风险写入口默认关闭策略。CI 安装 Helm 后会自动执行 `helm lint/template`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\sbom-check.ps1
```

结果口径：默认模式验证 Maven reactor、Python `pyproject.toml`、Dockerfile、Compose 镜像变量和 `.dockerignore` 是否具备生成 SBOM 的源头信息；若本机没有 Syft 或仍存在 `latest` 镜像 tag，会以 `WARN` 提示正式发布前需要清理。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-image-signatures.ps1
```

结果口径：默认模式只验证镜像签名准入条件，不生成私钥、不读取私钥、不推送镜像、不访问生产仓库；若本机没有 Cosign 或本地示例镜像仍使用 `latest` tag，会以 `WARN` 提示正式发布前需要在 CI/CD 或企业 registry 中完成真实签名与不可变镜像引用。真实发布验证可追加 `-VerifyPublishedImages`、`-Images` 和 keyless/公钥策略参数。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-restore-check.ps1
```

结果口径：默认模式只验证备份恢复交付边界，不连接数据库、不读取 Secret、不导出业务数据、不执行恢复覆盖；它会检查 [backup-restore-runbook.md](backup-restore-runbook.md)、有状态 Compose volume、Keycloak PostgreSQL-backed 存储契约、config-as-code 路径和恢复清单输出边界。恢复演练环境可追加 `-CheckLocalTools` 检查工具链，或追加 `-WriteRecoveryInventory` 生成不含 Secret 的恢复范围清单。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capacity-baseline-check.ps1
```

结果口径：默认模式只验证容量基线交付边界，不执行真实压测、不读取 Secret、不触发 worker、不提交工具、不写业务数据；它会检查 [capacity-baseline-runbook.md](capacity-baseline-runbook.md)、Gateway、Java 服务、Python Runtime、Kafka、MySQL/Redis/MinIO/Chroma/Neo4j、Agent plan 和观测配置。压测 runner 可追加 `-CheckLocalTools` 检查工具链，或追加 `-WriteBaselinePlan` 生成不含 Secret 的容量计划。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\failure-drill-check.ps1
```

结果口径：默认模式只验证故障演练交付边界，不执行故障注入、不停止容器、不修改网络、不删除 volume、不读取 Secret、不触发 worker；它会检查 [failure-drill-runbook.md](failure-drill-runbook.md)、Compose 组件、恢复与容量前置条件、Prometheus/Alertmanager 观测路径。演练 runner 可追加 `-CheckLocalTools`，或追加 `-WriteDrillPlan` 生成无敏感信息计划。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-platform-closure-audit.ps1 `
  -RunPythonTests `
  -RunMavenTests `
  -WriteEvidence
```

结果口径：最终审计同时核对 Java 业务模块、OIDC/权限、11 类 Agent 能力域、LangGraph、多智能体角色分层、部署、观测、生产门禁与文件规模，并可复跑 Python/Maven 全量测试。详细结论见 [final-platform-closure-audit.md](final-platform-closure-audit.md)；证据 JSON 只写入 Git 忽略的 `target/final-platform-closure`。

## 5. 生产上线前待办

这些事项属于“商业化生产加固”，不是继续扩展本地 demo 功能。后续若继续推进，优先级应高于新增 Agent 角色或新增业务分支。

- 安全：接入正式企业 IdP 或 Keycloak 集群，启用 HTTPS、证书信任链、mTLS、Secret Manager、密钥轮换和最小权限服务账号。
- 供应链：补齐 SBOM、镜像签名、漏洞扫描、基础镜像升级策略和企业私有镜像仓库发布流程。
- 部署：从 Compose 推进到 Kubernetes/Helm 或客户认可的编排平台，配置资源 requests/limits、探针、滚动升级、回滚和多环境分层。
- 数据可靠性：补齐 PostgreSQL、MySQL、Redis、Kafka、MinIO、Neo4j、Chroma 的备份恢复、容量规划、数据保留、恢复演练和灾备策略；Keycloak 自建场景必须纳入 PostgreSQL 独立库备份与 realm/config-as-code 恢复。
- 可观测性：将当前健康快照和告警覆盖进一步接入真实告警路由、值班流程、SLO、错误预算和事故复盘。
- 性能：形成 gateway、Java 服务、Python Runtime、向量检索、Kafka 消费、数据库查询和 Agent plan 的最小容量基线。
- 审计与合规：补齐敏感操作审计留存、管理员行为审计、导出审计、工具执行审计、合规脱敏链路和租户边界证明。
- 多租户：当前代码已尽量保留 tenant/workspace 语义，但正式商用仍需要租户级配额、隔离策略、数据分区、日志脱敏和跨租户防护测试。

## 6. 后续路线建议

后续不建议再进入“大量新增功能模块”的节奏，而是按以下顺序收敛：

1. 保持当前功能冻结，只修复会导致测试、容器启动、OIDC、gateway 路由、只读 smoke 失败的问题。
2. 建立生产部署包：Secret/TLS、Kubernetes/Helm、SBOM、镜像签名、漏洞扫描。
3. 建立数据可靠性包：迁移框架、备份恢复、容量基线、故障演练。
4. 建立商业验收包：演示脚本、验收清单、模块能力矩阵、受控关闭说明、生产待办说明。
5. 只有当上述交付包稳定后，再评估是否继续扩展暂缓 Agent、ETL 开发、数据资产、合规脱敏等更大产品面。

## 7. 完成度判断

当前项目可以判断为“本地完整闭环已完成，具备继续做生产化交付的基础”。它不应再被视为简单 demo，也不应继续无边界扩写局部模块；接下来的价值主要来自生产可部署性、可靠性、安全合规和可运维性，而不是继续堆叠新的 Agent 名称或控制面字段。

## 7.1 2026-08-05 增量交付基线

本轮在原有 Agent 工具治理闭环上补齐了生产审计和用户会话能力，不再仅依赖“用户身份透传 + 进程内状态”：

- Agent 执行身份同时记录 `userId + agentId + sessionId + runId + delegationId`，工具执行前校验委托有效期、工具范围和目标资源范围。
- Agent 会话、委托、工具绑定、Run、对话消息和审批确认迁移到 PostgreSQL durable store；permission-admin 审批事实也具备 JDBC 持久化实现。
- 会话列表、详情、继续追问、置顶和归档均按可信 tenant/project/actor 上下文做对象归属校验，普通用户不能通过请求参数读取他人会话。
- 审批事实登记增加可信服务来源与内部 token 校验，普通客户端不能直接伪造 `APPROVED`；Gateway 会先移除客户端伪造的 Agent/内部服务 Header。
- 前端配套提供当前项目内的个人历史会话、置顶、归档、恢复和继续追问入口，继续追问会在原会话下创建新的 Run。
- BuildKit 历史缓存从 `63.46GB` 清理到 `0B`，并新增默认只读、显式 `-Prune` 才执行的限额维护脚本，不操作镜像、容器和业务数据卷。

本轮验证证据：Java 21 受影响模块完整 Reactor 共 536 个测试通过，变更定向回归 39 个测试通过，Python Runtime 相关回归共 35 个测试通过，前端 ESLint 与生产构建通过；Vite 仍提示主 bundle 大于 500 kB，属于后续路由级拆包优化项，不阻断本轮交付。

## 7.2 2026-08-05 Agent 补参恢复验收

- 同一会话内允许用户用新一轮补参计划替代仍处于 `PLANNING/WAITING_HUMAN` 的旧计划，但只有全部工具均未执行或仅形成只读结果时才允许替代。
- 替代旧计划时保留已完成的只读核验事实，取消尚未执行的旧工具节点；任何 `EXECUTING` 工具或非只读终态结果都会阻断替代，防止掩盖外部副作用。
- 控制面接入失败会返回具体、低敏且可恢复的错误信息；前端除 toast 外还提供常驻恢复区，并从当前表单重新提交已选择的数据源、对象映射、字段映射和 WHERE 配置。
- 普通用户和项目 OWNER 的历史会话路由已开放，但会话列表、详情、置顶和归档仍按 tenant/project/actor 做最终归属校验。
- 已执行 Flyway 迁移保持不可变，历史迁移校验和恢复正常；新权限策略通过 `V47` 交付。
- 回归结果：Agent Runtime `541/541`、permission-admin `75` 个通过并有 `1` 个 Testcontainers 环境用例跳过、Python Runtime `854/854`、前端 lint/build 全部通过；真实容器启动确认 permission-admin `47` 个 Flyway 迁移与 Agent Runtime `3` 个 Flyway 迁移校验成功，Vite 大包 warning 不阻断本轮功能验收。

## 7.3 2026-08-05 Agent 同名任务修复 Run 持久化验收

- 故障根因不是 Agent 建议的新任务名错误，而是确认执行线程在 Python continuation 回调创建下一 Run 后，继续使用调用前读取的旧会话快照执行整聚合保存；JDBC `replaceRuns()` 因而删除了新 Run，前端拿到的 `nextRunId` 成为悬空引用。
- `AgentSessionStore` 新增原子对话消息追加契约。PostgreSQL 实现只在同一事务中插入 `agent_conversation_message` 并向前推进 `agent_session.last_message_at/update_time`，不读取、不删除也不替换 Run、委托或工具绑定。
- 确认执行服务在返回 continuation 前重新读取 durable session 并校验 `nextRunId`。若远程响应声明的 Run 未持久化，系统会清除悬空 ID、保留低敏诊断及同名任务建议名称，并返回 `NEXT_RUN_NOT_DURABLE` 可重试状态。
- 同名修复生命周期会把原失败 Run 中已成功的源端/目标端元数据事实重新绑定为可信 `fromAuditId + fromRunId` 引用。新 Run 不复制元数据正文、不信任模型提供的数据源 ID，也不会因仅保留 `fromTool` 而错误提示“缺少源端元数据结果”。
- 前端兼容修复前已经留在历史会话中的坏 Run：识别“Agent Run 不存在”后撤销失效确认入口，常驻展示原因，并允许用户使用保留的完整任务配置和建议名称重新生成审核计划；重新生成不会跳过预览与用户确认，也不会直接保存或执行。
- 真实链路复验：原始重名失败 Run 重新生成的修复 Run 在助手消息追加后仍保持 `WAITING_HUMAN`；确认更名后成功保存任务 `38` 及 2 条对象映射。后续预检查因两张目标表各已有 6 行且配置为 `FULL + INSERT` 正确阻断，Agent 明确给出清空目标表、改为 UPDATE/merge 或新建空表三种需用户确认的方案，没有擅自删除数据或修改写入策略。
- 回归结果：新增服务层/JDBC 增量写定向测试共 `8/8` 通过，Python 更名/continuation 定向测试 `7/7` 通过；Agent Runtime 全量 `544/544` 通过；Python Runtime 全量 `855/855` 通过；前端 `npm run lint` 与 `npm run build` 通过。Vite 主 bundle 大于 500 kB 的既有 warning 仍不阻断本轮缺陷验收。

## 7.4 2026-08-05 Agent 单回合连续处理语义验收

- 新增 `AgentInteractionOrigin` 跨运行时契约，明确区分 `USER_MESSAGE`、`FORM_SUBMISSION`、
  `APPROVAL_DECISION`、`AGENT_CONTINUATION`、`SYSTEM_RECOVERY` 与 `AUTOMATIC_CONTINUATION`。
- 只有用户首次输入或真正从会话输入框发送自然语言追问/纠偏时才持久化 `USER` 消息；任务缺项表单、预览审批、
  采用修复建议、模型工具二轮、MCP 结果回填和故障恢复仍创建 Durable Run 与完整审计，但不再复制 session 初始目标。
- 每个 Run 在 `variables.interactionOrigin` 保存来源快照。前端历史折叠条据此显示“已提交任务配置”“已完成确认操作”
  “Agent 自动继续”等动作标签，展开后仍可查看公开模型输出、工具/API 调用、低敏参数、结果和错误证据。
- 新增 Agent Runtime `V4` Flyway 迁移：仅把同一 session 中与 objective 完全相同的旧 USER 消息保留最早一条；
  不同文本的历史追问一律保留，Run、Agent 回复、工具审计、审批事实和执行结果均不删除。
- 前端在迁移尚未执行的滚动升级窗口提供同样的保守兼容回放。新数据完全依赖显式来源，用户有意重复发送同一句
  自然语言时仍会被正确保存，不再使用通用字符串去重。
- 验证证据：Agent Runtime Java 21 Reactor `548/548`、Python Runtime `857/857`、前端 `npm run lint` 与
  `npm run build` 全部通过；V4 在 PostgreSQL 回滚事务中验证为回填 `5 + 11` 个 Run 来源并清理 `11` 条重复
  USER 消息，事务已回滚后再由容器 Flyway 正式执行。

## 7.5 2026-08-06 六专业 Agent 受治理闭环交付审查

本次审查针对当前工作区的未提交多 Agent 候选改动；未将未提交内容表述为已发布版本，也未修改业务源代码。当前可执行 specialist roster 为六个角色：`KNOWLEDGE_AGENT`、`DATASOURCE_AGENT`、`DATA_SYNC_AGENT`、`PRECHECK_AGENT`、`RECOVERY_AGENT` 和 `MONITOR_AGENT`。长期八专项 Agent 表仍是产品蓝图，不能作为本轮六 Agent 已全部上线的证据。

已核对的闭环事实：

- Python `SpecialistAgentRegistry` 和 `SpecialistAgentCoordinator` 按角色、checkpoint、租户/项目/操作者范围、delegation 和工具白名单 fail-closed 调度；依赖波次通过低敏 handoff 传递，不把 prompt、SQL、工具参数、凭据、样本或模型原文交给 Java fact store。
- `DATA_SYNC_AGENT` 只生成配置/ToolPlan 草案，必须经过 Java ToolPlan bridge；Java 控制面负责权限、审批、outbox、worker receipt 和执行反馈；成功反馈再触发 PRECHECK/MONITOR 资源复核。
- `RECOVERY_AGENT` 只读取失败事实和 RAG 证据生成待审批方案，Recovery 验收要求停在 Java handoff/审批等待态，不自动批准或执行。单项只读预览缺少可验证配置时以 `RECOVERY_ACTION_INPUT_INCOMPLETE` 跳过该项，其他完整只读预览仍可继续；没有任何完整动作时明确等待补参，不创建不可执行 ToolPlan。
- Agent Runtime `V5__specialist_agent_turn_facts.sql` 提供按 session/run 的低敏 durable fact，记录角色、状态、范围、checkpoint 和 handoff/bridge 引用，不保存 prompt、SQL、工具参数、凭据、样本或模型原文；permission-admin `V48__specialist_agent_turn_fact_route_policy.sql` 提供对应内部登记和只读查询路由策略，Compose overlay 已启用 fact fail-closed、数据源/data-sync 控制面地址和相关依赖。
- Java ToolPlan bridge 只接收 `DATA_SYNC_AGENT`/`RECOVERY_AGENT` 的受控候选，Java 负责权限、审批、outbox、worker receipt 和反馈；反馈含可信 `taskId`/`executionId` 后，才在独立只读波次运行 `PRECHECK_AGENT`/`MONITOR_AGENT`。

本次验证证据：

- `python -m pytest python-ai-runtime\tests -q` 全量 `1044 passed`，无失败；六 Agent 定向测试包含在内。
- Java Reactor `593` 个测试通过，未出现失败或错误。
- `git diff --check` 退出码 `0`；`docker compose -f docker-compose.yml -f docker-compose.application.yml config --quiet` 退出码 `0`。
- `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-six-agent-governed-e2e.ps1 -RunSpecialistStatusAggregationRegressionTest` 通过；该回归只使用脚本内低敏夹具验证首轮失败可由同角色后置成功恢复的聚合语义，不访问 Keycloak、Agent API 或 Docker。
- `local-six-agent-governed-e2e.ps1` PowerShell AST 错误数为 `0`，默认 `-PlanOnly` 未访问 Keycloak/Agent API；本次变更文件的敏感模式扫描无命中，审查输出未记录任何凭据值。

尚未解除的阻塞项：

- 当前回归证据不等同于 Docker 黑盒 E2E：本次未运行 `local-six-agent-governed-e2e.ps1 -Execute`，也未在已启动 Compose 服务、已应用迁移和非 dry-run provider 环境中完成 Success/Recovery 两场景。因此不能声称六 Agent Docker 黑盒 E2E 已通过。
- 当前多个本次变更的 Java/Python/测试/脚本文件超过仓库既有的单文件 500 行控制线，其中多个 specialist、bridge 和验收脚本达到千行级；需要拆分并重新做结构审查后再冻结交付。
- Compose 的开发默认共享凭据仍不适合生产；正式环境必须注入并轮换内部服务 token、Gateway 签名密钥和模型 API key。

## 7.6 2026-08-09 双仓迁移后最终复核

本轮以当前 Backend/Frontend dirty worktree、当前源码构建镜像和本机真实服务为证据，未读取或重放旧 Codex 对话。Backend `docs/` 的 38 份 Markdown 已逐份复核，并按其对本轮审查的约束归类如下：

- 迁移/架构：`agent-runtime-postgresql-data-migration.md`、`ai-memory-postgresql-data-migration.md`、`ai-memory-postgresql-schema.md`、`data-quality-postgresql-data-migration.md`、`datasource-management-postgresql-data-migration.md`、`data-sync-postgresql-data-migration.md`、`development-jdk21.md`、`gateway-oidc-keycloak-integration.md`、`permission-admin-postgresql-data-migration.md`、`postgresql-migration-roadmap.md`、`python-ai-runtime-package-layout.md`、`task-management-postgresql-data-migration.md`。
- Agent/RAG：`agent-skill-publication-manifest.md`、`langgraph-postgresql-durable-checkpointer.md`、`mcp-client-integration.md`、`rag-command-worker-implementation.md`、`rag-pipeline-implementation.md`。
- 生产运维：`backup-restore-runbook.md`、`capacity-baseline-runbook.md`、`containerized-application-deployment.md`、`failure-drill-runbook.md`、`kubernetes-helm-deployment.md`、`local-e2e-docker-troubleshooting.md`、`observability-agent-runtime-runbook.md`、`observability-data-sync-runbook.md`、`production-environment-values.md`、`production-hardening-runbook.md`、`tenant-onboarding-flashsync.md`。
- 最终收敛：`codex-migration-handoff-2026-08-09.md`、`final-convergence-delivery-checklist.md`、`final-delivery-closure-runbook.md`、`final-platform-closure-audit.md`、`local-e2e-closure-runbook.md`。
- 产品与学习资料：`ai-agent-interview-answers.md`、`ai-agent-project-technical-learning-path.md`、`ai-agent-resume-project-experience.md`、`ai-agent-technology-radar.md`、`platform-product-roadmap.md`。

这些文档直接限定了本轮代码审查：PostgreSQL/pgvector 是持久化目标，Kafka 保持 Java/Python 异步边界，JDK 21/Spring Boot 3.5.11 不漂移；六 Specialist 必须在 durable checkpoint、权限、双主体审批、审计、幂等和低敏可观测性边界内运行；RAG 证据、LangGraph checkpoint 与 Specialist turn fact 必须可持久化且按 application/tenant/project/session/run 范围隔离；Recovery 只能形成受治理 handoff，不能自动批准；Gateway、data-sync 与前端 API/WebSocket 必须消费同一合同。生产资料把备份恢复、容量、故障演练、Keycloak、Secret 注入和监控保持为发布门禁；产品/学习资料仅解释长期八 Agent 蓝图，不能覆盖本轮真实六角色 roster 或被当作上线证据。

本轮验证证据（2026-08-10 最终复跑）：

- Java 全 Reactor `mvn test` 为 `BUILD SUCCESS`；Surefire 汇总 `1321 tests`、`0 failures`、`0 errors`、`9 skipped`。
- Python Runtime 全量 `1087 passed`，只有一条 Starlette/TestClient 弃用警告。
- Frontend `npm run lint`、`npm run build`（含 `tsc -b`）和 `package.json` 中 5 个 Agent/data-sync 合同脚本全部通过；Vite 仅提示主包 `2120.77 kB`、gzip `641.08 kB`，属于后续性能治理项。
- 当前源码构建的 Gateway、permission-admin、task-management、datasource-management、data-sync、agent-runtime、python-ai-runtime 镜像均健康；Agent Runtime V5-V7 与 permission-admin V48-V52 已由 Flyway 应用。
- `local-data-sync-platform-e2e.ps1 -UseContainerJdbcUrls -SkipDependencyStart -Strict` 通过：目标表 20 行、失败分片选择性重试、dirty-row `PRIMARY_KEY_EQ` replay、权限审计均通过；human 对 worker/scheduler 内部入口返回预期 403。
- 六 Agent 聚合回归和退出码回归通过，`PlanOnly=0`、聚合成功 `=0`、本地受控失败 `=1`，避免 CI 把终端 `[FAIL]` 误判为成功。
- Compose 已显式设置 `DATASMART_LANGGRAPH_CHECKPOINT_STORE=postgresql` 和 `FAIL_OPEN=false`。Gateway RAG 查询返回 `HTTP 200`、2 条 citation，并在 `ai_memory.langgraph_thread_checkpoint`/`langgraph_checkpoint_event` 各写入 3 条 `retrieve -> evidence_gate -> grounded_answer_completed` 记录；重启 Python Runtime 后，经 Gateway latest/events 仍返回 version 3 和 3 个事件。同项目另一 actor 读取同一 thread 返回 `403`，证明 V52、Gateway HMAC 和 Python tenant/project/actor 二次校验同时生效。

当前唯一阻止六 Agent 黑盒门禁关闭的外部条件是模型 Provider 授权。Success 请求 `six-agent-success-20260810-checkpoint-final` 已运行到 `DATASOURCE_AGENT=COMPLETED`，并在 `DATA_SYNC_AGENT=FAILED/DATA_SYNC_SPECIALIST_MODEL_FAILED` 停止；Recovery 请求 `six-agent-recovery-20260810-checkpoint-final` 针对 `76/1805` 已运行 `KNOWLEDGE_AGENT`、`DATASOURCE_AGENT`、`PRECHECK_AGENT`、`MONITOR_AGENT`，并在 `RECOVERY_AGENT=FAILED/RECOVERY_PLANNING_MODEL_FAILED` 停止。对应 turn durable facts 已落库，最近窗口和全库审批事实均为 `0`。Runtime 等价 Provider 探测返回 HTTP 401，健康诊断显示 `xckjj-gpt56-sol-responses` 最近 4 次调用错误率 100%、连续失败 4 次。Runtime 继续使用 `gpt-5.6-sol`/`xhigh`，未降级 dry-run，未创建/启动同步任务，未自动批准 Recovery，也未产生恢复副作用。取得有效 Provider 凭据后必须重跑 Success/Recovery 并让脚本所有断言通过；在此之前不得声称真实六 Agent success/recovery E2E 已全部通过。

补充退出码证据：同日使用新的 Recovery 幂等请求号在独立 `powershell.exe -File` 子进程中重走真实链路后，目标模型路由的失败样本增至 5、错误率仍为 100%，E2E 子进程明确返回 `1`；审批事实与审批确认事实全库计数仍均为 `0`。因此失败退出码契约已经由真实 Provider 故障验证，不再存在 CI 把该外部失败误判为通过的问题。

## 8. 总闸门入口（2026-08-06）

当前项目已经从“持续补功能”进入“闭环交付候选”阶段。后续不建议再分散记忆多条验收命令，而应优先使用最终交付总闸门：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1
```

如果本地 Compose 全平台服务已经启动，可以追加真实只读 E2E smoke，并把低敏证据写入 `target/final-delivery-closure`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1 `
  -RunLiveSmoke `
  -WriteEvidence
```

如果要做最终候选版本验收，可以追加容器化交付、全量测试和严格模式：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1 `
  -RunContainerizedDelivery `
  -RunLiveSmoke `
  -RunFullTests `
  -WriteEvidence `
  -Strict
```

依赖恢复演练入口为：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-dependency-recovery-drill.ps1 `
  -RecoverKafkaChain `
  -RestartPythonRuntime
```

该恢复脚本只处理 Zookeeper/Kafka/Python Runtime 这类本地依赖漂移，不删除 volume、不重置数据库、不清空 Kafka topic、不触发 worker，也不读取业务数据。详细说明见 [final-delivery-closure-runbook.md](final-delivery-closure-runbook.md)。
