# DataSmart Govern 生产环境值与 Secret 管理说明

## 1. 文档目标

本文用于说明 DataSmart Govern 从本地 Compose 闭环迁移到生产环境时，各类环境变量、Secret、镜像仓库、OIDC/TLS、资源参数和受控开关应该如何管理。它不是 `.env.application.example` 的复制版，而是生产交付时给平台管理员、DevOps、SRE、安全团队和后续 AI 辅助开发线程使用的配置边界说明。

当前仓库已经提供 `.env.application.example`，用于本地学习和只读 E2E smoke。生产环境不能直接沿用这个文件中的示例密码、示例 Keycloak admin、示例 HMAC secret 或 `localhost` issuer。生产配置必须从 Secret Manager、Kubernetes Secret、CI/CD Secret、企业配置中心或客户认可的密钥系统注入。

## 2. 配置分层原则

### 2.1 普通配置

普通配置是不直接构成凭据的运行参数，例如镜像 tag、运行 profile、服务域名、日志级别、资源限额、是否启用某个受控 worker。这些配置可以进入 Helm values、Nacos 配置、Kubernetes ConfigMap 或企业配置中心，但仍应通过代码审查和发布流程管理。

### 2.2 Secret 配置

Secret 配置包括数据库密码、OIDC client secret、HMAC secret、模型 API Key、MinIO secret、Grafana admin 密码、Keycloak admin 密码、Keycloak 数据库密码、私有镜像仓库凭据、TLS 私钥等。它们不得提交到 Git，不得写入普通 ConfigMap，不得出现在日志、事件、Prometheus label、Agent runtime event attributes 或 smoke 输出中。

### 2.3 运行态开关

worker、dispatcher、真实工具提交、DataSync run-once、DataQuality executor 写入链路属于运行态开关。它们不是普通功能开关，而是“是否允许平台主动消费任务或写业务数据”的安全边界。生产打开这些开关前，必须确认权限、审计、幂等、重试、死信、回滚和值班流程已经到位。

## 3. 全局与镜像配置

| 变量 | 类型 | 生产来源 | 说明 |
| --- | --- | --- | --- |
| `DATASMART_IMAGE_TAG` | 普通配置 | CI/CD 发布参数 | 应固定到可追踪版本号、Git SHA 或制品版本；生产不建议使用 `local` 或漂移 tag。 |
| `DATASMART_RUNTIME_PROFILE` | 普通配置 | Helm values / ConfigMap | 本地为 `container`；生产可按客户环境拆成 `prod`、`staging`、`preprod`。 |
| `DATASMART_*_IMAGE` | 普通配置 | 企业镜像仓库 values | 生产建议统一指向 Harbor/Nexus/ACR/ECR 等私有仓库，并配合镜像签名和漏洞扫描。 |
| `DATASMART_KEYCLOAK_IMAGE` | 普通配置 | 企业镜像仓库 values | 本地 DaoCloud Quay 路径为 `quay.m.daocloud.io/keycloak/keycloak:26.6.4`；生产建议同步到私有仓库。 |

镜像配置的核心原则是“开发可用镜像加速，生产使用受控制品”。DaoCloud 适合国内本地拉取加速，但客户生产环境通常需要企业私有仓库、固定版本、SBOM、镜像签名和准入策略。

## 4. 数据库与中间件 Secret

| 变量 | 类型 | 生产来源 | 说明 |
| --- | --- | --- | --- |
| `DATASMART_POSTGRES_PASSWORD` | Secret | Secret Manager / Kubernetes Secret | PostgreSQL 目标库密码。本地 Compose 同时承载已迁移业务 schema、AI Memory、LangGraph durable state 和 Keycloak 独立库；生产建议拆分服务账号和最小权限。 |
| `DATASMART_MYSQL_PASSWORD` | Secret | Secret Manager / Kubernetes Secret | MySQL 业务库密码。生产建议使用独立账号、最小权限、定期轮换，并避免复用 root。 |
| `DATASMART_NEO4J_PASSWORD` | Secret | Secret Manager / Kubernetes Secret | Neo4j 图谱库密码。若客户使用托管图数据库，应改由托管服务凭据注入。 |
| `DATASMART_MINIO_ACCESS_KEY` | Secret | Secret Manager / Kubernetes Secret | MinIO 访问 key。生产建议按服务账号拆分权限，不使用示例值。 |
| `DATASMART_MINIO_SECRET_KEY` | Secret | Secret Manager / Kubernetes Secret | MinIO secret key。不得进入日志、事件、指标 label 或提交历史。 |
| `DATASMART_GRAFANA_ADMIN_PASSWORD` | Secret | Secret Manager / Kubernetes Secret | Grafana 管理员初始密码。生产建议接入企业 SSO 并关闭长期共享管理员账号。 |
| `DATASMART_KEYCLOAK_ADMIN_USERNAME` | Secret/受控配置 | Secret Manager / Kubernetes Secret | Keycloak 管理员用户名。生产不应长期暴露默认 admin。 |
| `DATASMART_KEYCLOAK_ADMIN_PASSWORD` | Secret | Secret Manager / Kubernetes Secret | Keycloak 管理员密码。生产应启用轮换和审计。 |
| `DATASMART_KEYCLOAK_DB_NAME` | 普通配置 | Helm values / ConfigMap | Keycloak 独立 database 名称。本地默认 `keycloak`；生产可按客户数据库命名规范调整。 |
| `DATASMART_KEYCLOAK_DB_USERNAME` | Secret/受控配置 | Secret Manager / Kubernetes Secret | Keycloak 数据库账号。生产应使用专用账号，不复用 DataSmart 业务服务账号或 PostgreSQL 管理员。 |
| `DATASMART_KEYCLOAK_DB_PASSWORD` | Secret | Secret Manager / Kubernetes Secret | Keycloak 数据库密码。不得进入 Git、日志、指标、runtime event 或 smoke 输出。 |

如果客户环境已经提供 PostgreSQL、MySQL、Redis、Kafka、MinIO、Neo4j、Chroma 或 Keycloak 托管服务，优先接入客户托管服务，而不是在业务 chart 中自建所有中间件。这样更符合企业运维边界，也更容易满足备份、HA、审计和容量要求。自建 Keycloak 时，Keycloak 的 PostgreSQL database 必须进入同一套备份、恢复、PITR、审计和容量规划。

## 5. Gateway、OIDC 与 TLS

| 变量 | 类型 | 生产来源 | 说明 |
| --- | --- | --- | --- |
| `DATASMART_GATEWAY_OIDC_ISSUER_URI` | 普通配置 | Helm values / ConfigMap | 生产必须使用 HTTPS issuer，例如 `https://idp.example.com/realms/datasmart`，不应使用 `localhost`。 |
| `DATASMART_GATEWAY_OIDC_JWK_SET_URI` | 普通配置 | Helm values / ConfigMap | 可显式配置为网关容器可访问的 JWKS 地址。生产若 issuer discovery 可达，也可按安全策略回退 discovery。 |
| `DATASMART_GATEWAY_SIGNATURE_SECRET` | Secret | Secret Manager / Kubernetes Secret | Gateway 与 Python Runtime 之间的 HMAC secret，至少 32 字节高熵随机值，必须可轮换。 |
| `DATASMART_GATEWAY_SIGNATURE_KEY_ID` | 普通配置 | Helm values / ConfigMap | HMAC key id，用于密钥轮换和审计定位。 |

本地 Compose 中 issuer 和 JWKS 拆分，是因为宿主机获取 token 时 `iss` 是 `http://localhost:18080/...`，而 gateway 容器内访问 Keycloak 需要走 `http://keycloak:18080/...`。生产环境更推荐使用统一 HTTPS 域名，让用户、gateway 容器和服务间调用都访问同一个可信 issuer，从根上减少内外地址分裂。

TLS 相关证书、私钥和 CA bundle 不应写入本仓库。Kubernetes 场景建议通过 cert-manager、企业 Ingress、Service Mesh 或客户证书管理平台提供。

## 6. Python Runtime 与模型 Provider

| 变量 | 类型 | 生产来源 | 说明 |
| --- | --- | --- | --- |
| `DATASMART_AI_OPENAI_COMPATIBLE_BASE_URL` | 普通配置 | Helm values / ConfigMap | OpenAI-compatible 模型服务地址，可指向 vLLM、企业模型网关或托管推理服务。 |
| `DATASMART_AI_OPENAI_COMPATIBLE_API_KEY` | Secret | Secret Manager / Kubernetes Secret | 模型服务 API Key。不得进入 prompt、日志、runtime event、Prometheus label 或异常消息。 |

模型 Provider 应保持可替换，不应把某个具体模型族写死到业务服务或长期接口中。生产配置应该通过模型路由、能力标签、成本策略、上下文限制、超时、重试和降级策略控制，而不是让业务模块直接绑定某个模型名称。

## 7. 受控开关

这些开关的具体变量名会随着部署形态和服务配置演进，但语义必须保持一致：

| 开关类型 | 默认状态 | 打开前置条件 |
| --- | --- | --- |
| 任务 worker | 关闭 | 已确认任务来源、租户边界、幂等键、失败重试、死信队列、手动熔断和审计。 |
| Agent outbox dispatcher | 关闭 | 已确认事件投递目标、重复投递处理、投递失败重试、积压告警和人工暂停流程。 |
| 真实工具提交 | 关闭 | 已确认 permission-admin 策略、工具 schema、审批流程、危险路径、命令安全、审计和回滚。 |
| DataSync run-once | 关闭 | 已确认数据源授权、目标写入范围、回执投影、失败补偿和数据恢复方式。 |
| DataQuality 写入执行器 | 关闭 | 已确认清洗策略、影响范围、审批记录、报告导出和回滚方案。 |

只读 smoke 和本地闭环不应打开这些开关。生产启用时建议先在预发环境按单租户、单任务、低并发灰度验证，再逐步提升并发和范围。

## 8. Kubernetes/Helm values 建议结构

正式 Helm values 建议至少拆分为以下层次：

```yaml
global:
  imageRegistry: registry.example.com/datasmart
  imageTag: "2026.07.01-a5572a3"
  runtimeProfile: prod

identity:
  issuerUri: https://idp.example.com/realms/datasmart
  jwkSetUri: https://idp.example.com/realms/datasmart/protocol/openid-connect/certs

secrets:
  existingSecret: datasmart-platform-secrets

gateway:
  ingress:
    enabled: true
    tlsSecretName: datasmart-gateway-tls
  resources:
    requests:
      cpu: "500m"
      memory: "1Gi"
    limits:
      cpu: "2"
      memory: "2Gi"

pythonRuntime:
  modelProvider:
    openaiCompatibleBaseUrl: https://model-gateway.example.com/v1
  resources:
    requests:
      cpu: "1"
      memory: "2Gi"
    limits:
      cpu: "4"
      memory: "8Gi"
```

上面的 YAML 是结构建议，不是当前仓库已经提供的 Helm chart。它的价值是提前固定生产 values 的边界，避免未来 Helm 化时把 Secret、普通配置、资源参数和业务开关混在一起。

## 9. 环境矩阵

| 环境 | 目标 | 允许事项 | 禁止事项 |
| --- | --- | --- | --- |
| local | 本地学习、只读 smoke、功能闭环验证 | 使用 `.env.application.example` 派生本地 `.env.application`；使用 DaoCloud 镜像加速；worker 默认关闭。 | 使用真实客户数据；提交真实 Secret；打开高风险写入链路。 |
| dev | 团队开发联调 | 使用开发 Secret；允许少量测试任务；可连接共享中间件。 | 复用生产凭据；连接生产数据库；绕过 gateway 鉴权。 |
| staging | 发布前验收 | 使用接近生产的 OIDC、TLS、镜像仓库、资源配置；执行受控 E2E 和容量基线。 | 使用示例密码；使用 `localhost` issuer；跳过审计和告警。 |
| prod | 客户正式运行 | 使用正式 Secret Manager、企业 IdP、私有镜像仓库、备份恢复、告警和值班流程。 | 使用 demo realm、示例账号、示例密码、未签名镜像、无备份数据卷。 |

## 10. 发布前检查清单

生产发布前至少确认：

- `.env.application` 没有被提交，真实 Secret 不存在于 Git 历史。
- `DATASMART_GATEWAY_OIDC_ISSUER_URI` 使用 HTTPS 正式 issuer。
- `DATASMART_GATEWAY_SIGNATURE_SECRET` 已由 Secret Manager 注入，且不是示例值。
- Keycloak、MySQL、Redis、Kafka、MinIO、Neo4j、Chroma 的生产凭据都由受控 Secret 注入。
- 镜像来自企业私有仓库，并完成漏洞扫描、SBOM 归档和签名验证。
- worker、dispatcher、真实工具提交等开关保持默认关闭，除非审批、审计、回滚和告警已经完成。
- 所有服务都配置资源 requests/limits、健康探针、日志标准输出和滚动升级策略。
- 备份恢复、容量基线和故障演练至少完成一次预发验证。

如果以上任一项没有满足，项目仍可继续作为本地闭环和预发验证环境使用，但不应宣称已经达到正式商用生产发布状态。
