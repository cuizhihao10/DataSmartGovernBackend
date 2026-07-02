# MySQL 到 PostgreSQL 渐进迁移路线

## 2026-07-03 更新：data-sync PostgreSQL 代码路径迁移

`data-sync` 已完成 PostgreSQL 运行路径迁移，进入阶段 2 第三个核心业务服务的代码切换验收：

- `data-sync` POM 移除平台业务库层面的 MySQL 驱动依赖，新增 pgJDBC、Flyway Core、`flyway-database-postgresql` 与 `mybatis-plus-jsqlparser`；
- `application.yml` 默认 datasource 切换为 PostgreSQL `data_sync` schema，并补充 Hikari 连接池、Flyway schema、迁移位置和 MyBatis-Plus 表名前缀说明；
- 新增 `MyBatisPlusConfig`，把分页插件切换为 PostgreSQL 方言，并通过 `MetaObjectHandler` 统一填充 `createTime/updateTime`；
- 新增 `db/migration/postgresql/data-sync/V1__data_sync_schema_baseline.sql`，覆盖当前 data-sync 微服务自有的 10 张控制面事实表；
- 10 张表包括：`data_sync_template`、`data_sync_task`、`data_sync_execution`、`data_sync_callback_idempotency`、`data_sync_task_management_receipt_outbox`、`data_sync_checkpoint`、`data_sync_execution_recovery_plan`、`data_sync_error_sample`、`data_sync_incident_record`、`data_sync_audit_record`；
- PostgreSQL V1 明确不包含 `task_data_sync_*` 与 `agent_memory_*`，前者后续随 task-management/data-sync 边界收敛，后者必须迁入阶段 3 的 `ai_memory`；
- Mapper SQL 已完成 PostgreSQL 方言修正：`NOW()/DATE_ADD/DATE_SUB` 转为 `LOCALTIMESTAMP` 与 interval 表达式，布尔条件转为 `TRUE/FALSE`，小批量删除改为 CTE 删除模式；
- 修复 MyBatis 注解 SQL 中比较符的 PostgreSQL 实测问题：动态 `<script>` SQL 保留 XML 转义，普通更新语句通过显式 `<script>` 包装让 `&lt;` 在执行前还原为真实比较符；
- `docker-compose.application.yml` 对 `data-sync` 显式覆盖公共迁移期 MySQL datasource，改依赖 PostgreSQL、Redis、Kafka、Nacos、datasource-management 与 task-management；
- 新增 gated 真实 PostgreSQL 集成测试，覆盖 Flyway V1、10 张表存在性、identity 主键回填、BOOLEAN 映射、MyBatis-Plus 分页、执行器租约、恢复计划状态推进、receipt outbox 和 CTE 幂等清理。

该阶段完成后，`data-sync` 已具备新安装和当前开发环境直接使用 PostgreSQL 的代码路径。下一批应补 `data-sync` MySQL 到 PostgreSQL 存量迁移脚手架，迁移对象限定为上面的 10 张 `data_sync_*` 表；`task_data_sync_*` 与 `agent_memory_*` 继续登记为延期迁移对象，不能被临时塞进 data-sync 或 datasource-management。

## 2026-07-03 更新：datasource-management 存量迁移脚手架

`datasource-management` 已在 PostgreSQL 代码路径迁移之后补齐存量数据迁移入口：

- 新增 `scripts/datasource-management-mysql-to-postgresql.py`，支持 `plan/export/import/verify/all` 五种模式；
- 默认 `plan` 只读，`import/all` 必须显式传入 `--apply`；
- 默认拒绝导入到非空 PostgreSQL 目标表，避免 seed/test data 与 MySQL 历史事实混成不可解释状态；
- 迁移 datasource-management 当前代码真实使用的 14 张控制面表，不迁移 `data_sync_*`、`task_data_sync_*`、`agent_memory_*`；
- 导出 JSONL 与低敏 `manifest.json`，PostgreSQL 导入使用 `COPY FROM STDIN`；
- 保留 MySQL 原始主键 ID，并在导入后按最大 ID 校正 PostgreSQL identity sequence；
- 对账使用行数与稳定 SHA-256 摘要，摘要覆盖凭据和配置字段但不输出明文值；
- manifest 只记录敏感字段名、行数、checksum 和延期迁移表，不保存 JDBC URL、用户名、密码、SQL 预览、token、prompt、模型输出或客户数据样本；
- 新增 `docs/datasource-management-postgresql-data-migration.md`，说明停写、备份、导出、导入、对账、只读观察、目标非空保护和敏感导出目录保管要求。

该阶段完成后，`datasource-management` 的商业迁移闭环从“新安装可运行”推进到“具备可审计存量搬迁脚手架”。后续已进入 `data-sync` PostgreSQL 迁移，重点处理 `data_sync_*` 独立 schema、执行状态、恢复计划、回放补数、错误样本、worker receipt 和 task 桥接边界。`agent_memory_*` 继续登记为阶段 3 `ai_memory` 迁移对象，不能迁入 datasource-management。

## 决策

DataSmart 的目标持久化数据库调整为 PostgreSQL。MySQL 从目标架构降级为迁移期兼容数据库，所有业务微服务、
Agent 长期记忆和未来 LangGraph durable state 最终都应使用 PostgreSQL；迁移完成后删除 MySQL 运行依赖。

这不是一次性字符串替换。当前仓库已经存在大量 MySQL DDL、JDBC 配置、初始化脚本、运维脚本和本地 E2E 假设，
如果直接切换连接地址，最常见的结果是服务启动失败、索引语义变化、时间字段行为漂移和自增主键异常。
因此迁移以“单服务可回滚切换”为单位，每个批次都必须完成 schema、数据、代码、测试、监控和回滚闭环。

## 目标分工

| 能力 | 目标存储 | 设计说明 |
|---|---|---|
| Java 业务事实 | PostgreSQL 独立 schema | 每个微服务只访问自己的 schema，禁止跨服务直接 JOIN |
| Agent 长期记忆与用户画像 | PostgreSQL `ai_memory` | 关系字段、JSONB、全文检索和向量放在同一事务边界 |
| 语义向量 | pgvector | 使用 HNSW/IVFFlat，逐步替代 Chroma |
| LangGraph 长期 checkpoint | PostgreSQL | 后续接官方 Postgres checkpointer，保存 super-step 状态 |
| 会话、锁、限流、短期 checkpoint | Redis | 继续承担低延迟、带 TTL 的短生命周期状态 |
| 血缘与知识图谱 | Neo4j | 不因数据库迁移改变图谱职责 |
| 大型文件和产物 | MinIO | 不把报告、脚本正文和大对象塞入 PostgreSQL |

## 迁移护栏

从本决策生效起：

1. 新增 DDL 优先编写 PostgreSQL 版本，不得新增 `AUTO_INCREMENT`、`TINYINT(1)`、`ENGINE=InnoDB`、
   `ON UPDATE CURRENT_TIMESTAMP`、反引号标识符等 MySQL 专属语法。
2. 持久化代码不得依赖 MySQL 方言函数；分页、JSON、时间、upsert 和批量写入必须显式验证 PostgreSQL 行为。
3. 迁移期间禁止业务双写作为长期方案。推荐停写窗口迁移，或使用可审计 CDC/双读校验后切换。
4. 每个服务必须有独立 schema、迁移版本、连接配置、数据校验报告和回滚点。
5. 数据迁移前必须验证行数、主键范围、唯一约束、外键、空值、时间时区、JSON 可解析性和关键业务聚合结果。
6. MySQL 不得在所有服务迁移完成前删除；但也不得因为兼容期存在而继续扩展其功能范围。

## 分阶段执行

### 阶段 0：基础设施与冻结

- 引入 PostgreSQL 17 + pgvector 0.8.3 Compose 服务。选择 17 是为了与 Spring Boot 3.5.11 当前管理的
  Flyway 正式验证范围对齐，避免使用 PostgreSQL 18 时携带“尚未测试支持”警告。
- 建立服务级 schema：`permission_admin`、`task_management`、`datasource_management`、`data_sync`、
  `data_quality`、`agent_runtime`、`observability`、`ai_memory`。
- 父 POM 固定 pgJDBC 版本，模块完成迁移时再显式引入驱动。
- 冻结新的 MySQL 专属 DDL。

### 阶段 1：低耦合服务试点

推荐先迁移 `observability`，再迁移 `data-quality`：

- 两者业务写入边界相对清晰，出现问题不会首先破坏任务调度主链；
- 可以先验证 MyBatis-Plus、分页、时间字段、JSON、索引和 Actuator 健康检查；
- 试点形成可复用的 PostgreSQL `application.yml`、迁移脚本和 E2E 模板。

### 阶段 2：核心业务服务

按 `permission-admin -> datasource-management -> data-sync -> task-management` 推进：

- permission-admin 先迁移，确保租户、角色、审批和数据范围事实稳定；
- datasource-management 与 data-sync 随后迁移，验证连接配置、模板和执行事实；
- task-management 最后迁移核心业务服务，因为它承载调度、重试、outbox 和恢复主链。

### 阶段 3：Agent 与记忆

- 迁移 `agent-runtime` 控制面表、outbox、worker receipt、checkpoint 和审计索引；
- 为 Python Runtime 增加 PostgreSQL memory store 与 pgvector adapter；
- 接入 LangGraph Postgres checkpointer；
- 完成召回率、过滤正确性、checkpoint 恢复、幂等重放和容量压测后下线 Chroma。

### 阶段 4：基础组件与 MySQL 下线

- 评估 Nacos PostgreSQL 数据源插件或替代部署模式，迁移 Nacos 内部持久化；
- Keycloak 使用 PostgreSQL 正式数据库，不再使用开发内置库；
- 停止 MySQL 写入，执行最终增量同步和只读观察；
- 删除 MySQL 驱动、Compose 服务、数据卷、迁移脚本入口和 E2E 假设。

## 单服务验收门禁

每个微服务只有同时满足以下条件，才能标记为“已迁移”：

- PostgreSQL DDL 可在空库幂等执行；
- MySQL 历史数据已转换并通过校验；
- 服务默认 URL 为 PostgreSQL，并只把 PostgreSQL 作为平台业务事实库；如果服务本身承担外部连接器职责
  （例如 datasource-management 需要连接客户侧 MySQL），可保留对应外部连接器驱动，但必须在 POM 和文档中明确它不用于平台库；
- 单元测试、模块测试、真实 PostgreSQL 集成测试通过；
- 列表、分页、状态流转、幂等、并发更新和 outbox 场景通过；
- Prometheus、日志和健康检查可以区分数据库连接失败、连接池耗尽与慢查询；
- 已执行备份恢复演练，并保留可操作的回滚方案。

## 当前状态

阶段 0 已完成：目标架构、PostgreSQL/pgvector Compose、服务 schema 和 pgJDBC 版本基线已经建立。
本地真实容器验证已确认 PostgreSQL 17 健康、vector 0.8.3 可用、8 个服务 schema 初始化成功。

阶段 1 的两个代码路径试点已经完成，阶段 2 的前三个核心服务也已完成代码路径切换：

- `observability` 已移除 MySQL 驱动，切换到 pgJDBC、PostgreSQL `observability` schema 和 Flyway V1；
- `data-quality` 已把规则、执行、报告、异常四张业务表转换为 PostgreSQL V1，切换 pgJDBC、
  MyBatis-Plus PostgreSQL 分页方言、独立 schema 与 Compose 依赖；
- data-quality 真实 PostgreSQL 17 集成测试已覆盖 identity 主键回填、规则分页、最大执行序号、
  异常动态聚合和测试数据显式清理；
- 新增 [data-quality MySQL 到 PostgreSQL 存量数据迁移说明](data-quality-postgresql-data-migration.md)
  与 `scripts/data-quality-mysql-to-postgresql.py`，用于导出、导入、对账和 identity sequence 校正；
- `permission-admin` 已完成 PostgreSQL `permission_admin` schema、pgJDBC、Flyway V1、
  MyBatis-Plus PostgreSQL 分页方言、outbox PostgreSQL 时间表达式和 Compose 覆盖配置；
- permission-admin 真实 PostgreSQL 17 集成测试已覆盖 Flyway V1、8 张权限中心自有表、默认角色种子、
  Page 分页、审计插入、outbox PENDING/SENDING/FAILED/IGNORED 状态推进和测试数据显式清理；
- 新增 [permission-admin MySQL 到 PostgreSQL 存量数据迁移说明](permission-admin-postgresql-data-migration.md)
  与 `scripts/permission-admin-mysql-to-postgresql.py`，用于迁移权限中心 8 张自有表、执行空目标保护、
  COPY 导入、行数与 SHA-256 摘要对账、identity sequence 校正，并把旧 MySQL 中混放的 `agent_memory_*`
  表记录为 `deferredTables`，明确后续迁入 `ai_memory` 而不是 `permission_admin`；
- `datasource-management` 已完成 PostgreSQL `datasource_management` schema、pgJDBC、Flyway V1、
  MyBatis-Plus PostgreSQL 分页方言和 Compose 覆盖配置；
- datasource-management PostgreSQL V1 覆盖当前代码真实使用的 14 张控制面表：
  `datasource_config`、`datasource_readonly_sql_execution_audit`、`sync_template`、`sync_task`、
  `sync_agent_command_receipt`、`sync_execution`、`sync_checkpoint`、`sync_audit_record`、
  `sync_permission_policy_binding`、`sync_permission_policy_change_request`、
  `sync_permission_approval_delegate_rule`、`sync_governance_alert`、`sync_alert_delivery_record`、
  `sync_permission_governance_notification`；
- datasource-management 真实 PostgreSQL 17 集成测试已覆盖 Flyway V1、14 张表存在性、identity 主键回填、
  BOOLEAN 字段映射、MyBatis-Plus Page 分页、数据源登记、同步模板、同步任务、执行记录、检查点、
  只读 SQL 审计和 Agent 命令 receipt 的低敏插入与显式清理；
- datasource-management 的 `mysql-connector-j` 被有意保留为外部客户 MySQL 数据源连接器驱动，
  不再用于 Spring Boot 平台业务 datasource；后续不能把它误判为平台业务库尚未迁移。
- `data-sync` 已完成 PostgreSQL `data_sync` schema、pgJDBC、Flyway V1、MyBatis-Plus PostgreSQL 分页方言和 Compose 覆盖配置；
- data-sync PostgreSQL V1 覆盖当前代码真实使用的 10 张控制面表：`data_sync_template`、`data_sync_task`、
  `data_sync_execution`、`data_sync_callback_idempotency`、`data_sync_task_management_receipt_outbox`、
  `data_sync_checkpoint`、`data_sync_execution_recovery_plan`、`data_sync_error_sample`、
  `data_sync_incident_record`、`data_sync_audit_record`；
- data-sync 真实 PostgreSQL 17 集成测试已覆盖 Flyway V1、10 张表存在性、identity 主键回填、BOOLEAN 字段映射、
  MyBatis-Plus Page 分页、execution 租约认领与心跳、恢复计划状态推进、receipt outbox 投递状态和 CTE 幂等清理；
- `observability`、`data-quality`、`permission-admin`、`data-sync` 四个服务均不再从应用层依赖 MySQL 驱动。

这里的“代码路径完成”只代表新安装和当前开发环境可以直接使用 PostgreSQL。存量客户环境如果已经在
MySQL 中产生 data-quality、permission-admin、datasource-management 或 data-sync 业务数据，仍必须完成停写/导出、类型转换、identity
sequence 校正、行数与业务聚合对账、只读观察和回滚点保留，才能按上面的单服务验收门禁标记为商业迁移完成。

下一批应补 `data-sync` 的 MySQL 到 PostgreSQL 存量迁移脚手架。该脚手架必须限定迁移 10 张 `data_sync_*`
控制面表，并重点处理执行状态、恢复计划、错误样本、checkpoint、receipt outbox 与幂等记录的顺序和对账；
`task_data_sync_*` 暂随 task-management/data-sync 边界确认，`agent_memory_*` 已登记为阶段 3 的 `ai_memory`
迁移对象，不能在 data-sync、datasource-management、权限中心或 MySQL 初始化脚本中继续扩展。
