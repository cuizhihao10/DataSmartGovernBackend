# DataSmart Govern 最终收敛交付清单

## 1. 文档目标

本清单用于把当前项目从“持续扩展功能”推进到“可验收、可演示、可继续生产化”的收敛状态。它不是新的功能规划，也不是要求继续无限补模块；它的核心作用是固定当前项目的交付边界，让后续每一次变更都能先判断自己属于“闭环缺口修复”“生产化加固”还是“暂缓的新能力扩展”。

当前仓库已经具备完整的本地容器化闭环：基础中间件、Keycloak 认证中心、gateway、8 个 Java 微服务、Python AI Runtime、Prometheus、Grafana、Chroma、Neo4j、MinIO 都可以在同一套 Compose 栈中启动，并通过只读 smoke 验证关键控制面。这个结论只代表“本地单机集成闭环已经成立”，不等于“生产发布完全完成”。

## 2. 已闭环能力

### 2.1 平台入口与认证授权

- Keycloak 已作为本地 OIDC 身份中心接入，支持服务账号 token 获取、issuer 校验和 gateway 资源服务器验签。
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
- 基础 Compose 负责 MySQL、Redis、Kafka、Nacos、Keycloak、Prometheus、Grafana、Neo4j、Chroma、MinIO、Alertmanager 等依赖。
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

结果口径：默认模式用于收敛阶段，已闭环和已文档化的生产加固契约应通过；生产环境值与 Secret 管理说明见 [production-environment-values.md](production-environment-values.md)。容量基线、故障演练等尚未交付的生产事项会以 `WARN` 形式保留，提醒它们是正式上线前的阻塞项。若进入真实发布门禁，可追加 `-StrictProductionGates`，把所有 `WARN` 提升为失败。

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

结果口径：默认模式只验证备份恢复交付边界，不连接数据库、不读取 Secret、不导出业务数据、不执行恢复覆盖；它会检查 [backup-restore-runbook.md](backup-restore-runbook.md)、有状态 Compose volume、config-as-code 路径和恢复清单输出边界。恢复演练环境可追加 `-CheckLocalTools` 检查工具链，或追加 `-WriteRecoveryInventory` 生成不含 Secret 的恢复范围清单。

## 5. 生产上线前待办

这些事项属于“商业化生产加固”，不是继续扩展本地 demo 功能。后续若继续推进，优先级应高于新增 Agent 角色或新增业务分支。

- 安全：接入正式企业 IdP 或 Keycloak 集群，启用 HTTPS、证书信任链、mTLS、Secret Manager、密钥轮换和最小权限服务账号。
- 供应链：补齐 SBOM、镜像签名、漏洞扫描、基础镜像升级策略和企业私有镜像仓库发布流程。
- 部署：从 Compose 推进到 Kubernetes/Helm 或客户认可的编排平台，配置资源 requests/limits、探针、滚动升级、回滚和多环境分层。
- 数据可靠性：补齐 MySQL、Redis、Kafka、MinIO、Neo4j、Chroma 的备份恢复、容量规划、数据保留、恢复演练和灾备策略。
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
