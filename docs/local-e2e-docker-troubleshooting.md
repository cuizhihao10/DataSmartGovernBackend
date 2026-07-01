# DataSmart Govern 本地 E2E Docker 与中间件排障说明

本文记录 Windows 本地真实 E2E 环境的 Docker、镜像、MySQL 端口和迁移登记排障流程。它是 `local-e2e-closure-runbook.md` 的补充文档，重点解决“环境还没启动起来”的问题，不替代业务 smoke check。

## 1. 适用场景

当出现以下问题时，优先阅读本文：

- Docker Desktop 已安装，但 `docker compose up` 拉取镜像反复出现 `EOF`、`TLS handshake timeout`、`connection reset`。
- Windows 本机 `MySQL80` 占用 `3306`，导致项目 Docker MySQL 无法绑定默认端口。
- MySQL 容器能启动，但初始化脚本中断，后续 migration 报基础表不存在。
- fresh Docker 数据卷已经由 `init.sql` 创建了快照结构，但 migration history 为空。
- readiness 脚本显示 MySQL/Redis 已可用，但 Nacos/Kafka/Keycloak/Grafana 仍未启动。

## 2. 国内镜像预拉取策略

不要直接把 `docker-compose.yml` 中的 `image` 改成某个国内镜像站地址。原因是 Compose 文件属于项目长期部署契约，而镜像站只是本地网络环境的临时兜底；把镜像站写死会影响 CI、客户内网部署、海外网络和后续私有仓库接入。

仓库提供脚本：

```powershell
.\scripts\local-e2e-docker-image-cache.ps1
```

脚本的工作方式：

- 按 `docker-compose.yml` 的标准镜像名维护镜像清单。
- 拉取时默认优先使用 DaoCloud 镜像前缀，例如 Docker Hub 镜像使用 `docker.m.daocloud.io/library/mysql:8.0`，Quay 镜像使用 `quay.m.daocloud.io/keycloak/keycloak:26.6.4`。
- 如果 DaoCloud 临时不可用，脚本会继续尝试仓库内配置的其他国内镜像源；默认不会回退 Docker Hub 或 Quay 官方源，避免真实 E2E 被官方源超时长时间拖住。
- 拉取成功后重新打标准 tag，例如 `mysql:8.0`。
- 后续 `docker compose up` 仍然使用标准镜像名，不需要知道镜像来自哪个临时源。

常用命令：

```powershell
.\scripts\local-e2e-docker-image-cache.ps1
.\scripts\local-e2e-docker-image-cache.ps1 -Scope All
.\scripts\local-e2e-docker-image-cache.ps1 -ImageName mysql,redis,nacos
.\scripts\local-e2e-docker-image-cache.ps1 -ImageName zookeeper,kafka -PullTimeoutSeconds 600
.\scripts\local-e2e-docker-image-cache.ps1 -ImageName keycloak -PullTimeoutSeconds 600
.\scripts\local-e2e-docker-image-cache.ps1 -ImageName grafana -AllowOfficialFallback
```

`-AllowOfficialFallback` 只建议在排查“国内镜像是否缺失某个 tag”时临时使用。日常闭环联调默认应走 DaoCloud 和其他国内镜像源，不应默认直连官方源。

安全边界：

- 只执行 `docker image inspect`、`docker pull`、`docker tag`。
- 不启动容器，不连接 MySQL，不执行 SQL。
- 不创建任务，不触发 worker loop，不访问业务接口。
- 不打印密码、token、SQL、业务 payload、prompt、模型输出或 HTTP 响应正文。

## 3. Docker Desktop registry mirrors 的现实限制

本轮真实环境中，已把以下内容写入 `C:\Users\Cui\.docker\daemon.json`，并通过 `docker desktop restart` 让 Docker Desktop Linux engine 重新加载：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run",
    "https://proxy.vvvv.ee",
    "https://dockerproxy.net",
    "https://dockerproxy.link",
    "https://docker.jiaxin.site"
  ]
}
```

当前 `docker info` 已能看到上述 mirrors，说明 Docker Desktop 已经读取配置。需要注意的是，Docker Hub 镜像可以通过 `registry-mirrors` 加速，但 `quay.io/keycloak/keycloak` 这类非 Docker Hub 镜像不会自动走 Docker Hub mirror，仍应通过 `local-e2e-docker-image-cache.ps1` 显式拉取 `quay.m.daocloud.io/...` 并重新打标准 tag。

判断命令：

```powershell
docker info --format '{{json .RegistryConfig.Mirrors}}'
docker desktop restart
docker desktop status
```

如果企业网络允许，更推荐的生产级方案是准备企业内部 Harbor/Nexus/Artifactory 镜像仓库，把项目依赖镜像同步进去，再由部署环境统一配置 registry mirror 或私有仓库认证。

## 4. Windows MySQL80 占用 3306 的处理

`MySQL80` 不是 Windows 系统组件，而是本机安装的 MySQL Server Windows 服务。它可能通过如下路径运行：

```text
D:\ENV\MySQL\MySQL Server 8.0\bin\mysqld.exe
```

如果它占用了 `3306`，不要为了项目联调强行停止本机服务。使用覆盖文件把项目 Docker MySQL 映射到 `13306`：

```powershell
$env:DATASMART_LOCAL_MYSQL_PORT = "13306"
docker compose -f docker-compose.yml -f docker-compose.local-e2e.yml up -d mysql redis
```

验证：

```powershell
docker ps --format "{{.Names}} {{.Image}} {{.Status}} {{.Ports}}"
```

期望看到：

```text
datasmart-mysql mysql:8.0 Up ... 0.0.0.0:13306->3306/tcp
datasmart-redis redis:7.2-alpine Up ... 0.0.0.0:6379->6379/tcp
```

readiness 探针：

```powershell
$env:DATASMART_MYSQL_USER = "root"
$env:DATASMART_MYSQL_PASSWORD = "password"
$env:DATASMART_LOCAL_MYSQL_PORT = "13306"
.\scripts\local-e2e-environment-readiness.ps1 -ProbeMySqlCredential
```

## 5. MySQL 8 初始化脚本兼容性

MySQL 8.0.46 不再接受旧写法：

```sql
GRANT ALL PRIVILEGES ON nacos.* TO 'root'@'%' IDENTIFIED BY 'password';
```

项目已修正为：

```sql
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON nacos.* TO 'root'@'%';
```

如果在修复前已经启动过 MySQL 容器，数据卷可能处于半初始化状态。半初始化的典型表现是：

- `docker logs datasmart-mysql` 中出现 `ERROR 1064 ... IDENTIFIED BY 'password'`。
- `datasmart_govern` 中只有 `datasmart_schema_migration_history` 或极少量表。
- 后续 migration 报 `data_sync_template` 等基础表不存在。

处理方式是只重建项目 MySQL 卷，不删除 Redis 或其他卷：

```powershell
$env:DATASMART_LOCAL_MYSQL_PORT = "13306"
docker compose -f docker-compose.yml -f docker-compose.local-e2e.yml stop mysql
docker compose -f docker-compose.yml -f docker-compose.local-e2e.yml rm -f mysql
docker volume rm datasmartgovernbackend_mysql_data
docker compose -f docker-compose.yml -f docker-compose.local-e2e.yml up -d mysql
```

这个操作只适合本地开发卷。生产数据库必须走备份、审批、迁移计划、回滚方案和变更窗口。

## 6. fresh snapshot 库的迁移登记

当前 `docker/mysql/init/init.sql` 是本地开发快照初始化脚本，已经包含部分后续 migration 的结构。fresh Docker 卷启动后，业务表可能已经存在，但 `datasmart_schema_migration_history` 为空。

因此 fresh snapshot 库不建议直接全量 `-Apply` 所有历史 migration。推荐流程：

```powershell
$env:DATASMART_MYSQL_USER = "root"
$env:DATASMART_MYSQL_PASSWORD = "password"
.\scripts\local-mysql-migration-governance.ps1 -ConnectionMode LocalCli -MySqlPort 13306
.\scripts\local-mysql-migration-governance.ps1 -ConnectionMode LocalCli -MySqlPort 13306 -BaselineExisting
.\scripts\local-mysql-migration-governance.ps1 -ConnectionMode LocalCli -MySqlPort 13306
```

本轮验证结果：

- MySQL Docker 容器通过 `13306` 暴露。
- `datasmart_govern` 初始化后存在 51 张业务表。
- migration history 最终读取到 52 条记录。
- 默认计划模式汇总为 `PASS=108, WARN=0, FAIL=0`。

`-BaselineExisting` 只登记历史，不执行 SQL。它适合本地 snapshot 开发库，不应作为生产跳过迁移的方式。后续商业化收敛仍应引入 Flyway/Liquibase。

## 7. 当前镜像拉取状态

本轮已成功准备并运行：

- `mysql:8.0`
- `redis:7.2-alpine`

本轮已成功拉取但未启动：

- `prom/prometheus:latest`
- `confluentinc/cp-zookeeper:7.6.0`
- `confluentinc/cp-kafka:7.6.0`
- `nacos/nacos-server:v2.3.0`
- `quay.io/keycloak/keycloak:26.6.4`
- `grafana/grafana:latest`

其中 `quay.io/keycloak/keycloak:26.6.4` 是通过 `quay.m.daocloud.io/keycloak/keycloak:26.6.4` 拉取后 retag 得到的，符合“本地拉取用 DaoCloud、Compose 仍用标准镜像名”的策略。

下一步应启动 Nacos、Kafka、Keycloak、Prometheus 与 Grafana，并用 readiness 脚本确认端口、凭据和低敏诊断是否恢复。若后续镜像再次缺失，优先使用 DaoCloud 前缀，不再默认直连官方源。
