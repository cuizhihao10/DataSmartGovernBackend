# DataSmart Govern 全平台容器化交付说明

## 1. 交付目标

仓库现在把部署分成两个可组合层次：

- `docker-compose.yml`：MySQL、Redis、Kafka、Nacos、Keycloak、Neo4j、MinIO、Chroma、Prometheus、Alertmanager、Grafana。
- `docker-compose.application.yml`：8 个 Spring Boot 微服务、Python AI Runtime，以及容器模式的 Prometheus 抓取覆盖。

这种分层同时支持两种开发方式：只启动中间件并在 IDE 中调试服务；叠加应用 overlay 后启动完整平台。Compose 是本地集成、演示和单机交付入口，正式多节点生产环境仍应迁移到 Kubernetes/Helm 或客户认可的容器编排平台。

## 2. 构建原理

根 POM 默认跳过 Spring Boot `repackage`，避免父工程和 `platform-common` 被误包装成应用；8 个包含 `main` 方法的模块显式开启 repackage。执行 `mvn package` 后，每个应用 jar 都包含 `BOOT-INF`、依赖和启动清单，可直接 `java -jar`。

`docker/runtime/java-service.Dockerfile` 是共享多阶段 Dockerfile：

1. builder 使用 Maven + JDK 21，按 `MODULE` 白名单只编译目标模块及 `platform-common`。
2. runtime 只保留 JRE 21、可执行 jar、CA 证书和健康检查工具。
3. 进程以非 root `datasmart` 用户运行，容器根文件系统在 Compose 中设为只读。
4. `JAVA_OPTS` 使用容器内存比例并在 OOM 时退出，让编排器负责重启而不是保留不可预测进程。

Python Runtime 同样使用多阶段构建，默认安装 `api,rag,kafka,redis` extras，因此镜像内包含 FastAPI、LangGraph、Chroma client、Kafka client 和 Redis client，但不包含测试目录或 pip 缓存。

## 3. 环境与密钥

参考 `.env.application.example` 创建本地环境文件：

```powershell
Copy-Item .env.application.example .env.application
```

示例密码只用于本地学习。生产环境必须外置 MySQL、Neo4j、MinIO、Grafana、Keycloak、Gateway HMAC 和模型 Provider 密钥，禁止把真实值提交到 Git。应用 Compose 默认保持任务 worker、Agent outbox dispatcher 和真实工具提交关闭，避免平台启动时自动消费历史任务或修改客户数据。

## 4. 一键构建与启动

先执行交付契约检查：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\containerized-delivery-check.ps1
```

首次需要构建代表镜像时：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\containerized-delivery-check.ps1 `
  -BuildRepresentativeImages
```

DaoCloud 临时出现 TLS timeout 时，可以只对本次构建切换备用国内镜像，不改变仓库默认值：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\containerized-delivery-check.ps1 `
  -BuildRepresentativeImages `
  -MavenImage docker.1ms.run/library/maven:3.9.9-eclipse-temurin-21 `
  -JavaRuntimeImage docker.1ms.run/library/eclipse-temurin:21-jre-jammy `
  -PythonImage docker.1ms.run/library/python:3.11-slim-bookworm
```

启动完整平台：

```powershell
docker compose --env-file .env.application `
  -f docker-compose.yml `
  -f docker-compose.application.yml `
  up -d --build
```

如果此前通过 IDE 或 `mvn spring-boot:run` 启动了 8080-8091 端口上的服务，应先停止这些进程，避免宿主机端口冲突。

## 5. 服务寻址与认证

容器内不能用 `localhost` 访问其他服务。应用 overlay 把数据库、Kafka、Redis、Nacos 和内部 HTTP 地址覆盖为 Compose 服务 DNS，例如 `mysql:3306`、`kafka:29092`、`permission-admin:8085`。

observability 的 `service-base-urls` 也按模块代码注入容器 DNS；未配置时自动回退 `localhost:<defaultPort>`，所以 IDE 模式和容器模式可以共享一套代码。

OIDC token 的 issuer 默认保持 `http://localhost:18080/realms/datasmart`，供宿主机客户端获取和校验；Gateway 容器通过独立的内部 JWK URL `http://keycloak:18080/.../certs` 获取公钥。生产环境应使用统一 HTTPS 域名、正式企业 IdP/Keycloak 集群和受信任证书。

## 6. 可观测性

基础 Prometheus 配置抓取宿主机进程；应用 overlay 会把挂载替换为 `docker/prometheus/prometheus.application.yml`，改用容器 DNS 抓取 8 个 Java 服务和 Python Runtime。

常用检查：

```powershell
docker compose -f docker-compose.yml -f docker-compose.application.yml ps
Invoke-WebRequest http://localhost:8080/actuator/health -UseBasicParsing
Invoke-WebRequest http://localhost:8090/agent/capabilities/closure-readiness -UseBasicParsing
Invoke-WebRequest http://localhost:9090/-/ready -UseBasicParsing
```

最后继续执行现有真实只读 smoke：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-e2e-smoke-check.ps1 `
  -CheckServiceAccountToken `
  -CheckAgentGatewayDiagnostics
```

## 7. 当前生产边界

本交付物完成的是单机容器化闭环，不等于多节点生产发布。正式商用上线前仍需要完成 TLS/mTLS、Secret Manager、镜像签名与 SBOM、漏洞扫描、资源 requests/limits、滚动升级、数据库备份恢复、Kafka/Redis/MySQL 高可用、对象存储生命周期、容量压测和故障演练。

