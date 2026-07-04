# DataSmart Govern 最终交付闭环 Runbook

## 1. 文档定位

本 Runbook 用来承接当前项目的收敛阶段：既定功能和本地闭环已经基本完成，后续重点不再是继续横向扩展新 Agent 或新业务模块，而是把已经完成的能力沉淀成可重复验收、可恢复、可解释、可交付的工程证据。

新增的总入口是：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1
```

这个命令默认运行静态和只读门禁，包括生产就绪、Helm 交付、SBOM 准入、镜像签名准入、备份恢复、容量基线、故障演练和最终平台闭环审计。它默认不会触发真实 worker、不会执行 Agent 工具、不会创建任务、不会读取业务数据、不会写入目标端，也不会打印 token、secret、prompt 或模型输出。

## 2. 推荐验收命令

快速收口检查：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1
```

本地服务已经启动时，追加只读 E2E smoke：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1 `
  -RunLiveSmoke `
  -WriteEvidence
```

最终候选版本需要更强证据时，再追加全量测试与容器化交付校验：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1 `
  -RunContainerizedDelivery `
  -RunLiveSmoke `
  -RunFullTests `
  -WriteEvidence
```

如果要模拟发布门禁，把 warning 也当作阻断项：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1 `
  -RunContainerizedDelivery `
  -RunLiveSmoke `
  -RunFullTests `
  -WriteEvidence `
  -Strict
```

## 3. 子门禁含义

- `production-readiness-check.ps1`：验证生产加固文档、OIDC/JWKS、Secret Manager、TLS/mTLS、供应链、数据可靠性等静态合同。
- `helm-delivery-check.ps1`：验证 Kubernetes/Helm 交付骨架、Secret 引用、安全上下文、探针、滚动更新和高风险 worker 默认关闭策略。
- `sbom-check.ps1`：验证仓库是否具备生成 SBOM 所需的依赖来源、镜像变量和排除规则。
- `verify-image-signatures.ps1`：验证镜像签名准入条件，真实签名应放在 CI/CD 或企业镜像仓库链路中完成。
- `backup-restore-check.ps1`：验证 PostgreSQL、MySQL、Redis、Kafka、MinIO、Neo4j、Chroma、Keycloak、Nacos 等状态组件的恢复范围和持久化合同。
- `capacity-baseline-check.ps1`：验证容量基线 runbook、观测指标、Prometheus/Grafana/Alertmanager 路径和低敏计划输出能力。
- `failure-drill-check.ps1`：验证故障演练场景、治理边界、组件覆盖和恢复前置条件。
- `final-platform-closure-audit.ps1`：验证 Java 服务、Python Runtime、LangGraph、多 Agent、Agent 能力矩阵、身份权限、部署与运维制品是否构成整体闭环。
- `local-e2e-smoke-check.ps1`：在 `-RunLiveSmoke` 时执行真实本地只读链路验收，覆盖 Keycloak、gateway、Java 控制面、Python Runtime 诊断和低基数指标。

## 4. 依赖恢复演练

本地长时间运行后，如果出现 Zookeeper 退出、Kafka bootstrap 失败、Python Runtime unhealthy，可以先执行只读诊断：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-dependency-recovery-drill.ps1
```

需要恢复 Kafka 依赖链时：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-dependency-recovery-drill.ps1 `
  -RecoverKafkaChain `
  -RestartPythonRuntime
```

这个脚本不会删除 volume，不会重置数据库，不会重建 Keycloak realm，不会清空 Kafka topic，也不会触发任何业务 worker。它只做有界恢复：拉起 Zookeeper/Kafka，必要时重启 Python Runtime，然后让使用者继续跑只读 smoke。

## 5. 闭环边界

当前项目可以判断为“工程交付候选基线已经闭合”。这意味着本地 Compose、OIDC/Keycloak、gateway、Java 微服务、Python AI Runtime、LangGraph、多 Agent 控制面、RAG/记忆/工具治理、可观测性、备份恢复说明、容量基线说明和故障演练说明都已经有对应代码或交付制品。

但它不等于客户生产环境已经完成。正式商用仍需要客户环境或预生产环境提供以下证据：正式企业 IdP 或生产 Keycloak、TLS/mTLS、Secret Manager、企业镜像仓库、真实 SBOM/漏洞扫描/签名、Kubernetes/Helm 部署、容量压测、备份恢复演练、故障注入演练、告警接收人和值班流程。

## 6. 后续冻结规则

后续修改应优先遵循：

- 只修复会破坏启动、鉴权、路由、只读 smoke、备份恢复、容量基线、故障演练或交付证据的问题。
- 不再为了“看起来更完整”继续横向新增 Agent 名称、连接器名称或局部控制面字段。
- 如果新增功能不可避免，必须先说明它属于 P0/P1 闭环缺口，而不是新版本扩展项。
- 所有真实副作用能力继续默认关闭，只有在权限、审计、幂等、回滚、容量和故障演练就绪后才能启用。

## 7. 最近一次本地验证结果

截至 2026-07-05，本地已完成一次完整收口验证：

```powershell
.\mvnw.cmd -DskipTests package "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
```

结果：Maven reactor 10 个模块全部 `BUILD SUCCESS`，并确认编译使用 Java 21。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-dependency-recovery-drill.ps1 `
  -RecoverKafkaChain `
  -RestartPythonRuntime `
  -WaitSeconds 90
```

结果：`PASS=11, WARN=0, FAIL=0`。脚本按依赖顺序确认 Zookeeper/Kafka 可恢复，并在 Compose service 不可用时按容器名 `datasmart-python-ai-runtime` 兜底重启 Python Runtime，最终恢复到 healthy。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-delivery-closure-check.ps1 `
  -RunContainerizedDelivery `
  -RunLiveSmoke `
  -WriteEvidence
```

结果：`failedGates=0, gatesWithWarnings=7`，真实只读 E2E smoke 为 `PASS=89, WARN=0, FAIL=0`。warning 来自 Helm/SBOM/镜像签名/备份恢复/容量基线/故障演练/final audit 中尚未在本机强制完成的发布前增强项，例如 Helm 工具链、Cosign/Syft、真实容量压测、真实故障注入和全量测试复跑；这些属于客户或 CI 发布环境应继续补证的生产化事项，不是当前本地闭环失败。
