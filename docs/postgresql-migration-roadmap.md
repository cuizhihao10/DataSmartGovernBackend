# MySQL 到 PostgreSQL 渐进迁移路线

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

- 引入 PostgreSQL 18 + pgvector 0.8.3 Compose 服务。
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
- 服务只装配 pgJDBC，默认 URL 为 PostgreSQL；
- 单元测试、模块测试、真实 PostgreSQL 集成测试通过；
- 列表、分页、状态流转、幂等、并发更新和 outbox 场景通过；
- Prometheus、日志和健康检查可以区分数据库连接失败、连接池耗尽与慢查询；
- 已执行备份恢复演练，并保留可操作的回滚方案。

## 当前状态

阶段 0 已开始：目标架构、PostgreSQL/pgvector Compose、服务 schema 和 pgJDBC 版本基线已经建立。
本地真实容器验证已确认 PostgreSQL 18 健康、vector 0.8.3 可用、8 个服务 schema 初始化成功。
当前尚无 Java 业务服务完成切换，MySQL 仍承担现有运行流量，不能把“PostgreSQL 容器已启动”误认为迁移完成。
