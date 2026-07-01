# DataSmart Govern 生产化加固 Runbook

## 1. 文档定位

本 Runbook 用于承接当前项目已经完成的“本地完整闭环”，把后续工作从继续扩展功能转向生产化交付。它不会要求继续新增 Agent 角色、业务字段或控制面入口，而是把真正商用上线前必须补齐的安全、部署、供应链、数据可靠性、容量和故障演练要求拆成可执行检查项。

当前项目的关键结论是：本地 Compose 全平台、OIDC 服务账号、gateway 授权、Java 微服务、Python AI Runtime、LangGraph、多智能体控制面、Prometheus/Grafana 和只读 smoke 已经闭环。下一阶段的目标不是证明“功能还能继续写”，而是证明“客户环境可以安全部署、稳定运行、可恢复、可审计、可升级”。

## 2. 生产化加固总原则

### 2.1 不再用本地示例配置代表生产配置

`.env.application.example`、`docker-compose.yml` 和 `docker-compose.application.yml` 只适合本地学习、集成验证和单机演示。生产环境必须把密码、token、HMAC secret、OIDC client secret、模型 API Key、MinIO 密钥、Grafana 管理员密码等值放入 Secret Manager、Kubernetes Secret、CI/CD Secret 或企业统一密钥系统中。

### 2.2 不把 Compose 当作最终生产编排

Compose 的价值是快速启动和可重复联调。正式商用部署应迁移到 Kubernetes/Helm、客户已有容器平台或等价的企业编排系统，并明确资源配额、滚动升级、健康探针、节点亲和、网络策略、服务暴露、Ingress/TLS 和回滚方案。

### 2.3 不让生产动作默认自动写数据

当前 Compose 默认关闭 worker、dispatcher 和真实工具提交，这是正确的安全边界。生产启用这些能力前，必须先完成权限策略、审计留存、幂等保护、失败重试、死信处理、手动熔断、回滚方案和运维值班流程。

### 2.4 不把 AI 运行时当成唯一事实来源

Agent、LangGraph、长期记忆、Skill、工具调用和模型路由都应服务于平台业务闭环。任务、数据源、质量规则、同步执行、授权、审计和报告等业务事实仍应沉淀在 Java 业务服务和持久化存储中，避免把关键业务状态只保存在 Agent 内存或运行时事件中。

## 3. 安全与身份加固

### 3.1 认证中心

本地环境使用 Keycloak realm import 作为可重复 OIDC 认证中心。生产环境可以继续使用 Keycloak，但必须以集群化、持久化、备份可恢复和 HTTPS 暴露方式部署；如果客户已有企业 IdP，也可以接入企业 OIDC/SAML 身份体系。

生产验收至少需要确认：

- issuer 使用正式 HTTPS 域名，不再使用 `localhost`。
- gateway 的 JWKS 地址与 issuer 策略清晰，容器内访问地址和 token `iss` 语义不会冲突。
- 服务账号与人类用户账号分离，内部机器入口必须同时校验角色和主体类型。
- token 传播规则清晰，禁止把用户 token、服务账号 token 或模型 API Key 打入日志。
- OIDC client secret、HMAC secret、Keycloak admin 密码全部由 Secret Manager 提供。

### 3.2 TLS 与 mTLS

生产流量必须启用 TLS。对内部高敏服务调用、Agent 工具提交、管理端口、数据库和消息队列访问，应根据客户安全等级评估是否启用 mTLS 或专用网络策略。

建议分三层落地：

1. 南北向入口：Ingress/API Gateway 统一 HTTPS，证书来自企业 CA 或可信公有 CA。
2. 东西向服务：服务间调用至少处于受控内网和 NetworkPolicy 内，高安全场景启用 mTLS。
3. 存储连接：MySQL、Redis、Kafka、MinIO、Neo4j 等连接启用加密传输或专用网络隔离。

### 3.3 审计与权限

生产环境必须建立审计事件保留策略。重点审计对象包括管理员操作、角色权限变更、服务账号变更、数据导出、质量规则变更、工具执行、数据同步执行、敏感字段访问、模型/Agent 受控动作和 worker/dispatcher 启停。

## 4. 供应链加固

### 4.1 镜像来源

当前项目默认使用 DaoCloud 国内镜像加速，其中 Docker Hub 体系使用 `docker.m.daocloud.io`，Quay 体系 Keycloak 使用 `quay.m.daocloud.io`。生产环境建议将镜像同步到企业 Harbor/Nexus/ACR/ECR 等私有仓库，并建立固定版本、签名、扫描和晋级流程。

### 4.2 SBOM 与签名

生产发布前建议对 Java 服务镜像、Python Runtime 镜像和基础中间件镜像生成 SBOM，并使用 Cosign 或企业等价工具完成镜像签名。上线门禁应至少包含：

- 镜像 tag 不使用 `latest` 作为生产固定版本。
- 生成 SBOM 并归档到发布制品库。
- 漏洞扫描结果达到客户定义阈值。
- 镜像签名验证纳入部署前检查。
- 基础镜像升级和安全补丁有明确维护责任人。

## 5. Kubernetes/Helm 交付建议

Compose 到 Kubernetes/Helm 的迁移不应简单机械翻译，而应按服务职责拆分 values 和 chart：

- `gateway`：Ingress、TLS、OIDC 配置、限流、路由、HPA。
- Java 业务服务：Deployment、Service、Actuator probes、资源限制、配置引用、日志标准输出。
- Python Runtime：模型 provider、LangGraph/Skill/Memory 配置、资源限制、必要时 GPU nodeSelector。
- 中间件：优先使用客户已有托管服务；若必须自建，需采用成熟 Helm chart 并配置持久卷和备份。
- observability：Prometheus/Grafana/Alertmanager 应接入客户监控体系，避免形成孤岛。

最小 Helm 验收应包括 `helm template`、`helm lint`、命名空间隔离、Secret 引用、资源配额、探针、滚动升级和回滚说明。

## 6. 数据可靠性与备份恢复

生产环境至少要为以下组件定义备份恢复策略：

- MySQL：业务主库、迁移版本、备份周期、PITR、恢复演练。
- Redis：会话和短期状态，明确哪些数据可丢、哪些需要 AOF/RDB。
- Kafka：topic、分区、副本、保留周期、死信队列、重放策略。
- MinIO：报告、导出文件、工具 artifact、生命周期、跨可用区复制。
- Neo4j：知识图谱、血缘、业务关系，明确全量/增量备份策略。
- Chroma：向量数据和长期记忆索引，明确是否可从源数据重建以及重建耗时。
- Keycloak：realm、client、用户、角色、服务账号和密钥轮换历史。

恢复演练不应只写文档。至少需要定期完成一次从备份恢复到临时环境，并执行 gateway health、认证 token、核心服务 health、Python Runtime closure readiness 和只读 smoke。

## 7. 容量基线与性能验收

当前项目已经有本地闭环 smoke，但还没有正式容量基线。生产前建议先形成保守基线：

- Gateway：认证鉴权、路由、Agent 控制面代理的 P95/P99 延迟。
- Java 服务：任务查询、数据源连接测试、质量报告导出、同步诊断接口的吞吐和延迟。
- Kafka：worker command、receipt、runtime event、outbox topic 的 backlog 与消费延迟。
- Python Runtime：Agent plan、LangGraph 执行门禁、长期记忆检索、模型 provider 调用、指标导出的延迟。
- 存储：MySQL 慢查询、Redis 命中率、MinIO 上传下载、Chroma 检索、Neo4j 查询延迟。

容量基线的目标不是一开始追求极限性能，而是给客户部署、资源 requests/limits、告警阈值和扩容策略提供第一版依据。

## 8. 故障演练

建议优先演练这些故障：

- Keycloak 不可用：gateway 应返回清晰 401/503 语义，不应泄露内部错误。
- 下游服务实例不可用：gateway LoadBalancer 应返回 503，不应误报 500。
- Kafka 暂停：worker command、receipt、outbox 应可观测 backlog，并具备恢复策略。
- Python Runtime 不可用：Agent plan 或诊断入口应受控失败，不应影响普通 Java 业务服务健康。
- MySQL 短暂不可用：Java 服务应暴露 health 异常，并避免无限重试打满连接池。
- Chroma/Neo4j 不可用：长期记忆或 GraphRAG 能力应降级，不应阻断所有平台控制面。

每次故障演练都应记录触发方式、预期结果、实际结果、恢复步骤、发现问题和后续修复项。

## 9. 当前仓库对应检查命令

当前仓库新增了生产就绪静态检查脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\production-readiness-check.ps1
```

默认模式用于收敛推进：已满足本地闭环和生产加固文档契约时返回成功。容量基线和故障演练已经具备静态门禁与无敏感信息计划输出能力，但真实压测与真实故障注入仍应在客户预生产或专用 runner 中完成。

Kubernetes/Helm 交付就绪检查可单独运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\helm-delivery-check.ps1
```

该脚本默认只做静态检查，不连接 Kubernetes 集群、不读取 Secret、不创建 namespace、不部署服务。它会验证 [Kubernetes/Helm 交付说明](kubernetes-helm-deployment.md)、`helm/datasmart-govern` chart 结构、Secret 引用、非 root/只读根文件系统、探针、RollingUpdate、资源限制和高风险 worker 默认关闭策略。如果本机或 CI runner 安装了 Helm，它会自动执行 `helm lint` 和 `helm template`；未安装 Helm 时默认只给 warning，正式发布门禁可用 `-StrictTooling` 收紧。

供应链 SBOM 就绪检查可单独运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\sbom-check.ps1
```

如果需要生成不含 Secret 的源码侧依赖范围快照，可追加 `-WriteSourceInventory`，输出会写入被 Git 忽略的 `target/sbom`。该快照不是完整 CycloneDX/SPDX SBOM；正式发布时仍应在 CI 中对已构建镜像使用 Syft 或企业 SBOM 工具生成标准格式制品。

镜像签名验证就绪检查可单独运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-image-signatures.ps1
```

该脚本默认只做静态准入检查：枚举 `.env.application.example` 中的 `DATASMART_*_IMAGE` 镜像范围、提示仍使用 `latest` tag 的本地示例镜像、检查本机是否具备 Cosign 工具、确认生产文档已经说明镜像签名边界。它不会生成私钥、读取私钥、推送镜像或访问生产仓库，避免把发布流水线职责混入开发机脚本。

当镜像已经由 CI/CD 构建并推送到企业 registry 后，可以使用 keyless 身份策略或公钥策略验证真实签名：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-image-signatures.ps1 `
  -VerifyPublishedImages `
  -Images "harbor.example.com/datasmart/gateway:1.0.0" `
  -CosignIdentityRegexp "^https://github.com/example/.+/.github/workflows/.+@refs/tags/.+$" `
  -CosignIssuer "https://token.actions.githubusercontent.com"
```

如果客户采用 KMS 托管密钥或企业制品库公钥验证，也可以传入 `-CosignPublicKey`。注意这里应传公钥或证书策略，不应传私钥；签名动作本身仍建议放在 CI/CD、Harbor/Nexus/ACR/ECR 等企业发布链路中。

备份恢复就绪检查可单独运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-restore-check.ps1
```

该脚本默认只做静态检查，不连接数据库、不读取 Secret、不导出数据、不恢复覆盖、不触发 worker。它会验证 [备份恢复 Runbook](backup-restore-runbook.md) 是否覆盖 `RPO/RTO/PITR` 和 MySQL、Redis、Kafka、MinIO、Neo4j、Chroma、Keycloak、Nacos 等关键组件；也会验证 Compose 是否为核心有状态组件声明持久化 volume，以及 Keycloak、Prometheus、Grafana、Alertmanager 等配置是否具备 config-as-code 恢复路径。

如果要在恢复演练环境中检查本机工具，可追加 `-CheckLocalTools`；如果要生成不含 Secret 的恢复范围清单，可追加 `-WriteRecoveryInventory`，输出会写入被 Git 忽略的 `target/backup-restore`。该清单不是实际备份制品，只能作为发布审查和恢复演练准备材料。

容量基线就绪检查可单独运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capacity-baseline-check.ps1
```

该脚本默认只做静态检查，不运行压测、不读取 Secret、不触发 worker、不提交工具、不写业务数据。它会验证 [容量基线 Runbook](capacity-baseline-runbook.md) 是否覆盖 `Gateway`、Java 服务、Python Runtime、Kafka、MySQL/Redis/MinIO/Chroma/Neo4j 和 Agent plan 等关键链路；也会验证 Prometheus、Grafana、Alertmanager 等观测路径是否具备基础配置，避免压测后无法解释瓶颈。

如果要在 CI 或专用压测 runner 中检查本机工具，可追加 `-CheckLocalTools`；如果要生成不含 Secret 的容量基线计划，可追加 `-WriteBaselinePlan`，输出会写入被 Git 忽略的 `target/capacity-baseline`。该计划不是实际压测报告，只能作为真实容量验收的场景矩阵和指标清单。

故障演练就绪检查可单独运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\failure-drill-check.ps1
```

该脚本默认只检查 [故障演练 Runbook](failure-drill-runbook.md)、Compose 服务、备份恢复、容量基线、Prometheus 与 Alertmanager 等前置条件，不停止容器、不修改网络、不删除 volume、不读取 Secret、不触发 worker 或写入业务数据。`-WriteDrillPlan` 可生成不含敏感信息的场景计划，`-CheckLocalTools` 可在专用演练 runner 中检查 Docker、kubectl 和 Helm。

静态检查通过不等于真实演练完成。正式上线前仍应在接近生产的隔离环境完成人工批准的 Keycloak、Gateway、MySQL、Kafka、Python Runtime 等 P0 故障场景，并归档 RTO/RPO、告警时间线、恢复证据和复盘行动项。

如果需要把 warning 也视为失败，可使用严格模式：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\production-readiness-check.ps1 -StrictProductionGates
```

严格模式适合真正上线前或 CI 发布阶段；当前项目仍处于“本地闭环后生产化加固”的早期，因此默认模式更适合持续推进。

## 10. 后续收敛顺序

推荐后续按以下顺序推进：

1. 修复生产就绪检查中的配置漂移和文档缺口。
2. 先阅读 [生产环境值与 Secret 管理说明](production-environment-values.md)，建立 Secret/TLS、正式 IdP、多环境变量和受控开关的交付边界。
3. 基于 Kubernetes/Helm 交付骨架执行 `helm lint`、`helm template` 和客户环境部署评审。
4. 建立 SBOM、镜像签名和漏洞扫描流水线。
5. 基于备份恢复脚本执行真实恢复演练，并归档恢复记录、耗时、失败点和修复项。
6. 建立容量基线脚本和性能报告模板。
7. 在隔离预生产环境执行故障演练并归档事故复盘证据。

只要这些生产交付项没有稳定，就不建议再继续扩展新的 Agent、ETL、数据资产或合规脱敏大功能。现在最重要的不是“项目还能变多复杂”，而是“项目能不能被客户安全地部署和长期运维”。
