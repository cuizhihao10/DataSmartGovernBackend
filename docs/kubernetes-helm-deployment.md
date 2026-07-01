# DataSmart Govern Kubernetes/Helm 交付说明

## 1. 文档定位

本文说明 DataSmart Govern 从本地 Docker Compose 闭环迁移到 Kubernetes/Helm 的第一版生产交付边界。它不是要求立即在本机部署到真实集群，也不是把 `docker-compose.yml` 机械翻译成 Kubernetes YAML；它的目标是给后续客户环境、CI/CD、DevOps 和 SRE 团队一个可审查、可扩展、可门禁的 Helm 起点。

当前仓库新增的 chart 位于：

```text
helm/datasmart-govern
```

该 chart 只覆盖应用运行层：`gateway`、8 个 Java 微服务中的业务服务、`python-ai-runtime`。MySQL、Redis、Kafka、Nacos、Keycloak、MinIO、Neo4j、Chroma、Prometheus/Grafana/Alertmanager 等有状态基础设施，生产环境优先使用客户托管服务、企业已有平台或成熟官方 chart，而不是全部塞进同一个业务 chart。

## 2. 设计原则

### 2.1 应用 chart 与中间件解耦

Compose 适合本地一键启动，因此会把中间件和应用服务放在同一个文件里。生产 Kubernetes 不应照搬这种结构。原因包括：

- MySQL、Kafka、Redis、MinIO、Keycloak 等有状态服务通常由 DBA、SRE 或客户平台团队统一托管。
- 有状态服务的备份恢复、容量规划、高可用和升级策略，与无状态应用服务完全不同。
- 业务 chart 如果自建所有中间件，会让权限、存储、网络和恢复责任变得模糊。

因此当前 chart 只负责应用层 Deployment、Service、Ingress、Secret 引用、探针、资源和安全上下文。

### 2.2 Secret 只引用，不落库

`values.yaml` 中只声明 `global.existingSecret`，不保存任何密码、token、OIDC client secret、模型 API Key、MinIO secret 或 TLS 私钥。真实生产值应由以下机制之一提供：

- Kubernetes Secret；
- ExternalSecrets；
- SealedSecrets；
- Vault；
- 云厂商 KMS/Secret Manager；
- 客户已有企业密钥系统。

chart 会生成一个 `secret-contract` ConfigMap，用来说明需要哪些 Secret key。这个 ConfigMap 不是 Secret，只是交付审查契约。

### 2.3 高风险写入口默认关闭

生产部署不是“服务启动就自动消费任务”。当前 values 继续保持这些高风险入口默认关闭：

- `DATASMART_TASK_AGENT_ASYNC_WORKER_ENABLED=false`
- `DATASMART_TASK_AGENT_TOOL_ACTION_CONTROLLED_SUBMIT_ENABLED=false`
- `DATASMART_AGENT_RUNTIME_OUTBOX_DISPATCHER_ENABLED=false`
- `DATASMART_AGENT_RUNTIME_ASYNC_COMMAND_DISPATCHER_ENABLED=false`
- `DATASMART_QUALITY_TASK_MANAGEMENT_EXECUTOR_COORDINATOR_ENABLED=false`

打开这些开关前，必须完成权限、审批、审计、幂等、失败重试、死信、回滚和值班流程。

## 3. Chart 结构

```text
helm/datasmart-govern
├── Chart.yaml
├── values.yaml
├── values-production.example.yaml
└── templates
    ├── _helpers.tpl
    ├── application-services.yaml
    ├── gateway-ingress.yaml
    ├── network-policy.yaml
    ├── secret-contract.yaml
    ├── serviceaccount.yaml
    └── NOTES.txt
```

关键文件说明：

- `values.yaml`：默认应用层部署值，保留生产安全边界，不包含真实 Secret。
- `values-production.example.yaml`：生产覆盖示例，展示镜像仓库、OIDC、Ingress、Secret、资源和副本数如何覆盖。
- `application-services.yaml`：用一个模板生成 8 个 Java 服务和 Python Runtime 的 Deployment/Service，避免复制粘贴导致探针、资源、安全上下文漂移。
- `secret-contract.yaml`：列出 chart 需要的 Secret key，帮助平台管理员准备密钥。
- `gateway-ingress.yaml`：只暴露 gateway，其他服务默认 ClusterIP 内部访问。
- `network-policy.yaml`：预留 NetworkPolicy 收敛入口，默认关闭，避免在未知客户网络里误阻断外部托管服务。

## 4. 本地静态检查

Kubernetes/Helm 就绪检查脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\helm-delivery-check.ps1
```

默认检查内容：

- chart 文件结构是否完整；
- values 是否引用外部 Secret；
- Pod/Container 安全上下文是否包含非 root、只读根文件系统、禁用权限提升；
- worker、dispatcher、真实提交等高风险写入口是否默认关闭；
- Deployment 是否包含 readiness/liveness probe、RollingUpdate、resources；
- 文档和最终收敛清单是否已接入 Helm 检查命令。

如果本机或 CI runner 安装了 Helm，脚本会自动运行：

```powershell
helm lint .\helm\datasmart-govern
helm template datasmart-govern .\helm\datasmart-govern --values .\helm\datasmart-govern\values-production.example.yaml
```

如果未安装 Helm，默认只输出 WARN；进入正式发布门禁时，可以在 CI 中安装 Helm，并使用 `-StrictTooling` 把缺少 Helm 视为失败。

## 5. 渲染与安装示例

仅渲染，不安装：

```powershell
helm template datasmart-govern .\helm\datasmart-govern `
  --namespace datasmart `
  --values .\helm\datasmart-govern\values-production.example.yaml
```

安装或升级前，必须先准备 Secret：

```powershell
kubectl -n datasmart create secret generic datasmart-prod-secrets `
  --from-literal=mysql-password=REPLACE_ME `
  --from-literal=neo4j-password=REPLACE_ME `
  --from-literal=minio-access-key=REPLACE_ME `
  --from-literal=minio-secret-key=REPLACE_ME `
  --from-literal=gateway-signature-secret=REPLACE_ME `
  --from-literal=model-api-key=REPLACE_ME
```

安装或升级示例：

```powershell
helm upgrade --install datasmart-govern .\helm\datasmart-govern `
  --namespace datasmart `
  --create-namespace `
  --values .\helm\datasmart-govern\values-production.example.yaml
```

上述命令只是示例。真实客户环境应把 Secret 创建、镜像签名验证、SBOM 归档、Helm lint/template、变更审批、灰度发布和回滚策略放入 CI/CD。

## 6. 发布前检查

正式发布前至少确认：

- `global.imageRegistry` 指向企业私有镜像仓库。
- `global.imageTag` 是不可变版本、Git SHA 或发布版本，不是 `local` 或漂移 tag。
- `global.existingSecret` 已存在，且由 Secret Manager 或企业密钥系统托管。
- `DATASMART_GATEWAY_OIDC_ISSUER_URI` 使用 HTTPS 正式 issuer。
- `ingress.tls.enabled=true`，并且 TLS secret 来自可信证书链。
- 每个服务都配置资源 requests/limits。
- readiness/liveness probe 能在客户环境真实通过。
- worker、dispatcher、真实工具提交是否启用有明确审批记录。
- 中间件连接地址指向客户托管服务或成熟 chart，而不是本地 Compose 地址。
- 备份恢复、容量基线、故障演练已经在预发环境完成至少一次。

## 7. 当前边界

当前 chart 是“最小生产交付骨架”，不是最终完整云原生平台：

- 未包含 MySQL/Redis/Kafka/Keycloak/MinIO/Neo4j/Chroma 的生产级 StatefulSet 或官方 chart 依赖。
- 未包含 Service Mesh、mTLS、ExternalSecrets、cert-manager、HPA、PDB 的完整模板。
- NetworkPolicy 默认关闭，生产环境应按客户网络拓扑逐步收紧。
- 未连接真实集群做安装验证，因为本仓库不能假设客户集群、证书、Secret 和镜像仓库已经存在。

下一步如果继续收敛生产交付，优先补容量基线和故障演练，而不是继续扩大业务功能面。
