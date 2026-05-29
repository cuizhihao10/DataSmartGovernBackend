# DataSmart Govern 全平台产品能力蓝图与模块边界规划

> 本文档用于纠正项目推进过程中过度集中于 `datasource-management` 的问题。
> 后续所有功能实现都应先对照本文档判断模块归属、产品场景、性能要求和未来迁移方向，再进入代码实现。

## 1. 为什么需要这份规划

DataSmart Govern 的目标不是一个单模块数据同步工具，而是一个面向企业的数据治理平台。

当前仓库已经包含 `gateway`、`task-management`、`datasource-management`、`data-quality`、`observability` 等微服务模块，产品规划中还包括 `permission-admin`、`data-asset`、`compliance-masking`、`agent-runtime`、`python-ai-services` 等后续能力。

过去一段实现中，`datasource-management` 承担了过多“临时平台能力”，例如本地权限策略、审批通知、治理告警 outbox。这些能力短期有助于打通数据同步控制面，但如果长期放在该模块内，会导致三个问题：

- `datasource-management` 边界膨胀，逐渐承担权限中心、通知中心、告警中心等职责。
- 其他模块建设被延后，平台整体看起来不像完整商业产品。
- 后续为了迁移到正确模块，容易反复重构，影响项目持续演进。

因此后续推进必须遵循“平台级能力先定边界，领域模块只做本领域事实”的原则。

## 2. 产品级模块地图

| 模块 | 产品定位 | 当前重点 | 不应长期承担的职责 |
| --- | --- | --- | --- |
| `gateway` | 统一入口、认证鉴权、路由、限流、会话、跨服务上下文传播 | JWT/IdP 接入、租户上下文、路由策略、限流、审计追踪头 | 业务审批、任务状态机、数据质量规则执行 |
| `permission-admin` | 统一权限、角色、菜单、路由、数据范围、审批策略中心 | 角色模型、权限矩阵、菜单与按钮权限、审批策略、权限变更审计 | 具体数据同步逻辑、质量检测执行、AI 推理 |
| `task-management` | 全平台任务编排与执行生命周期中心 | 任务模板、调度、队列、重试、暂停、取消、回放、执行记录 | 某个领域的业务规则细节，例如字段映射或质量规则算法 |
| `datasource-management` | 数据源、连接器、元数据、同步模板和同步控制面 | 连接器能力矩阵、连接测试、元数据发现、同步模式约束、同步任务领域配置 | 统一权限中心、统一通知中心、统一告警中心 |
| `data-quality` | 数据质量规则、检测执行、异常分析、质量报告 | 规则管理、规则运行、质量评分、异常明细、修复建议、报告导出 | 数据源连接管理、全平台任务调度、权限中心 |
| `observability` | 指标、日志、追踪、告警、运维看板 | Prometheus/Grafana、告警规则、服务健康、任务运行指标、队列与 outbox 指标 | 具体业务审批流程、领域数据写入规则 |
| `data-asset` | 数据资产目录、标签、血缘、搜索、资产关系 | 资产注册、元数据入湖、业务标签、血缘图谱、影响分析 | 同步执行、质量检测算法、权限审批流 |
| `compliance-masking` | 敏感数据识别、脱敏策略、合规审计 | 敏感字段识别、脱敏模板、访问审批、合规报告 | 通用任务编排、连接器注册、告警投递中心 |
| `agent-runtime` | 多智能体编排、工具调用、任务协作工作区 | Agent 生命周期、技能插件、工作区隔离、任务上下文 | 业务事实最终存储、权限最终裁决 |
| `python-ai-services` | AI 算法、模型推理、RAG、规则生成、异常解释 | 模型提供商抽象、Embedding/Rerank、质量规则生成、文本/图像理解 | Java 业务事务、RBAC、租户权限判断 |
| `docker` / 部署 | 本地开发与后续生产部署脚手架 | Compose 开发环境、环境变量、初始化脚本、后续 Helm/K8s 路线 | 业务逻辑、权限策略、运行时状态机 |

## 3. 当前过渡能力的归宿

`datasource-management` 中已经实现的一些能力不能简单删除，它们应被视为“领域内过渡实现”，后续逐步迁移或对接到平台级模块。

| 当前能力 | 当前所在模块 | 短期价值 | 长期归宿 |
| --- | --- | --- | --- |
| 本地角色、菜单、路由绑定 | `datasource-management` | 让数据同步控制面先具备可解释的权限边界 | `permission-admin` 统一维护，领域模块只消费权限判定结果 |
| 权限变更申请、审批、委托 | `datasource-management` | 支撑同步领域高风险权限变更 | `permission-admin` 形成通用审批策略和权限变更中心 |
| 权限通知、提醒、升级 | `datasource-management` | 打通审批 SLA 和站内通知雏形 | 统一通知中心或 `permission-admin` 通知域，领域模块发事件 |
| 治理告警 outbox | `datasource-management` | 同步领域告警可靠投递 | `observability` 汇聚指标和告警，领域模块保留本领域告警事件 |
| 队列健康和任务租约 | `datasource-management` | 支撑同步任务执行控制 | `task-management` 统一沉淀任务生命周期、队列和执行器模型 |

后续实现时，如果某个能力明显是平台级能力，应优先设计平台模块接口；只有在没有对应模块且当前业务闭环必须推进时，才允许在领域模块内做临时实现，并在注释和文档中写清楚未来迁移方向。

## 4. 需求与场景扩展原则

每个功能开工前必须回答以下问题。

### 4.0 代码解耦与文件规模约束

后续实现不能再默认把业务流程持续堆进单个 `ServiceImpl` 或 Controller。当前仓库已经出现多个超过 1000 行的实现类，这会导致职责边界模糊、修改风险扩大、学习成本升高，也不利于把项目推进到商业化生产项目。

后续新增或重构代码时遵循以下约束：

- 单个 Java 文件尽量控制在 500 行以内；超过 500 行时必须优先评估是否可以拆分职责。
- `ServiceImpl` 只承载事务边界、状态机编排和跨组件协调，不应长期承载视图组装、策略解释、SQL 构造、外部调用协议、审计详情拼接等所有细节。
- 可拆分为 `assembler`、`policy`、`validator`、`calculator`、`coordinator`、`executor`、`client`、`publisher`、`repository/helper` 等小型协作者。
- Controller 只定义 API 契约、参数接收和响应包装，不承载复杂业务判断。
- 同一类中如果同时出现“状态变更、列表查询、风险解释、批量动作、外部调用、审计构造”，应拆分为多个领域协作者。
- 拆分不是为了制造文件数量，而是为了让每个类只回答一个清晰问题，并让后续功能扩展不再反复推翻主类。

优先整改顺序：

- 第一优先级：超过 1000 行的 `ServiceImpl`，尤其是 `SyncTaskServiceImpl`、`TaskServiceImpl`、`DataSourceManagementServiceImpl`、`DataQualityServiceImpl`。
- 第二优先级：超过 500 行的 Controller 或平台服务，例如 `DataQualityController`、`PermissionAdminServiceImpl`。
- 第三优先级：把可配置策略从硬编码常量迁移到 properties 或策略服务，例如队列 aging 阈值、退避阈值、SLA 阈值。

阶段性目标不是一次性把所有文件都压到 500 行以下，而是在每次新增功能前先拆出最自然的协作者，逐步形成稳定的模块内分层。

### 4.1 场景问题

- 这个功能只服务一个演示流程，还是服务一组企业场景？
- 是否需要覆盖多租户、多角色、多数据源、多任务类型？
- 是否存在审批、审计、导出、回放、恢复、归档等完整生命周期动作？
- 失败后用户如何感知，管理员如何介入，系统如何恢复？

### 4.2 模块归属问题

- 这是平台级能力，还是领域级能力？
- 如果放在当前模块，未来是否会迁移到独立模块？
- 是否需要通过 Kafka 事件、REST API、gRPC 或数据库事实表与其他模块集成？
- 是否会造成某个模块职责膨胀？

### 4.3 性能与可靠性问题

- 目标并发是多少？
- 单次扫描或批处理是否有限流？
- 是否支持重试、幂等、超时、死信、租约恢复？
- 高峰期是否会造成队列积压？
- 是否需要指标、日志、追踪和告警？

### 4.4 安全与合规问题

- 是否涉及敏感数据、连接密钥、样本预览、导出文件？
- 是否需要脱敏、审批、审计或数据范围限制？
- 是否存在越权、跨租户访问、权限提升风险？
- 服务账号和人类账号是否区分？

## 5. 分阶段路线图

### Phase 0：平台边界与基础约定先行

目标是先建立稳定边界，降低后续返工概率。

优先落地：

- 统一 API 响应、错误码、分页、校验和异常处理约定。
- 统一租户上下文模型，明确 `tenantId` 如何从 gateway 传递到各服务。
- 统一审计事件模型，明确谁在什么时间对什么资源做了什么。
- 统一服务间调用身份，区分用户请求、服务账号、Agent 请求。
- 统一配置分层，区分本地开发配置、测试配置和生产配置。
- 明确数据库迁移策略，避免长期依赖单个初始化 SQL 文件。

### Phase 1：入口、权限与任务底座

目标是让平台具备商业产品的控制面骨架。

优先落地：

- `gateway`：认证、租户上下文、路由策略、限流、traceId、用户身份透传。
- `permission-admin`：角色、菜单、路由、按钮、数据范围、审批策略、权限变更审计。
- `task-management`：全平台任务生命周期、调度、队列、重试、暂停、取消、回放、执行记录。

原因：这些能力会被数据源、质量、资产、合规、AI 任务共同依赖，如果继续后置，会导致每个领域模块重复造本地版本。

### Phase 2：核心业务域产品化

目标是让平台从“控制面骨架”进入“可运营业务产品”。

优先落地：

- `datasource-management`：连接器能力矩阵、连接测试、元数据发现、同步模式约束、同步模板。
- `data-quality`：规则配置、规则执行、异常明细、质量评分、修复建议、质量报告。
- `data-asset`：资产目录、标签、血缘、搜索、资产影响分析。
- `compliance-masking`：敏感识别、脱敏策略、访问审批、合规报告。

### Phase 3：可观测与生产运维

目标是让系统可监控、可排障、可容量规划。

优先落地：

- `observability`：统一指标、日志、链路追踪、告警规则、Grafana 看板。
- 任务队列指标、执行器指标、连接器健康指标、质量规则执行指标。
- 告警分级、告警抑制、通知通道、死信处理、运维事件记录。
- 容量指标：队列积压、任务吞吐、执行延迟、错误率、重试率、租约恢复次数。

### Phase 4：AI 与智能体能力

目标是让 AI 成为治理平台的增强能力，而不是替代业务系统。

优先落地：

- `agent-runtime`：Agent 工作区、工具调用、任务上下文、权限隔离。
- `python-ai-services`：模型提供商抽象、Embedding/Rerank、规则生成、异常解释、报告摘要。
- `agent-runtime` 与 `task-management`：优先采用“草稿/审批/真实创建”三段式任务治理，不允许 Agent 直接把高风险任务写入可调度队列。
- `agent-runtime` 与 `data-quality`：形成 `datasource.metadata.read -> quality.rule.suggest -> task.create.draft` 的安全工具链，先让 Agent 产出可审阅治理计划，再逐步接入真实任务创建。

**2026-05-24 落地进展：**

- `agent-runtime` 已新增 `task.create.draft` 本地草稿工具，能够读取同一 Run 内最近一次 `quality.rule.suggest` 成功输出，并生成 `DATA_QUALITY_SCAN` 或 `MANUAL_REVIEW` 任务草稿。
- 当前版本不会调用 `task-management /tasks`，也不会创建 `PENDING` 任务，因此不会进入执行器认领队列，避免模型绕过审批消耗生产资源。
- 草稿输出包含 `summary`、`taskDraft`、`approval`、`riskControls` 和 `recommendedActions`，为前端确认页、审批单和后续真实创建接口预留结构化契约。
- 下一阶段建议在 `task-management` 中补正式 DRAFT/PENDING_APPROVAL 状态或独立 task_draft 表，再把 Agent 草稿接入持久化审批流。

**2026-05-24 追加落地进展：**

- `agent-runtime` 已新增显式 `outputRef/jsonPath` 工具输出引用协议，后续工具可以通过 `toolCode + auditId + jsonPath` 精确读取前序工具输出。
- `quality.rule.suggest` 已支持 `metadataRef/datasourceMetadataRef/outputRef`，避免多次读取元数据时误用最近一次输出。
- `task.create.draft` 已支持 `suggestionRef/qualityRuleSuggestionRef/outputRef`，避免多次生成规则建议后任务草稿引用错规则集。
- 下一阶段前端审批页和 Python Runtime 应展示并生成这些引用，让“Agent 为什么基于某次输出生成当前任务草稿”可解释、可复盘。

**2026-05-24 task-management 草稿落地进展：**

- `task-management` 已新增 `task_draft` 持久化表和 `/task-drafts` API，支持 DRAFT、PENDING_APPROVAL、APPROVED、REJECTED、CONVERTED 状态流转。
- 草稿转换真实任务时必须先 APPROVED，并复用现有任务创建生命周期，确保真实任务仍以受控 PENDING 进入队列。
- gateway 已新增 `/api/task/task-drafts/**` 的 `TASK_DRAFT` 授权元数据，permission-admin 已新增 `TASK_DRAFT` 资源类型。
- 下一步应把 agent-runtime 的 `task.create.draft` 输出接入该持久化能力，并补转换幂等、审批策略实例和任务类型参数 schema。
- 模型栈保持可替换，不把具体模型家族写死进业务模块。
- AI 输出必须落回业务模块形成可审计事实，例如规则草案、质量建议、资产标签建议。

## 6. 后续 8 个推荐实施增量

### 增量 1：全局平台契约 v1

范围：

- 统一 API、错误码、审计事件、租户上下文、traceId 规范。
- 明确各模块如何接收用户身份、租户、角色、服务账号。

当前落地状态：

- 已新增 `platform-common` Maven 模块，作为跨微服务共享契约模块，而不是某个业务域的工具包。
- 已提供统一 API 响应、分页响应、平台错误码、业务异常、请求上下文、上下文 Header、操作者类型、审计事件和审计结果等基础模型。
- 已让 `gateway`、`task-management`、`datasource-management`、`data-quality`、`observability` 依赖 `platform-common`，后续迁移 Controller、异常处理、审计投递和上下文传播时可以逐步替换，不需要一次性大重构。
- 本阶段暂不删除各模块已有的 `ApiResponse` 或本地异常处理，避免把“平台契约建立”变成高风险横向重构；后续每推进一个模块时再按路由、权限、状态流转和审计要求分批接入。

价值：

- 后续模块不会各自定义一套请求上下文和错误结构。
- 为 gateway、permission-admin、observability 打基础。

### 增量 2：gateway 入口治理 v1

范围：

- 请求身份解析、租户上下文传播、基础限流、路由策略说明、traceId 注入。

当前落地状态：

- 已增强 `GatewayContractFilter`，开始使用 `platform-common` 中的 `PlatformContextHeaders` 作为统一 Header 命名来源。
- 已新增 `GatewayContextProperties`，将“是否信任外部上下文 Header”“默认请求来源”“旧版 X-Request-Id 兼容”等运行期策略从过滤器流程中拆出。
- 网关现在会统一生成或透传 `X-DataSmart-Trace-Id`，写入 `X-DataSmart-Source-Service`、`X-DataSmart-Request-Source`、路由前缀和原始路径，并默认清理外部伪造的租户、操作者、角色和工作区 Header。
- 当前仍未实现真实 JWT/IdP 身份解析；这一步只完成入口上下文契约和安全边界基线，后续应继续接入认证结果并把可信身份写入平台 Header。
- 已新增 `GatewayAuthorizationFilter`、`GatewayAuthorizationProperties` 和 `PermissionAdminDecisionClient`，让 gateway 具备调用 permission-admin `/permissions/evaluate` 做路由级授权判定的能力。
- 网关授权当前默认 `enabled=false`、`shadow-mode=true`、`fail-open-on-error=true`，目的是先保留本地开发可用性；当认证中心、权限中心和服务启动编排稳定后，应切换为启用授权、关闭影子模式，并在生产环境使用 fail-closed。
- 当前 gateway 通过 HTTP 契约调用 permission-admin，没有在 Maven 层依赖 permission-admin 模块，避免微服务之间出现编译期强耦合。

价值：

- 让所有模块从一开始就处在统一入口治理下。

### 增量 3：permission-admin 模块骨架

范围：

- 角色、菜单、路由、数据范围、权限策略、审批策略的实体和基础 API。
- 从 `datasource-management` 的本地权限模型提炼可迁移语义。

当前落地状态：

- 已新增 `permission-admin` Maven 模块，并加入父工程 reactor，服务端口为 `8085`。
- 已建立角色、菜单、角色菜单绑定、路由策略、数据范围策略、权限审计记录等基础领域模型。
- 已提供角色列表、角色菜单、路由策略、数据范围、权限矩阵和权限判定 `evaluate` API，作为后续 gateway 与业务模块统一授权的基础入口。
- 已在网关中新增 `/api/permission/**` 路由和契约说明；由于当前全局路由 RewritePath 尚未统一完成，permission-admin 暂时同时支持 `/permissions/**` 与 `/api/permission/**` 两组路径。
- 已新增 `docker/mysql/init/permission-admin.sql`，模块化维护权限中心表结构和平台默认角色、菜单、路由策略、数据范围种子数据。
- 当前只完成权限中心骨架与基础判定；审批流、权限变更 API、Redis 缓存、Kafka 权限变更事件、JWT claim 映射仍是后续工作。

价值：

- 阻止权限治理继续在领域模块内扩散。

### 增量 4：task-management 全平台任务核心

范围：

- 通用任务状态机、执行记录、调度计划、队列、重试、取消、暂停、回放、任务事件。

价值：

- 数据同步、质量检测、资产扫描、合规脱敏、AI 分析都可以复用同一任务底座。

### 增量 5：datasource-management 回归领域边界

范围：

- 连接器能力矩阵、连接器状态、元数据发现、同步模式校验、字段映射。

价值：

- 让它成为专业的数据接入与同步控制面，而不是平台权限/通知/告警中心。

### 增量 6：data-quality 功能闭环

范围：

- 规则执行、质量报告、异常明细、评分模型、修复建议、执行任务对接。

价值：

- 数据治理产品不能只有接入和同步，质量能力是核心卖点之一。

### 增量 7：observability 汇聚领域信号

范围：

- 汇聚任务、同步、质量、权限、告警、outbox 的指标和告警规则。

价值：

- 从“每个模块自己查状态”升级为“平台级运维视图”。

### 增量 8：资产与合规模块启动

范围：

- `data-asset` 建资产目录和血缘。
- `compliance-masking` 建敏感识别和脱敏策略。

价值：

- 让产品从数据同步平台升级为数据治理平台。

## 7. 非功能目标草案

这些目标先作为方向性约束，后续可以根据真实压测结果调整。

| 能力 | 初始目标 |
| --- | --- |
| API 权限判定 | 单次本地判定 P95 小于 20ms；远程权限中心判定应支持缓存 |
| 任务创建 | 单实例每分钟支持 500 条任务创建请求的后台写入能力 |
| 任务队列扫描 | 单轮扫描限制批量，默认 50 到 100 条，避免全表扫描 |
| 同步任务并发 | 支持全局、租户、连接器、数据源四层并发限制 |
| 告警投递 | 必须支持重试、死信、租约恢复、投递审计 |
| 审计日志 | 高风险操作必须 100% 留痕，包括操作者、租户、资源、动作、结果 |
| 多租户隔离 | 核心业务表、缓存 key、对象存储路径、指标标签都应携带租户语义 |
| 样本预览 | 必须限制行数、字段数和超时，后续接入脱敏策略 |
| 配置管理 | 本地开发配置和生产配置必须逐步分离 |
| 可观测性 | 每个核心任务链路必须能关联 traceId、taskId、tenantId |

## 8. 防止反复重构的执行规则

后续每次实现前必须先写清楚以下内容：

- 本轮功能属于哪个模块。
- 为什么不放到其他模块。
- 当前是否是临时过渡实现。
- 如果是过渡实现，未来迁移到哪个模块。
- 本轮至少覆盖哪些角色、状态、失败场景和性能限制。
- 是否需要更新 `current-repo-state.md`。

实现时遵循：

- 先扩展接口和领域模型，再补具体实现。
- 平台级能力不要塞进领域模块。
- 领域模块可以产生领域事件，但不要承担全平台聚合职责。
- 注释必须说明业务目的、状态流转、权限边界、性能限制和未来扩展点。
- 不因为短期方便引入不可替换的模型、向量库、连接器或权限实现。

## 9. 下一步建议

推荐下一轮从“增量 1：全局平台契约 v1”开始。

原因：

- 它不会继续加重 `datasource-management`。
- 它会为 gateway、permission-admin、task-management、observability 的后续实现提供共同语言。
- 它能减少后续每个模块重复定义 API 响应、错误码、审计、租户上下文和 traceId。

如果要直接进入代码，建议优先落地：

- `common` 或模块内统一 API/错误/审计契约的设计方案。
- gateway 的请求上下文传播约定。
- task-management 的通用任务事件契约。
- permission-admin 的模块骨架和数据模型草案。

## 10. 最近落地记录

### 2026-04-25：gateway 开发期可信身份注入基线

本次在 `gateway` 中新增了默认关闭的开发期身份注入能力，用于在真实 JWT/IdP 尚未完整接入前，受控模拟普通用户、租户管理员、平台管理员、服务账号、Agent 等不同操作者身份。

落地内容：

- 新增 `GatewayDevelopmentIdentityProperties`，通过 `datasmart.gateway.development-identity` 管理开发令牌解析策略、允许角色、默认 actorType、默认 workspaceId、错误处理方式等配置。
- 新增 `GatewayDevelopmentIdentityFilter`，过滤器顺序为 `-95`，位于 `GatewayContractFilter(-100)` 之后、`GatewayAuthorizationFilter(-90)` 之前，确保外部伪造 Header 先被清理，再由受控令牌写入可信平台上下文。
- 支持开发令牌格式 `dev:{tenantId}:{actorId}:{actorRole}[:actorType][:workspaceId]`，可从 `X-DataSmart-Dev-Identity` 或 `Authorization: Bearer dev:...` 读取。
- 格式错误时默认返回平台统一 `UNAUTHORIZED` 响应，避免错误令牌悄悄退化成默认匿名身份，导致权限排查出现误判。

设计边界：

- 这不是生产认证能力，不提供签名校验、过期时间、撤销、刷新、设备绑定等正式登录态能力。
- 生产方向仍然是接入 JWT/OAuth2/企业 IdP 或服务账号签名过滤器，但它们应输出同一组 `X-DataSmart-*` 平台上下文，从而保持下游模块稳定。
- 当前仍缺少权限决策缓存、JWT claim 映射、服务账号密钥治理、生产 fail-closed 策略、路由元数据授权映射和权限变更事件失效机制。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-05-05：代码解耦与 500 行文件规模约束启动

本次根据新的设计规范，开始把“单个实现类过长、职责耦合过高”的问题纳入持续整改范围。当前盘点显示，仓库中已经存在多个超过 1000 行的 `ServiceImpl`，这说明继续只扩功能会让核心类越来越难维护。

落地内容：

- 在本文档新增“代码解耦与文件规模约束”，明确 Java 文件尽量控制在 500 行以内。
- 规定 `ServiceImpl` 应聚焦事务边界、状态机编排和跨组件协调，不再无限承载视图组装、策略解释、外部调用、审计拼接等细节。
- 新增 `TaskQueueItemViewAssembler`，把任务运营队列视图组装、排队时长计算、租约剩余计算、风险等级和推荐动作解释从 `TaskServiceImpl` 拆出。
- `TaskServiceImpl.inspectQueueItems` 改为委托 `TaskQueueItemViewAssembler.toView`，主服务不再直接承担运营 DTO 解释职责。
- 新组装器保持 500 行以内，并用详细中文注释解释拆分目的、输入输出、风险规则和后续 SLA 扩展方向。

当前盘点：

- `SyncTaskServiceImpl` 约 1758 行，职责最重，后续应优先拆分状态机、执行器交互、审计记录、管理员动作。
- `TaskServiceImpl` 从约 1351 行降到约 1201 行，仍远超 500 行，需要继续拆执行器认领、租约恢复、管理员动作、队列统计。
- `DataSourceManagementServiceImpl` 约 1223 行，后续应拆连接测试、元数据发现、只读 SQL 执行、安全校验。
- `DataQualityServiceImpl` 约 1159 行，后续应拆规则生命周期、检测执行、任务调度集成、异常聚合。

设计价值：

- 从“补功能”转向“补功能同时维护结构质量”，避免商业化产品越做越难演进。
- 先用低风险拆分建立模板，再逐步处理更复杂的同步任务和数据质量模块。
- 保留学习型中文注释，让拆分后的每个小类仍能作为技术学习参考，而不是只把代码切碎。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-05-05：datasource-management 同步队列策略解耦

本次继续落实“降低耦合、单个 Java 文件尽量控制在 500 行以内”的规范，优先处理 `datasource-management` 中最大的 `SyncTaskServiceImpl`。由于同步任务服务同时承担状态流转、执行器租约、队列健康、治理告警、审计记录和运营说明生成，直接继续堆功能会让后续商业化扩展成本越来越高。

落地内容：

- 新增 `SyncQueuePolicySupport`，专门承载同步任务队列相关的纯策略、纯计算和运营解释逻辑。
- 从 `SyncTaskServiceImpl` 拆出排队老化阈值解析、巡检批量解析、全局/租户队列预警阈值解析。
- 从主服务拆出排队老化判断、排队秒数计算、队列压力等级判断、健康建议文案生成和老化任务 incidentNote 生成。
- `SyncTaskServiceImpl` 继续负责事务编排、状态更新、告警创建和审计写入，不再直接承载这些可复用的队列策略细节。
- 新支撑类保留详细中文注释，解释每个阈值、默认值、下限保护、压力等级和运营建议的产品含义，方便后续学习和继续扩展。

当前盘点：

- `SyncTaskServiceImpl` 当前约 1653 行，仍远高于 500 行，需要继续拆分执行器租约、状态机动作、告警编排、审计载荷构建和响应 DTO 组装。
- `SyncQueuePolicySupport` 当前约 220 行，保持在 500 行以内，职责边界清晰。
- 这次属于低风险结构治理：不改变接口契约、不改变数据库结构、不改变同步任务状态机，只移动纯计算与文案策略。

商业化后续选择：

- 队列阈值可以进一步升级为按租户套餐、任务优先级、执行器池、数据源类型或连接器成本动态配置。
- 队列压力等级可以从当前 HEALTHY/WATCH/SATURATED 三档扩展为结合 P95/P99 等历史指标的 SLA 风险模型。
- 运营建议文案未来可以改为结构化建议码，供前端国际化、告警中心、自动化恢复流程和工单系统统一消费。
- 下一步建议继续拆 `SyncTaskServiceImpl` 的执行器租约与状态动作，或者横向切到 `DataQualityServiceImpl`，避免继续只围绕一个模块优化。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-05-05T18:29:30+08:00`。

### 2026-05-05：datasource-management 同步任务服务批量解耦提速

本次根据“拆分效率不能过低”的反馈，调整为按职责块批量迁移，而不是每次只移动一组 100-200 行的小逻辑。目标是更快降低 `SyncTaskServiceImpl` 的耦合度，同时保持每个新类都低于 500 行、中文注释足够详细、编译可验证。

落地内容：

- 新增 `SyncTaskPermissionSupport`，统一承载任务创建、任务更新、普通任务操作、审批、执行器认领、执行器心跳、执行进度回写、执行结果回写、队列健康查看、队列老化巡检和管理员强制控制权限。
- 新增 `SyncQueueAlertSupport`，承载全局队列压力告警、租户队列压力告警、全局老化队列告警和单任务老化告警编排。
- 新增 `SyncQueueCapacitySupport`，承载执行器认领候选选择、队列入队容量保护、运行并发容量保护、租户公平调度、数据源并发限制和队列压力快照。
- `SyncTaskServiceImpl` 改为委托权限、告警、策略、容量四类支持组件，主服务继续保留状态机流转、执行记录、检查点、审计和核心事务边界。
- 本轮没有修改 API 契约、数据库结构和状态枚举，属于行为保持型的大规模结构治理。

规模变化：

- `SyncTaskServiceImpl` 从约 1653 行降到约 1036 行，本轮累计减少约 617 行。
- `SyncQueueCapacitySupport` 约 389 行，保持 500 行以内。
- `SyncTaskPermissionSupport` 约 291 行，保持 500 行以内。
- `SyncQueuePolicySupport` 约 220 行，保持 500 行以内。
- `SyncQueueAlertSupport` 约 166 行，保持 500 行以内。

设计价值：

- 权限守卫从主服务中独立后，后续对接 permission-admin、JWT claim、服务账号签名或数据范围策略时，不需要继续污染任务状态机。
- 容量治理从主服务中独立后，后续可以更自然地扩展多执行器池、租户套餐配额、数据源保护、优先级调度和高并发认领优化。
- 告警编排从主服务中独立后，后续接入 observability、工单、Webhook、邮件或 IM 通知时，不会把同步任务服务继续做成上帝类。
- 这一步证明后续可以按“职责块”批量拆分，效率应明显高于一次只抽一个小方法或小工具类。

当前风险与下一步：

- `SyncTaskServiceImpl` 仍约 1036 行，下一轮建议继续拆执行记录/检查点写入、任务生命周期状态动作、审计 payload 构造，目标再降 300-500 行。
- `DataSourceManagementServiceImpl`、`TaskServiceImpl`、`DataQualityServiceImpl` 仍都超过 1000 行，不能只盯着 datasource-management，一个阶段后应横向切到 data-quality 或 task-management。
- 当前容量策略仍是数据库扫描 + Java 排序，适合早期控制面；高并发商业化场景需要进一步考虑专用队列表、乐观锁抢占、Redis/ZSet、分片队列或独立调度器。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-05-05T18:45:46+08:00`。

### 2026-05-05：datasource-management 执行记录、检查点与审计支撑解耦

本次继续沿用“职责块批量拆分”节奏，处理 `SyncTaskServiceImpl` 中仍然集中的执行记录、检查点和审计工具逻辑。该类逻辑虽然看起来只是持久化细节，但在真实商业化数据同步平台中会持续扩展为断点续传、CDC 位点、审计中心、合规留痕和统一事件流，因此不适合继续停留在主服务中。

落地内容：

- 新增 `SyncExecutionPersistenceSupport`，承载执行记录列表查询、检查点列表查询、执行记录创建、执行记录归属校验、最近执行记录获取和检查点 upsert。
- 新增 `SyncAuditSupport`，承载同步任务审计记录写入、轻量 JSON payload 构造和统一文本截断。
- `SyncTaskServiceImpl` 的执行记录创建、检查点保存、执行记录查询、审计记录查询改为委托支撑组件。
- 主服务保留少量 `recordAudit/buildPayload/truncate` 委托方法，避免一次性修改所有业务调用点造成过大回归风险；后续可在下一轮继续直接替换调用点。
- 本轮仍然不改变 API、数据库结构、状态枚举和任务状态机语义。

规模变化：

- `SyncTaskServiceImpl` 从约 1036 行降到约 934 行。
- `SyncExecutionPersistenceSupport` 约 174 行，保持 500 行以内。
- `SyncAuditSupport` 约 127 行，保持 500 行以内。
- 当前 datasource-management 的同步任务支撑类均低于 500 行。

设计价值：

- 执行记录和检查点独立后，后续支持分片执行、CDC 位点、增量水位线、断点续传、失败回放时，不需要继续扩大主服务。
- 审计能力独立后，后续对接统一审计中心、合规中心、SIEM、安全脱敏、审计事件外送时，有了清晰扩展点。
- `SyncTaskServiceImpl` 现在主要剩余生命周期动作和事务编排，职责已经比最初清晰很多。

当前风险与下一步：

- `SyncTaskServiceImpl` 仍约 934 行，还没有达到 500 行目标，下一轮应继续拆任务生命周期动作协调器，或横向切到其他超大类。
- `DataSourceManagementServiceImpl` 约 1223 行、`TaskServiceImpl` 约 1201 行、`DataQualityServiceImpl` 约 1159 行，当前都比 `SyncTaskServiceImpl` 更大，下一阶段不应只继续优化同步任务服务。
- 检查点 upsert 当前使用 executionId + shardOrPartition 的简化语义，未来高并发分片执行应补唯一约束和乐观更新策略。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-05-05T18:52:31+08:00`。

### 2026-05-05：data-quality 执行报告与异常运营支撑解耦

本次根据“不要只围绕 datasource-management 优化”的原则，横向切到 `data-quality` 模块，开始处理 `DataQualityServiceImpl` 过大的问题。该服务原本同时承担规则生命周期、任务调度、执行回调、报告生成、异常明细查询、异常聚合和各种工具方法，职责过于集中。

落地内容：

- 新增 `QualityExecutionReportSupport`，集中承载质量检测 execution、report、anomaly detail 和 anomaly aggregation 能力线。
- 从 `DataQualityServiceImpl` 拆出运行中执行记录创建、执行记录获取、运行态校验、结果指标校验、成功/失败执行回写。
- 从主服务拆出质量报告生成、通过率计算、报告摘要构造、异常明细持久化、规则最近检测快照回写。
- 从主服务拆出报告列表、报告分页、报告异常明细列表、异常明细分页、异常聚合和规则执行记录列表查询。
- 主服务保留规则生命周期、规则目标建模、目标校验、扫描计划、任务调度等更靠近规则编排的职责。

规模变化：

- `DataQualityServiceImpl` 从约 1159 行降到约 816 行，本轮减少约 343 行。
- `QualityExecutionReportSupport` 约 385 行，保持 500 行以内。
- 当前最大超限文件变为 `DataSourceManagementServiceImpl` 约 1223 行、`TaskServiceImpl` 约 1201 行、`SyncTaskServiceImpl` 约 934 行、`DataQualityServiceImpl` 约 816 行。

设计价值：

- quality execution 和 quality report 的概念被明确拆开，符合商业化产品中“技术执行成功/失败”和“业务质量通过/失败”不能混淆的要求。
- 异常明细和异常聚合独立后，后续可以继续扩展异常运营台、质量大盘、异步导出、TopN 问题字段、AI 根因分析和整改工单。
- 报告生成逻辑独立后，人工执行、定时任务执行、批量回放和未来 API 触发都可以复用同一套判定标准，避免多入口结果不一致。

当前风险与下一步：

- `DataQualityServiceImpl` 仍约 816 行，后续可继续拆规则生命周期、目标字段建模、目标校验和任务调度 payload 构造。
- `QualityExecutionReportSupport` 当前使用逐条插入异常明细，未来大规模异常样本需要批量写入、分批事务、Kafka 异步写入或对象存储归档。
- `DataSourceManagementServiceImpl` 和 `TaskServiceImpl` 仍超过 1200 行，下一步建议优先处理 `DataSourceManagementServiceImpl` 的连接测试、元数据发现、只读 SQL 执行和安全校验。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-05-05T19:02:10+08:00`。

### 2026-04-30：task-management 运营队列接口接入 gateway/permission-admin 权限边界

本次没有继续单纯扩展 task-management 的队列接口，而是补齐“入口层授权”这一条商用产品必须具备的横向闭环。原因是 `/tasks/operations/**` 虽然属于 task-management，但它暴露的是队列健康、死信、延期、执行器租约等运营视角，如果仍然复用普通 `/api/task/**` 查看权限，普通用户或项目负责人可能误访问跨任务运行信息。

落地内容：

- gateway `route-metadata` 新增 `/api/task/operations/**`，并放在 `/api/task/**` 之前，确保更具体的运营语义先命中。
- gateway 默认授权元数据新增 `TASK_OPERATION`，即使 `application.yml` 缺少该段配置，也能保持正确的兜底语义。
- permission-admin 新增 `TASK_OPERATION` 资源类型，用于把普通任务资源和任务运营资源拆分开。
- MySQL 权限种子新增普通用户、项目负责人、服务账号对 `/api/task/operations/**` 的高优先级 `DENY` 策略。
- MySQL 权限种子新增运营人员、租户管理员对 `/api/task/operations/**` 的 `GET ALLOW` 策略，平台管理员继续通过 `/api/**` 全局策略访问。
- MySQL 数据范围种子新增 `TASK_OPERATION` 的租户级和平台级范围，便于后续把队列查询真正收敛到租户或平台视角。

设计价值：

- 把“用户任务列表”和“运营队列工作台”从权限语义上拆开，避免粗粒度任务查看权限越权覆盖运营接口。
- 让 gateway、permission-admin、task-management 三个模块围绕同一个运营队列能力形成闭环，而不是只在 task-management 内部堆功能。
- 为后续批量死信恢复、批量暂停、批量取消、队列导出等高风险操作预留单独策略入口。
- 服务账号被显式拒绝读取运营工作台，避免执行器身份被滥用为跨队列巡检入口。

当前边界：

- gateway 授权默认仍是 `enabled=false` 且 `shadow-mode=true`，真实拦截需要后续在联调环境开启。
- 当前仅为 `GET` 运营查看接口配置了显式允许；未来批量恢复、批量取消、导出等动作应继续拆成 `EXECUTE`、`FORCE_RETRY`、`FORCE_CANCEL`、`EXPORT` 等更细粒度策略。
- permission-admin 的数据范围表达式当前只随判定结果返回，尚未被 task-management 自动注入到查询条件中。
- 已有数据库不会自动获得新增种子策略，需要重新初始化或执行增量 SQL。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-29：task-management DEFERRED 延迟回队列与 data-quality 背压退避基线

本次补齐质量执行器并发护栏后的生产化行为：资源不足不再被误报成任务失败，而是通过 `DEFERRED` 状态延迟回队列。

落地内容：

- `task-management` 新增执行尝试状态 `DEFERRED`，用于标识某一次 run 被执行器主动退避。
- `task-management` 新增 `TaskDeferRequest`、`TaskService.deferTask` 与 `POST /tasks/{id}/defer` 路由。
- `TaskMapper` 的认领查询从只扫描 `PENDING` 扩展为扫描 `PENDING` 和到期的 `DEFERRED`。
- `claimTask` 条件更新同步支持到期 `DEFERRED`，避免延迟未到期任务被提前认领。
- `TaskServiceImpl.deferTask` 会结束当前 run、清空当前执行器/心跳/租约字段、设置未来 `queuedTime`，并写入 `DEFER` 执行日志。
- `data-quality` 新增本地 `TaskDeferRequest` 合同模型和 `TaskManagementClient.deferTask`。
- `data-quality` 新增 `executor-throttle-defer-seconds` 配置，用于控制并发护栏拒绝后的退避秒数。
- `QualityTaskExecutorCoordinator` 识别 `ConcurrencyRejectedException`，改为调用 task-management defer，而不是 fail。
- `QualityExecutorMetrics` 与 scheduler 摘要日志新增 `THROTTLED_DEFERRED` / `DEFERRED` 结果识别。
- 更新 `docker/mysql/init/init.sql` 中 task 状态注释、run 状态注释和队列认领索引。

设计价值：

- 把“业务失败”和“容量背压”分离，避免失败率、告警和人工关注被资源保护场景污染。
- 让 data-quality 执行器可以在全局、租户、数据源并发触顶时主动释放任务，不继续压垮当前实例或客户源库。
- 通过 `queued_time` 形成轻量延迟队列，不需要立刻引入独立 MQ 延迟队列或复杂调度器。
- 保留每一次退避 run 的审计轨迹，后续可以用于容量规划、租户公平调度和 SLA 分析。

当前边界：

- `DEFERRED` 当前不增加 retryCount，也没有最大连续 defer 次数；后续应加入死信/人工关注策略，避免任务无限退避。
- 延迟秒数当前是固定配置，不是指数退避，也未按租户等级、数据源健康度、任务优先级动态调整。
- 当前仍是数据库轻量队列；高吞吐场景下需要进一步设计分片认领、批量认领、队列表或外部队列。
- 已有数据库如需索引变化，需要手动迁移或重新初始化 Docker MySQL 数据卷。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-30：task-management DEAD_LETTER 死信与连续退避上限基线

本次继续完善 `DEFERRED` 的生产安全边界：避免任务因为容量不足而无限“认领 -> 退避 -> 再认领”。

落地内容：

- `task-management` 新增 `DEAD_LETTER` 任务状态，用于表示任务已停止自动调度并需要人工关注。
- `Task` 主表新增 `deferCount` 和 `maxDeferCount`，区分容量退避次数与业务失败重试次数。
- `CreateTaskRequest` 新增可选 `maxDeferCount`，允许高敏感任务配置更低的自动退避上限。
- `TaskServiceImpl.deferTask` 在每次退避时递增 `deferCount`，未超过上限则继续进入 `DEFERRED`。
- 当 `deferCount > maxDeferCount` 时，任务进入 `DEAD_LETTER`，清空执行器/租约字段、停止设置 `queuedTime`，并置 `attentionRequired=true`。
- 管理员 `forceRetry` 支持从 `DEAD_LETTER` 恢复到 `PENDING`，同时重置 `deferCount`。
- `data-quality` coordinator 新增 `THROTTLED_DEAD_LETTER` outcome，用于识别 task-management defer 后任务已经进入死信。
- `QualityExecutorMetrics` 与 scheduler 摘要日志新增 `DEAD_LETTER` 轮次识别。
- 更新 `docker/mysql/init/init.sql`，为 `task` 表补充 `defer_count`、`max_defer_count` 字段，并扩展状态注释。

设计价值：

- 防止容量长期不足时任务在队列里无限循环，形成隐性调度风暴。
- 把“业务失败重试”和“容量退避保护”拆成两套计数，避免 retryCount 被容量波动消耗。
- 让运营人员能通过 `attentionRequired=true` 和 `DEAD_LETTER` 快速定位需要人工处理的任务。
- 为后续死信列表、死信恢复、告警规则、容量规划和租户 SLA 报表打基础。

当前边界：

- 还没有独立死信列表 API、死信原因结构化字段或批量恢复接口。
- `maxDeferCount` 当前是任务级字段，尚未按任务类型、租户等级、数据源健康度做动态策略。
- 仍未接入告警系统；当前只通过状态、日志和指标表达死信风险。
- 已有数据库需要手动迁移或重新初始化 Docker MySQL 数据卷，才能拥有 `defer_count` 和 `max_defer_count` 字段。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-30：task-management 队列运营视图查询基线

本次在 `DEFERRED` 和 `DEAD_LETTER` 状态链路之上，补齐运营人员发现问题任务的基础查询能力。

落地内容：

- 新增 `TaskQueueInspectionRequest`，作为任务队列运营视图查询 DTO。
- 新增 `TaskService.inspectQueue`，把队列查询默认状态、分页上限、排队时长过滤和排序策略收口到服务层。
- 新增 `GET /tasks/operations/queue`，用于查询 PENDING、RUNNING、DEFERRED、DEAD_LETTER、FAILED、PAUSED 等运营相关任务。
- 支持按 `status`、`type`、`priority`、`attentionRequired`、`currentExecutorId`、`deferCountAtLeast` 过滤。
- 支持按 `queuedBefore`、`queuedAfter`、`queuedOlderThanSeconds` 定位排队过久或延迟未释放任务。
- 默认排除 `SUCCESS/CANCELLED`，如需事故复盘可使用 `includeTerminal=true` 查询所有状态。
- 查询排序策略为：需要关注优先、queuedTime 更早优先、updateTime 更新优先、id 倒序兜底。

设计价值：

- 让 `DEFERRED` 和 `DEAD_LETTER` 不只是内部状态，而是能被运营后台直接发现和筛选。
- 为后续队列大盘、任务积压告警、死信恢复工作台、SLA 报表打基础。
- 把普通业务列表和运营队列视图区分开，避免一个接口承担过多语义。
- 通过分页上限保护数据库，避免运营查询在积压场景下进一步放大系统压力。

当前边界：

- 当前仍返回 `Task` 主表快照，尚未提供专门的队列视图 VO 或聚合统计。
- 暂未提供批量恢复、批量暂停、批量取消或死信导出接口。
- 暂未接入 permission-admin 的细粒度 route-policy，后续应将 `/tasks/operations/**` 归入运营角色权限。
- 暂未提供队列统计摘要，例如各状态数量、最老排队时间、P95 排队时长。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-30：task-management 队列运营汇总接口基线

本次继续补齐任务队列运营能力：在明细列表之外，新增用于运营首页、队列大盘和告警判断的汇总接口。

落地内容：

- 新增 `TaskQueueSummaryResponse`，用于承载队列健康汇总指标。
- 新增 `TaskService.summarizeQueue`，复用队列运营视图过滤语义，避免列表与汇总口径漂移。
- 新增 `GET /tasks/operations/queue/summary`，与 `/tasks/operations/queue` 使用同一组查询参数。
- 汇总返回 `totalCount`、`statusCounts`、`pendingCount`、`runningCount`、`deferredCount`、`deadLetterCount`、`failedCount`、`pausedCount`。
- 汇总返回 `attentionRequiredCount`，便于运营台快速判断需要人工处理的任务规模。
- 汇总返回 `oldestQueuedTime` 和 `oldestQueuedAgeSeconds`，用于识别最老排队任务和 SLA 风险。
- 汇总返回 `maxObservedDeferCount`，用于发现接近 `maxDeferCount` 的容量背压风险。
- 实现上使用轻量 count/top1 查询，避免在队列积压场景下拉全量任务到内存中统计。

设计价值：

- 让运营人员先看队列健康概览，再进入明细列表排障。
- 为 Grafana、告警规则、SLA 看板和运营首页提供第一批后端指标。
- 复用列表过滤条件，保证“汇总数字”和“明细列表”基于同一口径。
- 通过状态分布和最老排队时间，提前发现任务积压、执行器不足、死信堆积和长期延迟问题。

当前边界：

- 汇总仍是实时查询 MySQL，没有缓存、预聚合或指标推送。
- 状态分布目前按已知状态逐个 count，后续大规模场景可下沉为 Mapper group by SQL 或指标系统。
- 暂未提供 P95/P99 排队时长、按任务类型/租户/执行器维度聚合。
- 暂未接入 gateway/permission-admin 的运营路由权限策略。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-30：task-management 队列运营项视图基线

本次继续把队列运营能力从“能查原始任务”推进到“能直接解释任务风险”，为真正的运营工作台打基础。

落地内容：

- 新增 `TaskQueueItemView`，作为运营队列列表的专用响应对象。
- 新增 `TaskService.inspectQueueItems`，复用 `inspectQueue` 的分页、排序和过滤口径。
- 新增 `GET /tasks/operations/queue/items`，返回带解释字段的队列列表。
- 为队列项补充 `queueAgeSeconds`、`queuedDelayRemainingSeconds`、`heartbeatAgeSeconds`、`leaseRemainingSeconds`。
- 为队列项补充 `riskLevel`、`riskReason`、`recommendedAction`，把状态、排队时长、租约、退避次数转成运营可读说明。
- 风险规则覆盖 `DEAD_LETTER`、`attentionRequired`、运行中租约过期、`DEFERRED` 延迟/到期、`FAILED`、接近退避上限、PENDING 排队过久、PAUSED 等场景。

设计价值：

- 前端不再需要从原始 Task 字段自行推断风险原因，降低多端展示口径不一致的风险。
- 运营人员可以直接看到“为什么要关注”和“建议下一步做什么”。
- 保留原始 `/operations/queue` 兼容联调，同时新增 `/operations/queue/items` 服务真实运营工作台。
- 为后续批量处置、告警推荐、SLA 风险解释和智能运维建议打基础。

当前边界：

- 风险规则仍是确定性启发式规则，尚未结合租户 SLA、任务类型策略或历史执行分布动态计算。
- 推荐动作只是文本建议，不会自动执行恢复、暂停、取消或扩容动作。
- 暂未接入 permission-admin/gateway 对 `/tasks/operations/**` 的细粒度授权。
- 暂未提供批量操作接口和操作审批流。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-28：datasource-management 受控只读 SQL 执行契约基线

本次承接 `data-quality` 已经能够生成关系型质量扫描 SQL 计划的进度，补齐 datasource-management 侧的受控只读执行入口，避免后续质量扫描执行器直接持有源库凭据。

落地内容：

- 新增 `ReadOnlySqlExecutionProperties`，集中配置只读 SQL 执行开关、默认/绝对行数上限、默认/绝对查询超时。
- 新增 `ReadOnlySqlExecutionRequest` 和 `ReadOnlySqlExecutionResult`，定义跨模块短查询契约。
- 扩展 `DataSourceManagementService` 与 `DataSourceManagementServiceImpl`，新增 `executeReadOnlySql`。
- 扩展 `DataSourceManagementController`，新增 `POST /datasources/{id}/sql/read-only/execute`。
- 扩展本地权限模型，新增 `DATASOURCE_READONLY_QUERY` 资源和 `EXECUTE_READ_ONLY_QUERY` 动作。
- 扩展 `SyncAdminRoutePolicy`，新增 `DATASOURCE_READ_ONLY_SQL_EXECUTION` 路由策略。
- 更新 `datasource-management/src/main/resources/application.yml`，补充只读 SQL 执行配置和路由绑定。

设计价值：

- datasource-management 开始承担“数据源凭据与安全访问代理”的职责，其他模块不需要复制外部库密码。
- data-quality 后续可以把 2.46 生成的 metric SQL / anomaly sample SQL 交给本入口执行。
- SQL 执行前会校验数据源必须启用、角色必须具备只读执行权限、SQL 必须是单条 SELECT。
- 服务端会强制包裹 SQL 并应用最大返回行数，调用方不能绕过行数上限。
- JDBC 连接、Statement 超时、只读连接、结果字符串化都集中在同一服务层，便于后续接审计、脱敏、租户配额和连接池。

当前边界：

- 当前只支持单条 SELECT，不支持 WITH/CTE、复杂 SQL AST 校验或参数化模板。
- 当前 SQL 安全校验采用保守字符串策略，后续应引入 SQL Parser 或模板化表达式。
- 当前执行是同步短查询模型，不适合大规模离线扫描；长任务应继续走 task-management 异步执行。
- 当前尚未把 data-quality executor coordinator 接入这个只读执行端点。
- 当前没有审计表、敏感字段脱敏、连接池隔离、熔断、指标和租户级并发配额。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-28T20:37:27+08:00`。

### 2026-04-28：data-quality 关系型质量扫描受控执行闭环基线

本次把 2.46 的 SQL 模板生成能力与 2.47 的 datasource-management 受控只读 SQL 执行入口串起来，让质量执行器 coordinator 不再只是安全失败，而是可以对已支持的关系型规则完成真实受控扫描。

落地内容：

- 新增 `DatasourceReadOnlySqlExecutionProperties`，配置 data-quality 调用 datasource-management 只读 SQL 执行接口的开关、地址、服务账号角色、超时和行数上限。
- 新增 `DatasourceReadOnlySqlExecutionRequest`、`DatasourceReadOnlySqlExecutionResult`，作为 data-quality 内部消费 datasource-management 接口的局部合同模型。
- 新增 `DatasourceReadOnlySqlExecutionClient`，集中处理 metric SQL 与 anomaly sample SQL 的远程执行。
- 扩展 `QualityTaskExecutorCoordinator`，对 `RelationalQualitySqlTemplateBuilder` 返回 `supported=true` 的规则执行真实扫描。
- metric SQL 会解析 `measured_value`、`sample_size`、`exception_count`，并通过 `completeTaskExecution` 生成质量报告。
- 当 `exception_count > 0` 时，会执行异常样本 SQL，并转换为 `QualityAnomalyDetailRequest` 写入异常明细。
- 扩展 `QualityExecutorRunResult`，返回 reportId、measuredValue、sampleSize、exceptionCount。
- 更新 `data-quality/src/main/resources/application.yml`，补充 datasource-read-only-sql 配置及中文说明。

设计价值：

- data-quality 仍然只负责规则语义、SQL 模板、报告和异常明细，不直接持有源库凭据。
- datasource-management 继续承担源库连接、只读校验、行数上限、超时和权限边界。
- task-management 负责认领、心跳、完成/失败终态，三模块形成第一版完整闭环。
- 当前只让明确支持的 COMPLETENESS / UNIQUENESS 关系型规则生成报告，避免未成熟规则类型伪成功。

当前边界：

- 当前仍是手动 `run-once`，不是后台自动轮询或线程池并发执行。
- 当前 SQL 模板仍未做 MySQL/PostgreSQL 方言化引用和 AST 级安全校验。
- 当前异常样本的 recordIdentifier 只用 id 或 sample_index 兜底，尚未结合 datasource-management 主键元数据。
- 当前没有只读 SQL 执行审计表、脱敏策略、租户配额、连接池隔离、熔断和指标。
- 如果 datasource-management 未启动或源库不可访问，任务会失败，这是当前更安全的 fail-close 设计。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-28T20:57:32+08:00`。

### 2026-04-28：datasource-management 只读 SQL 执行审计基线

本次在 2.47 受控只读 SQL 执行入口之上补齐审计证据链，让质量扫描、异常样本采集、字段画像等源库访问动作不再只依赖普通日志。

落地内容：

- 新增 `DataSourceReadOnlySqlExecutionAudit` 实体，映射受控只读 SQL 执行审计记录。
- 新增 `DataSourceReadOnlySqlExecutionAuditMapper`，负责审计记录落库。
- 扩展 `DataSourceManagementServiceImpl.executeReadOnlySql`，成功和失败都会 best-effort 写入审计。
- 审计记录包含 datasourceId、datasourceName、datasourceType、purpose、actorRole、SQL 指纹、SQL 截断预览、请求/应用行数上限、请求/应用超时、返回行数、列数、耗时、执行状态和失败原因。
- 更新 `docker/mysql/init/init.sql`，新增 `datasource_readonly_sql_execution_audit` 表和按数据源、purpose、actorRole、状态、SQL 指纹的索引。

设计价值：

- 审计表回答“谁、为什么、访问了哪个数据源、执行结果如何、耗时多少”的合规问题。
- SQL 使用 SHA-256 指纹支持同类访问聚合，同时避免保存完整 SQL。
- SQL 预览仅保留截断文本，避免审计表本身变成新的敏感数据扩散点。
- 审计写入当前采用 best-effort，不因审计表未迁移阻断本地开发；生产环境可按客户合规要求改为 fail-close。

当前边界：

- 当前审计只记录 actorRole，尚未记录可信 actorId、tenantId、traceId、sourceService。
- 当前没有审计查询 API，也没有导出、保留期、归档和告警策略。
- SQL 预览尚未做字面量脱敏，后续应对手机号、邮箱、身份证、token 等敏感片段做遮蔽。
- 当前审计不记录结果集内容，这是有意设计；异常样本证据仍由 data-quality 的异常明细表承接。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-28T21:35:39+08:00`。

### 2026-04-29：只读 SQL 审计可信上下文与查询接口基线

本次把 2.49 的“审计落库”继续推进为“可追踪、可检索、可运营”的审计能力。

落地内容：

- 扩展 `ReadOnlySqlExecutionRequest`，支持 actorTenantId、actorId、actorType、sourceService、traceId 等平台上下文字段。
- 扩展 `DataSourceManagementController.executeReadOnlySql`，优先从 `X-DataSmart-*` Header 注入可信上下文，同时兼容旧请求体 actorRole。
- 扩展 `DataSourceReadOnlySqlExecutionAudit` 和初始化 SQL，新增 actorTenantId、actorId、actorType、sourceService、traceId 字段与索引。
- 扩展 `DataSourceManagementService` 和 `DataSourceManagementServiceImpl`，新增分页查询受控只读 SQL 审计能力。
- 新增 `GET /datasources/sql/read-only/audits`，支持按 datasourceId、purpose、actorRole、actorTenantId、executionStatus、sqlFingerprint、时间窗口过滤。
- 扩展 `SyncAdminRoutePolicy` 与本地路由绑定，新增 `DATASOURCE_READ_ONLY_SQL_AUDIT_QUERY`。
- 扩展 data-quality 的 datasource-management 只读 SQL 客户端，通过 `X-DataSmart-*` Header 透传服务账号身份、来源服务和 traceId。

设计价值：

- 审计记录不再只有角色，而是开始具备租户、操作者、来源服务和链路追踪维度。
- 质量扫描触发的源库访问可以通过 traceId 与 task-management 任务、data-quality 报告串联。
- 管理员可以通过分页接口检索审计，而不是直接查数据库表。
- 保留旧 body.actorRole 兼容路径，避免已实现的服务间调用被一次性打断。

当前边界：

- 审计查询当前复用 `SYNC_PERMISSION_POLICY + VIEW_POLICY` 本地权限，后续应抽象独立 `DATASOURCE_AUDIT + VIEW_AUDIT`。
- 当前只支持分页查询，不支持导出、归档、保留期、legal hold、异常访问告警。
- SQL 预览仍只截断不脱敏，后续应补字面量遮蔽和敏感模式识别。
- Header 当前由调用方传入，生产环境必须由 gateway 或服务间认证机制保证可信。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-29T00:01:01+08:00`。

### 2026-04-29：只读 SQL 审计预览脱敏基线

本次继续补齐 datasource-management 只读 SQL 审计链路的合规底座，让审计表既能支撑人工排查和同类 SQL 聚合，又尽量避免沉淀手机号、邮箱、身份证号、token、password 等敏感字面量。

落地内容：

- 新增 `ReadOnlySqlAuditMaskingProperties`，以 `datasmart.datasource.read-only-sql.audit-masking` 为配置前缀，独立控制审计预览脱敏开关和规则。
- 扩展 `DataSourceManagementServiceImpl`，在写入 `DataSourceReadOnlySqlExecutionAudit.sqlPreview` 前执行脱敏和截断。
- 保持 `sqlFingerprint` 基于原始 SQL 计算，保证同一 SQL 的审计聚合稳定；`sqlPreview` 使用脱敏 SQL，降低审计表敏感数据风险。
- 当前脱敏覆盖邮箱、中国大陆手机号、中国大陆居民身份证号、password/token/secret/api_key/authorization 等凭据赋值、Bearer Token、超长单引号字符串字面量。
- 更新 `DataSourceReadOnlySqlExecutionAudit.sqlPreview` 字段注释，明确其职责是“脱敏后的结构预览”，不是完整 SQL 留存。
- 更新 datasource-management `application.yml`，补充每个脱敏配置项的中文说明和默认值。

设计价值：

- 审计表从“截断但可能含敏感字面量”推进到“结构可读、敏感值默认遮蔽”。
- 指纹与预览职责分离，既保留运维聚合能力，也符合数据最小化原则。
- 配置独立于只读 SQL 执行开关，便于后续按客户合规等级调整规则。
- 这一步为未来 compliance-masking 独立模块预留边界：当前只是 datasource-management 写审计时的最低安全兜底。

当前边界：

- 当前仍是正则级脱敏，不是 SQL AST 级字面量解析，也不是字段分类分级策略中心。
- 规则主要覆盖中文客户环境常见模式，多国家手机号、护照号、银行卡、地址、姓名等需要后续扩展。
- 当前不记录“脱敏命中次数”和“命中类型”，后续如果要做合规运营看板，应补充指标或审计元数据。
- 关闭脱敏开关本身还没有进入配置变更审批和审计，生产环境后续应纳入 permission-admin 或合规策略中心。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-29T00:05:58+08:00`。

### 2026-04-29：data-quality 质量执行器后台调度基线

本次把质量任务执行从“只能手动 run-once 联调”推进到“可配置后台自动认领 task-management 质量任务”的第一版执行器调度能力。

落地内容：

- `DataQualityApplication` 启用 `@EnableScheduling`，让 data-quality 具备 Spring 定时任务能力。
- 扩展 `TaskManagementIntegrationProperties`，新增后台调度器开关、首次延迟、固定延迟、单轮最大处理数等配置。
- 新增 `QualityTaskExecutorScheduler`，按固定延迟触发质量任务认领和执行。
- 调度器默认关闭，只有 `enabled`、`executorCoordinatorEnabled`、`executorSchedulerEnabled` 三个开关同时满足才会自动消费任务。
- 调度器使用 `AtomicBoolean` 防止同一实例内重入，避免上一轮质量扫描未完成时下一轮再次触发。
- 扩展 `QualityTaskExecutorCoordinator.runBatch`，让手动入口和后台调度复用同一套 runOnce 执行逻辑。
- 新增 `POST /quality-rules/executor/coordinator/run-batch?maxRuns=`，用于在不开启后台自动调度时手动模拟一轮小批量消费。
- 更新 data-quality `application.yml`，补充调度器配置项和详细中文说明。

设计价值：

- task-management 的队列认领、租约心跳、任务终态与 data-quality 的 execution、report、anomaly detail 开始形成更接近生产的自动闭环。
- 默认关闭后台消费，避免本地或测试环境误启动后批量访问客户源库。
- 单轮默认 1 条任务，最大 20 条，为后续线程池并发、租户配额和数据源配额留出渐进式演进空间。
- scheduler 只负责“什么时候触发”，coordinator 继续负责“任务怎么执行”，避免调度逻辑和业务执行逻辑耦合。

当前边界：

- 当前仍是单实例内防重入，不是分布式调度锁；多实例环境依赖 task-management claim 原子性和租约机制。
- 当前没有 Micrometer 指标，调度摘要先进入日志，后续应输出 claimed/success/failed/no-task/latency 等指标。
- 当前没有线程池并发、租户公平队列、数据源并发配额和源库熔断，不能直接用高批量跑生产大规模扫描。
- 当前支持的真实扫描仍主要是关系型 COMPLETENESS/UNIQUENESS，其他规则和连接器继续安全失败。
- 当前 scheduler 只负责消费，不负责定时生成任务；规则级 cron、日历窗口、补数 backfill 仍应由 task-management 或专门调度配置承接。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-29T00:10:39+08:00`。

### 2026-04-29：data-quality 执行器调度指标基线

本次在后台调度器基础上补齐第一版 Micrometer 指标，让质量执行器从“只能看日志”推进到“可通过 Actuator 指标观察运行情况”。

落地内容：

- data-quality 新增 `spring-boot-starter-actuator` 依赖，使 `/actuator/metrics` 等运维端点真正可用。
- 新增 `QualityExecutorMetrics`，集中封装质量执行器指标采集逻辑，避免 Counter/Timer 代码散落在业务流程中。
- `QualityTaskExecutorCoordinator` 在每次 runOnce 结束时记录单条执行次数和耗时。
- `QualityTaskExecutorScheduler` 在每轮调度结束时记录调度轮次、耗时、重入跳过和外层异常。
- 指标标签刻意避免 taskId、runId、executionId、traceId 等高基数字段，只保留 trigger、taskType、outcome、claimed、result 等低基数字段。
- data-quality `application.yml` 补充 Actuator metrics 说明。

新增指标：

- `datasmart_quality_executor_run_total`：质量执行器单条任务执行次数。
- `datasmart_quality_executor_run_duration`：质量执行器单条任务执行耗时。
- `datasmart_quality_executor_scheduler_tick_total`：后台调度轮次数。
- `datasmart_quality_executor_scheduler_tick_duration`：后台调度单轮耗时。
- `datasmart_quality_executor_scheduler_skip_total`：调度器跳过次数，例如同实例重入跳过。
- `datasmart_quality_executor_scheduler_failure_total`：调度器外层未捕获异常次数。

设计价值：

- 自动执行器开启前，可以先通过 metrics 验证空队列轮询、手动批量、成功扫描、失败扫描是否符合预期。
- outcome 维度能区分 `NO_TASK`、`RELATIONAL_SCAN_SUCCEEDED`、`UNSUPPORTED_SCAN`、`FAILED_TO_PROCESS`，便于后续做告警阈值。
- 低基数标签避免 Prometheus/Grafana 在任务量上升后被高基数字段拖垮。
- 指标组件独立于 scheduler/coordinator，后续接入 Prometheus registry、OpenTelemetry 或统一 observability 模块时更容易演进。

当前边界：

- 当前只暴露 Actuator metrics，还没有在 data-quality 引入 Prometheus registry 和 `/actuator/prometheus`。
- 当前指标没有租户、数据源、规则类型维度，避免第一版基数过高；后续可在明确容量后谨慎增加。
- 当前没有基于指标的告警规则、Grafana 看板和 SLO。
- 当前没有把 task-management 队列深度、datasource-management SQL 耗时与 data-quality 执行器指标串成统一链路看板。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-29T00:16:34+08:00`。

### 2026-04-29：data-quality 执行器本实例并发护栏基线

本次在质量执行器调度和指标基础上补齐第一版本实例并发护栏，避免后续开启批量、线程池或更高调度频率时，单个 data-quality 实例同时压住过多租户、数据源和源库查询。

落地内容：

- 新增 `QualityExecutorConcurrencyGuard`，按 GLOBAL、TENANT、DATASOURCE 三个维度限制当前 JVM 内正在运行的质量扫描数量。
- 扩展 `TaskManagementIntegrationProperties`，新增并发护栏开关和全局/租户/数据源并发上限配置。
- 扩展 `QualityTaskPayload`，新增 `tenantId` 快照，用于执行器租户级并发护栏。
- 扩展 `QualityTaskScheduleRequest`，新增 `tenantId` 过渡字段，便于本地联调和后续 gateway 可信租户上下文接入。
- 扩展 `DataQualityServiceImpl`，提交任务时将 tenantId 写入 payload；未传入时回退到服务账号配置租户。
- 扩展 `QualityTaskPayloadParser`，对 tenantId 做非负校验。
- 扩展 `QualityTaskExecutorCoordinator`，payload 校验后先申请并发许可，拿到许可后才进入心跳、execution 创建和真实扫描。
- 扩展 `QualityExecutorMetrics`，新增 `datasmart_quality_executor_concurrency_rejected_total`，记录并发护栏拒绝次数。
- 更新 data-quality `application.yml`，补充并发护栏配置和中文说明。

设计价值：

- 在真正引入线程池前先建立资源保护边界，避免后续“先并发、再补治理”的返工。
- 单数据源默认并发 1，保护客户源库不被多个质量规则同时扫描打爆。
- 单租户默认并发 1，避免某个租户批量触发质量任务后挤占整个执行器。
- 全局默认并发 2，为本地和测试环境保留少量并行空间，但不鼓励直接高并发。
- 护栏使用 try-with-resources 释放许可，扫描成功、失败或异常都会释放计数。

当前边界：

- 当前是单 JVM 实例内护栏，不是分布式全局配额；多实例环境仍依赖 task-management claim 原子性和租约机制。
- 当前 tenantId 仍是过渡字段，生产环境应优先来自 gateway 注入的可信租户上下文，而不是前端请求体。
- 当前护栏触发后会走任务失败路径；更理想的产品形态是 task-management 支持 DEFERRED/REQUEUED 延迟回队列。
- 当前没有把并发活跃数暴露为 Gauge，只记录拒绝 Counter；后续可补 active gauge 和容量水位告警。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-29T22:37:18+08:00`。

### 2026-04-28：data-quality 关系型质量扫描 SQL 模板基线

本次开始推进关系型扫描执行器能力，但没有让 `data-quality` 直接连接客户源库，而是先补齐可审查的 SQL 模板生成层，保持 datasource-management 的连接、密钥、权限和元数据治理边界。

落地内容：

- 新增 `RelationalQualityScanSqlPlan`，用于返回关系型质量扫描的只读指标 SQL、异常样本 SQL、风险提示和执行建议。
- 新增 `RelationalQualitySqlTemplateBuilder`，负责把质量规则和扫描计划转换成安全 SQL 模板。
- 扩展 `DataQualityService` 与 `DataQualityServiceImpl`，新增 `buildRelationalSqlPlan`。
- 扩展 `DataQualityController`，新增 `POST /quality-rules/{id}/relational-sql-plan`。

当前支持范围：

- `COMPLETENESS` 字段级规则：生成空值检测 SQL，`measured_value` 表示字段非空率。
- `UNIQUENESS` 字段级规则：生成重复值检测 SQL，`measured_value` 表示字段唯一率。
- `VALIDITY`、`CONSISTENCY`、`ACCURACY` 暂时返回 `supported=false`，避免在缺少规则参数时生成误导性 SQL。

安全与性能保护：

- 表名、schema/database 名、字段名必须通过安全标识符白名单。
- whereClause 会拦截分号、注释、drop/delete/update/insert/truncate 等危险片段。
- 源查询统一追加 `LIMIT`，即使 FULL_SCAN 也受 `maxScannedRows` 上限保护。
- 异常样本 SQL 必须带 `LIMIT`，当前最高限制 1000 条。
- SQL 计划只生成不执行，真正执行仍需要只读连接、statement timeout、审计、脱敏和 datasource-management 授权。

设计价值：

- 执行器未来可以消费统一 SQL 模板，而不是每个执行器自行拼接 SQL。
- SQL 在执行前可被前端、运维或审计人员查看，便于解释质量任务会如何访问源库。
- 该设计为后续支持 MySQL/PostgreSQL 方言、分片扫描、参数化过滤模板、异常样本脱敏和执行指标打基础。

当前边界：

- 尚未真正执行 SQL，也没有接入 datasource-management 的受控连接获取能力。
- 当前 SQL 引用方式使用安全裸标识符，尚未按 MySQL/PostgreSQL 方言做反引号/双引号处理。
- 当前 whereClause 仍是轻量保护，后续应升级为参数化模板或 SQL AST 校验。
- 当前只支持完整性和唯一性，更多规则类型需要补充规则参数模型后再实现。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 2026-04-28T19:55:37+08:00。

### 2026-04-28：data-quality 默认关闭质量执行器 coordinator 基线

本次在 `data-quality` 中补齐了一个默认关闭、手动触发、单次处理的质量执行器 coordinator，用于把 task-management 认领、payload 校验、data-quality execution 回写、心跳和安全失败串成第一版执行闭环。

落地内容：

- 新增 `QualityExecutorRunResult`，用于返回 coordinator 单次运行摘要。
- 新增 `QualityTaskExecutorCoordinator`，实现 `runOnce` 单任务处理流程。
- 扩展 `TaskManagementIntegrationProperties`，新增 `executorCoordinatorEnabled=false` 默认关闭开关。
- 扩展 `data-quality/src/main/resources/application.yml`，补充 coordinator 开关说明。
- 扩展 `DataQualityController`，新增 `POST /quality-rules/executor/coordinator/run-once` 手动触发入口。

当前 runOnce 流程：

- 检查 task-management 集成和 coordinator 开关，默认关闭时直接跳过。
- 从 task-management 认领一条 `DATA_QUALITY_SCAN` 任务。
- 解析并校验 `QUALITY_SCAN_TASK_V1` payload。
- 发送 payload 校验完成心跳。
- 调用 data-quality start 创建质量检测 execution。
- 发送质量 execution 已开始心跳。
- 在真实扫描器尚未实现时，同时把 data-quality execution 和 task-management task 标记为明确失败。

设计价值：

- 这不是 demo 式“假成功”，而是生产更可信的“安全失败”模式。
- 当前没有真实扫描器，因此不生成虚假的质量报告，避免污染质量大盘、规则评分、审计证据和清洗流程。
- coordinator 每次只处理一条任务，方便本地学习、联调和排障，也避免配置错误时批量消费任务。
- 未来可以在同一协调器上继续扩展真实 MySQL/PostgreSQL 扫描、分片扫描、线程池并发、指标、告警和租户配额。

当前边界：

- coordinator 仍需手动调用，不是后台定时轮询。
- 真实源数据扫描器尚未实现，因此所有成功解析的任务都会走 unsupported scan 安全失败。
- 失败补偿目前是尽力而为；如果 task-management fail 回写也失败，仍需要依赖租约超时恢复。
- 暂未接入 Micrometer 指标、重试策略、熔断、分布式锁和服务账号签名鉴权。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 2026-04-28T19:46:49+08:00。

### 2026-04-28：data-quality task-management 执行期客户端合同基线

本次继续补齐 `data-quality` 到 `task-management` 的执行期通信合同，让后续质量执行器不只会提交任务，还能认领任务、上报心跳、标记完成和标记失败。

落地内容：

- 新增 `TaskExecutionClaimRequest`、`TaskExecutionHeartbeatRequest`、`TaskCompleteRequest`、`TaskFailRequest` 本地请求模型。
- 新增 `TaskExecutionClaimResult`、`TaskResponse`、`TaskExecutionRunResponse` 本地响应模型。
- 扩展 `TaskManagementClient`，新增 `claimNextQualityTask`、`heartbeatExecution`、`completeTask`、`failTask`。
- 扩展 `TaskManagementIntegrationProperties`，新增执行器 ID、默认租约秒数、服务账号上下文、sourceService 等配置。
- 更新 `data-quality/src/main/resources/application.yml`，补充执行器集成配置和中文说明。

设计价值：

- data-quality 仍不直接依赖 task-management 内部 Java 包，而是通过本地 JSON 合同维持微服务边界。
- 未来质量执行器 coordinator 可以复用同一客户端完成任务认领、续租、完成和失败回写，不需要把 RestClient 调用散落到业务流程中。
- 服务间调用会透传 `X-DataSmart-*` 平台上下文 Header，满足 task-management 服务层执行器权限校验，也为后续审计和链路追踪做准备。
- `failOpen` 策略被统一收口到客户端层，便于本地联调和生产 fail-closed 策略切换。

当前边界：

- 当前只是客户端合同，还没有真正的执行器 coordinator 自动循环认领任务。
- 还没有服务账号签名、mTLS、JWT client credentials 或网关级服务间鉴权，当前上下文 Header 仍是本地联调友好的配置注入。
- heartbeat/complete/fail 还没有业务调用方，下一步需要由质量执行器 coordinator 串联 payload 校验、data-quality 执行回调和 task-management 状态回写。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 2026-04-28T19:43:07+08:00。

### 2026-04-28：data-quality 质量任务 payload 版本化与提交前校验基线

本次没有急着写“假执行器”，而是先补齐 `DATA_QUALITY_SCAN` 任务 payload 的版本化合同与提交前校验能力，为后续真实执行器认领、解析、回调和重试打基础。

落地内容：

- 新增 `QualityTaskPayload`，把质量任务参数从临时 Map 升级为强类型、可版本化、可审计的 JSON 合同。
- 新增 `QualityTaskPayloadParser`，集中负责 payload 反序列化、schemaVersion 校验、sourceModule/taskKind 校验、规则快照校验和扫描计划校验。
- 扩展 `DataQualityServiceImpl`，在提交 task-management 前构造 `QUALITY_SCAN_TASK_V1` payload 并调用校验器。
- payload 中新增 `schemaVersion=QUALITY_SCAN_TASK_V1`，为后续 V2 扩展分片扫描、CDC 窗口、敏感字段策略和多连接器计划预留兼容空间。

设计价值：

- 执行器未来不再依赖“猜 JSON 字段”，而是消费明确的任务合同。
- 任务进入队列前先校验规则快照和扫描计划，减少不可执行任务进入 task-management 后再失败的概率。
- payload 保留规则版本、阈值、比较运算符和扫描计划快照，便于任务重试、历史审计和执行器排障。
- 这一步避免了把当前尚未接真实数据扫描的执行器做成 demo 式“假成功”，更符合商业化演进节奏。

当前边界：

- 还没有真实执行器自动认领 `DATA_QUALITY_SCAN` 并调用 data-quality 回调。
- 当前 payload 校验已经能挡住结构错误，但还没有按连接器类型校验字段类型、SQL 模板、权限范围和源系统负载风险。
- `QualityTaskPayloadParser#parseAndValidate` 主要为下一步执行器消费任务预留，当前提交链路使用的是结构化 validate。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 2026-04-28T19:36:27+08:00。

### 2026-04-27：data-quality 任务执行器回调与报告闭环基线

本次继续推进 `data-quality` 与 `task-management` 的执行闭环，让质量任务不止能提交到任务中心，还能由未来执行器回写开始、成功和失败状态。

落地内容：

- 扩展 `QualityCheckExecution`，新增 `taskId`、`taskRunId`、`executorId`、`scanPlanSnapshot`，用于关联任务中心运行态和保留扫描计划快照。
- 新增 `QualityExecutionStartRequest`、`QualityExecutionSuccessRequest`、`QualityExecutionFailRequest` 三个执行器回调 DTO。
- 扩展 `DataQualityService` 与 `DataQualityServiceImpl`，新增 `startTaskExecution`、`completeTaskExecution`、`failTaskExecution`。
- 将手动检测和任务检测成功回调统一收口到同一套报告生成 helper，避免不同入口出现质量判定逻辑分叉。
- 扩展 `DataQualityController`，新增 `/quality-rules/executor/executions/start`、`/succeed`、`/fail` 三类执行器回调接口。
- 更新 `docker/mysql/init/init.sql`，为 `quality_check_execution` 表补充任务关联字段、扫描计划快照字段和查询索引。

设计价值：

- `task-management` 继续负责排队、认领、心跳、超时和重试，`data-quality` 继续负责规则、执行记录、报告和异常明细，两个微服务边界更清晰。
- 执行状态和质量结果继续分离：execution `SUCCESS/FAILED` 描述扫描动作是否跑完，report `PASSED/FAILED` 描述业务数据是否达标。
- 扫描计划快照落到 execution，后续审计报告可以解释“当时到底按什么范围、什么模式、什么限制执行”。
- 失败回调不生成报告，避免把数据源连接失败、扫描超时、执行器崩溃等平台故障误计入业务数据质量失败。
- 回调接口为未来接入多实例执行器、Python AI 扫描器、分片扫描和重试补偿打下基础。

当前边界：

- 还没有真实质量执行器，也没有从 task-management 自动领取 `DATA_QUALITY_SCAN` 后调用回调的 worker。
- 成功回调仍由外部传入汇总指标和代表性异常样本，尚未真实扫描 MySQL/PostgreSQL/Kafka/文件/API。
- 回调幂等当前只靠 RUNNING 状态校验，后续应补幂等键、乐观锁或数据库条件更新。
- 异常明细仍是同步逐条写入，不适合一次性写入海量异常，后续应扩展批量写入、分页上报或对象存储归档。
- task-management 与 data-quality 之间仍缺少完成/失败后的反向状态同步，未来需要让质量执行器同时回写任务 run 状态。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 2026-04-27T22:27:52+08:00。

### 2026-04-27：data-quality 报告检索与异常明细基线

本次继续推进 `data-quality`，把质量检测结果从“能生成报告”扩展为“能被运营检索、能追溯异常样本、能支撑后续清洗闭环”的第一版产品能力。

落地内容：

- 新增 `QualityAnomalyDetail` 实体，用于保存报告下的异常样本、异常字段、记录定位、实际值、期望值、严重级别、清洗建议和样本载荷。
- 新增 `QualityAnomalyDetailMapper`，将报告摘要和异常明细拆表，避免报告列表被大体量异常样本拖慢。
- 新增 `QualityAnomalyDetailRequest`，让执行质量检测时可以同步传入异常样本明细。
- 扩展 `RunQualityCheckRequest`，新增 `anomalies` 字段，并用中文注释说明它与 sampleSize、exceptionCount 的关系。
- 扩展 `QualityCheckReport`，新增 `ruleType` 快照字段，便于按规则类型横向检索历史报告。
- 扩展 `DataQualityService` 和 `DataQualityServiceImpl`，新增报告分页检索、报告异常明细查询、异常明细事务内落库。
- 扩展 `DataQualityController`，新增 `/quality-rules/reports` 横向报告检索接口和 `/quality-rules/reports/{reportId}/anomalies` 异常明细接口。
- 更新 `docker/mysql/init/init.sql`，为 `quality_check_report` 增加 `rule_type` 字段和检索索引，并新增 `quality_anomaly_detail` 表。

设计价值：

- 报告分页查询支持 ruleId、ruleType、severity、checkStatus、targetObject、triggerType、时间范围和 failedOnly，能支撑质量运营台、告警台和审计检索。
- 异常明细把“失败数量”推进到“失败证据”，为后续清洗任务、人工复核、AI 复盘和质量趋势统计留下数据基础。
- 异常明细和报告创建在同一事务中完成，减少报告存在但异常证据丢失的风险。
- `quality_anomaly_detail` 当前使用通用字段，不绑定 MySQL 单一场景，后续可兼容 PostgreSQL、Kafka、文件、对象存储、API 等来源。

当前边界：

- 当前异常明细仍由调用方传入，还没有真实扫描器自动生成。
- 当前异常明细查询暂为列表返回；超大异常量场景应继续扩展分页、异步导出、MinIO 明细文件和冷热分层。
- 当前没有敏感字段脱敏策略，真实生产环境需要在写入 samplePayload 前接入合规脱敏模块。
- 已有数据库需要手动迁移或重新初始化 Docker MySQL 数据卷，以获得 `rule_type` 字段和 `quality_anomaly_detail` 表。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-27T21:23:53+08:00`。

### 2026-04-27：data-quality 异常分页与运营聚合基线

本次继续完善 `data-quality` 的异常运营能力，把上一阶段“按报告查看异常明细”扩展为“全局分页筛选异常 + 多维聚合统计异常”。

落地内容：

- 新增 `QualityAnomalyAggregationDimension`，把 FIELD、TYPE、SEVERITY、TARGET_OBJECT 等业务聚合维度映射到受控数据库列名。
- 新增 `QualityAnomalyAggregationItem`，作为异常聚合接口的响应模型。
- 扩展 `QualityAnomalyDetailMapper`，新增受白名单维度控制的异常聚合 SQL。
- 扩展 `DataQualityService` 和 `DataQualityServiceImpl`，新增异常明细分页查询和异常聚合统计能力。
- 扩展 `DataQualityController`，新增 `/quality-rules/anomalies` 和 `/quality-rules/anomalies/aggregation`。
- 更新 `docker/mysql/init/init.sql`，补充异常字段、目标对象、规则字段时间、报告类型时间等索引，支撑运营筛选和 TopN 聚合查询。

设计价值：

- 分页接口支持 reportId、ruleId、anomalyType、fieldName、severity、targetObject、时间范围等条件，适合质量运营台、人工复核页和清洗任务创建页。
- 聚合接口支持按字段、类型、严重级别、检测目标统计异常，适合快速定位“主要问题在哪里”。
- 聚合 SQL 使用服务层枚举白名单控制列名，避免把前端传入字符串直接拼接到 GROUP BY 中。
- 同一套筛选条件同时服务于明细下钻和聚合分析，为后续导出、告警、清洗建议、AI 复盘保留一致查询口径。

当前边界：

- 当前聚合仍基于明细表实时查询，适合早期运营台和中小规模数据；大规模生产场景应引入离线统计表、分区表或异步分析任务。
- 当前没有异步导出任务；异常量很大时不能依赖同步分页接口导出全量样本。
- 当前还没有和 compliance/masking 模块打通，异常样本中的 observedValue 和 samplePayload 后续必须补脱敏策略。
- 已有数据库需要手动迁移或重新初始化 Docker MySQL 数据卷，以获得新增索引。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-27T21:44:37+08:00`。

### 2026-04-27：data-quality 规则目标结构化与扫描策略抽象基线

本次继续推进 `data-quality`，把规则目标从单一 `targetObject` 字符串扩展为结构化目标模型，并新增质量扫描策略抽象，为后续真实数据源扫描做准备。

落地内容：

- 新增 `QualityRuleTargetType`，支持 `GENERIC`、`RELATIONAL_TABLE`、`RELATIONAL_FIELD`、`KAFKA_TOPIC`、`FILE_OBJECT`、`API_ENDPOINT`。
- 新增 `QualityTargetValidationStatus`，支持 `UNVALIDATED`、`VALIDATED`、`INVALID`、`UNSUPPORTED`。
- 新增 `QualityRuleTargetValidationResult`，返回规则目标校验结论、扫描策略、状态、说明和建议。
- 新增 `QualityScanStrategy` 接口、策略基类和策略注册表。
- 新增通用、关系型、Kafka、文件、API 五类目标校验策略。
- 扩展 `QualityRule`，新增 targetType、dataSourceId、databaseName、schemaName、tableName、fieldName、scanStrategy、targetValidationStatus、targetValidationMessage、targetValidatedTime。
- 扩展创建/更新规则 DTO 和服务方法，让规则可以保存结构化目标信息。
- 扩展 `DataQualityController`，新增 `/quality-rules/{id}/validate-target`，用于手动校验规则检测目标。
- 启用规则时会自动重新校验目标，明显不可扫描的规则不能进入 `ACTIVE`。
- 更新 `docker/mysql/init/init.sql`，为质量规则表增加结构化目标字段和相关索引。

设计价值：

- 保留 `GENERIC` 兼容旧规则和人工观测值检测，同时为真实数据源扫描打开结构化演进路径。
- 关系型规则可以开始表达 dataSourceId、databaseName、schemaName、tableName、fieldName，为 MySQL/PostgreSQL/Hive 等数据源校验做准备。
- Kafka、文件、API 目标被提前纳入模型，避免质量模块长期只围绕关系型数据库设计。
- 扫描策略通过 Spring Bean 注册，后续新增连接器或扫描方式时，不需要重写 DataQualityServiceImpl。
- 启用前校验规则目标，减少调度器未来领取“天然无法执行”的质量任务。

当前边界：

- 当前目标校验主要是结构性校验，还没有远程调用 datasource-management 确认数据源、表、字段真实存在。
- Kafka、文件、API 策略暂未执行真实采样，只是为后续执行器路由留下策略编码。
- 当前没有扫描任务调度、采样限流、SQL 生成、分区扫描、超时重试和大表保护。
- 已有数据库需要手动迁移或重新初始化 Docker MySQL 数据卷，以获得新增列和索引。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-27T21:55:29+08:00`。

### 2026-04-27：data-quality 接入 datasource-management 元数据校验契约

本次继续推进 `data-quality` 与 `datasource-management` 的跨模块协作，把关系型质量规则从“字段结构填完整”推进到“可选调用数据源模块确认表和字段真实存在”。

落地内容：

- 新增 `DatasourceMetadataValidationProperties`，用于配置远程元数据校验开关、datasource-management 地址、fail-open 策略和系统调用主体。
- 新增 data-quality 本地远程契约模型：`DatasourceMetadataDiscoveryRequest`、`DatasourceMetadataDiscoveryResponse`、`RemoteApiResponse`。
- 新增 `RelationalMetadataValidationOutcome`，作为关系型策略消费远程校验结果的中间模型。
- 新增 `DatasourceMetadataValidationClient`，通过 HTTP 调用 datasource-management 的 `/datasources/{id}/metadata/discover` 接口。
- 扩展 `RelationalQualityScanStrategy`，在结构性校验通过后调用远程元数据客户端，确认目标表和字段是否存在。
- 扩展 `data-quality/src/main/resources/application.yml`，补充详细中文配置说明。

设计价值：

- data-quality 不直接连接源库，不保存连接密钥，保持数据源连接、权限、元数据发现职责仍归 datasource-management。
- 本地采用局部 JSON 契约模型，而不是直接依赖 datasource-management 的 Java 包，保持微服务边界清晰。
- 默认 `enabled=false`，本地开发不强依赖另一个服务；生产可开启强校验。
- `fail-open` 提供开发友好性与生产严格性之间的切换点。
- 关系型规则现在具备从结构校验到真实元数据校验的演进路径。

当前边界：

- 当前远程调用仍是同步 HTTP，暂未接入服务发现、网关鉴权、重试、熔断和指标监控。
- 当前只校验表和字段存在，不校验采样权限、字段类型是否适配规则类型、数据量级和源库负载风险。
- 当前没有缓存远程元数据校验结果，频繁启用或批量校验规则时可能重复调用 datasource-management。
- Kafka、文件、API 策略还没有对应的真实远程校验客户端。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-27T21:59:58+08:00`。

### 2026-04-27：data-quality 质量扫描计划生成基线

本次继续推进 `data-quality`，在规则目标校验之后新增“扫描计划生成”能力，把质量规则进一步转换成后续调度器和执行器可消费的执行参数。

落地内容：

- 新增 `QualityScanExecutionMode`，支持 `SAMPLE_SCAN`、`FULL_SCAN`、`PARTITION_SCAN`、`INCREMENTAL_WINDOW`。
- 新增 `QualityScanPlanRequest`，承载执行模式、抽样上限、最大扫描行数、分区字段、where 条件、超时时间、异常样本采集策略等入参。
- 新增 `QualityScanPlan`，返回规则版本、目标信息、扫描策略、执行模式、扫描边界、风险级别、是否可调度、警告和建议。
- 扩展 `QualityScanStrategy`，新增默认扫描计划生成方法。
- 扩展 `QualityScanStrategyRegistry`，按目标类型路由扫描计划生成。
- 扩展 `RelationalQualityScanStrategy`，生成关系型表/字段扫描计划，并对全量扫描、缺少分区字段、高扫描行数、危险 where 条件等场景给出风险提示或阻断调度。
- 扩展 `DataQualityService` 和 `DataQualityServiceImpl`，新增扫描计划生成业务方法。
- 扩展 `DataQualityController`，新增 `/quality-rules/{id}/scan-plan`。

设计价值：

- 扫描计划成为“规则定义”和“任务执行”之间的中间契约，为后续接入 task-management 提供 payload 基础。
- 默认执行模式为 `SAMPLE_SCAN`，避免没有性能意识的规则直接走全量扫描。
- 关系型计划开始显式表达源库保护参数：最大扫描行数、超时、抽样、分区字段、异常样本上限。
- where 条件当前只作为计划文本，不直接执行；并且对明显危险 SQL 片段做基础阻断。
- 非关系型目标通过默认策略返回“暂不可调度计划”，保证 API 形态先稳定，后续逐步扩展 Kafka、文件、API 专用计划。

当前边界：

- 当前扫描计划只生成不落库、不调度、不执行。
- where 条件安全检查只是第一层保护，真正执行 SQL 前仍需参数化模板、SQL AST 解析、权限审计和脱敏策略。
- 尚未把计划发送到 task-management，也没有执行器认领、心跳、超时恢复、失败重试和报告回写闭环。
- 尚未为 Kafka、文件、API 目标生成真实可调度计划。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-27T22:12:53+08:00`。

### 2026-04-27：data-quality 提交质量检测任务到 task-management 基线

本次继续推进 `data-quality` 与 `task-management` 的跨模块协作，把“已校验规则 + 扫描计划”提交成任务中心中的 `PENDING` 任务。

落地内容：

- 新增 `TaskManagementIntegrationProperties`，用于配置 task-management 地址、开关、任务类型、默认优先级、默认重试次数和 fail-open 策略。
- 新增 `QualityTaskScheduleRequest`，承载扫描计划参数、任务优先级、最大重试次数、提交原因和 dryRun 开关。
- 新增 `QualityTaskScheduleResult`，返回扫描计划、任务提交状态、taskId、taskStatus、taskType、警告和提交时间。
- 新增 task-management 本地契约模型：`TaskCreateRequest`、`TaskCreateResponse`。
- 新增 `TaskManagementClient`，通过 HTTP 调用 task-management 的 `POST /tasks` 创建任务。
- 扩展 `DataQualityService` 和 `DataQualityServiceImpl`，新增 `scheduleQualityCheckTask`。
- 扩展 `DataQualityController`，新增 `/quality-rules/{id}/schedule-task`。
- 扩展 `data-quality/src/main/resources/application.yml`，补充 task-management 集成配置和中文说明。

设计价值：

- data-quality 不再只生成扫描计划，而是可以把计划提交给 task-management 统一排队。
- 任务参数中包含规则快照、规则版本、期望值、比较运算符、严重级别和 scanPlan，后续执行器可直接消费。
- dryRun 支持只生成计划不提交任务，适合前端预览、人工审核和 AI 规则生成后的安全审查。
- 任务类型固定为 `DATA_QUALITY_SCAN`，后续质量执行器可以按类型认领任务。
- 提交链路仍通过本地 JSON 契约解耦，不直接依赖 task-management 内部 Java 包。

当前边界：

- 当前只创建任务，不启动真实质量执行器；任务执行、扫描、心跳、报告回写仍待实现。
- 任务 payload 当前保存为 JSON 字符串，尚未建立版本化 schema 或 payload 校验器。
- 当前 HTTP 调用还没有服务发现、鉴权、重试、熔断和调用指标。
- task-management 还没有质量任务专用模板、任务类型枚举、队列配额或租户公平策略。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-04-27T22:17:59+08:00`。

### 2026-04-26：gateway 路由授权元数据映射基线

本次继续增强 `gateway` 的入口授权能力，把 `resourceType/action` 从过滤器中的硬编码推断，升级为 `datasmart.gateway.authorization.route-metadata` 可配置契约。

落地内容：

- `GatewayAuthorizationProperties` 新增 `routeMetadata`，支持按路径规则配置资源类型、默认动作、HTTP 方法动作映射和说明。
- `GatewayAuthorizationFilter` 优先读取 route metadata 构造 permission-admin 判定请求，未命中配置时才回退到旧的路径/方法推断逻辑。
- `application.yml` 新增 datasource、task、quality、permission、observability 五类模块前缀的默认授权元数据，并补充详细中文说明。

设计价值：

- 后续新增 `data-asset`、`compliance-masking`、`agent-runtime`、`python-ai-services` 等模块时，可以先补路由元数据，不必频繁修改网关核心过滤器。
- 这为更细粒度的动作语义预留入口，例如 `RETRY`、`CANCEL`、`APPROVE`、`EXPORT`、`ARCHIVE`、`FORCE_TERMINATE` 等商业系统常见操作。
- 当前仍是模块前缀级基线，不是最终端点级权限模型；未来应把高风险动作、按钮权限、管理动作和审批动作继续下沉到 permission-admin 统一维护。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-26：gateway 授权判定本地缓存与手动失效基线

本次继续增强 `gateway` 的生产性能能力，新增授权判定本地 TTL 缓存，避免开启强制授权后每个请求都远程调用 permission-admin。

落地内容：

- `GatewayAuthorizationProperties` 新增 `cache` 配置，支持启停缓存、影子模式缓存策略、拒绝结果缓存策略、审批结果缓存策略、允许/拒绝 TTL、最大条目数和管理端点配置。
- 新增 `GatewayAuthorizationDecisionCache`，使用 `ConcurrentHashMap + TTL + 最大容量保护` 实现本地缓存，并提供命中、未命中、清理次数和最近清理时间快照。
- `GatewayAuthorizationFilter` 接入缓存：先查本地缓存，未命中时调用 permission-admin，成功返回后按策略写入缓存。
- 新增 `GatewayAuthorizationCacheController`，在显式开启管理端点并配置内部 Token 后，支持查询缓存快照、全量清理、按租户清理。
- `application.yml` 新增详细中文配置说明，默认保持缓存关闭、管理端点关闭，避免影响当前联调和安全边界。

设计边界：

- 当前是单 gateway 实例本地缓存，不解决多实例一致性问题。
- 当前手动失效只适合联调、排障和过渡期运维，不是最终生产策略。
- 商业化下一阶段应接入 permission-admin 权限变更事件，例如 Kafka 广播策略变更，所有 gateway 实例按租户、角色、资源或策略版本自动失效。
- 后续应将缓存命中率、远程授权耗时、缓存条目数、失效次数接入 observability 指标体系。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-26：permission-admin 路由策略管理 API 与审计基线

本次把 `permission-admin` 从只读权限矩阵和 seed 策略，推进到第一版可运营的路由策略管理能力。

落地内容：

- 新增 `PermissionRoutePolicyMutationRequest`，用于创建和更新路由策略，包含租户、策略名称、角色、HTTP 方法、路径模式、ALLOW/DENY、优先级、启用状态和说明。
- 新增 `PermissionRoutePolicyEnabledRequest`，用于启用或禁用策略；当前刻意不提供物理删除，避免丢失审计证据和历史策略。
- 新增 `PermissionActorContext`，从 `X-DataSmart-*` Header 中承接操作者上下文，让 permission-admin 在服务层继续做高风险操作校验。
- `PermissionAdminController` 新增路由策略创建、更新、启停 API，并增强策略列表查询，支持角色可选和包含禁用策略。
- `PermissionAdminServiceImpl` 新增路由策略管理逻辑：字段规范化、角色存在校验、重复策略校验、平台管理员/租户管理员边界校验、变更审计记录。

当前服务内权限边界：

- 平台管理员可以管理全局策略和任意租户策略。
- 租户管理员只能管理自己租户的非全局策略。
- 普通用户、项目负责人、运营人员、审计员、服务账号默认不能直接修改路由策略。

设计边界：

- 当前策略变更后还不会自动通知 gateway 清理授权缓存。
- 当前高风险变更直接生效，尚未接入审批流。
- 当前审计记录写入 MySQL 审计表，后续应继续发布 Kafka 审计事件，并接入 observability。
- 当前路由策略仍是路径/方法级，后续应继续扩展 endpoint/action/button/admin-operation 级权限。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-26：权限策略变更事件与 gateway 缓存自动失效基线

本次把 `permission-admin` 的策略变更和 `gateway` 的授权缓存打通，形成第一版“权限策略变更 -> 事件发布 -> 网关缓存失效”的闭环。

落地内容：

- 在 `platform-common` 新增 `PermissionPolicyChangedEvent`，作为跨服务权限策略变更事件契约。
- 在 `permission-admin` 新增 `PermissionPolicyEventProperties` 和 `PermissionPolicyChangedEventPublisher`，路由策略创建、更新、启停成功后可发布 Kafka 事件。
- 在 `gateway` 新增 `GatewayPermissionPolicyEventProperties` 和 `GatewayPermissionPolicyChangedEventConsumer`，消费策略变更事件后清理本地授权缓存。
- `permission-admin` 与 `gateway` 的事件开关默认关闭，保证本地没有 Kafka 时仍能启动和联调。
- `gateway` 当前失效规则：全局租户 `tenantId=0` 执行全量缓存清理，普通租户执行租户级缓存清理。

设计边界：

- 当前事件发送不是 outbox 事务消息，Kafka 发送失败不会回滚数据库策略变更。
- 当前 gateway 使用固定 consumer group，本地开发可用；多 gateway 实例生产部署时，如果要让每个实例都清理本地缓存，应使用实例级 consumer group、广播机制，或改为 Redis 共享缓存。
- 当前只覆盖路由策略变更，后续角色绑定、菜单绑定、数据范围策略、审批策略变更也应发布同类事件。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-27：permission-admin 权限策略事件事务 outbox 基线

本次继续增强权限策略事件可靠性，把上一轮的“策略变更后直接发送 Kafka”升级为事务 outbox。

落地内容：

- 新增 `PermissionEventOutbox` 实体和 `PermissionEventOutboxMapper`，用于持久化待投递权限事件。
- `PermissionPolicyChangedEventPublisher` 改为写入 outbox，而不是在业务线程内直接发送 Kafka。
- 新增 `PermissionPolicyOutboxDispatcher`，定时扫描 PENDING/FAILED 事件，声明 SENDING 后发送 Kafka，成功标记 SENT，失败标记 FAILED 或 DEAD。
- `PermissionAdminApplication` 开启 `@EnableScheduling`，让 outbox 后台投递器可运行。
- `permission-admin` 配置新增 outbox 投递器开关、批量大小、调度间隔、发送超时、重试间隔、最大尝试次数和 SENDING 超时恢复。
- `docker/mysql/init/permission-admin.sql` 新增 `permission_event_outbox` 表结构。

设计价值：

- 策略变更、审计记录、outbox 事件可以处于同一个数据库事务中，减少“数据库已变更但 Kafka 事件丢失”的风险。
- Kafka 短暂不可用时，事件会留在数据库中等待重试，不会立即丢失。
- SENDING 超时恢复避免服务发送过程中崩溃后事件永久卡住。

当前边界：

- 还没有 outbox 查询、重放、人工标记 DEAD 恢复等管理 API。
- 还没有 outbox 指标、告警和死信事件可视化。
- 当前重试是固定间隔，后续可升级为指数退避。
- Docker 初始化 SQL 只会在新 MySQL 数据卷首次初始化时自动执行；已有数据库需要手动补表或重新初始化卷。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-27：permission-admin outbox 运维管理与审计查询基线

本次把上一轮 outbox 可靠事件能力继续补成可运营控制面，让权限中心不仅能“自动重试”，也能被管理员查询、恢复和审计。

落地内容：

- 新增 `PermissionOperationsController`，提供权限运维与审计类 API，路径为 `/permissions/operations/**` 和 `/api/permission/operations/**`。
- 新增 `PermissionOperationsService` 与实现类，把 outbox 运维逻辑从权限事实管理逻辑中拆出来，避免 `PermissionAdminServiceImpl` 继续膨胀。
- 新增 outbox 分页查询、详情查询、人工重试、人工忽略接口。
- 新增审计记录分页查询接口，支持按租户、操作者、角色、资源、动作、结果、traceId 和时间范围过滤。
- 扩展 `PermissionEventOutboxMapper`，支持人工重试和人工忽略的受控状态推进。
- 扩展 outbox 状态语义，新增 `IGNORED`，用于区分“系统重试耗尽的 DEAD”与“管理员确认不再投递”。
- 扩展 permission-admin 初始化 SQL，补充 `IGNORED` 状态说明，并为 `OPERATOR` 增加权限运维面查看与 outbox 重试的默认路由策略。

当前权限边界：

- 平台管理员可以查看全平台 outbox 和审计记录，可以重试和忽略 outbox 事件。
- 租户管理员、审计员、运营人员可以在自身租户范围内查看 outbox 和审计记录。
- 运营人员可以在自身租户范围内人工重试 FAILED、DEAD、IGNORED 事件。
- 忽略 outbox 事件只开放给平台管理员，因为它可能永久跳过一次权限缓存失效通知。

设计价值：

- 权限策略变更事件从“可靠存储”进一步走向“可运维恢复”。
- 当 Kafka、gateway 消费或授权缓存失效链路出现问题时，管理员可以定位和恢复事件，而不是只能查数据库脚本。
- outbox 人工操作会写入 `permission_audit_record`，保证故障恢复动作本身也可追责。

当前边界：

- 暂未接入 Micrometer 指标，后续应补 outbox backlog、DEAD 数量、最老待发送事件年龄和投递成功/失败计数。
- 暂未提供批量重试、批量忽略和导出能力，后续可面向大规模事故恢复扩展。
- 暂未接入审批流；忽略 outbox、跨租户策略变更、平台级 broad pattern 策略仍应逐步进入审批模型。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-27：task-management 任务运维控制面基线

本次把重心从 `permission-admin` 移到 P0 优先级的 `task-management`，补齐真实任务平台必须具备的一组运营干预能力。

落地内容：

- 新增 `TaskActorContext`，从 gateway 平台 Header 承接租户、操作者、角色和 traceId。
- 新增 `TaskAdminActionRequest`，用于暂停、恢复、取消、强制重试等动作携带原因和 `ignoreRetryLimit`。
- 新增 `TaskPriorityOverrideRequest`，用于管理员覆盖任务优先级并记录原因。
- 扩展 `TaskService` 与 `TaskServiceImpl`，新增强制暂停、强制恢复、强制取消、强制重试、优先级覆盖方法。
- 扩展 `TaskController`，新增 `/tasks/{id}/admin/pause`、`/admin/resume`、`/admin/cancel`、`/admin/retry`、`/admin/priority` 管理动作接口。
- 扩展 `TaskStatus`，预留 `RETRYING` 状态语义，为后续选择性重试、断点续跑、重试排队等能力铺路。
- 管理动作会把操作者、角色、租户、traceId、原因、重试次数和优先级变化写入 `task_execution_log`。

当前状态流转设计：

- 普通接口保持严格状态流转，例如普通暂停仍只允许 `RUNNING -> PAUSED`。
- 管理员强制暂停允许 `PENDING/RUNNING -> PAUSED`，用于冻结待调度或运行中任务。
- 管理员恢复采用 `PAUSED -> PENDING`，避免管理接口直接伪造 `RUNNING` 执行态。
- 管理员强制取消允许取消非成功、非已取消任务，适合事故止损和人工确认不再恢复。
- 管理员强制重试允许 `FAILED/CANCELLED/PAUSED -> PENDING`，并可在填写原因后忽略重试次数上限。
- 优先级覆盖不直接触发队列重排，因为当前还没有独立调度队列；后续接入队列后应补重排与公平性检查。

当前权限边界：

- 服务层允许 `OPERATOR`、`TENANT_ADMINISTRATOR`、`PLATFORM_ADMINISTRATOR` 执行任务运维动作。
- 当前 `task` 表暂未包含 tenantId / ownerId，因此还无法做租户级数据范围校验，只能先做角色级二次防线。
- 后续需要补任务表租户字段、任务负责人字段、项目字段，再把角色校验升级为“角色 + 数据范围”双重校验。

设计价值：

- 任务中心开始从 CRUD/生命周期动作走向真实运营平台。
- 管理员动作被显式建模，而不是简单更新 status 字段。
- 所有人工干预都进入执行日志，为后续审计中心、任务时间线、事故复盘和告警联动提供基础数据。

当前边界：

- 还没有独立任务队列表、执行记录表、租约抢占和调度器。
- 还没有任务依赖、批量操作、选择性重试、backfill/replay、artifact 查询。
- 当前执行日志 details 仍是文本，后续应升级为结构化 JSON，并补充 tenantId、actorId、traceId 索引字段。
- 当前管理员动作还未接入 permission-admin 的 route-policy 种子细化，gateway 侧仍主要依赖 `/api/task/**` 粗粒度策略。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-27：task-management 执行记录、认领租约与心跳恢复基线

本次继续推进 `task-management` 的生产化调度能力，把任务从“人工改状态”推进到“执行器认领、心跳续租、超时恢复”的第一版闭环。

落地内容：

- 新增 `TaskExecutionRun` 实体，用于记录每一次任务执行尝试。
- 新增 `TaskExecutionRunMapper`，支持查询最大 runNo、扫描超时 RUNNING 执行记录、结束运行中执行记录。
- 新增 `TaskExecutionRunState`，区分任务主状态和单次执行状态。
- 扩展 `Task` 实体，新增当前执行记录、当前执行器、入队时间、心跳时间、租约过期时间、是否需要运营关注、默认超时时间等字段。
- 扩展 `TaskMapper`，新增按优先级挑选可认领任务、条件认领任务、执行器心跳续租 SQL。
- 新增 `TaskExecutionClaimRequest`、`TaskExecutionClaimResult`、`TaskExecutionHeartbeatRequest`、`TaskLeaseRecoveryResult`。
- 扩展 `TaskService` 与 `TaskServiceImpl`，新增执行器认领、心跳续租、租约超时恢复、执行记录查询能力。
- 扩展 `TaskController`，新增 `/tasks/executions/claim`、`/tasks/executions/{runId}/heartbeat`、`/tasks/executions/recover-timeout`、`/tasks/{id}/runs`。
- 更新 `docker/mysql/init/init.sql`，为 `task` 表补执行器/租约字段，并新增 `task_execution_run` 表。

设计价值：

- `task` 主表继续保存当前快照，`task_execution_run` 保存每一次执行尝试，`task_execution_log` 保存状态事件。
- 认领通过数据库条件更新实现轻量并发保护，避免多个执行器同时领取同一条 PENDING 任务。
- 心跳同时更新主表和 run 表，使任务列表、任务详情和执行历史能看到一致进度。
- 超时恢复当前采用保守策略：把租约过期任务标记为 FAILED，并设置 `attentionRequired=true`，避免盲目自动重跑造成重复写入或重复调用外部系统。

当前边界：

- 当前仍是数据库轻量队列，不是最终高吞吐调度队列。
- 暂未实现定时自动恢复，`recover-timeout` 仍需手动触发；后续可挂到调度器或独立 worker。
- 暂未支持多租户公平调度、并发配额、任务依赖、批量领取、分片执行、选择性重试。
- 已有数据库不会自动拥有新增列和 `task_execution_run` 表，需要手动迁移或重新初始化 Docker MySQL 数据卷。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。

### 2026-04-27：data-quality 规则生命周期与检测执行记录基线

本次把实现重心切到 `data-quality`，避免继续只深挖 task-management，让产品能力从任务执行底座扩展到质量治理域。

落地内容：

- 扩展 `QualityRuleStatus`，从简单 ACTIVE/INACTIVE/DELETED 扩展为 `DRAFT`、`ACTIVE`、`INACTIVE`、`ARCHIVED`、`DELETED`。
- 新增 `QualityCheckExecution` 实体，用于记录每一次质量检测动作。
- 新增 `QualityCheckExecutionMapper`，支持按规则查询最大执行序号。
- 新增 `QualityCheckExecutionState`，区分检测动作执行状态与报告通过/失败状态。
- 扩展 `QualityRule`，新增规则版本、最近检测时间、最近检测结果、最近报告 ID、归档时间。
- 扩展 `QualityCheckReport`，新增 executionId、ruleVersion、severity、passRate、triggerType 等报告快照字段。
- 新增 `QualityRuleLifecycleRequest`，让启用、停用、归档、恢复动作可以携带原因。
- 扩展 `DataQualityService` 和 `DataQualityServiceImpl`，新增归档、恢复、执行记录查询，并在执行检测时创建 execution、生成 report、回写规则最近检测快照。
- 扩展 `DataQualityController`，新增 `/quality-rules/{id}/archive`、`/restore`、`/executions`，并让 enable/disable 支持原因请求体。
- 更新 `docker/mysql/init/init.sql`，新增 `quality_check_execution` 表，并扩展 `quality_rule`、`quality_check_report` 字段。

设计价值：

- 新建规则默认进入 `DRAFT`，避免尚未评审的规则直接参与生产检测。
- 报告保存规则版本、严重级别、触发类型、通过率等快照，保证规则修改后历史报告仍可解释。
- 执行记录和检测报告分离：执行记录回答“检测动作是否成功跑完”，报告回答“质量结果是否通过”。
- 归档和恢复为规则下线、历史保留、后续审计复盘提供了比直接删除更安全的生命周期语义。

当前边界：

- 当前检测仍是同步 API 传入 measuredValue、sampleSize、exceptionCount，不直接扫描真实数据源。
- 当前 execution 只覆盖成功执行路径；后续接入真实扫描或任务调度后，应记录扫描失败、超时、取消等失败执行。
- 暂未接入 task-management 调度，也未接入 datasource-management 元数据和真实数据抽样。
- 暂未实现规则审批、规则模板、质量维度聚合、异常明细、报告导出、告警联动。
- 已有数据库需要手动迁移或重新初始化 Docker MySQL 数据卷，以获得新增列和 `quality_check_execution` 表。

验证结果：

- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功。
### 2026-05-05：datasource-management 主服务大文件解耦与元数据/只读 SQL 支撑边界拆分

本次按最新 skill 标准继续整改 `datasource-management` 的耦合问题，不再只做小范围移动，而是一次性拆出两条高复杂度能力线，让主服务回到“数据源生命周期编排”的职责边界内。

落地内容：
- 新增 `DataSourceReadOnlySqlSupport`，承载受控只读 SQL 的开关校验、数据源启用校验、角色权限校验、SQL 安全校验、服务端行数/超时边界、JDBC 查询执行、结果归一化、执行审计写入、SQL 指纹计算、审计预览脱敏和审计分页查询。
- 新增 `DataSourceMetadataDiscoverySupport`，承载元数据发现的权限校验、连接器能力校验、JDBC `DatabaseMetaData` 探查、表/字段/主键/索引/样例预览组装、探查边界限制、warning 生成和进程内 TTL 缓存。
- `DataSourceManagementServiceImpl` 不再直接依赖只读 SQL 配置、审计 Mapper、元数据发现配置、权限评估器、正则脱敏规则和大量 JDBC 元数据组装细节，只保留数据源 CRUD、启停删除、连接测试、能力画像与支撑组件编排。
- `DataSourceManagementServiceImpl` 行数从本轮开始前约 1223 行压缩到 361 行，`DataSourceReadOnlySqlSupport` 为 450 行，`DataSourceMetadataDiscoverySupport` 为 486 行，均满足“单文件尽量控制在 500 行内”的当前规范。

设计价值：
- 数据源生命周期、元数据发现、受控数据访问三类业务边界开始分离，后续新增 PostgreSQL 方言增强、连接器插件化、异步元数据采集、审计导出、脱敏中心接入时，不需要继续让主服务膨胀。
- 只读 SQL 被独立建模为高敏感访问能力，便于未来叠加审批、配额、慢查询熔断、敏感字段识别、查询计划预检、SQL Parser/AST 校验和 compliance-masking 模块。
- 元数据发现被独立建模为结构探查能力，便于未来演进为后台采集任务、数据资产目录、血缘分析、字段画像、增量策略推荐和连接器能力矩阵。
- 当前保留同步短链路实现，但代码边界已经为 task-management 异步化、observability 指标化、permission-admin 细粒度策略化预留空间。

当前边界：
- 元数据发现缓存仍是进程内缓存，生产多实例场景应升级为 Redis 或持久化元数据结果表。
- 样本预览当前仍被强制拒绝，后续如果开放，需要补齐字段级脱敏、审批、审计与数据范围授权。
- 只读 SQL 校验仍是保守字符串策略，未来复杂查询、CTE、窗口函数、方言兼容应接入 SQL Parser/AST，而不是继续堆正则。
- 连接测试和通用 JDBC 连接创建仍留在主服务，后续可以继续拆出 `DataSourceConnectionSupport`，并考虑连接池、凭据托管、连接超时策略和探活指标。

验证结果：
- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-05-05T22:49:32+08:00`。
### 2026-05-05：data-quality 规则生命周期与任务调度提交支撑解耦

本次在完成 `datasource-management` 主服务瘦身后，继续转向 `data-quality`，避免项目演进只围绕单一微服务打转。目标是把质量规则生命周期、质量任务提交、执行报告查询三条能力线拆清楚，让 `DataQualityServiceImpl` 不再继续膨胀。

落地内容：
- 新增 `QualityRuleLifecycleSupport`，承载质量规则创建、更新、目标校验、启用、停用、归档、恢复、逻辑删除、名称去重、目标字段归一化和扫描计划生成。
- 新增 `QualityTaskSchedulingSupport`，承载质量扫描计划提交到 task-management 的 dryRun、集成开关、任务创建请求构造、payload 合同组装、payload 校验、租户 ID 解析和远程提交结果处理。
- `DataQualityServiceImpl` 继续保留跨模块编排、关系型 SQL 计划生成、执行器开始/成功/失败回调、人工检测入口和报告/异常查询委托，不再直接持有 task-management 客户端、任务集成配置、payload parser、ObjectMapper 或规则生命周期私有工具方法。
- `DataQualityServiceImpl` 行数从约 816 行压缩到 472 行，`QualityExecutionReportSupport` 为 385 行，`QualityRuleLifecycleSupport` 为 241 行，`QualityTaskSchedulingSupport` 为 156 行，全部满足“单文件尽量控制在 500 行内”的当前规范。

设计价值：
- 质量规则生命周期不再被当成普通 CRUD，而是明确表达 `DRAFT / ACTIVE / INACTIVE / ARCHIVED / DELETED` 的商业治理语义。
- 任务调度提交独立后，未来要升级为批量提交、租户级配额、任务模板、队列容量预检、outbox 可靠投递或服务发现客户端，不需要继续修改主服务。
- 执行报告能力、规则生命周期能力、任务提交能力三条线分离后，后续可以分别增强质量大盘、规则审批、异常样本运营、AI 根因分析和异步质量执行器。

当前边界：
- 当前仍是同步提交 task-management，没有 outbox 兜底；如果 task-management 短暂不可用，仍依赖现有 fail-open 策略。
- 规则生命周期还没有审批流、版本发布、灰度启用、批量变更和租户级规则目录。
- 质量任务调度还没有队列容量预检、任务依赖、执行窗口、租户公平调度和并发配额。
- `DataQualityServiceImpl` 已低于 500 行，但后续仍可继续把执行器回调编排、关系型 SQL 计划编排进一步独立，形成更干净的应用服务层。

验证结果：
- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-05-05T22:56:11+08:00`。
### 2026-05-05：datasource-management 同步任务主服务三段式解耦

本次继续响应“大文件不要每次只拆一点”的整改要求，针对 `SyncTaskServiceImpl` 一次性拆出三条核心能力线，使同步任务主服务从执行控制巨石逐步回到应用编排层。

落地内容：
- 新增 `SyncTaskLifecycleSupport`，承载同步任务创建、更新、提交审批、审批、排期、入队、归档、任务查询、模板启用校验、审批就绪校验、启用校验、状态守卫、队列字段维护和名称唯一性校验。
- 新增 `SyncExecutorDispatchSupport`，承载执行器认领、心跳续租、过期租约恢复、自动重新入队、租约过期执行记录失败化、认领结果构造和执行器相关审计。
- 新增 `SyncQueueInspectionSupport`，承载队列健康快照、全局/租户积压统计、老化任务扫描、运营关注标记、队列告警打开和队列巡检审计。
- `SyncTaskServiceImpl` 不再直接承载生命周期、worker 调度和队列巡检的大段细节，当前主要保留人工运行、暂停、恢复、重试、取消、管理员强制动作、优先级/超时覆盖、执行进度/完成/失败回调和查询委托。
- 文件行数结果：`SyncTaskServiceImpl` 从约 934 行压缩到 443 行，`SyncTaskLifecycleSupport` 约 283 行，`SyncExecutorDispatchSupport` 约 272 行，`SyncQueueInspectionSupport` 约 235 行，全部满足“单文件尽量控制在 500 行内”的当前规范。

设计价值：
- 同步任务生命周期、执行器调度、队列巡检三条商业化核心能力线独立出来，后续可以分别增强审批流、批量调度、租户公平、worker 分组、租约自愈、队列告警和运营大盘。
- 执行器调度不再只是“改成 RUNNING”，而是明确包含认领、心跳、租约、失联恢复和自动重新入队，为未来真实 worker 集群打基础。
- 队列健康从普通查询升级为运营观察面，后续可接入 observability 指标、告警中心、自动扩容、租户配额和积压治理策略。

当前边界：
- 人工运行、暂停、重试、取消、完成/失败回调仍留在 `SyncTaskServiceImpl`，后续可继续拆成 `SyncTaskExecutionControlSupport`。
- 租约恢复仍以数据库轻量查询为主，还没有分布式锁、乐观锁版本号、批量认领或 worker 分组。
- 队列巡检已能生成快照和告警，但还没有 Micrometer 指标、Prometheus 暴露、自动扩容联动或租户级 SLA。
- 当前同步任务仍未真正接入独立执行器进程，控制面契约已经准备好，但执行器实现仍是后续重点。

验证结果：
- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-05-05T23:07:02+08:00`。
### 2026-05-05：datasource-management 同步任务执行控制面解耦

本次继续在上一轮三段式解耦基础上推进 `SyncTaskServiceImpl`，把剩余的执行控制动作集中迁移到独立支撑组件，使同步任务主服务进一步收敛为应用门面。

落地内容：
- 新增 `SyncTaskExecutionControlSupport`，承载人工运行、暂停、恢复、常规重试、取消、管理员强制重试、管理员强制取消、优先级覆盖、超时覆盖、进度回写、完成回调和失败回调。
- `SyncTaskServiceImpl` 中上述执行控制方法全部改为委托调用，主服务不再直接持有执行器配置、执行记录 Mapper、队列容量组件或执行权限组件。
- `SyncTaskServiceImpl` 当前主要负责暴露 `SyncTaskService` 接口、声明事务边界，并把请求分发到生命周期、执行控制、执行器调度、队列巡检、执行记录和审计组件。
- 文件行数结果：`SyncTaskServiceImpl` 从上一轮约 441 行继续压缩到约 208 行，`SyncTaskExecutionControlSupport` 约 330 行，仍满足“单文件尽量控制在 500 行内”的当前规范。

设计价值：
- 执行控制面被独立建模后，后续可以集中补充回调幂等键、乐观锁、执行器签名校验、任务中止信号、分片完成汇总、补偿/回滚策略、执行指标和 SLA 事件。
- 主服务从“知道所有细节的胖服务”转为“按业务域分发的应用门面”，更利于长期维护和学习理解。
- 当前同步任务域已经形成较清晰的组件边界：生命周期、执行控制、执行器调度、队列容量、队列策略、队列告警、队列巡检、执行记录持久化、权限、审计。

当前边界：
- 执行控制仍是数据库轻量状态推进，没有乐观锁版本号、回调幂等键、执行器签名和分布式中止信号。
- 暂停/取消当前主要更新控制面状态，未来真实执行器接入后，还需要执行器主动感知取消信号并安全停止写入。
- 完成/失败回调没有区分分片级完成和任务级完成，未来分片并发执行时应补充分片执行记录和汇总策略。
- 优先级/超时覆盖已经独立，但还未触发真实队列重排或运行中超时策略重算。

验证结果：
- 已执行 `./mvnw compile`，8 个 Maven Reactor 模块全部编译成功，完成时间 `2026-05-05T23:13:14+08:00`。
### 2026-05-05：data-quality 质量执行器执行闭环继续解耦

本次继续按照最新 skill 标准推进 `data-quality`，重点不是新增一个孤立功能点，而是把已经落地的质量执行器闭环整理成更清晰、可长期演进的商业化结构。此前 `QualityTaskExecutorCoordinator` 已经具备 task-management 认领、payload 校验、execution 创建、心跳、关系型受控 SQL 扫描、报告回写、失败/延期收口和指标记录能力，但单文件约 609 行，超过“单文件尽量控制在 500 行内”的结构规范。

落地内容：
- 新增 `QualityExecutorOutcome`，统一维护质量执行器 outcome 字符串，避免接口返回、日志、指标、告警规则各自硬编码。
- 新增 `QualityExecutionFailureSupport`，集中承载失败收口逻辑，包括普通失败标记 `FAILED`、未支持策略标记、data-quality execution 失败回写、task-management 失败回写、并发护栏触发后的 defer 回队列和 DEAD_LETTER 提示。
- 新增 `QualityRelationalScanExecutor`，集中承载关系型质量扫描细节，包括 SQL 计划生成、datasource-management 只读 SQL 代理调用、metric 结果解析、异常样本转换、质量报告成功回写和 task-management 完成结果 JSON 构造。
- 重写 `QualityTaskExecutorCoordinator` 的职责边界，使其只保留主流程编排：开关判断、任务认领、payload 校验、并发许可、execution 创建、调用扫描器、完成任务、失败委托。
- 所有新增 Java 文件均按作者头模板创建，并补充中文字段、方法、设计意图、状态流转和商业化边界说明。

行数结果：
- `QualityTaskExecutorCoordinator` 从约 609 行降低到约 204 行。
- `QualityRelationalScanExecutor` 约 257 行。
- `QualityExecutionFailureSupport` 约 121 行。
- `QualityExecutorOutcome` 约 38 行。
- 本次涉及的新拆分文件均低于 500 行目标。

商业化设计价值：
- coordinator 不再同时扮演流程导演、SQL 执行员、异常补偿员和指标解析员，降低后续继续扩展 Kafka、文件、API、对象存储、跨表一致性、AI 根因分析等扫描器时的耦合成本。
- 失败收口被独立建模后，能够更清楚地区分“业务执行失败”和“容量背压延期”，避免并发护栏触发污染质量失败率与任务失败率指标。
- 关系型扫描被独立建模后，后续可以在该组件内继续扩展 PostgreSQL/MySQL 方言差异、复杂规则 SQL、分区扫描、慢查询熔断、字段脱敏、样本归档和批量证据上报，而不让任务编排层膨胀。
- outcome 常量独立后，后续接入 observability、告警中心、任务运营台和前端状态枚举时有统一语义来源。

当前边界：
- 当前真实扫描仍主要支持关系型 `COMPLETENESS` 与 `UNIQUENESS` 两类规则；`VALIDITY`、`ACCURACY`、`CONSISTENCY`、跨表校验、Kafka、文件和 API 场景还需要后续扩展专用执行器。
- 并发护栏仍是单 data-quality 实例内保护，不是全局分布式配额；多实例商业化部署后应结合 task-management 队列能力、Redis/数据库租约、租户 SLA 和数据源容量策略继续增强。
- 异常样本仍是小批量同步写入，海量异常证据后续应扩展为分片上报、批量落库、对象存储归档、脱敏策略和保留周期治理。
- `QualityRelationalScanExecutor` 当前仍从当前规则表读取结构字段；如果要严格按任务创建时快照执行，需要继续把 tableName、fieldName、targetType、comparisonOperator 等字段固化进 payload。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-05T23:21:32+08:00`。
### 2026-05-05：datasource-management 同步模板智能校验与审计职责解耦

本次继续按最新 skill 标准治理大文件和高耦合问题，重点处理 `SyncTemplateServiceImpl`。该类此前同时承载模板 CRUD、权限校验、元数据发现、同步模式校验、写入策略风险判断、字段映射 JSON 解析、字段类型/长度/必填校验、审计 payload 构造等多条职责线，单文件约 1023 行，已经不利于继续演进为商业化同步产品。

落地内容：
- 新增 `SyncTemplateAuditSupport`，集中承载同步模板创建、更新、智能校验的审计记录写入和轻量 payload 构造，避免模板主服务直接依赖审计表结构。
- 新增 `SyncTemplateFieldMappingSupport`，集中承载字段映射 JSON 解析、源/目标字段存在性校验、类型大类兼容判断、长度/精度截断风险识别、目标必填字段漏映射识别和自动映射建议生成。
- 新增 `SyncTemplateValidationSupport`，集中承载模板智能校验主流程，包括模板管理权限、数据源可用性、源/目标对象存在性、同步模式要求、主键字段、写入策略、结构风险和字段映射摘要。
- 重写 `SyncTemplateServiceImpl`，让其回到应用服务编排层：只保留模板创建、更新、预览、智能校验入口、事务边界、名称唯一性校验、数据源存在性校验和 support 委托。

行数结果：
- `SyncTemplateServiceImpl` 从约 1023 行降低到约 299 行。
- `SyncTemplateValidationSupport` 约 381 行。
- `SyncTemplateFieldMappingSupport` 约 316 行。
- `SyncTemplateAuditSupport` 约 118 行。
- 本次拆分后的模板相关文件均低于 500 行目标。

商业化设计价值：
- 模板主服务从“知道所有细节的胖服务”转为“事务入口和业务编排门面”，后续新增模板版本、审批发布、灰度启用、租户级模板目录和模板复制时，不需要继续把细节堆进 Impl。
- 字段映射独立后，可以继续扩展为 PostgreSQL/MySQL 方言差异、嵌套 JSON 路径映射、字段级脱敏、默认值表达式、类型转换表达式、异常字段旁路和 AI 辅助映射建议。
- 智能校验独立后，可以演进为多连接器校验策略注册表，分别支持关系型表、Kafka topic、MongoDB collection、文件 schema、API schema、对象存储清单和 CDC 位点校验。
- 模板审计独立后，为未来统一 audit-center、compliance-center、SIEM 投递、敏感配置变更对比和审批流事件预留了清晰扩展点。

当前边界：
- 智能校验仍以关系型表元数据为主要基础，Kafka、文件、API、对象存储、MongoDB 等连接器仍需要专门的 schema 校验策略。
- 字段映射目前只校验字段存在性、类型大类、长度/精度和必填字段，尚未支持复杂表达式、默认值、枚举映射、脱敏规则、时区转换和编码转换。
- `SyncGovernanceAlertServiceImpl` 仍约 877 行，是 datasource-management 当前最明显的剩余大文件，后续应优先拆出告警规则、告警状态流转、通知策略、聚合统计和审计能力。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-05T23:31:26+08:00`。
### 2026-05-05：datasource-management 治理告警服务 outbox 与投递链路解耦

本次继续处理 datasource-management 中最后一个明显超过 500 行的胖服务 `SyncGovernanceAlertServiceImpl`。该类此前同时承载告警打开/刷新、确认/解决、分页查询、权限边界、outbox 批量认领、租约恢复、健康快照、webhook 投递、通道链、投递记录、失败重试和死信判断，单文件约 877 行。

落地内容：
- 新增 `SyncAlertPermissionSupport`，集中承载告警租户查询范围、列表权限、单条告警动作权限和投递记录查看权限。
- 新增 `SyncAlertLifecycleSupport`，集中承载告警打开、刷新、去重、严重级别升级、确认、解决、死信重入队和列表查询。
- 新增 `SyncAlertDeliverySupport`，集中承载告警通道链、内部日志、通用 Webhook、飞书 Webhook、企业微信 Webhook、投递结果回写和投递记录落库。
- 新增 `SyncAlertOutboxSupport`，集中承载 outbox 到期候选查询、批量认领、租约释放、批量补投结果聚合、过期租约恢复和 outbox 健康快照。
- 重写 `SyncGovernanceAlertServiceImpl`，让主服务只保留接口实现、事务边界、权限/生命周期/outbox/投递组件编排。

行数结果：
- `SyncGovernanceAlertServiceImpl` 从约 877 行降低到约 219 行。
- `SyncAlertDeliverySupport` 约 324 行。
- `SyncAlertOutboxSupport` 约 259 行。
- `SyncAlertLifecycleSupport` 约 225 行。
- `SyncAlertPermissionSupport` 约 119 行。
- datasource-management 当前已没有超过 500 行的 Java 文件。

商业化设计价值：
- 治理告警开始具备更清晰的产品域边界：告警对象生命周期、权限边界、投递 outbox、通道投递、健康巡检分别独立，后续可以按能力线扩展。
- outbox 能力独立后，未来可以升级为 Redis 分布式租约、独立 outbox 表、Kafka 延迟队列、Prometheus 指标、告警积压告警和自动恢复任务，而不需要改动主服务入口。
- 投递链路独立后，可以继续接入邮件、短信、Slack、Teams、统一通知中心、签名校验、消息模板、多语言和租户级通道配置。
- 生命周期独立后，可以继续补充告警抑制、告警归并、负责人、SLA、升级策略、关闭原因、影响范围评估和事故复盘入口。

当前边界：
- 当前通道投递仍是同步 HTTP 调用；高并发告警场景应继续引入异步发送线程池、限流、熔断、批量发送和租户级配额。
- outbox 仍复用告警主表字段，未来告警量上升后建议拆独立 outbox 表或事件表，降低业务查询与投递调度之间的索引竞争。
- 当前 channelChain 只支持配置级全局通道链，尚未支持租户级、告警类型级、严重级别级差异化通知策略。
- 全仓当前超过 500 行的 Java 文件剩余 `TaskServiceImpl` 约 1104 行、`PermissionAdminServiceImpl` 约 625 行，应继续横向治理。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-05T23:39:25+08:00`。
### 2026-05-05：task-management 主服务门面化与调度职责解耦

本次横向切回 `task-management`，处理当前全仓最大的剩余胖服务 `TaskServiceImpl`。该类此前同时承载普通生命周期、管理员强控、队列运营查询、执行器认领、心跳续租、租约恢复、run 记录、执行日志和大量私有工具方法，约 1104 行，已经不适合作为后续商业化任务平台的持续演进基础。

落地内容：
- 新增 `TaskLifecycleSupport`，承载普通任务生命周期状态机：创建、启动、暂停、恢复、取消、普通重试、进度更新、完成、失败、执行器 defer 回队列和连续退避死信保护。
- 新增 `TaskAdminOperationSupport`，承载管理员强控动作：强制暂停、强制恢复、强制取消、强制重试、优先级覆盖，并统一写入操作者、原因和 trace 上下文。
- 新增 `TaskExecutionRunSupport`，承载执行器协议：任务认领、数据库条件抢占、run 创建、心跳续租、租约超时恢复、run 历史查询和当前 run 收口。
- 新增 `TaskQueueInspectionSupport`，承载队列运营视图、运营项 DTO 转换、状态分布、关注任务数、最老排队时间和最大 defer 次数统计。
- 新增 `TaskExecutionLogSupport`，承载结构化执行日志写入、日志查询、operator 标签、管理员 details 和空值文本规范化。
- 新增 `TaskOperationPermissionSupport`，承载服务层二次鉴权，区分管理员运维角色与执行器角色。
- 重写 `TaskServiceImpl` 为薄服务门面，只保留接口实现、事务边界和 support 委托。

行数结果：
- `TaskServiceImpl` 从约 1104 行下降到约 206 行。
- `TaskLifecycleSupport` 约 348 行。
- `TaskExecutionRunSupport` 约 245 行。
- `TaskAdminOperationSupport` 约 213 行。
- `TaskQueueInspectionSupport` 约 209 行。
- `TaskExecutionLogSupport` 约 128 行。
- `TaskOperationPermissionSupport` 约 72 行。
- task-management 当前 service 相关 Java 文件均低于 500 行。

商业化设计价值：
- task-management 从单体胖 Impl 变成可持续扩展的任务平台内核，普通状态机、强控运维、执行器租约、队列运营、日志审计和权限边界已经形成清晰分层。
- 后续新增多线程 worker、分布式执行器、租户公平调度、队列积压告警、任务依赖、replay/backfill、artifact 管理时，可以按能力线扩展，而不是继续堆回主服务。
- 执行器认领与心跳逻辑被独立出来后，更容易继续引入 Redis/DB 分布式租约、批量认领、worker 分组、容量配额和任务类型路由。
- 队列运营视图独立后，后续可接 observability 指标、Prometheus 暴露、SLA 大盘和自动扩容策略。
- 权限与日志独立后，为后续接入 permission-admin 的细粒度策略、租户数据范围校验、统一 audit-center 和合规导出预留了清晰入口。

当前边界：
- task 表仍未包含 tenantId / ownerId / projectId，因此服务层权限仍主要是角色级校验，尚未实现“角色 + 数据范围”的双重保护。
- 队列仍是基于 task 主表的轻量数据库队列，不是高吞吐专用队列；后续高并发场景需要评估独立队列表、Redis sorted set、Kafka 延迟流或调度器分片。
- 租约恢复仍需要显式调用接口触发，尚未接入定时扫描 worker、分布式锁、恢复指标和恢复告警。
- 普通 start/resume 仍保留直接进入 RUNNING 的旧语义；当真实 worker 成为主路径后，应逐步把用户侧启动动作改成入队等待认领。
- 全仓剩余超过 500 行的 Java 文件仅剩 `PermissionAdminServiceImpl`，约 625 行，应作为下一轮结构治理优先目标。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-05T23:51:01+08:00`。
### 2026-05-06：permission-admin 权限中心职责解耦与全仓大文件清零

本次在补齐 `current-repo-state.md` 后继续推进 `permission-admin` 结构治理，处理全仓最后一个超过 500 行的 Java 文件 `PermissionAdminServiceImpl`。该类此前同时承载权限事实查询、路由策略变更、访问判定、数据范围选择、审计记录拼装、JSON 快照构造和策略变更事件发布，约 625 行，职责密度已经偏高。

落地内容：
- 新增 `PermissionAdminSupport`，集中维护平台租户 ID、ANY 方法、编码归一化、租户范围归一化、审计文本和 JSON 转义等权限域通用约定。
- 新增 `PermissionAuditSupport`，集中承载访问判定审计和路由策略变更审计，为后续 audit-center、合规导出和 SIEM 投递预留统一入口。
- 新增 `PermissionQuerySupport`，集中承载角色、菜单、路由策略、数据范围策略和权限矩阵查询，让“权限事实读取”和“授权判定/策略变更”解耦。
- 新增 `PermissionDecisionSupport`，集中承载访问判定流程，包括路由策略匹配、DENY 优先、默认拒绝、数据范围选择和判定审计。
- 新增 `PermissionRoutePolicyMutationSupport`，集中承载路由策略创建、更新、启停、管理员权限校验、业务规则校验、before/after 审计和策略变更事件发布。
- 重写 `PermissionAdminServiceImpl` 为薄服务门面，只保留接口实现、事务边界和 support 委托。

行数结果：
- `PermissionAdminServiceImpl` 从约 625 行下降到约 109 行。
- `PermissionRoutePolicyMutationSupport` 约 252 行。
- `PermissionQuerySupport` 约 134 行。
- `PermissionDecisionSupport` 约 125 行。
- `PermissionAuditSupport` 约 104 行。
- `PermissionAdminSupport` 约 88 行。
- 全仓 Java 源码当前已无超过 500 行的文件。

商业化设计价值：
- 权限中心的“事实查询、访问判定、策略变更、审计写入、事件发布”五类能力边界被拆清楚，后续接入 Redis 缓存、Kafka 缓存失效、审批流和租户数据范围不会继续堆回 Impl。
- 访问判定路径独立后，更适合继续优化授权 P95 延迟、缓存一致性、默认拒绝策略、DENY 优先级和路径匹配器升级。
- 策略变更路径独立后，可继续补审批、双人复核、灰度发布、批量导入、策略版本、回滚和高风险操作告警。
- 审计路径独立后，后续可以统一审计事件分类、结构化 JSON、敏感字段脱敏、审计查询索引和合规导出。
- 查询路径独立后，管理后台的角色矩阵、菜单矩阵、策略列表、租户差异对比和权限报表可以在不影响授权判定路径的情况下扩展。

当前边界：
- 当前仍是数据库直查授权策略，尚未引入 Redis 本地/分布式缓存和策略变更订阅失效机制。
- 路径匹配仍是轻量 `/**` 后缀匹配，未来复杂路由模板建议升级为 Spring `PathPatternParser` 或 gateway route metadata 对齐。
- 数据范围策略当前只返回 scopeLevel/scopeExpression，还没有真正下沉到各业务模块的数据查询条件中。
- 高风险策略变更已审计和发布事件，但尚未接入审批流、双人复核、版本回滚和变更影响分析。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-06T21:20:50+08:00`。
### 2026-05-06：permission-admin 权限事实缓存与运维失效面
本次在完成全仓大文件治理后，开始从“结构优化”转向“商业化能力增强”，优先补齐权限中心高频授权链路的性能与可运维基础。gateway 侧已有最终授权判定缓存，本次补的是 permission-admin 内部的“权限事实缓存”，让 gateway 缓存未命中或业务服务直接调用 `/permissions/evaluate` 时，也不必每次都重复查询路由策略与数据范围策略表。

落地内容：
- 新增 `PermissionPolicyCacheProperties`，提供 `datasmart.permission.policy-cache` 配置，明确缓存开关、路由策略 TTL、数据范围策略 TTL、最大条目数和配置用途。
- 新增 `PermissionPolicyFactCache`，缓存 `tenantId + roleCode + includeDisabled` 的路由策略列表，以及 `tenantId + roleCode + resourceType` 的数据范围策略列表。
- `PermissionQuerySupport` 接入权限事实缓存，保留数据库查询方法为独立私有方法，后续替换 Redis、Caffeine 或多级缓存时不需要改写业务查询语义。
- `PermissionRoutePolicyMutationSupport` 在路由策略创建、更新、启停成功后注册“事务提交后失效”，避免事务未提交时并发 evaluate 把旧策略重新加载进缓存。
- `PermissionOperationsService` 与 `PermissionOperationsController` 新增缓存快照和手工失效接口：`GET /permissions/operations/policy-cache` 与 `POST /permissions/operations/policy-cache/evict`。

设计价值：
- 权限中心具备“gateway 最终判定缓存 + permission-admin 权限事实缓存”的双层性能保护，避免所有授权压力都落到 MySQL。
- 缓存失效被放到事务提交后执行，减少旧策略回填缓存的并发一致性风险。
- 缓存状态可查询、可手工清理，方便排查“策略已改但授权结果不符合预期”的生产问题。
- 当前仍使用本地缓存，符合项目阶段；后续可平滑升级为 Redis 分布式缓存、Kafka 策略版本广播、租户/角色/资源粒度失效和 Micrometer 指标。

当前边界：
- 缓存仍是单实例本地缓存，多实例 permission-admin 部署时需要继续接入 Kafka/Redis 做跨实例一致性。
- 数据范围策略目前还没有独立变更接口，因此主要依赖 TTL 和手工清理；后续补数据范围策略 CRUD 时应同步接入主动失效。
- 缓存指标当前通过管理 API 查看，尚未注册为 Micrometer 指标，也未接入 observability 告警。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-06T21:35:03+08:00`。

### 2026-05-06：task-management 任务归属与数据范围基础能力

本次从 permission-admin 的数据范围策略继续向业务模块下沉，优先补齐 `task-management` 的任务归属字段和查询收口能力。目标不是只给 task 表加字段，而是让任务平台开始具备真实商业产品所需的租户隔离、负责人归属、项目维度运营和执行器按范围认领能力。

落地内容：
- `Task` 新增 `tenantId / ownerId / projectId`，并补充中文字段说明，解释租户隔离、负责人归属、项目级运营和后续权限策略下沉的业务价值。
- `CreateTaskRequest` 新增租户、负责人、项目字段，并说明请求体不是最终可信来源，真实优先级应是 gateway 透传的可信 Header。
- 新增 `TaskDataScopeSupport`，集中承载任务创建归属解析、普通列表范围收口、队列运维范围收口、执行器认领租户过滤和管理员跨租户操作保护，避免把数据权限重新塞回 Controller 或 Impl。
- `TaskService` / `TaskServiceImpl` 新增 `listTasks`，让普通任务列表不再由 Controller 直接拼 Wrapper，而是统一进入服务层做 `tenantId / ownerId / projectId + actorContext` 叠加过滤。
- 队列运维接口 `/tasks/operations/queue`、`/items`、`/summary` 接入 actor header，运维视图不再是无范围的全局旁路查询。
- `TaskExecutionClaimRequest` 和 `TaskMapper.selectNextClaimCandidate` 支持按租户、负责人、项目过滤认领，为租户专属 worker、项目专属 worker、租户公平调度和维护窗口预留执行器协议。
- `TaskAdminOperationSupport` 在强制暂停、恢复、取消、重试和优先级覆盖前增加任务租户范围校验，避免“有动作权限但跨租户操作数据”的越权风险。
- data-quality 远程提交 task-management 的 `TaskCreateRequest` 同步携带 tenantId / ownerId，质量任务进入任务中心后可以直接参与租户级查询、队列隔离和后续配额控制。
- `docker/mysql/init/init.sql` 为 `task` 表补充 `tenant_id / owner_id / project_id` 字段和常用索引，包括租户状态索引、负责人状态索引、项目状态索引以及租户队列认领索引。

设计价值：
- task-management 开始从“任务调度底座”升级为“多租户任务运营平台”，后续 permission-admin 的数据范围策略不再停留在策略表，而是能落到真实业务查询条件中。
- 请求过滤与操作者范围采用叠加模式：请求参数只能缩小结果集，不能扩大权限，降低前端参数被篡改导致越权查询的风险。
- 平台级服务账号保留跨租户提交和消费能力，租户级身份默认收口到自身租户，兼顾商业化安全边界和后台 worker 的实际运行需要。
- 负责人和项目维度为“我的任务”、项目看板、失败通知、SLA 归属、项目级配额、项目级成本审计预留了稳定字段，不需要后续从 JSON payload 中反复解析。

当前边界：
- 这仍然是本地轻量数据范围规则，还没有真正调用 permission-admin 的数据范围策略 API，也没有解析 scopeExpression。
- task 详情、普通 start/pause/resume/cancel/retry 等旧接口仍有部分历史兼容路径没有全面改造成 actorContext 入参，后续应继续补齐。
- 当前数据库初始化脚本新增了列和索引，但已有本地 MySQL 数据卷不会自动迁移，需要后续补正式 migration 或手工 ALTER。
- 执行器认领仍是数据库轻量队列，租户公平性还只是过滤基础，尚未实现按租户配额、权重轮询、批量认领、队列分片或 Redis/Kafka 专用队列。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-06T21:53:07+08:00`。
- 已执行 Java 行数扫描，全仓当前无超过 500 行的 Java 文件；`TaskController` 约 413 行，`TaskLifecycleSupport` 约 359 行，新增 `TaskDataScopeSupport` 约 219 行。
### 2026-05-06：task-management 任务读写入口数据范围闭环

本次继续承接“任务归属与数据范围基础能力”，把上一轮已经落到列表、队列、认领和管理员强控动作上的范围保护，扩展到更常用也更容易被绕过的任务详情、执行日志、执行 run 记录、普通生命周期动作和删除动作。目标不是新增一个孤立接口，而是补齐“用户能直接通过 taskId 访问或操作任务”的全部主要路径，避免列表有范围过滤、详情和动作入口却仍然裸奔。

落地内容：
- `TaskService` 新增 `getTaskDetail(taskId, actorContext)` 与 `deleteTask(taskId, actorContext)`，并把普通 `start/pause/resume/cancel/retry`、日志查询、run 查询升级为携带 `TaskActorContext` 的服务契约。
- `TaskServiceImpl` 在详情、日志、run、删除路径统一复用 `TaskDataScopeSupport.validateTaskInActorScope`，让读取入口和写入入口使用同一套 tenant/owner 数据范围语义。
- `TaskLifecycleSupport` 在普通启动、暂停、恢复、取消、重试前增加数据范围校验，避免用户绕过列表直接构造 `/tasks/{id}/xxx` 操作其他租户或其他负责人任务。
- `TaskDataScopeSupport` 对普通用户进一步收窄 ownerId 范围：同租户内普通用户默认只能操作自己负责的任务；为了兼容历史任务和系统任务，`ownerId` 为空的数据暂不硬阻断。
- `TaskController` 为详情、普通生命周期动作、日志、run、删除入口透传平台上下文；新增 `TaskActorContextResolver` 统一解析 `HttpServletRequest` 与显式 Header 参数，避免每个路由都重复声明或重复解析 4 个 Header 导致 Controller 再次膨胀。
- 删除接口不再直接 `removeById`，而是进入服务层 `deleteTask`，先完成“任务存在性 + 数据范围”校验，再执行删除。

设计价值：
- 任务模块的权限边界从“列表和运维入口受控”升级为“按 ID 读取和按 ID 操作也受控”，更符合真实商用系统的越权防护要求。
- ownerId 在详情和动作路径生效后，可以支撑“我的任务”“我负责的扫描”“项目成员任务协作”等真实后台场景，而不是只停留在查询字段层面。
- 平台上下文解析被拆到独立 `TaskActorContextResolver`，Controller 只负责路由契约和服务编排，后续接入签名校验、服务账号白名单、actorType/sourceService 等上下文字段时也不需要逐个路由修改。
- 该闭环仍保持轻量本地规则，没有提前把 permission-admin 强耦合进 task-management，为后续接入远程数据范围策略、策略缓存、租户配额和项目级授权留下替换空间。

当前边界：
- 当前仍是本地 tenant/owner 规则，尚未真正调用 permission-admin 的数据范围策略 API，也没有解析 scopeExpression。
- executor 回调类接口如 progress/complete/fail/defer 仍主要面向服务账号和执行器协议，后续应继续补充执行器签名、幂等键、回调权限和租约一致性校验。
- 删除语义仍是物理删除，真实商用环境建议升级为逻辑删除、归档、回收站、审批删除或审计保留策略。
- 普通生命周期动作仍只做数据范围校验，动作级权限矩阵后续应继续下沉到 permission-admin，例如区分普通用户、项目负责人、租户管理员是否能取消、重试或暂停不同状态任务。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-06T22:10:22+08:00`。
- 已执行 Java 行数扫描，当前全仓 Java 文件仍无超过 500 行的文件；最高约 459 行，`TaskController` 约 394 行。
### 2026-05-07：task-management 执行器回调安全与幂等基础

本轮继续补齐上一轮留下的执行器回调边界。`progress/complete/fail/defer` 这些接口会直接改写运行中任务的进度、终态或队列归属，如果只依赖 taskId，会在真实生产环境中形成明显风险：旧 worker 租约过期后迟到回写、其他服务误传 taskId、网络超时导致重复完成/失败、容量背压重复 defer 等问题都会污染任务状态。

落地内容：
- 新增 `TaskExecutionCallbackContext`，把执行器回调需要的 `runId / executorId / idempotencyKey / actorContext` 统一建模。
- `TaskProgressRequest`、`TaskCompleteRequest`、`TaskFailRequest`、`TaskDeferRequest` 增加 `runId`、`executorId`、`idempotencyKey` 字段，并补充中文字段说明。
- `TaskService`、`TaskServiceImpl`、`TaskLifecycleSupport` 将 `updateProgress/completeTask/failTask/deferTask` 升级为携带执行器回调上下文的契约。
- `TaskLifecycleSupport` 新增执行器回调校验：调用方角色必须具备执行器权限；任务必须落在 actor 数据范围内；请求 executorId 必须匹配当前租约；请求 runId 必须匹配当前执行 run；租约过期后不接受迟到回写。
- `TaskExecutionLogSupport` 增加轻量幂等查询能力，通过执行日志 details 中的 `idempotencyKey` 识别重复回调。当前阶段先复用日志表，后续高吞吐场景建议升级为独立幂等表或 Redis 去重。
- `data-quality` 侧本地 task-management 合同 DTO 同步增加 `runId/executorId/idempotencyKey`，`TaskManagementClient` 按 `sourceService + taskId + runId + action` 生成稳定幂等键，避免网络重试时每次生成随机键。
- `QualityTaskExecutorCoordinator` 和 `QualityExecutionFailureSupport` 调用 complete/fail/defer 时同步传递 runId 和 executorId，使质量执行器闭环符合新的回调安全协议。

设计价值：
- 执行器回调从“按 taskId 写状态”升级为“按当前租约 + 当前 run + 服务账号角色 + 幂等键写状态”，更接近真实商用调度平台的 worker 协议。
- 旧 worker 迟到回写、新 worker 接管后被旧请求污染、重复 defer 导致 deferCount 虚增等风险被显式收口。
- data-quality 作为第一个真实消费者同步升级合同，为后续 data-sync、agent-runtime、ETL worker 复用同一回调协议打样。

当前边界：
- 幂等当前依赖执行日志 LIKE 查询，适合早期能力闭环，不适合高吞吐生产场景；后续建议落独立 callback_idempotency 表并建立唯一索引。
- 回调校验仍是本地角色和租约校验，尚未接入服务间签名、mTLS、网关服务账号白名单或执行器密钥轮换。
- progress 回调仍写任务主表快照，尚未做高频进度上报降采样、批量上报或指标化限流。
- complete/fail/defer 仍是单任务回调协议，后续分片任务需要补分片级 run、分片级幂等键和汇总策略。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-07T20:42:50+08:00`。
- 已执行 Java 行数扫描；后续使用 UTF-8 明确读取复核时发现仍有部分历史文件超过 500 行，因此该项需要以后续复核清单为准。

### 2026-05-07：task-management 回调幂等表与持久化去重

本轮继续承接上一轮“执行器回调安全与幂等基础”，把临时依赖执行日志 `details LIKE idempotencyKey` 的轻量去重方案，升级成正式的 `task_callback_idempotency` 持久化表与独立 support 组件。这个变化面向真实商用调度平台中的高频重试、HTTP 超时、worker 多实例并发、任务回放审计和运营排障场景，避免把可靠性能力继续耦合在执行日志查询里。

落地内容：
- 新增 `TaskCallbackIdempotency` 实体，字段覆盖任务 ID、回调动作、幂等键、runId、executorId、请求摘要、处理状态、响应摘要、失败摘要、首次看到时间和最近看到时间，并补充详细中文字段说明。
- 新增 `TaskCallbackIdempotencyMapper`，通过 MyBatis-Plus 基础 CRUD 对接幂等表；真正的并发裁决交给数据库唯一键 `task_id + action + idempotency_key`。
- 新增 `TaskCallbackIdempotencySupport`，集中承载幂等登记、唯一键冲突识别、重复请求刷新 `lastSeenTime`、成功状态回写和未来失败审计扩展点。
- `TaskLifecycleSupport` 不再调用执行日志 LIKE 查询判断重复回调，而是在 progress/complete/fail/defer 入口通过幂等 support 先登记 PROCESSING，业务成功后标记 SUCCEEDED。
- 新增 `TaskExecutorCallbackSupport`，把执行器角色、数据范围、executorId、runId、租约过期和回调日志详情从生命周期状态机中拆出，避免 `TaskLifecycleSupport` 因回调协议继续膨胀。
- 新增 `TaskQueueOperationsController` 和 `TaskAdminOperationsController`，分别承载队列运营接口与管理员强控接口，`TaskController` 收敛为普通任务资源、执行器协议和轨迹查询入口。
- `TaskExecutionLogSupport` 移除临时幂等查询职责，重新聚焦“任务可读轨迹”和“审计上下文拼装”，避免日志组件继续承担高并发可靠性判断。
- `docker/mysql/init/init.sql` 新增 `task_callback_idempotency` 表，包含唯一键、run/action 查询索引、executor/time 查询索引和 state/time 运维索引。

设计价值：
- 幂等从“日志副作用”升级为“业务事实表”，后续可以支撑重复回调看板、执行器异常重试分析、幂等记录保留策略和任务回放审计。
- 使用数据库唯一键承担并发下的最终裁决，比“先查日志再写日志”更适合多实例 worker 和多副本 task-management 部署。
- `TaskLifecycleSupport` 保持状态机职责，幂等逻辑独立成 support，避免单文件继续膨胀，也符合当前“单文件尽量小于 500 行”的解耦规范。
- 该模式可复用到后续 data-sync、agent-runtime、ETL worker、告警 outbox 投递和异步事件消费的幂等处理。

当前边界：
- 幂等记录当前参与上层业务事务；如果业务处理抛异常，幂等记录会一起回滚，避免悬挂 PROCESSING。未来如果要记录失败尝试，可用独立事务写 FAILED 审计。
- 当前重复请求直接返回成功，但尚未返回“首次处理响应体”；后续可以基于 `response_summary` 扩展统一幂等响应模型。
- 还没有幂等记录清理任务；生产化部署应增加按 `last_seen_time` 的保留策略、分区或归档策略，避免长期增长。
- 回调身份仍主要依赖 Header 角色、租约和 runId 校验；下一步仍建议补服务账号签名、mTLS、网关白名单和执行器密钥轮换。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-07T21:03:40+08:00`。
- 已执行 UTF-8 Java 行数扫描：本轮触碰的 `TaskController` 已收敛到约 292 行，`TaskLifecycleSupport` 已收敛到约 456 行；全仓仍有 4 个历史超限文件，后续应继续治理 `DataQualityController`、`PermissionOperationsServiceImpl`、`DataSourceMetadataDiscoverySupport`、`DataQualityServiceImpl`。

### 2026-05-07：跨模块历史大文件解耦与 500 行治理闭环

本轮没有继续堆叠新功能，而是优先偿还前期快速推进留下的结构债：对 `data-quality`、`datasource-management`、`permission-admin` 三个模块中仍超过 500 行或职责过宽的类做职责拆分。这个动作对商业化项目很关键，因为真正长期演进的后端产品不应依赖少数超大 Impl 或万能 Controller 承载所有逻辑，否则后续每次新增 PostgreSQL、Kafka、审计、告警、审批、租户隔离、高并发优化时都会反复重构同一个大文件。

落地内容：
- `data-quality` 新增 `QualityReportController`，承载质量报告、异常列表、异常聚合等查询类路由，让规则主控制器不再同时承担报告中心职责。
- `data-quality` 新增 `QualityExecutorOperationsController`，承载执行器回调和 coordinator 运维触发接口，把执行器协议从规则 CRUD 路由中拆出。
- `DataQualityController` 收敛为质量规则创建、查询、启停、计划、调度、手动触发、规则级报告和规则级执行记录入口，当前约 290 行。
- `DataQualityServiceImpl` 通过压缩重复说明和保持 support 边界，收敛到约 490 行，保留中文业务说明但避免继续越过单文件行数上限。
- `datasource-management` 新增 `DataSourceMetadataDiscoveryCacheSupport`，把元数据发现缓存、TTL 判断、结果复制和 cacheHit 语义从 `DataSourceMetadataDiscoverySupport` 中拆出。
- `DataSourceMetadataDiscoverySupport` 回归元数据发现主流程，聚焦权限校验、连接器能力判断、JDBC 元数据读取、字段/主键/索引/采样预览组织，当前约 476 行。
- `permission-admin` 新增 `PermissionOutboxOperationAuditSupport`，把 outbox 人工重试/忽略的审计记录组装、before/after 快照、JSON 转义从 `PermissionOperationsServiceImpl` 中拆出。
- `PermissionOperationsServiceImpl` 回归权限运维编排：outbox 查询、缓存快照、缓存清理、详情读取、人工重试、人工忽略、审计分页，当前约 468 行。
- 修正 outbox 人工忽略场景的空 reason 兜底处理，避免请求体或 reason 为空时因空指针中断运维动作。

设计价值：
- 全仓 Java 文件已完成“无超过 500 行文件”的阶段性闭环，后续新增功能要继续遵守“Controller 只做路由契约、Impl 只做业务编排、support 承载专门能力”的拆分方式。
- data-quality 的路由按“规则管理、报告查询、执行器/运维”拆分，后续扩展质量评分、异常工单、报告导出、规则审批、调度看板时不会继续挤压同一个 Controller。
- datasource-management 的元数据缓存单独成组件，后续升级 Redis 分布式缓存、按租户隔离缓存、显式刷新、连接器级 TTL 时有清晰替换点。
- permission-admin 的 outbox 审计单独成组件，后续审计同步写入 Kafka、OpenSearch、归档库或独立 audit-center 时，不需要继续膨胀运维服务实现。

当前边界：
- 这次主要是结构治理，不改变核心业务表结构；仍需继续补齐更深层的产品能力，例如 permission-admin 与 task-management 的远程策略协作、data-sync 模块正式落地、统一服务账号签名、租户级配额和全链路审计。
- data-quality 的报告、异常、执行器协议已经拆开，但质量规则 DSL、质量评分模型、异常工单闭环、报告导出和租户级质量看板仍需继续产品化。
- datasource 元数据缓存仍是本地进程缓存，生产多实例部署应升级为 Redis 或持久化元数据结果表，并补显式失效和权限上下文隔离。
- permission outbox 审计详情当前仍是轻量手写 JSON，后续字段增多时建议升级为 DTO + Jackson 序列化，并接入统一审计保留策略。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部模块编译成功，完成时间 `2026-05-07T21:16:08+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；当前最高文件约 499 行，后续仍建议继续主动拆分接近阈值的类，避免新增注释或功能后再次超限。

下一步建议：
1. 优先启动 data-sync 正式模块或 datasource 到 data-sync 的边界迁移，避免继续把同步任务、模板、队列、告警都压在 datasource-management 中。
2. 将 task-management 与 permission-admin 的 `TASK` 数据范围策略真正打通，解析 `scopeExpression` 并下沉到任务查询和操作条件。
3. 补服务间调用安全，包括服务账号签名、mTLS/网关白名单、执行器密钥轮换、回放防护和回调限流。
4. 开始设计租户级和项目级配额体系，包括并发数、队列长度、失败率、数据扫描量、质量检查频率和告警阈值。
5. 对当前接近 500 行的类继续做预防性拆分，尤其是 `SyncPermissionNotificationServiceImpl`、`DataSourceReadOnlySqlSupport` 和 `DataQualityServiceImpl`。

### 2026-05-07：data-sync 独立微服务第一版边界落地

本轮开始把数据同步从 datasource-management 的附属能力升级为独立微服务边界。根据 data-sync PRD、领域模型、API outline 和状态机文档，第一版优先落“同步模板 + 同步任务”的定义面，而不是直接实现数据搬运执行器。这样可以避免把连接器读写、任务状态、checkpoint、审计、告警、配额全部堆进 datasource-management 或一个超大 Impl 中。

落地内容：
- 父工程新增 `data-sync` Maven 子模块，Reactor 构建顺序中已出现 `Data Sync Module`。
- 新增 `DataSyncApplication`，作为数据同步微服务独立启动入口，默认端口 `8086`，服务名 `data-sync`。
- 新增 `application.yml`，配置 MySQL、Kafka、Nacos、MyBatis-Plus、Actuator 和日志说明。
- 新增同步模式枚举 `SyncMode`，覆盖 `FULL / INCREMENTAL_TIME / INCREMENTAL_ID / CDC_STREAMING / SCHEDULED_BATCH / ONE_TIME_MIGRATION / REPLAY / BACKFILL / OFFLINE_IMPORT / OFFLINE_EXPORT`。
- 新增同步任务状态枚举 `SyncTaskState`，按 PRD 状态机保留 `DRAFT / CONFIGURED / PENDING_APPROVAL / SCHEDULED / QUEUED / RUNNING / PAUSED / RETRYING / PARTIALLY_SUCCEEDED / SUCCEEDED / FAILED / CANCELLED / ARCHIVED`。
- 新增审批状态 `SyncApprovalState` 与触发类型 `SyncTriggerType`，避免把审批语义和执行状态混在同一个字段里。
- 新增 `SyncTemplate` 实体与 `data_sync_template` 表，保存源/目标数据源、同步模式、字段映射、过滤、分区、重试和超时策略。
- 新增 `SyncTask` 实体与 `data_sync_task` 表，保存任务状态、审批状态、优先级、调度配置、运行模式、触发方式、负责人和最近执行记录引用。
- 新增 `SyncTemplateMapper`、`SyncTaskMapper`，使用 MyBatis-Plus 承载第一版基础 CRUD。
- 新增 `DataSyncService` 与 `DataSyncServiceImpl`，实现模板创建、模板分页、模板详情、模板校验、任务创建、任务分页、任务详情、任务手动 run 入队。
- 新增 `SyncDataScopeSupport`，在 data-sync 内部先做本地租户范围兜底，平台管理员/服务账号可跨租户，普通角色默认只能访问自身租户。
- 新增 `SyncTemplateValidationSupport`，校验源目标数据源、同步模式和基础配置合法性；后续可接入连接器能力矩阵和 datasource-management 元数据。
- 新增 `SyncTaskStateMachineSupport`，集中判断任务是否允许进入队列，避免状态规则散落在 Impl 中。
- 新增 `DataSyncTemplateController` 与 `DataSyncTaskController`，提供 `/sync-templates` 与 `/sync-tasks` 的基础 API。
- gateway 新增 `/api/sync/**` 路由、权限资源类型 `DATA_SYNC_TASK` 和路由前缀识别，后续前端或统一入口可通过网关访问 data-sync。

设计价值：
- data-sync 从概念规划进入可编译、可启动、可落库的独立微服务阶段，项目不再只围绕 datasource-management 单模块扩张。
- 模板和任务分离后，可以支撑一个模板多次运行、定时运行、失败重试、回放、补数、审批和审计，而不会把运行状态污染到配置定义中。
- 状态机、租户范围和模板校验被拆到 support 层，避免第一版就形成新的超大 ServiceImpl。
- 同步模式从第一天就覆盖关系库、CDC、批处理、离线导入导出、回放补数等场景，为后续 MySQL、PostgreSQL、Kafka、文件、对象存储、API 连接器扩展预留产品边界。

当前边界：
- 本轮 runTask 只把任务推进到 `QUEUED`，尚未接入真正执行器、task-management worker 协议、checkpoint、失败样本和吞吐控制。
- 当前模板校验还没有调用 datasource-management 查询连接器能力，也没有验证字段映射是否真实存在。
- 当前数据范围是 data-sync 本地兜底规则，尚未真正调用 permission-admin 的策略判定与 scopeExpression。
- gateway 路由已注册 `/api/sync/**`，但路由前缀改写策略仍沿用现有网关模式，后续需要统一梳理各服务 direct path 与 gateway path 的映射规范。
- 表结构已写入初始化 SQL；已有本地 MySQL 数据卷不会自动创建新表，需要后续正式 migration 或手工执行 SQL。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-07T21:31:34+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；新增 data-sync 最大文件约 223 行。

下一步建议：
1. 给 data-sync 增加执行记录、checkpoint、错误样本和审计表，形成“任务定义 -> 单次执行 -> checkpoint -> 失败样本 -> 审计”的完整数据模型。
2. 接入 task-management：data-sync runTask 生成平台任务，由执行器通过 runId、executorId、idempotencyKey 回调状态。
3. 接入 datasource-management：校验模板中源/目标数据源是否存在、是否启用、是否支持 read/write/schema discovery/field mapping/checkpoint。
4. 设计连接器能力注册接口，先支持 MySQL/PostgreSQL 关系型批同步，再扩展 Kafka、文件、对象存储和 API。
5. 设计性能与可靠性要求：租户并发上限、连接器并发上限、checkpoint 写入间隔、批大小、重试退避、失败样本保留和队列积压告警。

### 2026-05-07：data-sync 执行事实模型与追踪查询能力

本轮继续承接 data-sync 独立微服务第一版边界，在“同步模板 + 同步任务”之上补齐执行事实层。目标是让同步任务进入队列后不再只是任务主表上的一个状态，而是拥有可追踪的 execution、可恢复的 checkpoint、可排障的 error sample 和可审计的 audit record。这是数据同步从配置管理走向商用执行平台的关键一步。

落地内容：
- 新增 `SyncExecutionState`，区分 `QUEUED / RUNNING / PAUSED / RETRYING / PARTIALLY_SUCCEEDED / SUCCEEDED / FAILED / CANCELLED` 等单次执行状态。
- 新增 `SyncAuditActionType`，集中维护 `CREATE_TEMPLATE / VALIDATE_TEMPLATE / CREATE_TASK / RUN_TASK / CREATE_EXECUTION / UPDATE_CHECKPOINT / RECORD_ERROR_SAMPLE` 等审计动作。
- 新增 `SyncExecution` 实体与 `data_sync_execution` 表，用于保存某个同步任务的第 N 次运行、状态、触发方式、入队时间、开始/结束时间、读写记录数、失败数、错误摘要和执行器 ID。
- 新增 `SyncCheckpoint` 实体与 `data_sync_checkpoint` 表，用于保存增量同步、CDC、分片批同步、文件位置、Kafka offset 等可恢复进度。
- 新增 `SyncErrorSample` 实体与 `data_sync_error_sample` 表，用于保存脱敏截断后的失败样本、错误类型、错误码、源端/目标端定位和是否可重试。
- 新增 `SyncAuditRecord` 实体与 `data_sync_audit_record` 表，用于保存同步模板、任务、执行、checkpoint 和错误样本相关的治理动作。
- 新增对应 Mapper：`SyncExecutionMapper`、`SyncCheckpointMapper`、`SyncErrorSampleMapper`、`SyncAuditRecordMapper`。
- 新增查询 DTO：`SyncExecutionQueryCriteria`、`SyncCheckpointQueryCriteria`、`SyncErrorSampleQueryCriteria`、`SyncAuditQueryCriteria`。
- 新增 `SyncAuditSupport`，将审计写入从 `DataSyncServiceImpl` 中拆出，后续扩展 Kafka、OpenSearch、归档库或 audit-center 时不污染主服务。
- `DataSyncService` 增加执行历史、checkpoint、错误样本、审计记录分页查询契约。
- `DataSyncServiceImpl.runTask` 现在会创建一条 `QUEUED` 执行记录、写入 `CREATE_EXECUTION` 和 `RUN_TASK` 审计，并把 `lastExecutionId` 回写到同步任务主表。
- 新增 `DataSyncExecutionController`，提供 `/sync-tasks/{taskId}/executions`、`/checkpoints`、`/errors`、`/audit` 查询接口，避免 `DataSyncTaskController` 继续膨胀。

设计价值：
- data-sync 形成“任务定义 -> 单次执行 -> checkpoint -> 错误样本 -> 审计记录”的基础追踪链路，后续执行器可以围绕 executionId 写入进度和结果。
- 执行记录与任务主表分离后，可以支持一次任务多次运行、失败重试、手工回放、历史补数、事故复盘和 SLA 统计。
- checkpoint 单独建表后，后续支持时间字段增量、ID range、Kafka offset、文件位置和分区窗口时不需要反复改任务主表。
- 错误样本表为运营排障、字段映射修复、失败重试策略和质量联动提供基础，不需要只依赖应用日志。
- 审计支撑组件独立后，数据导出、跨源写入、回放补数等高风险动作可以逐步纳入统一治理审计。

当前边界：
- 当前 `runTask` 创建的是 QUEUED 执行记录，尚未有 worker 认领、RUNNING 推进、SUCCEEDED/FAILED 终态回写。
- checkpoint 和错误样本已有表、实体和查询 API，但还没有执行器写入入口。
- 执行记录的 `executionNo` 目前通过查询已有执行数生成，适合早期单实例使用；高并发生产场景后续应改为数据库锁、唯一键重试或独立序列表策略。
- 审计当前只写成功动作摘要，失败动作审计、审批流、导出限制和敏感载荷脱敏策略仍需继续补。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-07T21:39:11+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；`DataSyncServiceImpl` 当前约 371 行。

下一步建议：
1. 为 data-sync 增加执行器回调 API：claim/start/progress/checkpoint/succeed/fail/defer，并复用 task-management 的 runId、executorId、idempotencyKey 设计。
2. 将 `executionNo` 生成改造成高并发安全模式，避免多个 runTask 并发时产生唯一键冲突或序号竞争。
3. 接入 datasource-management 连接器能力校验：源端是否 can_read、目标端是否 can_write、同步模式是否支持 checkpoint。
4. 增加 checkpoint 写入与错误样本写入接口，先支持关系型批同步的 TIME_FIELD 与 ID_RANGE。
5. 开始设计 data-sync 租户级/连接器级并发配额、队列积压告警和执行超时恢复。

### 2026-05-08：data-sync 执行器回调写入面第一版

本轮继续推进 data-sync 执行链路，在已有 execution、checkpoint、error sample 和 audit 查询能力之上，补齐执行器写入入口。目标是让 data-sync 不再只是“能创建 QUEUED execution 并查询事实表”，而是开始具备执行器推进状态、写入 checkpoint、完成成功、失败留样的最小闭环。

落地内容：
- 新增 `SyncExecutionStartRequest`，用于执行器把 `QUEUED` execution 推进到 `RUNNING`，并绑定 executorId。
- 新增 `SyncExecutionCheckpointRequest`，用于执行器写入 checkpoint 类型、checkpoint 值、分片/分区、读写计数。
- 新增 `SyncExecutionCompleteRequest`，用于执行器标记 execution 成功并写入最终读写计数和 checkpoint 引用。
- 新增 `SyncExecutionFailRequest`，用于执行器标记 execution 失败并写入错误样本。
- 新增 `SyncExecutionLifecycleSupport`，集中承载 start、checkpoint、complete、fail 四类状态推进，避免 `DataSyncServiceImpl` 继续膨胀。
- `DataSyncService` 增加 `startExecution / writeCheckpoint / completeExecution / failExecution` 契约。
- `DataSyncServiceImpl` 增加执行记录归属校验，确保 execution 必须属于当前 task，避免执行器或调用方跨任务写入。
- 新增 `DataSyncExecutorCallbackController`，提供 `/sync-tasks/{taskId}/executions/{executionId}/start`、`/checkpoints`、`/complete`、`/fail` 回调入口。
- start 回调会将 execution 推进到 `RUNNING`，并把同步任务主状态同步推进到 `RUNNING`。
- checkpoint 回调会插入 `data_sync_checkpoint`，同步刷新 execution 的 checkpointRef、recordsRead、recordsWritten。
- complete 回调会将 execution 推进到 `SUCCEEDED`，并将任务主状态同步推进到 `SUCCEEDED`。
- fail 回调会插入 `data_sync_error_sample`，将 execution 推进到 `FAILED`，并将任务主状态同步推进到 `FAILED`。

设计价值：
- data-sync 初步具备执行器协议闭环：runTask 创建 execution，执行器 start，执行器写 checkpoint，执行器 complete/fail。
- checkpoint 和错误样本不再只是查询表，而是已经有写入入口，为后续关系型批同步、增量同步和 CDC 打基础。
- lifecycle support 独立后，状态流转、executorId 校验、checkpoint 写入、终态回写不会堆在 `DataSyncServiceImpl` 中。
- executorId 校验让执行记录有了基本写入边界，后续可以继续扩展租约、心跳、服务账号签名和幂等表。

当前边界：
- 当前还没有 claim/lease/heartbeat/defer 协议，执行器启动 execution 前仍需要外部知道 taskId 和 executionId。
- `idempotencyKey` 已进入请求 DTO 和审计摘要，但还没有独立幂等表或唯一键裁决；高并发重试场景仍需补齐。
- start/complete/fail 目前主要依赖 executorId 字段校验，还没有服务账号签名、mTLS、网关白名单或执行器密钥轮换。
- complete 当前直接把任务标记为 `SUCCEEDED`，还没有考虑分片任务部分成功、汇总完成、失败分片重试和最终一致性。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T21:45:21+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；`DataSyncServiceImpl` 当前约 433 行，`SyncExecutionLifecycleSupport` 约 217 行。

下一步建议：
1. 为 data-sync 增加 claim/lease/heartbeat/defer 协议，让执行器可以自主认领 QUEUED execution，而不是由外部指定 executionId。
2. 增加 data-sync callback 幂等表，复用 task-management 的 `task_callback_idempotency` 思路，避免重复 complete/fail/checkpoint 污染状态。
3. 将执行器身份校验升级为服务账号签名、mTLS/网关白名单和 executor secret rotation。
4. 增加分片执行模型：execution shard、分片 checkpoint、分片错误样本、部分成功汇总和失败分片重试。
5. 接入 datasource-management 连接器能力校验与只读 SQL/写入执行器，先打通 MySQL/PostgreSQL 批同步最小闭环。

### 2026-05-08：data-sync 执行器认领、租约、心跳与退避协议

本轮继续补齐 data-sync 执行器协议，把上一轮“外部指定 executionId 后 start/checkpoint/complete/fail”的模式，推进为执行器可以主动从队列中认领 execution，并通过租约和心跳维持执行权。这个能力是后续多 worker 并发、租户隔离 worker、连接器级并发控制和超时恢复的基础。

落地内容：
- `SyncExecution` 新增 `heartbeatTime / leaseExpireTime / deferCount` 字段，用于记录执行器心跳、租约过期时间和退避次数。
- `data_sync_execution` 表新增对应列，并补充 `idx_data_sync_execution_lease`、`idx_data_sync_execution_queue` 索引，为后续超时恢复和队列认领查询预留性能基础。
- `SyncExecutionMapper` 新增 `selectNextClaimCandidate`，按 `QUEUED + queued_at 到期 + 可选 tenantId` 查询下一条可认领 execution。
- `SyncExecutionMapper` 新增 `claimQueuedExecution`，通过 `WHERE execution_state='QUEUED'` 条件更新完成多 worker 并发裁决。
- `SyncExecutionMapper` 新增 `heartbeatLease`，只有当前 RUNNING 状态且 executorId 匹配时才能续租。
- 新增 `SyncExecutionClaimRequest`、`SyncExecutionClaimResult`、`SyncExecutionHeartbeatRequest`、`SyncExecutionDeferRequest`。
- 新增 `DataSyncExecutorLeaseService` 与 `DataSyncExecutorLeaseServiceImpl`，专门承载 claim、heartbeat、defer，避免继续膨胀 `DataSyncServiceImpl`。
- 新增 `DataSyncExecutorLeaseController`，提供 `/sync-executions/claim`、`/sync-executions/{executionId}/heartbeat`、`/sync-executions/{executionId}/defer`。
- claim 成功后会把 execution 推进到 `RUNNING`、写入 executorId、heartbeatTime、leaseExpireTime，并同步把 task 主状态推进到 `RUNNING`。
- heartbeat 会刷新 recordsRead、recordsWritten、heartbeatTime、leaseExpireTime。
- defer 会把 execution 延迟回 `QUEUED`，清空 executorId 和租约字段，增加 deferCount，并同步把 task 主状态回到 `QUEUED`。

设计价值：
- data-sync worker 不再需要外部提前传入 executionId，可以主动从 data-sync 队列拉取工作，开始具备真正执行器协议雏形。
- claim 使用数据库条件更新作为并发裁决，适合当前单库阶段的多 worker 并发认领。
- heartbeat 和 leaseExpireTime 为后续执行器失联恢复、超时告警、自动 requeue 提供了稳定字段。
- defer 明确区分“业务失败”和“暂时退避”，适合目标端限流、连接器容量不足、维护窗口、租户配额耗尽等生产场景。

当前边界：
- 当前 claim 只按 queued_at 和 tenantId 过滤，还没有连接器类型、源/目标数据源、优先级、租户公平性、连接器并发上限。
- 当前没有自动扫描 leaseExpireTime 过期 execution 的恢复任务，过期租约仍需下一步补 recovery job 或运维接口。
- defer 只做延迟回队列和计数，尚未设置最大 defer 次数、死信状态或运营关注标记。
- 当前执行器身份仍基于 executorId 文本匹配，没有服务账号签名、mTLS、网关白名单、密钥轮换和幂等表。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T21:51:31+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；`DataSyncServiceImpl` 当前约 433 行，`DataSyncExecutorLeaseServiceImpl` 约 163 行。

下一步建议：
1. 增加 data-sync callback/lease 幂等表，避免重复 heartbeat、checkpoint、complete、fail、defer 污染状态。
2. 增加过期租约恢复能力：扫描 leaseExpireTime 过期 execution，自动 requeue、标记失败或进入人工关注。
3. 增加 claim 过滤条件和调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。
4. 将 deferCount 接入最大退避次数和死信/人工关注状态，避免无限退避。
5. 继续接入 datasource-management 连接器能力校验与实际关系型批同步执行器。

### 2026-05-08：data-sync 过期租约恢复能力

本轮继续完善 data-sync 执行器协议，补齐 worker 失联后的恢复路径。上一轮已经具备 claim、heartbeat、defer，但如果 worker 认领 execution 后进程崩溃、网络隔离或长时间不再心跳，execution 会长期停留在 `RUNNING`。本轮新增过期租约扫描与 requeue 能力，让平台可以把失联执行重新放回队列。

落地内容：
- 新增 `SyncExpiredLeaseRecoveryRequest`，支持指定租户、批次大小、是否 requeue 和恢复原因。
- 新增 `SyncExpiredLeaseRecoveryResult`，返回扫描数量、成功恢复数量、恢复 executionId 列表和结果说明。
- `SyncExecutionMapper` 新增 `selectExpiredRunningLeases`，按 `RUNNING + lease_expire_time < NOW()` 扫描过期租约 execution。
- `SyncExecutionMapper` 新增 `requeueExpiredLease`，通过状态和租约过期条件再次裁决，避免误回收刚续租或刚完成的 execution。
- `DataSyncExecutorLeaseService` 增加 `recoverExpiredLeases` 契约。
- `DataSyncExecutorLeaseServiceImpl` 实现过期租约恢复：扫描过期 execution、逐条原子 requeue、清理 executorId/heartbeat/lease、增加 deferCount、同步任务主状态为 `QUEUED`、写审计。
- `DataSyncExecutorLeaseController` 新增 `/sync-executions/recover-expired-leases` 运维入口，后续可被定时任务复用。

设计价值：
- data-sync 从“worker 认领后依赖 worker 自己完成”升级为“worker 失联后平台可恢复”，可靠性更接近生产调度系统。
- 过期恢复使用二次条件更新，避免扫描和恢复之间 execution 状态发生变化时被误处理。
- 先提供运维接口而不是直接定时任务，可以方便本地验证、人工排障和后续接入调度器。
- requeue 后保留 deferCount 和 errorSummary，可帮助运营人员识别频繁失联或频繁被恢复的任务。

当前边界：
- 当前仅支持 requeue 模式，暂不支持直接标记 FAILED、DEAD_LETTER 或 ATTENTION_REQUIRED。
- 当前没有自动 `@Scheduled` 扫描任务，仍需人工或外部调度调用恢复接口。
- deferCount 尚未接入最大退避次数和死信状态，因此极端情况下仍可能反复 requeue。
- 恢复接口尚未做管理员角色强校验或服务账号签名，后续应接入 permission-admin 与 gateway 授权策略。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T21:54:48+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；`DataSyncServiceImpl` 约 433 行，`DataSyncExecutorLeaseServiceImpl` 约 217 行。

下一步建议：
1. 增加最大 defer 次数和死信/人工关注状态，避免过期恢复与 defer 形成无限循环。
2. 增加自动定时恢复任务，并通过配置控制是否启用、扫描间隔、批次大小和恢复策略。
3. 增加 data-sync callback/lease 幂等表，避免重复恢复、重复 heartbeat、重复 complete/fail 污染状态。
4. 将恢复接口接入权限策略，仅允许平台管理员、运营人员或服务账号触发。
5. 增加租户级、连接器级、数据源级 claim 过滤和并发配额控制。

### 2026-05-08：data-sync 最大退避次数与人工介入状态

本轮继续沿着 data-sync 执行器协议做生产化补强。上一轮已经能把过期租约恢复回队列，但如果某个 execution 因为目标端长期限流、连接器反复崩溃、worker 频繁失联或同步配置存在结构性问题而不断 requeue，就会形成“无限退避循环”。本轮新增最大退避次数与人工介入状态，让系统在自动恢复不再安全或不再经济时主动停下来，把问题交给运营人员处理。

落地内容：
- 新增 `DataSyncExecutorProperties`，通过 `datasmart.data-sync.executor.max-defer-count` 配置单条 execution 最大退避次数，默认 5 次，并在代码侧限制有效范围，避免配置误伤。
- `SyncTaskState` 新增 `AWAITING_OPERATOR_ACTION`，用于表达任务已超出自动执行边界，需要运营人员检查配置、连接器、数据源、目标端容量或租户配额。
- `SyncTask` 新增 `attentionRequired / attentionReason` 字段，让运营台、告警规则和后续工单流转可以直接筛选需要人工关注的同步任务。
- `data_sync_task` 表新增 `attention_required / attention_reason` 字段与 `idx_data_sync_task_attention` 索引，并更新任务状态注释。
- `SyncExecutionMapper` 新增 `deferRunningExecution` 显式 SQL，主动 defer 时根据 `defer_count + 1 >= maxDeferCount` 决定回到 `QUEUED` 还是转为 `FAILED`。
- `SyncExecutionMapper.requeueExpiredLease` 增加最大退避次数裁决，过期租约恢复达到上限后不再 requeue，而是将 execution 置为 `FAILED`。
- `SyncTaskMapper` 新增 `markQueuedAfterLeaseTransition` 与 `markAwaitingOperatorAction`，显式清空或写入人工介入字段，避免实体更新策略忽略 null 导致旧原因残留。
- `DataSyncExecutorLeaseServiceImpl` 接入配置阈值，defer 与 recoverExpiredLeases 都会同步刷新任务主状态，并在审计 payload 中记录 deferCount、maxDeferCount 和最终状态。
- `SyncExpiredLeaseRecoveryResult` 增加人工介入数量与 executionId 列表，方便运维接口直接知道哪些执行记录没有被恢复回队列。

设计价值：
- 防止 data-sync worker 被长期故障任务拖垮，避免队列吞吐被少量坏任务持续占用。
- 把“业务失败”和“自动化边界已耗尽”拆开：execution 可以 FAILED，但 task 进入 `AWAITING_OPERATOR_ACTION`，更符合生产运营语义。
- 显式 SQL 清理 `executor_id / heartbeat_time / lease_expire_time / attention_reason`，规避 MyBatis-Plus 默认 null 更新策略带来的隐性状态残留。
- 配置化最大退避次数为不同客户、不同连接器和不同部署规模预留调优空间，后续可继续扩展为连接器级或租户级策略。

当前边界：
- 当前人工介入只是状态和字段，尚未接入告警中心、工单系统、消息通知或运营审批。
- 当前 max-defer-count 是服务级配置，尚未支持模板级、租户级、连接器级差异化阈值。
- 当前进入人工介入后还没有“运营处理动作”接口，例如确认忽略、修改后重跑、转人工补数、关闭任务、生成事故记录。
- 当前执行器身份仍只校验 executorId，尚未接入服务账号签名、mTLS、网关白名单和密钥轮换。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:00:52+08:00`。
- 已重新执行 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；最高文件为 499 行，data-sync 主要文件仍低于限制。

下一步建议：
1. 增加人工介入任务的运营处理接口：acknowledge、resolve、rerun、cancel、archive、createIncident。
2. 增加自动定时恢复任务，通过配置控制启用、扫描间隔、批次大小和恢复策略。
3. 增加 data-sync callback/lease 幂等表，避免重复 defer、重复恢复、重复 heartbeat、重复 complete/fail 污染状态。
4. 将 claim 扩展为商业调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。
5. 接入 permission-admin 与 gateway，限制恢复和人工介入处理只能由运营人员、平台管理员或服务账号执行。

### 2026-05-08：data-sync 人工介入运营处理闭环

本轮继续把 `AWAITING_OPERATOR_ACTION` 从“一个状态”推进为“一个可运营流程”。上一轮已经能在超过最大退避次数后把任务标为需要人工介入，但如果没有处理入口，运营人员只能看到问题，不能在系统内确认、修复、重跑、取消、归档或创建事故记录。本轮新增人工介入运营 API 和事故记录表，让 data-sync 在故障场景下更接近真实可商用产品的运维闭环。

落地内容：
- 新增 `SyncExecutionCreationSupport`，把创建 `QUEUED` execution 的逻辑从 `DataSyncServiceImpl` 拆出，供普通运行和人工介入后重跑复用。
- `DataSyncServiceImpl` 改为复用 `SyncExecutionCreationSupport`，文件行数从约 433 行降低到约 400 行，减少主服务膨胀。
- 新增 `SyncIncidentRecord` 与 `SyncIncidentRecordMapper`，提供 data-sync 内部事故记录落库能力。
- 新增 `SyncAttentionOperationRequest` 与 `SyncAttentionOperationResult`，统一承载人工介入处理备注、事故类型、严重级别、标题和描述。
- 新增 `DataSyncAttentionOperationService` 与 `DataSyncAttentionOperationServiceImpl`，独立承载 acknowledge、resolve、rerun、cancel、archive、createIncident。
- 新增 `DataSyncAttentionOperationController`，提供 `/sync-tasks/{taskId}/attention/acknowledge`、`/resolve`、`/rerun`、`/cancel`、`/archive`、`/incidents`。
- `SyncTaskMapper` 新增 `markAttentionAcknowledged`、`markAttentionResolved`、`markAttentionRerunQueued`、`closeAttentionTask`，显式处理状态和人工介入字段。
- `SyncAuditActionType` 新增人工介入与事故相关动作类型，保证运营动作进入审计流水。
- `docker/mysql/init/init.sql` 新增 `data_sync_incident_record` 表，支持按任务、租户状态、执行记录和操作人查询。

设计价值：
- 人工介入不再只是异常状态，而是具备“确认接手、修复完成、重跑验证、取消停止、归档收尾、事故跟踪”的运营动作。
- 普通 `/run` 仍不直接允许从 `AWAITING_OPERATOR_ACTION` 重跑，必须通过运营接口显式处理，避免绕过故障确认。
- 事故记录独立于错误样本和审计记录：错误样本描述数据/外部调用错误，审计记录描述谁做了什么，事故记录描述需要持续跟踪的问题单。
- 本地兜底权限先限制为 `PLATFORM_ADMINISTRATOR / TENANT_ADMINISTRATOR / OPERATOR / SERVICE_ACCOUNT`，后续可平滑接入 permission-admin。

当前边界：
- 事故记录当前只支持创建，尚未提供分页查询、状态流转、SLA 计时、负责人分派和解决摘要。
- 人工介入处理动作还没有接入网关强鉴权、菜单权限、按钮权限和 permission-admin 策略中心。
- 事故记录还没有接入通知渠道、告警规则、企业工单系统或统一 incident-center。
- rerun 当前创建单条 QUEUED execution，尚未结合连接器并发上限、租户公平调度和优先级队列。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:09:21+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；`DataSyncServiceImpl` 降至约 400 行。

下一步建议：
1. 增加 data-sync 事故记录查询与状态流转：list、detail、acknowledge、resolve、close、assign。
2. 增加自动定时恢复任务，通过配置控制启用、扫描间隔、批次大小和恢复策略。
3. 增加 data-sync callback/lease 幂等表，避免重复 defer、重复恢复、重复 heartbeat、重复 complete/fail 污染状态。
4. 将人工介入和恢复接口接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
5. 将 claim 扩展为商业调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。

### 2026-05-08：data-sync 事故记录查询与状态流转

本轮继续完善 data-sync 的运营事故管理能力。上一轮已经可以在人工介入任务上创建事故记录，但事故如果只能创建、不能查询和推进状态，就仍然不是完整运营对象。本轮把事故记录补齐为可列表、可详情、可确认、可分派、可解决、可关闭的生命周期对象，为后续 SLA、通知、工单系统和事故复盘打基础。

落地内容：
- 新增 `SyncOperatorPermissionSupport`，抽出 data-sync 本地运营权限兜底逻辑，避免人工介入服务和事故服务重复维护角色集合。
- `DataSyncAttentionOperationServiceImpl` 改为复用 `SyncOperatorPermissionSupport`，权限判断从人工介入服务中解耦。
- `SyncIncidentRecord` 新增 `assignedOperatorId / assignedOperatorRole / acknowledgedAt / resolvedAt / closedAt` 字段，用于负责人分派和事故生命周期追踪。
- `data_sync_incident_record` 表新增负责人字段、确认/解决/关闭时间字段和 `idx_data_sync_incident_assignee` 索引。
- `SyncIncidentRecordMapper` 新增 `acknowledgeIncident`、`assignIncident`、`resolveIncident`、`closeIncident` 显式 SQL，保证事故状态流转可控。
- 新增 `SyncIncidentQueryCriteria`、`SyncIncidentOperationRequest`、`SyncIncidentOperationResult`，分别承载查询条件、操作入参和动作结果。
- 新增 `DataSyncIncidentService` 与 `DataSyncIncidentServiceImpl`，提供事故分页、详情、确认、分派、解决、关闭。
- 新增 `DataSyncIncidentController`，提供 `/sync-incidents`、`/sync-incidents/{incidentId}`、`/acknowledge`、`/assign`、`/resolve`、`/close`。
- `SyncAuditActionType` 新增 `ACKNOWLEDGE_INCIDENT / ASSIGN_INCIDENT / RESOLVE_INCIDENT / CLOSE_INCIDENT`，事故处理动作会进入审计流水。

设计价值：
- 事故记录从“创建后沉淀数据”升级为“可持续运营对象”，更贴近真实企业运维台。
- 查询接口和操作接口区分可见性与可操作性：租户范围内可查看，状态修改需要运营角色。
- 显式 SQL 控制状态流转，例如只有 `OPEN` 可以 acknowledge，`CLOSED` 不能再 assign/resolve/close，避免事故生命周期倒退。
- 负责人字段与索引为“我的事故”、值班分派、SLA 统计和告警降噪提供基础。

当前边界：
- 事故状态仍是字符串，尚未抽成枚举或独立状态机组件；如果后续状态继续增多，应拆 `SyncIncidentStateMachineSupport`。
- 事故还没有 SLA 截止时间、升级策略、通知渠道、评论流或附件。
- 事故关闭目前只关闭事故本身，不会反向自动 resolve 人工介入任务；这个动作需要业务上明确是否联动。
- 事故管理仍是 data-sync 内部能力，尚未接入统一 incident-center 或外部工单系统。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:14:59+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；data-sync 最大文件仍为 `DataSyncServiceImpl` 约 400 行。

下一步建议：
1. 增加自动定时恢复任务，通过配置控制启用、扫描间隔、批次大小和恢复策略。
2. 增加 data-sync callback/lease 幂等表，避免重复 defer、重复恢复、重复 heartbeat、重复 complete/fail 污染状态。
3. 将人工介入、事故和恢复接口接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
4. 将 claim 扩展为商业调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。
5. 为事故补充 SLA 与通知：deadline、escalation、assignee reminder、incident comments。

### 2026-05-08：data-sync 过期租约自动定时恢复

本轮把过期租约恢复从“需要人工调用运维接口”推进为“可配置后台自动扫描”。此前 worker 失联后，平台已经具备手动恢复接口，但真实生产系统不能依赖人工发现卡死的 RUNNING execution。本轮新增 Spring Scheduling 定时恢复器，让 data-sync 可以周期性扫描过期租约，并复用现有恢复服务完成 requeue、最大退避次数裁决、人工介入和审计。

落地内容：
- `DataSyncApplication` 增加 `@EnableScheduling`，启用 Spring 定时任务能力。
- `DataSyncExecutorProperties` 增加 `recovery` 配置段，包含 `enabled / initialDelayMs / fixedDelayMs / batchSize / tenantId / reason`。
- `DataSyncExecutorProperties` 增加 `effectiveRecoveryBatchSize()`，对自动恢复批次做 1 到 500 的安全裁剪。
- 新增 `DataSyncExpiredLeaseRecoveryScheduler`，通过 `@Scheduled` 按配置周期触发过期租约恢复。
- scheduler 使用 `SERVICE_ACCOUNT` 系统身份写审计，便于区分人工恢复和系统自动恢复。
- scheduler 使用 `AtomicBoolean` 防止单 JVM 内恢复任务重叠运行。
- scheduler 不直接写数据库，而是复用 `DataSyncExecutorLeaseService.recoverExpiredLeases`，保证手动恢复和自动恢复走同一套状态流转与审计逻辑。
- `application.yml` 增加 `datasmart.data-sync.executor.recovery` 配置与中文说明。

设计价值：
- worker 崩溃、宿主机重启、网络隔离后，RUNNING execution 可以被自动恢复，不必依赖人工巡检。
- 自动恢复复用手动恢复逻辑，避免出现“接口恢复”和“定时恢复”两套状态规则。
- 配置化启停、启动延迟、扫描间隔、批次大小和租户范围，方便本地、测试、生产环境差异化部署。
- 单实例内避免重叠扫描，多实例下依赖数据库条件更新做二次裁决，为后续分布式锁预留空间。

当前边界：
- 多实例部署时仍可能重复扫描同一批过期 execution，虽然条件更新能保证只有一个实例恢复成功，但会有额外查询成本。
- 当前没有分布式锁、leader election 或调度中心分片；高规模生产环境建议补 Redis/DB 锁或统一调度器。
- 自动恢复当前只做周期扫描，尚未接入指标统计、告警事件或 Prometheus 计数器。
- 恢复策略仍以 requeue 为主，超过最大退避次数后进入人工介入，尚未支持租户级、连接器级差异化策略。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:18:36+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；data-sync 最大文件仍为 `DataSyncServiceImpl` 约 400 行。

下一步建议：
1. 增加 data-sync callback/lease 幂等表，避免重复 defer、重复恢复、重复 heartbeat、重复 complete/fail 污染状态。
2. 为自动恢复补充指标与告警：扫描数量、恢复数量、人工介入数量、失败次数、最近成功时间。
3. 将人工介入、事故和恢复接口接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
4. 将 claim 扩展为商业调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。
5. 为多实例自动恢复增加分布式锁、leader election 或调度分片。

### 2026-05-08：data-sync 回调与租约动作幂等表

本轮补齐 data-sync 执行器协议中的幂等保护。执行器回调、租约心跳、主动退避和运维恢复都可能因为网络超时、HTTP 响应丢失、客户端重试或多实例并发而重复到达。如果没有幂等表，重复请求可能造成 checkpoint 重复写入、错误样本重复、complete/fail 重复推进、defer 重复计数或恢复重复扫描。本轮新增 data-sync 独立幂等表与支撑组件，并接入关键执行链路。

落地内容：
- 新增 `SyncCallbackIdempotency` 实体，用于记录 data-sync 回调与租约动作幂等事实。
- 新增 `SyncCallbackIdempotencyMapper`，通过数据库唯一索引承担并发裁决。
- 新增 `SyncCallbackIdempotencySupport`，封装首次登记、重复请求识别、成功标记和重复请求触达时间刷新。
- `data_sync_callback_idempotency` 表加入 `tenant_id / sync_task_id / execution_id / scope_key / action / idempotency_key / executor_id / request_digest / callback_state` 等字段。
- 表唯一键使用 `tenant_id + action + scope_key + idempotency_key`，其中 `scope_key` 用于表达 `taskId:executionId` 或 `RECOVERY:ALL`，避免 MySQL 唯一索引遇到 NULL 字段时失效。
- `SyncExecutionLifecycleSupport` 接入 START、CHECKPOINT、COMPLETE、FAIL 幂等保护。
- 重复 START/COMPLETE 请求直接返回当前 execution；重复 CHECKPOINT 返回最近 checkpoint；重复 FAIL 返回最近错误样本，避免重复写入。
- `SyncExecutionHeartbeatRequest`、`SyncExecutionDeferRequest`、`SyncExpiredLeaseRecoveryRequest` 增加 `idempotencyKey`。
- `DataSyncExecutorLeaseServiceImpl` 接入 HEARTBEAT、DEFER、RECOVER_EXPIRED_LEASE 幂等保护。
- 自动定时恢复不传幂等键，继续依赖数据库状态条件更新做安全裁决；人工恢复接口可传 idempotencyKey 防止重复触发。

设计价值：
- 数据同步回调从“依赖调用方不要重复”升级为“服务端有持久化幂等裁决”，更适合真实网络和多实例部署。
- `scope_key` 让幂等保护同时支持单 execution 回调和全局恢复动作，避免表结构过早分裂。
- 幂等逻辑作为横切支撑组件独立存在，没有继续膨胀 `DataSyncServiceImpl` 或 `DataSyncExecutorLeaseServiceImpl`。
- 重复请求不再抛状态冲突，而是尽量返回首次处理后的当前业务结果，执行器重试体验更稳定。

当前边界：
- 幂等键目前仍是建议传入，尚未在所有执行器回调上强制必填；后续可以按生产协议逐步收紧。
- 当前没有幂等记录清理任务，长期运行后需要按保留期清理历史记录。
- 当前失败状态主要为未来独立事务失败审计预留，业务异常通常会随上层事务回滚。
- 自动恢复周期任务未生成稳定幂等键，主要依赖数据库条件更新；如果未来引入外部调度器，可为每轮调度生成 runId 级幂等键。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:24:24+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；data-sync 最大文件仍为 `DataSyncServiceImpl` 约 400 行。

下一步建议：
1. 为自动恢复补充指标与告警：扫描数量、恢复数量、人工介入数量、失败次数、最近成功时间。
2. 增加幂等记录清理任务，按保留期清理历史 `data_sync_callback_idempotency`。
3. 将人工介入、事故、恢复和执行器回调接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
4. 将 claim 扩展为商业调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。
5. 为多实例自动恢复增加分布式锁、leader election 或调度分片。

### 2026-05-08：data-sync 自动恢复指标与告警基础

本轮为 data-sync 自动恢复补齐第一版可观测性。上一轮已经具备过期租约自动扫描和恢复，但如果没有指标，运维只能看日志，无法稳定回答“自动恢复是否还在运行”“最近一次扫描了多少”“是否持续进入人工介入”“调度器是否持续失败”。本轮新增 Micrometer 指标组件，把自动恢复的运行事实暴露到 Actuator metrics，为 Prometheus/Grafana 和后续告警中心打基础。

落地内容：
- `data-sync/pom.xml` 新增 `spring-boot-starter-actuator`，让 data-sync 模块真正具备 `/actuator/metrics` 指标暴露能力。
- 新增 `DataSyncRecoveryMetrics`，集中封装自动恢复相关 Counter、Timer 和 Gauge，避免指标代码散落在 scheduler 中。
- `DataSyncExpiredLeaseRecoveryScheduler` 接入指标组件，记录成功轮次、失败轮次、重入跳过、单轮耗时、最近扫描结果。
- 新增轮次指标 `datasmart_data_sync_recovery_tick_total`，按 `result=EMPTY/RECOVERED/ATTENTION_REQUIRED/SCANNED_ONLY` 记录调度结果。
- 新增 execution 数量指标 `datasmart_data_sync_recovery_execution_total`，按 `outcome=SCANNED/REQUEUED/ATTENTION_REQUIRED` 记录扫描、恢复、转人工介入数量。
- 新增失败指标 `datasmart_data_sync_recovery_failure_total`，记录 scheduler 外层异常。
- 新增跳过指标 `datasmart_data_sync_recovery_skip_total`，记录单 JVM 内上一轮未结束导致的 reentry skip。
- 新增耗时指标 `datasmart_data_sync_recovery_tick_duration`，用于观察单轮恢复耗时和配置是否合理。
- 新增 Gauge：`last_scanned`、`last_recovered`、`last_attention_required`、`last_success_epoch_seconds`、`last_failure_epoch_seconds`。

设计价值：
- 自动恢复从“会运行”升级为“可观测、可告警”，更符合商业化生产运维要求。
- 指标组件独立封装，scheduler 仍主要表达调度流程，后续切换 Prometheus registry 或接 OpenTelemetry 不需要改业务逻辑。
- 指标标签保持低基数，不包含 taskId、executionId、tenantId、traceId，避免时序库被高基数字段拖垮。
- 最近成功/失败时间 Gauge 可以支持“恢复任务长时间未成功运行”的静默故障告警。

当前边界：
- 当前只暴露 Actuator metrics，尚未引入 Prometheus registry，因此还没有 `/actuator/prometheus`。
- 当前没有落库恢复指标快照，服务重启后 Gauge 会从 0 开始。
- 当前没有内置告警规则文件，后续需要在 observability 模块补 PrometheusRule/Grafana dashboard。
- 指标当前是全服务维度，没有租户级、连接器级和数据源级维度；这是为了避免过早引入高基数标签。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:30:12+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；data-sync 最大文件仍为 `DataSyncServiceImpl` 约 400 行。

下一步建议：
1. 增加幂等记录清理任务，按保留期清理历史 `data_sync_callback_idempotency`。
2. 在 observability 模块补 data-sync 自动恢复 Prometheus/Grafana 指标说明和告警规则草案。
3. 将人工介入、事故、恢复和执行器回调接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
4. 将 claim 扩展为商业调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。
5. 为多实例自动恢复增加分布式锁、leader election 或调度分片。

### 2026-05-08：data-sync 幂等记录保留期清理

本轮为 data-sync 的执行器回调幂等表补齐保留期清理能力。上一轮已经把 start、checkpoint、complete、fail、heartbeat、defer、recover 等动作接入持久化幂等保护，但真实生产环境中 checkpoint 和 heartbeat 频率很高，如果幂等记录无限保留，唯一索引和二级索引会持续膨胀，影响写入、备份、恢复和排障查询。本轮采用配置化保留期、小批量删除、调度指标的方式，避免为了清理历史数据而冲击主业务链路。

落地内容：
- `SyncCallbackIdempotencyMapper` 新增 `deleteExpiredRecords(expireBefore, limit)`，使用 MySQL `DELETE ... ORDER BY ... LIMIT ...` 进行小批量渐进式清理。
- `DataSyncExecutorProperties` 新增 `idempotencyCleanup` 配置块，支持 `enabled`、`retentionDays`、`initialDelayMs`、`fixedDelayMs`、`batchSize`。
- 新增 `SyncCallbackIdempotencyCleanupService`，负责根据保留期计算过期边界，并调用 Mapper 删除历史记录。
- 新增 `SyncIdempotencyCleanupResult`，在清理服务、调度器、指标和日志之间传递同一份清理事实。
- 新增 `DataSyncIdempotencyCleanupScheduler`，周期性执行幂等记录清理，并使用 `AtomicBoolean` 避免单 JVM 内重入。
- 新增 `DataSyncIdempotencyCleanupMetrics`，暴露清理轮次、删除数量、失败次数、跳过次数、单轮耗时、最近成功/失败时间等指标。
- `data-sync/src/main/resources/application.yml` 增加详细中文配置说明，便于后续按不同客户审计窗口和数据规模调优。
- `docker/mysql/init/init.sql` 为 `data_sync_callback_idempotency` 增加 `idx_data_sync_callback_retention(last_seen_time, id)`，支撑按保留期清理。

设计价值：
- 幂等能力从“可写入、可裁决”补齐到“可长期运营”，避免支撑表成为数据库增长黑洞。
- 清理逻辑拆分为 Mapper、support service、scheduler、metrics，避免把调度、SQL、保留期策略、观测代码耦合在一个大类中。
- 单轮删除上限和 fixedDelay 配合使用，更适合商业系统的温和后台维护，避免长事务和 binlog 峰值。
- 清理指标保持低基数，不把 tenantId、taskId、executionId、idempotencyKey 放入标签，保护 Prometheus/Grafana 的稳定性。

当前边界：
- 当前清理任务是单表单策略，尚未支持按租户、动作类型或执行器分层保留。
- 当前多实例并发清理依赖数据库删除事实自然裁决，尚未引入分布式锁；大规模部署时可进一步加 Redis/DB 锁。
- 当前只清理幂等记录，checkpoint、error sample、audit、incident 等表后续也需要独立保留期策略。
- 当前没有提供管理接口手动触发清理或查看清理历史，后续可接入 permission-admin 和运维菜单。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:35:55+08:00`。
- 已执行 UTF-8 Java 行数扫描，当前全仓 Java 文件无超过 500 行的文件；data-sync 最大文件仍为 `DataSyncServiceImpl` 约 400 行。

下一步建议：
1. 为 data-sync 的 checkpoint、error sample、audit record、incident record 制定分级保留期和归档策略。
2. 将人工介入、事故、恢复和执行器回调接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
3. 将 claim 扩展为商业调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。
4. 为多实例自动恢复和清理任务增加分布式锁、leader election 或租户分片，降低重复扫描成本。
5. 在 observability 模块补 data-sync 自动恢复与幂等清理的 Prometheus/Grafana 指标说明和告警规则草案。

### 2026-05-08：data-sync 运行数据分级保留期清理

本轮继续补齐 data-sync 的生产运行数据生命周期治理。上一轮只清理了执行器回调幂等表，但真正的商业系统还会持续产生 checkpoint、错误样本、审计记录和事故记录。这些数据都很有价值：checkpoint 支持恢复，错误样本支持排障，审计记录支持合规，事故记录支持运营闭环；但如果全部永久保留，会逐步拖慢索引、备份、查询和迁移。本轮按数据价值差异设计分级保留期，并通过小批量调度清理降低数据库压力。

落地内容：
- 新增 `DataSyncMaintenanceProperties`，把运行数据维护配置从执行器协议配置中拆出，避免 `DataSyncExecutorProperties` 继续膨胀。
- `SyncCheckpointMapper` 新增 `deleteExpiredCheckpoints(expireBefore, limit)`，按 `checkpoint_time` 小批量清理历史 checkpoint。
- `SyncErrorSampleMapper` 新增 `deleteExpiredErrorSamples(expireBefore, limit)`，按 `create_time` 小批量清理历史错误样本。
- `SyncAuditRecordMapper` 新增 `deleteExpiredAuditRecords(expireBefore, limit)`，按 `create_time` 小批量清理历史审计记录。
- `SyncIncidentRecordMapper` 新增 `deleteExpiredClosedIncidents(expireBefore, limit)`，只清理 `CLOSED` 且 `closed_at` 超过保留期的事故，避免误删未闭环事项。
- 新增 `SyncOperationalDataCleanupService`，集中执行四类运行数据的保留期策略。
- 新增 `SyncOperationalDataCleanupResult`，统一承载四类数据的过期边界和删除数量。
- 新增 `DataSyncOperationalDataCleanupScheduler`，周期触发运行数据清理，并使用 `AtomicBoolean` 避免单 JVM 内重入。
- 新增 `DataSyncOperationalDataCleanupMetrics`，暴露清理轮次、各类数据删除量、失败次数、跳过次数、耗时、最近成功/失败时间。
- `application.yml` 新增 `datasmart.data-sync.maintenance.operational-data-cleanup` 配置块，默认 checkpoint 30 天、错误样本 90 天、审计记录 365 天、已关闭事故 180 天。
- `init.sql` 为 checkpoint、error sample、audit record、incident record 增加保留期清理索引。

设计价值：
- data-sync 从“只实现业务写入”继续演进到“可长期运营”，运行数据不再无限增长。
- 运行数据按业务价值分级保留，而不是一刀切删除；审计保留最长，checkpoint 较短，事故只清理已关闭记录。
- 维护配置独立成 `DataSyncMaintenanceProperties`，减少执行器协议和平台维护策略之间的耦合。
- 清理调度、清理服务、Mapper SQL、指标组件分层拆开，继续保持单文件低行数和职责清晰。
- 指标只使用固定枚举 `data_type`，避免 tenantId/taskId/executionId 这类高基数标签拖垮时序库。

当前边界：
- 当前仍是“删除型保留期”，尚未实现归档到冷表、MinIO 对象存储或数据仓库。
- 当前各租户共用一套保留期，尚未支持租户级、行业级或合同级差异化保留策略。
- 当前没有管理接口查看清理历史、手动触发清理或临时冻结某类数据。
- 当前多实例并发清理依赖数据库删除结果自然裁决，尚未接入分布式锁或调度分片。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:45:32+08:00`。
- 已执行 UTF-8 Java 行数扫描，data-sync 最大文件仍为 `DataSyncServiceImpl` 约 400 行，新增文件最大约 156 行。

下一步建议：
1. 为 data-sync 自动恢复、幂等清理、运行数据清理补 Prometheus/Grafana 指标说明和告警规则草案。
2. 将人工介入、事故、恢复和执行器回调接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
3. 为多实例自动恢复和清理任务增加分布式锁、leader election 或租户分片，降低重复扫描成本。
4. 将 claim 扩展为商业调度策略：connectorType、sourceDatasourceId、targetDatasourceId、priority、tenant fairness、connector concurrency cap。
5. 设计冷归档能力：按租户/月份归档到冷表或 MinIO，支持审计导出和按需恢复查询。

### 2026-05-08：data-sync Prometheus 告警与可观测 Runbook

本轮把 data-sync 前几轮已经实现的 Micrometer 指标接入 Prometheus 抓取和告警规则草案。此前 data-sync 已经具备自动恢复、幂等清理、运行数据清理指标，但如果 Prometheus 不抓取 data-sync，且没有规则文件和运维说明，这些指标就只能停留在代码层。本轮补齐从指标暴露、Prometheus 抓取、规则加载、告警语义到中文 runbook 的第一版闭环。

落地内容：
- `data-sync/pom.xml` 新增 `micrometer-registry-prometheus`，让 data-sync 具备 `/actuator/prometheus` 暴露能力。
- `data-sync/src/main/resources/application.yml` 将 Actuator 暴露端点扩展为 `health,info,metrics,prometheus`，并显式开启 Prometheus export。
- `docker/prometheus/prometheus.yml` 新增 `data-sync` 抓取目标，指向 `host.docker.internal:8086/actuator/prometheus`。
- `docker/prometheus/prometheus.yml` 新增 `rule_files: /etc/prometheus/rules/*.yml`，让 Prometheus 可以加载模块化告警规则。
- `docker-compose.yml` 为 Prometheus 容器挂载 `./docker/prometheus/rules:/etc/prometheus/rules`。
- 新增 `docker/prometheus/rules/data-sync-alerts.yml`，覆盖服务不可抓取、目标缺失、自动恢复静默、恢复失败、恢复重入跳过、人工介入增长、幂等清理静默、幂等清理失败、运行数据清理静默、运行数据清理失败、运行数据清理量较高等告警。
- 新增 `docs/observability-data-sync-runbook.md`，用中文说明 data-sync 指标含义、Prometheus 抓取关系、低基数原则、Grafana 面板建议、告警等级和后续增强方向。

设计价值：
- data-sync 的“自愈能力”和“后台维护能力”从代码指标升级为可抓取、可告警、可解释的运营能力。
- 告警规则优先覆盖静默故障、调度失败、人工介入增长和清理积压，贴近真实生产值班场景。
- Prometheus 标签继续保持低基数，不把 tenantId、taskId、executionId、traceId 等动态字段放入时序标签。
- runbook 把每个指标背后的业务含义写清楚，避免后续只有图表但不知道如何排障。

当前边界：
- 当前只提供 Prometheus 规则和 Grafana 面板建议，尚未生成可导入的 Grafana dashboard JSON。
- 当前没有接入 Alertmanager 路由、通知接收人、静默策略或值班表。
- 本地未安装 `promtool`，本轮已人工复核 YAML 结构并通过 Maven 编译，但尚未用 Prometheus 官方工具校验规则语法。
- 当前 data-sync 指标仍是服务级视角，租户级/连接器级运营视图后续应通过聚合表或低基数中间指标实现，而不是直接把租户 ID 放到 Prometheus 标签。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-08T22:50:52+08:00`。
- 已读取复核 `docker/prometheus/rules/data-sync-alerts.yml` 与 `docker/prometheus/prometheus.yml`。
- 已执行 `Get-Command promtool`，当前环境未安装 promtool，因此规则文件尚未做官方语法校验。

下一步建议：
1. 增加 Grafana dashboard JSON 和 provisioning 配置，让 Prometheus 指标可以一键出图。
2. 接入 Alertmanager 路由草案，区分 critical/warning/info 的通知渠道和静默策略。
3. 将人工介入、事故、恢复和执行器回调接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
4. 为多实例自动恢复和清理任务增加分布式锁、leader election 或租户分片，降低重复扫描成本。
5. 设计 data-sync 冷归档能力：按租户/月份归档到冷表或 MinIO，支持审计导出和按需恢复查询。

### 2026-05-09：data-sync Grafana 自动化看板

本轮在上一轮 Prometheus 抓取和告警规则的基础上，继续补齐 Grafana 自动化配置。此前 Prometheus 已经可以抓取 data-sync，并且有第一版告警规则与 runbook；但如果 Grafana 仍需要手工配置数据源和手工导入看板，本地环境、测试环境和客户交付环境都会出现重复配置成本。本轮通过 provisioning 把 Prometheus 数据源、dashboard provider 和 data-sync 运维总览看板纳入代码版本管理。

落地内容：
- 新增 `docker/grafana/provisioning/datasources/prometheus.yml`，Grafana 启动时自动创建 `uid=prometheus` 的 Prometheus 数据源。
- 新增 `docker/grafana/provisioning/dashboards/dashboards.yml`，Grafana 启动时自动扫描 `/etc/grafana/dashboards` 下的 dashboard JSON。
- 新增 `docker/grafana/dashboards/data-sync-overview.json`，提供 data-sync 运维总览看板。
- `docker-compose.yml` 为 Grafana 挂载 provisioning 目录和 dashboards 目录。
- `docs/observability-data-sync-runbook.md` 增补 Grafana 自动化配置说明、挂载路径设计原因和当前看板包含的面板列表。

看板覆盖：
- data-sync 服务可用性。
- 过期租约自动恢复最近一轮扫描、恢复、转人工介入数量。
- 自动恢复 10 分钟失败次数。
- 自动恢复 15 分钟转人工介入数量。
- 自动恢复 15 分钟处理量。
- 运行数据 6 小时分类型清理量。
- 幂等清理和运行数据清理 15 分钟失败次数。
- 自动恢复、幂等清理、运行数据清理距离最近一次成功的秒数。

设计价值：
- Grafana 配置从“人工点页面”升级为“随代码交付”，更接近真实商业部署。
- 数据源使用固定 `uid=prometheus`，dashboard JSON 可以稳定引用，不依赖 Grafana 自动生成 ID。
- dashboard 挂载到 `/etc/grafana/dashboards`，避免和 `/var/lib/grafana` 数据卷互相遮挡。
- 看板聚焦自愈、清理、人工介入和可用性，优先覆盖值班人员最需要判断的生产信号。

当前边界：
- 当前看板是第一版 JSON，尚未通过实际 Grafana 容器渲染截图验证。
- 当前环境未安装 Docker CLI，因此未执行 `docker compose config` 或 Grafana 容器启动验证。
- 当前看板没有变量筛选、行分组、链接到 runbook 或 Alertmanager 告警详情。
- 当前仍是服务级看板，租户级/连接器级视图后续需要通过低基数聚合指标或运营聚合表实现。

验证结果：
- 已执行 dashboard JSON 解析校验：`ConvertFrom-Json` 成功读取标题、uid、version。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-09T19:36:20+08:00`。
- 已读取复核 Grafana datasource/provider YAML。
- 已执行 `Get-Command docker`，当前环境未安装 Docker CLI，因此未做容器级验证。

下一步建议：
1. 接入 Alertmanager 路由草案，区分 critical/warning/info 的通知渠道、静默策略和值班响应要求。
2. 为 Grafana dashboard 增加变量、runbook 链接和按功能分区的行折叠。
3. 将人工介入、事故、恢复和执行器回调接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
4. 为多实例自动恢复和清理任务增加分布式锁、leader election 或租户分片，降低重复扫描成本。
5. 设计 data-sync 冷归档能力：按租户/月份归档到冷表或 MinIO，支持审计导出和按需恢复查询。

### 2026-05-09：Alertmanager 告警路由草案

本轮在 Prometheus 规则和 Grafana 看板之后，补齐 Alertmanager 告警路由草案。Prometheus 规则负责判断“是否触发告警”，Grafana 负责“可视化观察”，但真实商业运维还需要回答“告警发给谁、按什么等级响应、哪些衍生告警应该被抑制、多久重复提醒”。本轮先用本地安全的空接收器占位，建立 critical/warning/info 的路由、分组和抑制结构，避免在没有真实密钥和通知渠道时误发外部通知。

落地内容：
- 新增 `docker/alertmanager/alertmanager.yml`，包含全局超时、按 `module/category/severity` 分组、critical/warning/info 路由和抑制规则。
- `docker/prometheus/prometheus.yml` 新增 `alerting.alertmanagers`，把 Prometheus 告警发送到 `alertmanager:9093`。
- `docker-compose.yml` 新增 `alertmanager` 服务，挂载配置文件和持久化数据卷。
- `docker-compose.yml` 新增 `alertmanager_data` 数据卷。
- `docs/observability-data-sync-runbook.md` 新增 Alertmanager 路由章节，说明本地空接收器设计、生产接入建议和抑制规则语义。

设计价值：
- 可观测链路从“指标 -> 规则 -> 看板”继续扩展到“告警路由与抑制”，更接近真实值班体系。
- 本地环境使用空接收器，既能验证 Alertmanager 路由结构，又避免误发真实通知。
- 路由按 `critical/warning/info` 分层，为后续短信、电话、企业 IM、工单系统接入预留清晰边界。
- 抑制规则按 `module + category` 处理根因和衍生告警，减少同一故障引发的告警风暴。

当前边界：
- 当前 receiver 仍为空接收器，尚未接入邮件、企业微信、钉钉、飞书、PagerDuty 或工单 webhook。
- 当前没有密钥注入方案，后续真实渠道必须通过环境变量、密钥管理系统或部署平台注入，不能写入仓库。
- 当前没有定义值班表、升级策略、维护窗口或业务日历。
- 当前没有容器级启动验证。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-09T19:41:32+08:00`。
- 已读取复核 `docker/alertmanager/alertmanager.yml`。
- 已执行 `Get-Command amtool`，当前环境未安装 amtool，因此未做 Alertmanager 官方配置校验。
- 已执行 `Get-Command docker`，当前环境未安装 Docker CLI，因此未做 `docker compose config` 或容器启动验证。

下一步建议：
1. 为 Alertmanager 增加真实通知渠道模板，但用环境变量或示例占位方式避免提交密钥。
2. 为 Grafana dashboard 增加变量、runbook 链接和按功能分区的行折叠。
3. 将人工介入、事故、恢复和执行器回调接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
4. 为多实例自动恢复和清理任务增加分布式锁、leader election 或租户分片，降低重复扫描成本。
5. 设计 data-sync 冷归档能力：按租户/月份归档到冷表或 MinIO，支持审计导出和按需恢复查询。

### 2026-05-09：Alertmanager 通知模板与真实渠道示例

本轮在 Alertmanager 路由草案基础上，补齐真实通知接入前的模板和示例配置。上一轮已经有 critical/warning/info 的路由、分组和抑制，但 receiver 仍为空接收器；如果后续直接在主配置里临时粘贴 webhook 或 SMTP 配置，很容易出现消息格式不统一、密钥误提交、不同客户交付方式混乱等问题。本轮把通知标题、正文和接收器示例拆出来，形成可复用、可学习、可安全交付的接入基础。

落地内容：
- `docker/alertmanager/alertmanager.yml` 新增 `templates: /etc/alertmanager/templates/*.tmpl`，为后续真实通知渠道加载统一模板。
- 新增 `docker/alertmanager/templates/datasmart-notification.tmpl`，定义 `datasmart.alert.title`、`datasmart.alert.text`、`datasmart.webhook.payload`。
- 新增 `docker/alertmanager/notification-channels.example.yml`，提供 webhook、email、低优先级 webhook 三类示例 receiver。
- `docker-compose.yml` 为 Alertmanager 挂载 `./docker/alertmanager/templates:/etc/alertmanager/templates`。
- `docs/observability-data-sync-runbook.md` 新增“通知模板与真实渠道接入”章节，说明模板用途、示例文件为什么不自动加载、真实渠道接入步骤和安全要求。

设计价值：
- 告警通知格式从“各渠道自己拼”升级为“统一模板复用”，便于跨邮件、IM、工单系统保持一致。
- 示例 receiver 不自动加载，避免本地开发误发通知，也避免提交真实 webhook/token/SMTP 密码。
- 将密钥注入要求前置到文档，明确生产环境应使用环境变量、Secret、Vault、Nacos 加密配置或部署平台密钥。
- 后续接真实渠道时，只需要复制示例 receiver 并绑定模板，不必重新设计告警格式。

当前边界：
- 当前主 Alertmanager 配置仍使用空接收器，真实通知渠道尚未启用。
- `notification-channels.example.yml` 是示例文件，不会被 Alertmanager 自动加载。
- Alertmanager webhook 的实际载荷是否能完全自定义取决于接收端或中转网关能力，当前 `datasmart.webhook.payload` 主要作为自研告警网关模板参考。
- 当前环境未安装 `amtool` 和 Docker CLI，尚未做官方配置校验或容器级验证。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-09T19:46:41+08:00`。
- 已读取复核 `alertmanager.yml`、`datasmart-notification.tmpl`、`notification-channels.example.yml`。
- 已执行 `Get-Command amtool` 与 `Get-Command docker`，当前环境均不可用，因此未做官方配置校验和容器启动验证。

下一步建议：
1. 为 Grafana dashboard 增加变量、runbook 链接和按功能分区的行折叠。
2. 将人工介入、事故、恢复和执行器回调接入 permission-admin 与 gateway，形成菜单、按钮和服务账号权限闭环。
3. 为多实例自动恢复和清理任务增加分布式锁、leader election 或租户分片，降低重复扫描成本。
4. 设计 data-sync 冷归档能力：按租户/月份归档到冷表或 MinIO，支持审计导出和按需恢复查询。
5. 设计租户级/连接器级低基数运营聚合指标，避免直接把租户 ID 放进 Prometheus 标签。

### 2026-05-09：data-sync 权限治理与网关入口闭环

本轮从单纯 data-sync 内部能力转向跨模块商业化治理：把同步模板、同步任务、执行器租约、执行器回调、事故工作台和人工介入运营动作接入 gateway 与 permission-admin 的权限模型。这样做的原因是，真实企业产品不能只依赖业务服务内部的角色兜底校验；高风险同步恢复、事故关闭、执行器回调等动作必须在入口层、权限中心、菜单矩阵和审计语义上都有清晰边界。

落地内容：
- `gateway` 路由新增 `RewritePath`，将外部 `/api/task/**`、`/api/datasource/**`、`/api/sync/**`、`/api/quality/**`、`/api/permission/**` 改写到各服务内部控制器路径，修复“网关前缀存在但下游 Controller 不感知前缀”的集成断点。
- `GatewayAuthorizationFilter` 和 `PermissionDecisionSupport` 的路径匹配升级为 `PathPatternParser`，支持 `*`、`/**` 和未来 `{id}` 风格的端点级策略。
- `gateway.application.yml` 为 data-sync 增加端点级 route-metadata，区分 `SYNC_TEMPLATE`、`SYNC_TASK`、`SYNC_EXECUTION`、`SYNC_INCIDENT`、`SYNC_OPERATION`。
- `PermissionResourceType` 增加同步模板、执行、事故、运营动作等资源类型，避免继续把所有 data-sync 能力塞进单一同步任务资源。
- `PermissionAction` 增加 `VALIDATE`、`RUN`、`CANCEL`、`ARCHIVE`、`ACKNOWLEDGE`、`ASSIGN`、`RESOLVE`、`CLOSE`、`CLAIM`、`HEARTBEAT`、`DEFER`、`CALLBACK`、`RECOVER` 等动作编码，为按钮级权限和审计分类预留标准词汇。
- `permission-admin.sql` 增加 data-sync 菜单、角色菜单绑定、端点级路由策略和数据范围策略，覆盖普通用户、项目负责人、运营、审计、租户管理员、平台管理员和服务账号。

设计价值：
- gateway 公开 API、业务服务内部 Controller 和 permission-admin 策略矩阵形成闭环，避免“路由能转发但权限不可解释”或“权限可配置但请求进不了服务”。
- 普通用户/项目负责人可以管理授权范围内同步任务，但默认不能执行人工介入、事故处置和执行器回调，降低越权恢复和伪造执行结果风险。
- 运营人员获得同步事故、人工介入和过期租约恢复能力；服务账号获得执行器租约和回调能力；审计员保持只读视角。
- 端点级路径匹配为后续按钮权限、审批流、服务账号签名、租户级策略覆盖和缓存失效提供基础。

当前边界：
- gateway 授权仍默认 `enabled=false`、`shadow-mode=true`，本轮是能力和配置闭环，不代表本地开发默认强制拦截。
- 当前路由策略仍主要基于路径和 HTTP 方法；更细的 action 语义已经有枚举和 route-metadata，但 permission-admin 判定还没有把 action 作为策略匹配条件。
- 服务账号签名、JWT/IdP、审批流和数据范围表达式下沉到业务查询还没有完整落地。
- `RewritePath` 尚未通过真实网关运行请求验证；当前只完成编译验证。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-09T19:58:27+08:00`。
- 已执行 Java 文件行数扫描，当前最高为 `SyncPermissionNotificationServiceImpl.java` 499 行，新改动文件均低于 500 行。
- 已复核 gateway 路由、data-sync route-metadata、permission-admin 资源/动作枚举和 `permission-admin.sql` 种子数据。

下一步建议：
1. 将 permission-admin 的路由策略从“路径 + HTTP 方法”升级为“路径 + HTTP 方法 + resourceType + action”，让 `CALLBACK`、`RECOVER`、`ACKNOWLEDGE` 等动作真正参与策略命中。
2. 为 data-sync 服务账号增加签名校验或短期令牌校验，避免只凭角色 Header 访问执行器回调。
3. 把 permission-admin 返回的数据范围表达式下沉到 data-sync 查询服务，真正限制普通用户、项目负责人、运营和审计员的任务/事故可见范围。
4. 为高风险同步动作增加审批模型，例如跨租户恢复、批量取消、紧急补数、关闭 P1/P2 事故。
5. 为 gateway 路由增加集成测试或轻量启动验证，覆盖 `/api/sync/sync-tasks`、`/api/sync/sync-incidents`、`/api/sync/sync-executions/claim` 等路径改写。

### 2026-05-09：permission-admin 动作级路由策略判定

本轮承接上一轮 data-sync 权限治理闭环，继续把 permission-admin 的路由策略从“路径 + HTTP 方法”升级为“路径 + HTTP 方法 + resourceType + action”。上一轮 gateway 已经能把 data-sync 请求解释成 `SYNC_EXECUTION + CALLBACK`、`SYNC_EXECUTION + RECOVER`、`SYNC_INCIDENT + CLOSE` 等语义，但 permission-admin 仍只按路径和方法匹配策略，导致 action 只是审计字段，不能真正参与授权。本轮补齐策略表、实体、DTO、判定逻辑、事件、审计和数据库迁移。

落地内容：
- `permission_route_policy` 增加 `resource_type` 和 `action` 字段，新策略可以精确表达业务资源和业务动作。
- `PermissionRoutePolicy`、`PermissionRoutePolicyMutationRequest` 增加资源类型和动作字段，并补充中文说明。
- `PermissionDecisionSupport` 匹配逻辑增加 resourceType/action 判断；字段为空表示旧式通配策略，保持历史兼容。
- `PermissionRoutePolicyMutationSupport` 创建/更新策略时会规范化 resourceType/action，并在重复策略校验中纳入两个新维度。
- `PermissionAuditSupport` 的策略变更审计快照记录 resourceType/action。
- `PermissionPolicyChangedEvent` 和 outbox 发布器增加 resourceType/action，后续 gateway 或审计聚合可以感知动作级策略变化。
- `gateway` route-metadata 进一步细化 data-sync 动作：模板校验 `VALIDATE`、手动运行 `RUN`、执行器认领 `CLAIM`、心跳 `HEARTBEAT`、延期 `DEFER`、回调 `CALLBACK`、恢复 `RECOVER`、事故确认/分派/解决/关闭等。
- `permission-admin.sql` 更新新表结构、语义索引、种子策略语义补全和动作级显式策略。
- 新增 `docker/mysql/migrations/20260509_permission_route_policy_semantics.sql`，用于已有数据库升级，避免旧数据卷缺少新列。

设计价值：
- 权限判定从“技术入口控制”进一步升级为“业务动作控制”，更接近真实商用 IAM/RBAC 设计。
- 服务账号执行器回调可以通过 `SYNC_EXECUTION + CALLBACK` 精确授权，普通用户、项目负责人、租户管理员可以被显式拒绝伪造回调。
- 运维恢复可以通过 `SYNC_EXECUTION + RECOVER` 单独治理，后续可接审批、值班授权、维护窗口和操作限流。
- 事故动作可以被拆成 `ACKNOWLEDGE / ASSIGN / RESOLVE / CLOSE`，后续可实现 P1/P2 事故关闭审批或审计报表。
- 迁移脚本让已有数据库也能平滑升级，避免只照顾全新环境的 demo 式交付。

当前边界：
- `permission_route_policy` 的新字段已经参与匹配，但数据范围表达式还没有下沉到 data-sync 查询 SQL。
- 当前动作级策略仍基于 gateway route-metadata，尚未由 permission-admin 统一托管端点元数据。
- MySQL 迁移脚本已提供，但当前环境未连接真实 MySQL 执行验证。
- 服务账号仍主要依赖网关注入角色上下文，还没有请求签名、短期 token 或 mTLS。

验证结果：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-09T20:05:51+08:00`。
- 已执行 Java 文件行数扫描，当前最高文件 499 行；本轮修改后的 `GatewayAuthorizationProperties` 和 `GatewayAuthorizationFilter` 均为 405 行。
- 已执行 `git diff --check`，未发现空白错误。

下一步建议：
1. 为 data-sync 执行器回调与租约接口增加服务账号签名或短期令牌校验，避免仅靠 Header 角色判断。
2. 将 permission-admin 的数据范围策略下沉到 data-sync 查询服务，按租户、负责人、项目、角色限制任务和事故可见范围。
3. 为 P1/P2 同步事故关闭、跨租户恢复、批量取消、紧急补数增加审批流模型。
4. 将 gateway route-metadata 逐步抽象成 permission-admin 可维护的端点元数据中心，减少网关配置膨胀。
5. 为权限判定增加单元测试或轻量集成测试，覆盖 `CALLBACK`、`RECOVER`、`ACKNOWLEDGE`、`CLOSE` 等动作级策略。

## 3.00 data-sync 执行器服务账号签名校验（2026-05-09）

本轮承接 2.99 的动作级权限判定，进一步补齐 data-sync 内部机器协议安全边界。上一轮已经能在 gateway 和 permission-admin 层把执行器回调解释为 `SYNC_EXECUTION + CALLBACK`、把认领和心跳解释为 `CLAIM / HEARTBEAT / DEFER`，但业务服务内部仍然会接收可伪造的 Header 上下文。本轮新增 HMAC 服务账号签名校验，使 worker 侧接口具备“服务账号身份 + 时间窗口 + nonce 防重放”的基础生产能力。

已完成：
- 新增 `DataSyncExecutorSecurityProperties`，提供 `datasmart.data-sync.executor.security` 配置块，支持启停开关、签名算法、时钟偏差窗口、nonce 缓存容量、Header 名称和服务账号密钥映射。
- 新增 `DataSyncExecutorServiceAccountSignatureSupport`，使用 HMAC-SHA256 校验执行器请求签名，支持 Base64 与十六进制签名值，并使用常量时间比较降低签名侧信道风险。
- 签名 canonical string 覆盖 HTTP 方法、请求路径与查询串、traceId、服务账号 ID、时间戳和 nonce，保证同一个签名不能随意迁移到不同路径、不同 trace 或不同服务账号上下文。
- 增加本实例内 nonce 防重放缓存，拒绝允许时间窗口内同一服务账号重复使用同一个 nonce。
- `DataSyncExecutorLeaseController` 的 claim、heartbeat、defer 接入签名校验。
- `DataSyncExecutorCallbackController` 的 start、checkpoint、complete、fail 接入签名校验。
- `application.yml` 新增完整中文配置说明，明确本地默认关闭、商用部署建议开启、生产密钥不应提交到 Git。

商业化设计意义：
- 执行器接口从“只要能伪造 actorRole Header 就可能调用”升级为“必须持有平台发放的服务账号密钥”，降低内网横向移动和误调用风险。
- 时间戳与 nonce 让签名具备基础防重放能力，适合 claim、heartbeat、checkpoint 等高频机器动作。
- 服务账号签名与 permission-admin 动作级策略形成双层边界：入口层判断资源动作是否允许，业务服务层确认调用者是否是真实 worker。
- 配置项保留 Header 名称、算法、窗口和密钥映射，为后续多 worker、多租户专属 worker、密钥轮换和 KMS 接入预留空间。

当前边界：
- 签名默认 `enabled=false`，本地开发不会强制拦截；生产环境需要通过配置中心或环境变量开启并注入密钥。
- 当前 nonce 缓存是单实例内存实现，多实例部署下应升级为 Redis、Caffeine 集群缓存或统一执行器网关，避免跨实例重放。
- 当前签名没有覆盖请求体。后续如需更强完整性，应增加 `X-DataSmart-Body-Digest` 并通过 Servlet Filter 缓存 body 后参与校验。
- 当前没有实现密钥轮换窗口、多密钥版本号、mTLS 或短期 JWT，这些属于更完整的机器身份体系下一阶段。

验证：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-09T20:15:54+08:00`。
- 已执行新增和修改 Java 文件行数扫描：`DataSyncExecutorServiceAccountSignatureSupport` 219 行、`DataSyncExecutorSecurityProperties` 110 行、两个控制器均低于 150 行，符合单文件不超过 500 行要求。

下一步建议：
1. 将 permission-admin 的数据范围策略下沉到 data-sync 查询服务，按租户、负责人、项目、角色限制任务、执行记录、事故和审计记录可见范围。
2. 为执行器签名增加 Redis 分布式 nonce 存储、服务账号密钥版本号和灰度轮换窗口，支撑多实例生产部署。
3. 为 P1/P2 同步事故关闭、跨租户恢复、批量取消、紧急补数增加审批流模型。
4. 为 gateway route-metadata 设计 permission-admin 可维护的端点元数据中心，减少网关配置膨胀。
5. 增加执行器签名单元测试或轻量集成测试，覆盖缺失 Header、过期时间戳、错误签名、重复 nonce、Base64/Hex 两种签名格式。

## 3.01 permission-admin 数据范围下沉到 data-sync 查询链路（2026-05-09）

本轮承接 3.00 的服务账号签名校验，继续把权限治理从“入口能不能访问”推进到“进入接口后能看到哪些数据”。此前 permission-admin 的判定结果中已经包含 `dataScopeLevel` 和 `dataScopeExpression`，但 gateway 没有透传给业务服务，data-sync 也只做了本地租户兜底。本轮补齐跨模块链路：permission-admin 判定结果由 gateway 写入统一 Header，data-sync 控制器读取 Header 形成 `SyncActorContext`，Service 层再把范围翻译成 MyBatis 查询条件。

已完成：
- `PlatformContextHeaders` 新增 `X-DataSmart-Data-Scope-Level`、`X-DataSmart-Data-Scope-Expression`、`X-DataSmart-Approval-Required` 三个统一上下文 Header，并补充中文注释。
- `GatewayPermissionDecisionResult` 增加 `dataScopeExpression` 字段，完整接收 permission-admin 的范围表达式。
- `GatewayAuthorizationFilter` 在权限允许时把 data scope 和 approval required 透传到下游业务服务，请求被拒绝或影子拒绝时不伪造范围上下文。
- `SyncActorContext` 增加数据范围级别、范围表达式和审批要求字段，并保留四参数构造方法兼容后台调度器和旧调用点。
- 新增 `SyncDataVisibility`，把 permission-admin 范围结果翻译为 data-sync 查询可见性对象。
- `SyncDataScopeSupport` 增加 `resolveVisibility`、`validateOwnedReadable`、`validateAnyActorReadable`，支持 SELF、PROJECT、TENANT、PLATFORM 四类范围的本地落地。
- `DataSyncTaskController`、`DataSyncTemplateController`、`DataSyncExecutionController`、`DataSyncIncidentController` 读取 gateway 透传 Header，并注入到 `SyncActorContext`。
- `DataSyncServiceImpl` 的模板列表、任务列表、模板详情、任务详情接入 SELF/TENANT/PLATFORM 过滤：普通用户默认只能看自己创建的模板和自己负责的任务。
- 执行历史、checkpoint、错误样本和审计记录通过 `syncTaskId` 先校验任务可见性，再查询运行证据，避免同租户内越权查看他人任务执行详情。
- `DataSyncIncidentServiceImpl` 的事故列表和详情接入 SELF/TENANT/PLATFORM 过滤：SELF 范围下只能看自己创建或被分派的事故。

商业化设计意义：
- 权限中心的数据范围策略不再只停留在审计结果里，而是真正进入业务查询条件，产品从“路由级 RBAC”向“业务数据级授权”前进。
- 普通用户、项目负责人、审计员、运营、租户管理员和平台管理员可以共享同一套接口，但看到的数据范围不同，更接近真实企业后台。
- gateway 不解析数据范围表达式，只负责可信透传；data-sync 按自己的领域字段安全转换为查询条件，避免把表达式直接拼 SQL 造成注入风险。
- 统一 Header 后，datasource-management、data-quality、task-management 后续也能复用同一条下沉链路。

当前边界：
- `PROJECT` 范围目前阶段性降级为租户范围，因为 data-sync 表结构暂时没有 `project_id`、`workspace_id` 或业务域字段；后续需要补数据模型后才能真正项目级过滤。
- 当前没有解析任意 `scopeExpression` DSL，只按 `scopeLevel` 落地，避免在没有安全解析器时把表达式直接拼进 SQL。
- 执行记录、checkpoint、错误样本和审计记录当前依赖任务路径里的 `taskId` 校验可见性；未来如果提供跨任务全局执行列表，需要增加与任务表 join 或冗余 owner/project 字段。
- 网关授权仍默认可能处于 shadow/fail-open 配置，生产环境需要结合 gateway 授权开关、permission-admin 策略和业务服务兜底一起启用。

验证：
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-09T20:26:24+08:00`。
- 已执行 `git diff --check`，未发现空白错误；仅有 Git 在 Windows 下的 LF/CRLF 提示。
- 已执行 Java 文件行数扫描，当前最高文件 459 行，`GatewayAuthorizationFilter` 407 行，`DataSyncServiceImpl` 377 行，均低于 500 行。

下一步建议：
1. 为 data-sync 表结构增加 `project_id` 或 `workspace_id`，让 PROJECT 范围从租户级降级恢复为真正项目级隔离。
2. 为 data scope 下沉增加单元测试或轻量集成测试，覆盖 SELF/TENANT/PLATFORM、缺失 Header、普通用户越权查询等场景。
3. 把同一套数据范围 Header 消费模式复制到 datasource-management 和 data-quality，避免只有 data-sync 做业务数据级授权。
4. 为执行器签名增加 Redis 分布式 nonce 存储、密钥版本号和灰度轮换窗口。
5. 为 P1/P2 同步事故关闭、跨租户恢复、批量取消、紧急补数增加审批流模型。

## 3.02 data-sync 项目/工作空间范围字段与 PROJECT 隔离基础（2026-05-09）

本轮承接 3.01 的数据范围下沉链路，补齐 data-sync 领域模型缺失的项目/工作空间字段。上一轮 permission-admin 已经能返回 `PROJECT` 数据范围，权限种子策略也写了 `project_id IN ${actorProjectIds}`，但 data-sync 表没有 `project_id/workspace_id`，导致 PROJECT 只能阶段性降级为租户范围。本轮让模板、任务和运行事实具备项目/空间维度，并把创建、查询、执行、checkpoint、错误样本、事故记录的传播链路打通。

已完成：
- `SyncTemplate`、`SyncTask`、`SyncExecution`、`SyncCheckpoint`、`SyncErrorSample`、`SyncIncidentRecord`、`SyncAuditRecord` 增加 `projectId` 和 `workspaceId` 字段，并补充中文业务说明。
- `CreateSyncTemplateRequest` 增加 `projectId/workspaceId`，模板创建时保存所属项目与空间。
- `CreateSyncTaskRequest` 增加 `projectId/workspaceId`，任务创建时默认继承模板；如果显式传入，则必须与模板一致，避免把模板挂到错误项目。
- `SyncTemplateQueryCriteria`、`SyncTaskQueryCriteria`、`SyncIncidentQueryCriteria` 增加项目/空间查询条件。
- `DataSyncTemplateController`、`DataSyncTaskController`、`DataSyncIncidentController` 支持 `projectId/workspaceId` 查询参数。
- `SyncDataVisibility` 和 `SyncDataScopeSupport` 支持项目/空间范围字段；请求携带项目或空间条件时，会在 data-sync 查询层实际过滤。
- `DataSyncServiceImpl` 的模板/任务列表按 `tenantId + projectId/workspaceId + SELF` 组合收敛；任务创建时校验项目/空间必须与模板一致。
- `SyncExecutionCreationSupport` 创建 execution 时从任务冗余项目/空间；`SyncExecutionLifecycleSupport` 写 checkpoint 和错误样本时继续传播；`DataSyncAttentionOperationServiceImpl` 创建事故时继续传播。
- `docker/mysql/init/init.sql` 为 data-sync 模板、任务、执行、checkpoint、错误样本、事故、审计表增加项目/空间字段和关键查询索引。
- 新增 `docker/mysql/migrations/20260509_data_sync_project_workspace_scope.sql`，为已有数据库幂等补列、回填历史任务/运行事实项目空间字段，并创建核心项目级索引。

商业化设计意义：
- data-sync 不再只有租户级隔离基础，开始具备租户内部项目级和空间级治理能力。
- 项目负责人、项目看板、项目级 SLA、项目级成本统计、项目级事故复盘都有了可落地字段。
- 运行事实表冗余项目/空间，后续做跨任务项目报表、失败率统计、容量分析时不必每次 join 任务表，性能和可维护性更接近生产系统。
- 迁移脚本考虑已有数据卷，避免只服务新环境，降低长任务演进时的数据库升级断点。

当前边界：
- 当前还没有把 `${actorProjectIds}` 从 permission-admin/gateway 物化为下游 Header 或缓存快照，所以 PROJECT 范围在“不传 projectId”时仍无法自动收敛到用户项目集合。
- 当前只对模板、任务、事故列表直接暴露 project/workspace 筛选；执行历史、checkpoint、错误样本和审计仍主要通过任务路径访问，后续如果做全局运行证据列表，应继续增加项目/空间查询接口。
- `SyncAuditRecord` 已增加项目/空间字段，但多数审计写入仍未完全填充项目/空间；后续可让 `SyncAuditSupport` 根据 taskId 回查任务或新增显式项目/空间参数。
- MySQL 迁移脚本已提供但当前环境未连接真实 MySQL 执行验证。

验证：
- 已执行 `./mvnw -pl data-sync -am compile`，platform-common + data-sync 编译成功，完成时间 `2026-05-09T20:33:39+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-09T20:35:18+08:00`。
- 已执行 `git diff --check`，未发现空白错误；仅有 Git 在 Windows 下的 LF/CRLF 提示。
- 已执行 Java 文件行数扫描，当前最高文件 459 行，`DataSyncServiceImpl` 403 行，仍低于 500 行。

下一步建议：
1. 在 gateway/permission-admin 链路中物化 `actorProjectIds` 或项目授权快照，让 PROJECT 范围不依赖用户主动传 `projectId`。
2. 补齐 `SyncAuditSupport` 的项目/空间写入，让项目级审计报表和权限证据查询可直接按项目过滤。
3. 为 data scope 和 PROJECT 范围增加单元测试或轻量集成测试，覆盖普通用户、项目负责人、租户管理员、平台管理员。
4. 把数据范围 Header 消费模式复制到 datasource-management 和 data-quality，实现跨模块一致的数据级授权。
5. 为执行器签名增加 Redis 分布式 nonce 存储、密钥版本号和灰度轮换窗口。

## 3.03 data-sync 审计项目/空间范围自动回填（2026-05-10）

本轮承接 3.02 的项目/工作空间字段补齐，处理审计链路仍未填充项目/空间的问题。3.02 已经让 `SyncAuditRecord` 拥有 `projectId/workspaceId` 字段，但多数业务方法调用 `SyncAuditSupport.saveAudit(...)` 时只传 `tenantId + syncTaskId + executionId`，如果不继续补齐，项目级审计报表会出现字段存在但数据为空的断点。本轮把项目/空间回填收敛到审计支撑组件内部。

已完成：
- `SyncAuditSupport` 注入 `SyncTaskMapper`。
- `saveAudit(...)` 在插入审计记录前调用 `fillProjectScope(...)`。
- `fillProjectScope(...)` 在存在 `syncTaskId` 时读取同步任务，并把任务上的 `projectId/workspaceId` 写入审计记录。
- 对 `syncTaskId` 为空的模板级审计保留当前行为，并在注释中说明后续可通过 templateId 或显式重载继续补齐。

商业化设计意义：
- 项目级审计报表、项目级权限证据导出和项目负责人复盘具备了基础数据来源。
- 调用方不用在每个业务方法里重复传项目/空间，减少业务代码耦合和漏填风险。
- 审计范围回填集中在支撑组件内部，后续如果优化为批量缓存、任务快照或异步审计，也只需要改一个位置。

当前边界：
- 模板级审计当前 `syncTaskId` 为空，仍无法自动回填项目/空间；后续可增加 `templateId` 审计字段或提供带项目/空间的审计重载方法。
- 当前每条任务审计会按 `syncTaskId` 查询一次任务，适合现阶段清晰优先；高吞吐执行器回调场景后续可通过任务快照、缓存或显式参数降低查询成本。

验证：
- 已执行 `./mvnw -pl data-sync -am compile`，platform-common + data-sync 编译成功，完成时间 `2026-05-10T13:08:57+08:00`。
- 已执行 `git diff --check`，未发现空白错误；仅有 Git 在 Windows 下的 LF/CRLF 提示。
- 已执行 Java 文件行数扫描，当前最高文件 459 行，`DataSyncServiceImpl` 403 行，仍低于 500 行。

下一步建议：
1. 为模板级审计补 `templateId/projectId/workspaceId` 直接写入能力，避免模板审计记录项目字段为空。
2. 在 gateway/permission-admin 链路中物化 `actorProjectIds` 或项目授权快照，让 PROJECT 范围不依赖用户主动传 `projectId`。
3. 为 data scope 和 PROJECT 范围增加单元测试或轻量集成测试。
4. 把数据范围 Header 消费模式复制到 datasource-management 和 data-quality。
5. 为执行器签名增加 Redis 分布式 nonce 存储、密钥版本号和灰度轮换窗口。

## 3.04 data-sync 模板级审计范围补齐（2026-05-10）

本轮承接 3.03 的审计范围回填边界，继续处理“模板级审计没有 `syncTaskId`，因此无法通过任务反查项目/空间”的问题。真实商用产品里的模板创建、模板校验、模板禁用、模板变更审批等动作，同样属于重要审计证据；如果这些记录只把模板 ID 写进文本 payload，而没有结构化字段，后续做项目级审计报表、模板生命周期追踪、监管导出和安全排查时都会出现查询成本高、准确性差、权限过滤难的问题。本轮把模板级审计从“payload 中隐含模板信息”升级为“审计表结构化记录模板、项目、空间”。

已完成：
- `SyncAuditRecord` 增加 `templateId` 字段，并补充中文注释说明其适用场景：模板创建、模板校验、模板禁用等没有 `syncTaskId` 的操作。
- `SyncAuditSupport` 新增 `saveTemplateAudit(...)` 方法，调用方传入完整 `SyncTemplate` 后，由审计支撑组件统一写入 `tenantId/projectId/workspaceId/templateId`，避免业务层重复拼装审计范围字段。
- `DataSyncServiceImpl` 的模板创建与模板校验审计改为调用 `saveTemplateAudit(...)`，使模板级审计可以直接按模板、项目和工作空间查询。
- `docker/mysql/init/init.sql` 的 `data_sync_audit_record` 增加 `template_id` 字段和 `idx_data_sync_audit_template_time` 索引，支撑模板维度审计查询。
- 新增 `docker/mysql/migrations/20260510_data_sync_template_audit_scope.sql`，为已有数据库幂等补列、按历史 `action_payload` 尽力回填 `template_id`，并根据模板表回填历史审计的项目/空间字段。

商业化设计意义：
- 模板生命周期审计不再依赖字符串 payload 解析，审计证据从“可读文本”升级为“可查询、可过滤、可关联”的结构化事实。
- 项目级审计报表可以覆盖模板和任务两类核心对象，减少“任务审计完整、模板审计缺失”的治理断点。
- `saveTemplateAudit(...)` 把模板审计写入规范集中到支撑组件中，后续如果扩展模板版本、审批流、变更差异、审计异步化或审计消息队列，只需要在统一入口增强。
- `template_id + create_time` 索引为模板详情页的审计时间线、模板变更追踪和监管导出预留了查询性能基础。

当前边界：
- 迁移脚本对历史 `action_payload` 的 `templateId=...` 解析属于尽力回填；如果历史 payload 格式变化或缺失模板 ID，仍需要人工脚本或离线治理任务补数。
- 当前只补齐了模板创建与模板校验两个已存在的模板级审计动作；后续模板更新、禁用、发布、版本回滚、审批提交等动作落地时，也应统一调用 `saveTemplateAudit(...)`。
- 当前还没有全局模板审计查询 API，结构化字段已经具备，但前端审计页和报表接口仍需后续补齐。
- 当前未连接真实 MySQL 执行迁移脚本，仅完成 SQL 文件设计与 Java 编译验证。

验证：
- 已执行 `./mvnw -pl data-sync -am compile`，platform-common + data-sync 编译成功，完成时间 `2026-05-10T13:14:40+08:00`。
- 已执行 `git diff --check`，未发现空白错误；仅有 Git 在 Windows 下的 LF/CRLF 提示。
- 已执行 Java 文件行数扫描，当前最高文件 438 行，仍低于 500 行约束。

下一步建议：
1. 在 gateway/permission-admin 链路中物化 `actorProjectIds` 或项目授权快照，让 PROJECT 范围不依赖用户主动传 `projectId`。
2. 为 data scope、PROJECT 范围和模板级审计增加单元测试或轻量集成测试，覆盖普通用户、项目负责人、租户管理员、平台管理员。
3. 增加全局审计查询 API，支持按 `templateId/syncTaskId/projectId/workspaceId/actionType/timeRange` 过滤。
4. 把数据范围 Header 消费模式复制到 datasource-management 和 data-quality，实现跨模块一致的数据级授权。
5. 为执行器签名增加 Redis 分布式 nonce 存储、密钥版本号和灰度轮换窗口。

## 3.05 PROJECT 数据范围项目授权快照物化（2026-05-10）

本轮承接 3.02 到 3.04 的 PROJECT 范围字段与审计补齐，继续处理更关键的权限闭环问题：此前 `PROJECT` 数据范围虽然已经能表达 `project_id IN ${actorProjectIds}`，data-sync 也有 `projectId/workspaceId` 字段，但 `${actorProjectIds}` 并没有被 permission-admin/gateway 物化为下游可执行的项目集合。这意味着项目负责人如果不主动传 `projectId`，后端仍无法自动收敛到授权项目；如果只依赖前端传参，也不符合商用权限边界。本轮把“项目授权事实 -> 权限判定结果 -> 网关 Header -> data-sync 查询条件”打通。

已完成：
- `platform-common` 新增统一 Header：`X-DataSmart-Authorized-Project-Ids`，用于承载 permission-admin 已计算好的项目授权快照。
- `permission-admin` 新增 `PermissionProjectMembership` 实体和 `PermissionProjectMembershipMapper`，落地 `permission_project_membership` 项目成员授权事实表。
- `PermissionQuerySupport` 新增 `listActorProjectIds(...)`，按 `tenantId + actorId + enabled` 查询授权项目集合，并做去重。
- `PermissionDecisionResult` 和 `GatewayPermissionDecisionResult` 增加 `authorizedProjectIds` 字段。
- `PermissionDecisionSupport` 在命中 PROJECT 数据范围时查询项目成员表，把 `${actorProjectIds}` 物化为 `List<Long>`；如果没有授权项目，则返回空集合，由下游安全收敛为空结果。
- `GatewayAuthorizationFilter` 把 `authorizedProjectIds` 转成逗号分隔 Header 透传给下游服务。
- `GatewayContractFilter` 将 data scope、authorized project 和 approval Header 纳入不可信 Header 清理范围，避免客户端伪造授权项目集合。
- `data-sync` 新增 `SyncActorContextHeaderSupport`，统一解析 Header，避免多个 Controller 复制数据范围和项目集合解析逻辑。
- `SyncActorContext`、`SyncDataVisibility`、`SyncDataScopeSupport` 增加授权项目集合和 `projectScopeEnforced` 标识；当 gateway 明确透传 PROJECT 范围时，data-sync 必须按授权项目集合收敛。
- `DataSyncServiceImpl` 的模板/任务列表自动追加 `project_id IN (authorizedProjectIds)`；详情接口继续校验资源 `projectId` 是否在授权集合内，防止通过 ID 猜测读取其他项目对象。
- `DataSyncIncidentServiceImpl` 的事故列表和详情也接入授权项目集合过滤，避免事故工作台暴露跨项目失败原因和处置记录。
- `permission-admin.sql` 新增 `permission_project_membership` 表与本地学习用样例授权数据。
- 新增 `docker/mysql/migrations/20260510_permission_project_membership.sql`，用于已有数据库幂等创建项目成员授权表与样例数据。

商业化设计意义：
- PROJECT 范围从“策略表达式存在”升级为“可执行、可审计、可透传、可落地的授权快照”，更接近真实企业 IAM/RBAC + 数据范围模型。
- 项目负责人不再依赖前端主动传 `projectId` 才能限制范围，后端会自动把列表收敛到授权项目集合。
- 如果项目负责人没有项目授权，data-sync 返回空集合而不是退回租户范围，避免安全降级。
- 项目成员授权表为后续项目内角色、授权来源、有效期、组织同步、审批授权、空间级权限和成员管理界面预留了扩展点。
- gateway 只负责可信透传，不直接查询项目成员表；data-sync 只消费项目 ID 集合，不理解 permission-admin 内部模型，跨模块边界更清晰。

当前边界：
- `permission_project_membership` 目前只有基础字段，还没有管理后台 API、批量导入、组织同步、有效期、审批来源和成员变更审计。
- 当前只对 data-sync 的模板、任务、事故列表和详情接入授权项目集合；datasource-management、data-quality、task-management 还没有消费该 Header。
- 执行历史、checkpoint、错误样本和审计查询当前仍主要通过任务路径访问；如果后续提供全局查询接口，需要继续按授权项目集合过滤。
- 当前项目授权集合通过 Header 逗号分隔传输，适合中小规模项目集合；如果单个用户拥有上千项目，应演进为权限快照 ID、Redis 缓存或服务端二次查询，避免 Header 过大。
- 迁移脚本已提供但当前环境未连接真实 MySQL 执行验证。

验证：
- 已执行 `./mvnw -pl permission-admin,gateway,data-sync -am compile`，相关模块编译成功，完成时间 `2026-05-10T13:24:38+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-10T13:24:59+08:00`。
- 已执行 `git diff --check`，未发现空白错误；仅有 Git 在 Windows 下的 LF/CRLF 提示。
- 已执行 Java 文件行数扫描，当前最高文件 438 行，`DataSyncServiceImpl` 418 行，仍低于 500 行约束。

下一步建议：
1. 为 PROJECT 数据范围增加轻量测试，覆盖授权项目为空、授权项目命中、请求未授权 projectId、详情 ID 越权读取等场景。
2. 把 `X-DataSmart-Authorized-Project-Ids` 消费模式复制到 datasource-management 和 data-quality，避免只有 data-sync 实现项目级数据隔离。
3. 为 `permission_project_membership` 增加管理 API，支持项目成员新增、禁用、查询、批量导入和审计。
4. 为项目授权快照设计高项目数量场景方案，例如 Header 大小限制、Redis 快照 ID、权限缓存失效和分页授权查询。
5. 增加全局审计查询 API，支持按 `templateId/syncTaskId/projectId/workspaceId/actionType/timeRange` 过滤。

## 3.06 PROJECT 数据范围测试与公共 Header 编解码契约（2026-05-10）

本轮承接 3.05 的项目授权快照物化，优先补“安全边界可验证性”和“跨模块复用基础”。PROJECT 数据范围属于权限安全链路，不能只依赖人工阅读代码或编译通过；一旦后续重构把空授权集合退化成租户范围，项目负责人就可能看到同租户其他项目的数据。本轮先用纯单元测试把 data-sync 的 PROJECT 行为固定下来，并把授权项目 Header 的解析/格式化规则从 data-sync 私有工具上移到 platform-common，方便 datasource-management、data-quality、task-management 后续复用同一套规则。

已完成：
- `data-sync/pom.xml` 增加 `spring-boot-starter-test` 测试依赖，作用域为 `test`，不进入运行时。
- 新增 `SyncActorContextHeaderSupportTest`，覆盖 `X-DataSmart-Authorized-Project-Ids` 的空值、坏片段、非正数、重复 ID 和审批标识解析。
- 新增 `SyncDataScopeSupportTest`，覆盖 PROJECT 范围下授权项目集合自动收敛、请求未授权 projectId 拒绝、授权 projectId 放行、空授权集合不降级、详情接口 projectId 越权拒绝、迁移期角色兜底不强制空集合等 7 个关键场景。
- 新增 `PlatformAuthorizedProjectHeaderSupport` 到 `platform-common`，集中提供授权项目 Header 的 `format(...)` 和 `parse(...)` 能力。
- `GatewayAuthorizationFilter` 改为使用 `PlatformAuthorizedProjectHeaderSupport.format(...)` 编码项目授权集合。
- `SyncActorContextHeaderSupport` 改为使用 `PlatformAuthorizedProjectHeaderSupport.parse(...)` 解码项目授权集合。
- 已复核 datasource-management 与 data-quality 当前实体模型：`DataSourceConfig`、`QualityRule`、`QualityCheckReport`、`QualityCheckExecution` 等核心表当前尚未具备 `projectId/workspaceId` 字段，因此本轮没有强行接入“看起来过滤、实际无字段可过滤”的假安全逻辑。

商业化设计意义：
- PROJECT 数据范围从“功能实现”进一步升级为“有回归测试保护的安全能力”，降低后续重构破坏权限边界的风险。
- Header 编解码规则集中到 platform-common 后，gateway、data-sync、datasource-management、data-quality 后续会共享同一种坏值处理、去重、空集合语义，避免跨模块权限行为漂移。
- 测试选择纯单元测试，不启动 Spring 容器、不连接 MySQL/Nacos，执行速度快，适合作为后续 CI 的基础安全回归集。
- 明确拒绝在缺少 project/workspace 字段的模块里做假过滤，保持产品安全口径诚实：没有领域字段就不能宣称已经完成项目级隔离。

当前边界：
- 当前测试主要覆盖支撑组件，没有覆盖真实 Controller + MyBatis 查询 SQL；后续应补轻量集成测试或 Mapper 层 SQL 条件断言。
- datasource-management 和 data-quality 还没有项目/空间字段，因此暂未接入 `X-DataSmart-Authorized-Project-Ids` 的真实过滤。
- `PlatformAuthorizedProjectHeaderSupport` 当前使用逗号分隔 Header，适合中小规模项目集合；高项目数量用户仍需权限快照 ID 或 Redis 服务端缓存方案。
- 目前只有 data-sync 有测试依赖和测试样例；其他模块后续应逐步建立对应的安全边界测试。

验证：
- 已执行 `./mvnw -pl data-sync -am test`，2 个测试类、9 个测试用例全部通过，完成时间 `2026-05-10T13:31:54+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-10T13:32:06+08:00`。
- 已执行 `git diff --check`，未发现空白错误；仅有 Git 在 Windows 下的 LF/CRLF 提示。
- 已执行 Java 文件行数扫描，当前最高文件 438 行，仍低于 500 行约束。

下一步建议：
1. 为 datasource-management 的 `DataSourceConfig`、同步模板、同步任务等核心表补 `projectId/workspaceId` 字段、迁移脚本和列表过滤，让数据源与数据同步旧模块具备真实项目级隔离。
2. 为 data-quality 的 `QualityRule`、`QualityCheckExecution`、`QualityCheckReport`、`QualityAnomalyDetail` 补项目/空间字段，并在规则、报告、异常列表中消费授权项目 Header。
3. 为 permission-admin 增加 `permission_project_membership` 管理 API，支持项目成员新增、禁用、查询、批量导入和审计。
4. 增加 Mapper/Controller 层集成测试，验证 `project_id IN (...)`、空授权集合恒 false、详情越权拒绝能真实落到接口行为。
5. 设计高项目数量下的权限快照方案，避免 Header 超长：例如 `X-DataSmart-Authorization-Snapshot-Id` + Redis/Nacos 缓存 + 权限变更事件失效。

## 3.07 datasource-management 项目/工作空间隔离基础（2026-05-10）

本轮承接 3.06 的跨模块 PROJECT Header 契约，正式把 datasource-management 从“具备数据源功能但缺少项目字段”推进到“可以真实消费 PROJECT 数据范围”的阶段。数据源连接、元数据发现、受控只读 SQL 和旧同步控制面都属于高敏感治理资源，如果只按租户隔离，项目负责人仍可能看到同租户其他项目的数据源、模板和任务。本轮优先补领域字段、入口过滤、详情校验和数据库迁移，先把安全边界变成可执行条件。

已完成：
- `DataSourceConfig` 增加 `tenantId/projectId/workspaceId` 字段，并补充中文注释说明租户、项目、空间在权限、审计、配额和成本统计中的作用。
- 旧同步控制面的 `SyncTemplate`、`SyncTask` 增加 `projectId/workspaceId` 字段；任务创建时从模板继承项目/空间，避免模板与任务归属不一致。
- `CreateDataSourceRequest` 增加必填 `tenantId/projectId` 和可选 `workspaceId`；`CreateSyncTemplateRequest` 增加必填 `projectId` 和可选 `workspaceId`。
- 新增 `DatasourceProjectScopeSupport` 与 `DatasourceProjectVisibility`，集中解析 `X-DataSmart-Data-Scope-Level` 和 `X-DataSmart-Authorized-Project-Ids`，统一处理 PROJECT 范围、空授权集合和详情资源校验。
- `DataSourceManagementController` 改为中文学习型注释，并在数据源列表、详情、更新、启停、删除、连接测试、能力画像、元数据发现和受控只读 SQL 入口加入项目可见性校验。
- `SyncTemplateController` 支持 `projectId/workspaceId` 列表筛选，并对详情、更新、校验和预览入口做项目级越权校验。
- `SyncTaskController` 支持 `projectId/workspaceId` 列表筛选，并对详情、更新、审批、调度、运行、暂停、恢复、重试、取消、进度回写、完成、失败、执行历史、checkpoint 和审计查询做项目级校验。
- `DataSourceManagementService` 创建接口扩展租户/项目/空间参数；数据源名称唯一性从全局唯一收敛为“租户 + 项目内唯一”。
- `SyncTemplateServiceImpl` 模板名称唯一性收敛为“租户 + 项目内唯一”，模板预览和审计 payload 补项目/空间信息。
- `SyncTaskLifecycleSupport` 任务名称唯一性收敛为“租户 + 项目内唯一”，任务创建继承模板项目/空间，并校验请求租户必须与模板租户一致。
- `docker/mysql/init/init.sql` 为 `datasource_config`、`sync_template`、`sync_task` 增加项目/空间列、项目维度唯一键和项目/空间查询索引。
- 新增 `docker/mysql/migrations/20260510_datasource_management_project_scope.sql`，为已有数据库幂等补列、回填旧数据、替换旧唯一索引并增加项目查询索引。

商业化设计意义：
- datasource-management 不再是假设“同一租户内所有数据源都对所有人可见”，开始具备真实项目级资产隔离能力。
- 数据源、模板、任务三类对象共享项目/空间归属，为项目级数据源台账、项目级同步看板、项目级成本核算和项目级审计导出打下基础。
- PROJECT 范围下授权项目为空会返回空结果，不会退回租户范围，避免权限安全降级。
- 详情和敏感动作入口都增加 projectId 校验，降低通过 ID 猜测访问其他项目资源的风险。
- 将项目范围解析收敛到 support 组件，避免 Controller 或 Service 大文件继续膨胀，也方便 data-quality 后续复制同样模式。

当前边界：
- 本轮优先覆盖 datasource、旧 sync template、旧 sync task；受控只读 SQL 审计表仍未冗余 datasource projectId，后续项目级审计查询还需要补审计快照字段。
- `SyncTaskAdminController` 的管理员全局队列治理接口仍主要依赖原有管理员权限，后续如果要开放给项目运维角色，需要继续补项目范围过滤或拆分项目级运营接口。
- 迁移脚本默认把历史数据回填到 `project_id=1`，真实客户环境上线前应结合组织/项目台账做更精确的数据归属治理。
- 当前仍使用逗号分隔授权项目 Header；高项目数量用户需要 Redis 权限快照 ID 或服务端二次查询方案。
- 当前只完成编译验证，尚未补 datasource-management 的 PROJECT 范围单元测试/接口测试。

验证：
- 已执行 `./mvnw -pl datasource-management -am compile`，platform-common + datasource-management 编译成功，完成时间 `2026-05-10T13:53:31+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-10T13:53:51+08:00`。
- 已执行 `git diff --check`，未发现空白错误；仅有 Git 在 Windows 下的 LF/CRLF 提示。
- 已执行 datasource-management Java 文件行数扫描，当前最高文件 459 行，`DataSourceManagementController` 358 行，仍低于 500 行约束。

下一步建议：
1. 为 datasource-management 增加 PROJECT 范围单元测试，覆盖授权项目为空、授权项目命中、请求未授权 projectId、详情 ID 越权、受控 SQL 越权等场景。
2. 为 data-quality 的规则、执行、报告、异常明细补 `projectId/workspaceId`，并消费同一套授权项目 Header。
3. 为 `datasource_readonly_sql_execution_audit` 补 datasource 项目/空间快照字段，支持项目级 SQL 访问审计报表。
4. 设计数据源归属迁移 API，支持管理员把数据源、模板、任务从一个项目转移到另一个项目，并记录审批和审计。
5. 继续推进高项目数量下的权限快照方案，避免 Header 超长导致网关或服务端拒绝请求。

## 3.08 data-quality 项目/工作空间隔离基础与 datasource 测试补强（2026-05-10）

本轮承接 3.07 的 datasource-management 项目隔离能力，继续把 PROJECT 数据范围从 data-sync/datasource-management 扩展到 data-quality。质量规则、质量报告和异常样本都属于高敏感治理数据：异常明细里可能包含字段值、主键摘要、样本载荷和清洗建议，如果只按租户隔离，项目成员可能看到其他项目的数据质量问题和业务样本。本轮优先补齐 data-quality 的归属字段、入口过滤、报告/异常检索过滤和数据库迁移，同时为 datasource-management 的项目范围解析补单元测试。

已完成：
- `QualityRule`、`QualityCheckExecution`、`QualityCheckReport`、`QualityAnomalyDetail` 增加 `tenantId/projectId/workspaceId` 字段，并补充中文注释说明租户、项目、空间在质量规则、执行事实、报告摘要和异常样本中的业务意义。
- `CreateQualityRuleRequest` 增加必填 `tenantId/projectId` 和可选 `workspaceId`，创建规则时从入口就建立项目归属。
- 新增 `QualityProjectVisibility` 与 `QualityProjectScopeSupport`，集中解析 `X-DataSmart-Data-Scope-Level` 与 `X-DataSmart-Authorized-Project-Ids`，统一处理 PROJECT 范围、空授权集合、请求未授权 projectId 和详情资源校验。
- `DataQualityController` 接入项目范围校验：规则创建会拒绝写入未授权项目，规则列表支持 `projectId/workspaceId` 筛选，规则详情、更新、目标校验、扫描计划、SQL 计划、调度、启停、归档、恢复、删除、手动检测、规则报告和执行历史都会先校验规则 projectId。
- `QualityReportController` 接入项目范围校验：报告列表、异常明细列表、异常分页和异常聚合都支持 `projectId/workspaceId` 过滤，并消费授权项目 Header。
- `DataQualityService` 与 `DataQualityServiceImpl` 扩展报告/异常查询方法，向查询支撑组件传递 `QualityProjectVisibility`，避免 Controller 直接拼复杂查询条件。
- `QualityRuleLifecycleSupport` 创建规则时写入租户/项目/空间，规则名称唯一性从全局唯一调整为“租户 + 项目内唯一”。
- `QualityExecutionReportSupport` 在创建 execution/report/anomaly 时从规则继承租户/项目/空间，并在报告、异常分页和异常聚合查询中追加 PROJECT 可见范围。
- `QualityAnomalyDetailMapper.aggregateAnomalies(...)` 增加项目/空间和授权项目集合过滤，保证异常聚合大盘不会跨项目统计。
- `docker/mysql/init/init.sql` 为 data-quality 四张核心表增加租户/项目/空间列和项目维度查询索引。
- 新增 `docker/mysql/migrations/20260510_data_quality_project_scope.sql`，用于已有数据库幂等补列、回填执行/报告/异常归属并补项目查询索引。
- `datasource-management/pom.xml` 增加 `spring-boot-starter-test` 测试依赖。
- 新增 `DatasourceProjectScopeSupportTest`，覆盖 PROJECT Header 解析、脏值过滤、未授权 projectId 拒绝、空授权集合不降级、非 PROJECT 范围不强制过滤和详情资源 projectId 校验。

商业化设计意义：
- data-quality 开始具备真实项目级质量治理边界，不再假设同租户所有质量规则和异常样本都可见。
- 规则、执行、报告、异常四层都冗余项目/空间字段，为项目级质量大盘、空间级质量趋势、异常工作台、清洗任务入口和审计导出打下基础。
- PROJECT 范围下空授权集合返回空结果，不退回租户范围，避免权限安全降级。
- 详情和动作入口读取规则后校验 projectId，降低通过 ID 猜测越权操作其他项目规则的风险。
- datasource-management 的项目范围解析开始有单元测试保护，后续重构 Controller 或权限 Header 协议时能更早发现安全语义漂移。

当前边界：
- data-quality 的 Controller 已接入 PROJECT 范围，但还没有补 Controller/Mapper 层集成测试；当前主要通过编译和 datasource 支撑组件单测验证。
- `quality_check_execution` 的全局执行查询入口当前仍主要按规则路径访问；如果后续做全局执行历史或执行器运营台，需要继续增加项目过滤接口。
- `quality_anomaly_detail` 的样本载荷字段后续需要继续补脱敏、长度限制、导出审批和数据保留策略，避免项目内授权后仍暴露过多敏感原始值。
- 迁移脚本默认历史数据回填到 `project_id=1`；真实客户环境应结合项目台账做更精确归属治理。
- 当前仍使用逗号分隔授权项目 Header；高项目数量用户仍需要权限快照 ID、Redis 缓存或服务端二次查询方案。

验证：
- 已执行 `./mvnw -pl data-quality -am compile`，platform-common + data-quality 编译成功，完成时间 `2026-05-10T15:05:25+08:00`。
- 已执行 `./mvnw -pl datasource-management -am test`，1 个测试类、6 个测试用例全部通过，完成时间 `2026-05-10T15:06:14+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-10T15:06:23+08:00`。
- 已执行 `git diff --check`，未发现空白错误；仅有 Git 在 Windows 下的 LF/CRLF 提示。
- 已执行 Java 文件行数扫描，当前最高文件 `SyncPermissionNotificationServiceImpl` 438 行，`QualityExecutionReportSupport` 435 行，`DataQualityController` 350 行，仍低于 500 行约束。

下一步建议：
1. 为 data-quality 增加 PROJECT 范围单元测试或轻量 Controller 测试，覆盖授权项目为空、未授权 projectId、报告越权、异常聚合越权等场景。
2. 继续补 `datasource_readonly_sql_execution_audit` 的项目/空间快照字段，让受控 SQL 访问审计也能按项目查询。
3. 为 permission-admin 增加 `permission_project_membership` 管理 API，支持项目成员新增、禁用、查询、批量导入和成员变更审计。
4. 设计高项目数量下的授权快照方案，避免 `X-DataSmart-Authorized-Project-Ids` Header 过长。
5. 开始规划 data-quality 下一阶段商业能力：质量规则审批发布、规则版本历史、异常处理工单、质量评分模型、质量告警和清洗任务联动。

## 3.09 data-quality 测试闭环与 datasource 只读 SQL 审计项目快照（2026-05-10）
本轮承接 3.08 的项目级治理隔离能力，优先补齐两个容易被商业客户审计关注的缺口：第一，data-quality 的 PROJECT Header 解析和资源可见性规则不能只靠人工阅读代码确认，需要至少有单元测试保护；第二，datasource-management 的受控只读 SQL 审计如果只记录“谁执行了 SQL”，但不记录“被访问的数据源当时属于哪个项目/工作空间”，后续很难做项目级审计报表、合规导出、越权访问排查和成本归因。因此本轮把项目隔离从“业务对象列表”继续推进到“审计事实表”。

已完成：
- `data-quality/pom.xml` 增加 `spring-boot-starter-test`，让 data-quality 模块具备独立单元测试能力，后续可以继续补 Controller、Mapper、权限边界和异常聚合测试。
- 新增 `QualityProjectScopeSupportTest`，覆盖 PROJECT 范围 Header 解析、授权项目脏值过滤、授权项目命中、未授权 projectId 拒绝、空授权集合不降级、TENANT/非 PROJECT 范围不强制过滤、详情资源 projectId 校验等场景。
- `DataSourceReadOnlySqlExecutionAudit` 增加 `datasourceTenantId`、`datasourceProjectId`、`datasourceWorkspaceId`，并补充详细中文字段说明，明确 actor 所属租户与 datasource 所属租户可能不同，审计事实必须同时保留“操作者视角”和“被访问资产视角”。
- `DataSourceReadOnlySqlSupport.recordReadOnlySqlExecutionAudit(...)` 在写入审计记录时，从 `DataSourceConfig` 快照数据源归属，避免后续数据源迁移项目后历史审计口径被污染。
- `DataSourceReadOnlySqlSupport.pageReadOnlySqlExecutionAudits(...)` 增加 `DatasourceProjectVisibility` 参数，支持 `projectId/workspaceId` 查询条件，并在 PROJECT 数据范围下追加授权项目集合过滤。
- `DataSourceManagementController` 的只读 SQL 审计分页接口增加 `projectId/workspaceId` 查询参数，并消费 `X-DataSmart-Data-Scope-Level` 与 `X-DataSmart-Authorized-Project-Ids` Header，让审计列表与数据源列表共享同一套项目可见性语义。
- `DataSourceManagementService` 与 `DataSourceManagementServiceImpl` 同步扩展审计分页方法签名，使 Controller 只负责解析入口上下文，真正的查询过滤仍落在 service/support 层。
- `docker/mysql/init/init.sql` 为 `datasource_readonly_sql_execution_audit` 增加数据源租户、项目、工作空间快照列和项目/空间维度时间索引。
- 新增 `docker/mysql/migrations/20260510_datasource_readonly_sql_audit_project_scope.sql`，为已有数据库幂等补列、从 `datasource_config` 回填历史审计归属，并补充项目/空间维度查询索引。

商业化设计意义：
- 受控 SQL 审计从“按操作者追踪”升级为“按操作者 + 被访问资产归属追踪”，更接近真实企业的数据访问审计、项目成本核算和合规报表要求。
- 审计事实表采用归属快照，而不是查询时动态关联数据源当前归属，原因是数据源可能迁移项目、转交负责人或调整工作空间；历史审计应回答“当时访问了哪个项目的数据源”，不能被当前状态覆盖。
- PROJECT 范围下授权项目为空时返回空分页，不降级为租户级审计视图，避免用户在无项目授权时看到同租户全部 SQL 执行历史。
- data-quality 的项目可见性开始有独立测试保护，后续做规则版本、异常工单、质量评分和告警联动时，可以复用同一套安全语义。
- 审计查询增加项目/空间索引，避免未来项目级审计报表在租户内数据量增长后退化为大范围扫描。

当前边界：
- 当前 data-quality 测试仍是支撑组件单元测试，尚未覆盖 Controller + MyBatis 的真实 SQL 条件拼接；后续需要补轻量 Web 层测试或 Mapper 条件断言。
- `datasource_readonly_sql_execution_audit` 的历史回填依赖 `datasource_config` 当前归属；如果客户已经发生过项目迁移，真实上线时仍需要结合审计时间线或迁移记录做更精细修正。
- 当前审计接口支持项目/空间筛选，但还未提供审计导出、审批下载、脱敏展示和保留周期配置，这些是商业化合规交付的后续重点。
- 授权项目集合仍通过逗号 Header 传递，高项目数量用户会遇到 Header 长度和网关转发限制，后续应推进授权快照 ID 或服务端权限查询方案。
- 只读 SQL 审计已经有归属快照，但敏感 SQL 文本、参数、结果规模、失败原因的脱敏策略还需要继续增强，避免审计系统本身成为敏感信息泄露面。

验证：
- 已执行 `./mvnw -pl data-quality -am test`，data-quality 模块 1 个测试类、6 个测试用例全部通过，完成时间 `2026-05-10T19:10:26+08:00`。
- 已执行 `./mvnw -pl datasource-management -am compile`，platform-common + datasource-management 编译成功，完成时间 `2026-05-10T19:14:42+08:00`。
- 已执行 `./mvnw -pl datasource-management -am test`，datasource-management 模块 1 个测试类、6 个测试用例全部通过，完成时间 `2026-05-10T19:14:53+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-10T19:15:03+08:00`。
- 已执行 Java 文件行数扫描，当前最高文件 `DataSourceReadOnlySqlSupport.java` 440 行、`SyncPermissionNotificationServiceImpl.java` 438 行、`QualityExecutionReportSupport.java` 435 行，仍低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows 环境下常见的 LF/CRLF 提示。

下一步建议：
1. 优先为 permission-admin 增加 `permission_project_membership` 管理 API，补齐“项目成员从哪里来、谁能授权项目、授权如何审计”的产品闭环。
2. 为 data-quality 补 Controller/Mapper 层测试，验证项目过滤不仅存在于 support 方法，也真实落到接口行为和 SQL 条件。
3. 设计授权快照 ID 方案：由 gateway/permission-admin 生成 `X-DataSmart-Authorization-Snapshot-Id`，业务服务按快照查询授权项目集合，解决 Header 过长问题。
4. 为受控 SQL 审计继续补导出审批、字段脱敏、保留周期、异常访问告警和按项目/用户/数据源聚合报表。
5. 开始把项目隔离能力横向推广到 task-management 的任务队列、人工介入、执行历史和管理员运维接口，避免治理链路只在 datasource/data-quality 两个模块内闭环。

## 3.10 permission-admin 项目成员管理闭环（2026-05-10）
本轮从“业务模块消费授权项目集合”回到权限源头，补齐 `permission_project_membership` 的管理控制面。前几轮 datasource-management、data-quality、data-sync 已经开始根据 PROJECT 数据范围收敛查询，但如果项目成员授权只能靠初始化 SQL 或人工改库维护，产品就还不是一个可运营、可审计、可商用的权限系统。因此本轮新增项目成员授权 API、服务层校验、成员变更审计、网关资源语义和数据库策略迁移，让项目授权具备管理后台所需的基础闭环。

已完成：
- 新增 `PermissionProjectMembershipController`，提供项目成员授权分页、详情、新增/幂等更新、批量导入、更新、启用、禁用接口。
- 新增 `PermissionProjectMembershipService` 与 `PermissionProjectMembershipServiceImpl`，独立承载项目成员授权业务逻辑，避免继续膨胀 `PermissionOperationsServiceImpl`。
- 新增项目成员管理 DTO：`ProjectMembershipQueryCriteria`、`ProjectMembershipCreateRequest`、`ProjectMembershipUpdateRequest`、`ProjectMembershipBatchUpsertRequest`、`ProjectMembershipStateChangeRequest`、`ProjectMembershipMutationResult`。
- 新增 `PermissionProjectMembershipAuditSupport`，统一写入成员授权 before/after 审计快照，覆盖新增、幂等更新、批量导入、更新、启用、禁用等动作。
- 服务层实现角色边界：平台管理员可跨租户管理；租户管理员只能管理本租户；项目负责人只能管理自己拥有 OWNER 授权的项目，且不能授予或调整 OWNER 角色；运营和审计只读。
- 新增批量导入上限，单次最多 200 条，避免同步接口形成长事务和审计表瞬时暴涨。
- 更新 `PermissionResourceType` 增加 `PROJECT_MEMBERSHIP`，更新 `PermissionAction` 增加 `IMPORT/ENABLE/DISABLE`，为按钮级权限和高风险动作审批预留语义。
- 更新 gateway `route-metadata`，将项目成员管理从泛化的 `SYSTEM_SETTING` 拆为 `PROJECT_MEMBERSHIP`，并把批量导入、启用、禁用分别映射为 `IMPORT/ENABLE/DISABLE`。
- 更新 `docker/mysql/init/permission-admin.sql`，补充项目成员管理相关 route policy 种子数据。
- 新增 `docker/mysql/migrations/20260510_permission_project_membership_admin_api.sql`，为已有数据库补充项目成员管理 API 的权限策略事实。
- 顺手修正两个超过 500 行的支撑类：`QualityExecutionReportSupport` 由 519 行压到 497 行，`DataSourceReadOnlySqlSupport` 由 511 行压到 499 行；新 `PermissionProjectMembershipServiceImpl` 控制在 489 行。

商业化设计意义：
- PROJECT 数据范围从“可消费”升级为“可维护、可审计、可排障”：管理员可以解释某个 actor 为什么能看到某些项目，也可以禁用授权而不是物理删除证据。
- 幂等 upsert 适合真实后台重试、组织同步、Excel 导入重跑和审批流补偿，不会因为请求重试产生重复成员。
- 项目负责人可管理项目成员，但不能授予 OWNER，避免项目内自扩权；更高风险的 OWNER 授权仍保留给租户管理员或平台管理员。
- 批量导入、启用、禁用具备独立 action 语义，后续可以独立挂审批、限流、告警和审计报表。
- 项目成员管理被拆成独立 Controller/Service/Audit Support，降低 permission-admin 大类耦合，也符合单文件行数控制要求。

当前边界：
- 当前还没有为 permission-admin 增加单元测试依赖和成员管理测试；本轮完成编译验证，后续应补 service 级角色边界测试。
- 成员管理当前是同步写入，适合 200 条以内批量导入；更大规模组织同步应升级为异步导入任务并提供失败明细文件。
- 当前成员表尚未包含有效期、审批单号、授权人、授权继承链和外部组织组 ID；这些是后续商业化 IAM 能力的自然扩展点。
- 当前权限判定仍实时读取成员表或本地缓存事实；高项目数量用户仍需要授权快照 ID 或 Redis 服务端缓存方案。
- 项目基础资料本身还没有独立 project-service，因此成员 API 暂时只校验授权边界，不校验项目是否存在或是否已归档。

验证：
- 已执行 `./mvnw -pl permission-admin -am compile`，platform-common + permission-admin 编译成功，完成时间 `2026-05-10T19:26:06+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，最终完成时间 `2026-05-10T19:29:14+08:00`。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows 环境下常见 LF/CRLF 提示。
- 已执行全项目 Java 文件行数扫描，当前最高文件 499 行，所有 Java 文件均控制在 500 行以内。

下一步建议：
1. 为 permission-admin 增加 `spring-boot-starter-test`，补项目成员管理 service 单元测试，覆盖平台管理员、租户管理员、项目负责人、运营、审计、越租户、越项目、OWNER 授权限制等场景。
2. 设计 `authorization snapshot id`：由 permission-admin/gateway 生成授权快照，业务服务按快照 ID 查询项目集合，解决 Header 超长和高项目数量问题。
3. 为项目成员授权增加有效期、审批单号、授权人、外部组织组 ID 和失效扫描任务，支撑真实 IAM/IdP 同步。
4. 将 PROJECT 隔离推广到 task-management 的任务队列、人工介入、执行历史和管理员运维接口。
5. 规划 project-service 或 tenant-project 台账，补项目基础资料、项目状态、项目归档、项目负责人变更和项目级配额。

## 3.11 permission-admin 项目成员授权测试收口（2026-05-13）
本轮不是继续扩展新的 Java 业务面，而是对 3.10 刚落地的 PROJECT 授权源头做必要测试收口。原因是 datasource-management、data-sync、data-quality 后续都会消费 permission-admin 物化出来的授权项目集合；如果项目成员管理的租户边界、项目负责人边界或 OWNER 自扩权规则没有测试保护，后续进入 AI/Agent 阶段后再回头修权限源头会更危险。因此本轮只补最小必要测试，不继续扩展成员有效期、审批单号、组织同步等 P2/P3 能力。

已完成：
- `permission-admin/pom.xml` 增加 `spring-boot-starter-test`，仅作用于测试范围，不改变运行时依赖。
- 新增 `PermissionProjectMembershipServiceImplTest`，采用 JUnit 5 + Mockito 纯单元测试，不启动 Spring 容器、不连接 MySQL/Nacos。
- 覆盖租户管理员本租户授权成功并写审计。
- 覆盖租户管理员越租户授权被拒绝。
- 覆盖项目负责人可维护自己 OWNER 项目的非 OWNER 成员。
- 覆盖项目负责人即使在自己负责项目内也不能授予 OWNER，避免项目内自扩权。
- 覆盖运营人员可只读查询项目成员授权，但不能变更成员。
- 覆盖项目负责人不能读取非自己负责项目的成员详情。
- 覆盖批量导入超过 200 条会被拒绝，避免同步接口成为长事务入口。

设计意义：
- 这组测试把 PROJECT 授权源头的核心安全语义固定下来，后续 AI Agent 调用数据源、质量规则、同步任务时，可以复用这条权限链路。
- 测试选择纯单元测试而非集成测试，是为了低成本进入 CI，并避免为了验证业务规则启动完整中间件。
- 这是一轮 Java Core 收口，不代表继续无止境深挖 permission-admin；成员有效期、审批单号、组织同步、授权快照 ID 等能力先进入后续规划，不在本轮继续实现。

验证：
- 已执行 `./mvnw -pl permission-admin -am test`，1 个测试类、7 个测试用例全部通过，完成时间 `2026-05-13T21:57:20+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 9 个模块编译成功，完成时间 `2026-05-13T21:57:37+08:00`。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows 环境下常见 LF/CRLF 提示。
- 已执行全项目 Java 文件行数扫描，当前最高文件 499 行，所有 Java 文件均控制在 500 行以内。

下一步建议：
1. Java Core 暂时不继续扩展 P2/P3 管理能力，除非它直接阻塞 AI/Agent 主链路。
2. 下一阶段优先启动 AI 模型服务抽象：LLM Provider、Embedding Provider、Rerank Provider、模型配置与调用契约。
3. 随后启动类 OpenClaw 智能网关与 Agent Runtime 骨架，把 Java 任务、数据源、质量规则作为工具能力接入。
4. Java 侧仅保留必要支撑项：授权快照 ID、Agent 服务账号权限、任务回调协议、工具调用审计。

## 3.12 agent-runtime 模型路由控制面骨架（2026-05-13）
本轮正式从 Java Core 局部收口切入 AI/Agent 主线，新增 `agent-runtime` 微服务模块。该模块第一版不直接跑大模型，也不绑定旧的 Qwen2 基线，而是先建立“模型调用契约 + 工作负载路由 + 可替换 Provider 抽象 + gateway/permission-admin 入口语义”。这样后续接入 Python AI 服务、vLLM、SGLang、Qwen3.5/DeepSeek/Mistral 等模型时，不需要把业务模块再次推翻。

已完成：
- 父工程 `pom.xml` 增加 `agent-runtime` 模块，Maven Reactor 从 9 个模块扩展为 10 个模块。
- 新增 `agent-runtime/pom.xml`，依赖 `platform-common`、Spring Web、Validation、Nacos Discovery，保持与现有 Java 微服务一致的启动和注册方式。
- 新增 `AgentRuntimeApplication`，作为 Java 侧 AI 控制面启动类。
- 新增模型能力抽象：`ModelCapability`，区分 `CHAT/CODE/MULTIMODAL/EMBEDDING/RERANK`。
- 新增模型 Provider 类型抽象：`ModelProviderType`，支持 `DRY_RUN/OPENAI_COMPATIBLE/VLLM/SGLANG/PYTHON_AGENT_SERVICE`。
- 新增模型工作负载类型：`ModelWorkloadType`，覆盖 Agent 推理、治理问答、代码生成、Embedding、Rerank、多模态理解。
- 新增 `AgentRuntimeProperties`，通过 `datasmart.agent-runtime.model-routes` 配置工作负载到 Provider/模型/能力/超时的路由表。
- 新增模型调用 DTO：`ModelMessage`、`ModelChatRequest`、`ModelChatResponse`、`ModelRouteView`。
- 新增 `ModelRouteRegistry`，集中解析和展示模型路由，避免后续 Agent 编排器、RAG 服务、Controller 各自读取配置。
- 新增 `ModelGatewayService`，实现第一版 dry-run 模型调用，明确标记尚未接入真实模型 Provider。
- 新增 `ModelGatewayController`，提供 `/agent-runtime/models/routes` 和 `/agent-runtime/models/chat`，并兼容外部 `/api/agent/models/**`。
- 更新 gateway 路由，新增 `/api/agent/** -> lb://agent-runtime`，并配置 AI Runtime 的 route metadata。
- 更新 `docker/mysql/init/permission-admin.sql` 和新增 `docker/mysql/migrations/20260513_agent_runtime_route_policy.sql`，把 `/api/agent/models/routes` 与 `/api/agent/models/chat` 纳入 `AI_RUNTIME` 权限策略。

设计意义：
- 这是 AI/Agent 主线的第一块“稳定接口地基”，不是 Java 局部功能继续膨胀。
- 模型选择从代码硬编码变为工作负载路由配置，避免绑定某个模型家族或推理框架。
- 当前配置使用 `Qwen3.5-placeholder` 等新一代模型占位名，同时保留 DeepSeek/Mistral/vLLM/SGLang/Python 服务的替换空间。
- `DRY_RUN` 响应明确告诉调用方“模型路由已命中，但真实 Provider 尚未接入”，避免误把骨架当成真实推理能力。
- gateway 与 permission-admin 同步接入，保证未来 AI 入口不会绕过既有 RBAC、审计和服务发现链路。

当前边界：
- 当前还没有真实模型 Provider，`/api/agent/models/chat` 只返回 dry-run 响应。
- 当前还没有 Agent 会话、工作区、工具调用、记忆、任务编排和流式输出。
- 当前没有 Python AI 服务、vLLM/SGLang 部署配置，也没有模型密钥/endpoint 的生产级 Secret 管理。
- 当前没有把 Java 工具能力接入 Agent，例如数据源元数据、质量规则生成、同步任务编排、任务中心回调。

验证：
- 已执行 `./mvnw -pl agent-runtime -am compile`，platform-common + agent-runtime 编译成功，完成时间 `2026-05-13T22:02:57+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-13T22:03:55+08:00`。
- 已执行 Java 文件行数扫描，当前最高文件 499 行，所有 Java 文件仍低于 500 行。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 继续在 `agent-runtime` 内新增 Agent 会话与工作区骨架：AgentSession、Workspace、ToolBinding、RunState。
2. 设计 Java 工具注册契约，把 datasource metadata、data-quality rule、task-management task 作为 Agent 可调用工具暴露。
3. 设计真实 Provider 适配层：OpenAI-compatible/vLLM HTTP Client、Python Agent Service Client、超时/重试/错误映射。
4. 设计 Agent 调用审计，把模型路由、工作负载、actor、tenant、project、traceId 写入审计或事件。
5. 再决定是否启动 Python AI 服务目录，承接真实模型推理、RAG、GraphRAG 和工具执行插件。

## 3.13 agent-runtime 会话、工作空间、工具绑定与运行状态骨架（2026-05-13）

本阶段继续沿 AI/Agent 主线推进，不回到单个 Java 业务模块的局部深挖。3.12 已经完成模型路由控制面，但“模型路由”只能回答“某类工作负载调用哪个模型”；真正的类 OpenClaw 智能体产品还必须回答“Agent 在哪个租户/项目/工作空间内工作、能调用哪些工具、一次运行处于什么状态、如何取消和审计”。因此本轮新增 Agent 会话控制面，先固化上下文、工具和运行状态契约，为后续 Python Runtime、LangGraph/OpenClaw 编排、工具注册和审计事件打地基。

已完成：
- 新增 `AgentSessionState`、`AgentRunState`、`AgentToolType`、`AgentToolBindingStatus`、`WorkspaceIsolationLevel`，明确会话、运行、工具绑定和工作空间隔离语义。
- 新增 Agent 会话 DTO：`CreateAgentSessionRequest`、`BindAgentToolRequest`、`StartAgentRunRequest`、`AgentSessionView`、`AgentWorkspaceView`、`AgentToolBindingView`、`AgentRunView`。
- 新增 `AgentSessionMemoryStore`，作为第一阶段内存仓储，用于跑通 API 契约和状态流转；代码注释中明确说明它不是最终商业化持久化方案。
- 新增内部运行时记录：`AgentSessionRecord`、`AgentRunRecord`、`AgentToolBindingRecord`，避免 Controller DTO 与内部状态机直接耦合。
- 新增 `AgentSessionService`，支持创建会话、列表查询、详情查询、追加工具绑定、发起 Agent Run、取消 Agent Run。
- 新增 `AgentSessionController`，暴露 `/agent-runtime/sessions/**` 与 `/api/agent/sessions/**`，覆盖会话创建、查询、工具绑定、运行创建和运行取消。
- `AgentRuntimeProperties` 增加 `max-tool-bindings-per-session`、`max-runs-per-session`、`max-active-runs-per-session`、`session-ttl-hours`，为工具数量、运行数量、并发运行和会话生命周期预留配置治理入口。
- `agent-runtime/application.yml` 增加会话控制面配置说明，强调当前单会话同一时间只允许一个未完成运行，避免同一工作空间并发调用工具导致竞态。
- gateway `route-metadata` 增加 Agent 会话、工具绑定、运行创建、运行取消语义，全部归类为 `AI_RUNTIME`。
- `permission-admin.sql` 和 `20260513_agent_runtime_route_policy.sql` 补充 Agent 会话相关权限策略，让普通用户、项目负责人、运营人员、审计员、租户管理员在 AI Runtime 中有清晰的入口权限边界。

设计意义：
- Agent Runtime 从“模型调用入口”升级为“智能体运行控制面”，开始具备会话上下文、工作空间隔离、工具边界和运行状态这四类核心概念。
- 工作空间 key 按租户、项目、工作空间、会话生成，后续可作为 Redis、MinIO、Chroma、Neo4j 子图或临时文件目录的命名空间前缀。
- 工具绑定先抽象为业务能力类型，而不是直接写死 Java 类或 HTTP URL，便于后续把 datasource metadata、data-quality rule、task-management task 等能力注册为 Agent 工具。
- 运行状态先覆盖 CREATED、PLANNING、WAITING_MODEL、TOOL_CALLING、WAITING_HUMAN、SUCCEEDED、FAILED、CANCELLED，后续可与 LangGraph/OpenClaw 节点状态、WebSocket/SSE 进度推送和审计事件对齐。
- 当前使用内存仓储是有意控制范围：先稳定领域契约，避免在 Agent 状态模型尚未定型时过早设计数据库表并频繁迁移。

当前边界：
- 会话和运行记录仍在内存中，服务重启会丢失，多实例之间不共享，不能作为合规审计事实来源。
- 当前 Agent Run 是 dry-run 控制面状态，不会真实调用模型、Python Runtime 或下游工具。
- 当前没有实现 WebSocket/SSE 流式进度、Kafka 运行事件、工具调用审计、token 统计、成本核算和失败重试。
- 当前会话列表尚未分页，也未按状态、渠道、创建时间、工具类型、失败运行等维度筛选。
- 权限策略只覆盖入口级 RBAC；真正的项目数据范围、工具级二次校验和高风险审批仍需在后续工具适配器中实现。

验证：
- 已执行 `./mvnw -pl agent-runtime -am compile`，platform-common + agent-runtime 编译成功，完成时间 `2026-05-13T22:14:12+08:00`。
- 已执行 `./mvnw -pl agent-runtime -am test`，1 个测试类、4 个测试用例全部通过，完成时间 `2026-05-13T22:17:00+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-13T22:17:38+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，新增文件最高 `AgentSessionService.java` 344 行，低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 继续补 Agent 工具注册契约：定义工具元数据、入参 schema、风险等级、是否只读、是否需要审批、下游服务映射。
2. 设计工具适配器第一批能力：datasource metadata 只读查询、data-quality 规则建议、task-management 任务创建或查询。
3. 设计 Agent 运行事件和审计模型，把 sessionId、runId、toolBindingId、modelRoute、tenantId、projectId、traceId 写入审计链路。
4. 决定会话持久化方案：MySQL 保存长期事实，Redis 保存在线上下文，Kafka/EventStore 保存运行事件。
5. 随后启动 Python AI 服务目录或 Provider 适配层，承接真实模型推理、RAG/GraphRAG 和工具插件执行。

## 3.14 agent-runtime 工具注册契约与工具目录（2026-05-13）

本阶段继续推进 AI/Agent 主线，从“会话能绑定工具”升级为“平台能声明哪些工具可被 Agent 发现、选择和治理”。上一轮 `BindAgentToolRequest` 已经能把工具绑定到会话，但如果没有统一工具目录，后续很容易演变成模型或前端随意拼 toolCode、随意填写下游服务和端点，这不符合商用数据治理产品的安全、审计和可运维要求。因此本轮新增配置驱动的 Agent 工具注册契约，先把工具元数据、风险等级、审批要求、执行模式、输入 schema、目标服务映射固定下来，再进入真实工具适配器实现。

已完成：
- 新增 `AgentToolRiskLevel`，覆盖 `LOW/MEDIUM/HIGH/CRITICAL`，用于区分只读元数据、建议生成、任务创建、导出或高风险执行等不同风险层级。
- 新增 `AgentToolExecutionMode`，覆盖 `SYNC/ASYNC_TASK/DRAFT_ONLY/APPROVAL_REQUIRED`，用于表达工具是同步调用、异步任务、仅生成草稿还是必须审批后执行。
- `AgentRuntimeProperties` 增加 `toolRegistry` 配置结构，并新增 `ToolDefinitionProperties`、`ToolInputFieldProperties`，支持工具编码、工具类型、下游服务、端点模板、是否只读、风险等级、审批要求、幂等性、超时、重试、允许动作和输入字段 schema。
- 新增工具目录视图 DTO：`AgentToolDefinitionView` 与 `AgentToolInputFieldView`。
- 新增 `AgentToolRegistryService`，提供工具列表、工具详情和工具启用判断，支持按工具类型、风险等级和启用状态过滤。
- 新增 `AgentToolRegistryController`，暴露 `/agent-runtime/tools/**` 与 `/api/agent/tools/**` 只读工具目录接口。
- `agent-runtime/application.yml` 配置第一批工具目录：`datasource.metadata.read`、`quality.rule.suggest`、`task.create.draft`。
- gateway `route-metadata` 增加 `/api/agent/tools/**`，归类为 `AI_RUNTIME + VIEW`。
- 修正 gateway `route-metadata` 中上一轮 Agent 段落缩进偏深的问题，避免 gateway 启动时无法正确绑定 AI Runtime 元数据。
- `permission-admin.sql` 与 `20260513_agent_runtime_route_policy.sql` 补充 Agent 工具目录只读权限策略，覆盖普通用户、项目负责人、运营人员、审计员和租户管理员。
- 新增 `AgentToolRegistryServiceTest`，覆盖启用工具过滤、工具类型/风险过滤、输入 schema 展示和未知工具拒绝。

设计意义：
- 工具目录让 Agent 能力从“会话里随手填 toolCode”升级为“平台可治理能力目录”，后续 Agent 编排器只能在受控工具集合内规划。
- 工具风险等级和执行模式为审批流、人工确认、按钮权限、审计报表和事故排查预留统一语义。
- 输入 schema 让前端、Agent 编排器和审计系统能理解工具需要哪些参数，例如 datasourceId、projectId、businessGoal，而不是把所有上下文塞进自然语言 prompt。
- 第一批工具覆盖数据源元数据、质量规则建议和任务草稿创建，分别对应只读查询、建议生成和高风险写入草稿，为后续真实适配器提供清晰切入点。
- 配置驱动而非数据库驱动是阶段性选择：当前工具集合少、契约还在演进，先用配置保证可审查、可版本化，后续可迁移到 permission-admin 或独立 tool-registry 管理表。

当前边界：
- 工具目录当前只提供元数据和查询接口，不执行真实下游调用。
- 会话绑定工具时尚未强制校验 toolCode 必须存在于工具目录；这是为了保留研发期临时工具空间，进入真实执行阶段应逐步收紧。
- 输入 schema 目前是轻量结构，不是完整 JSON Schema；复杂对象、数组、oneOf/anyOf 后续再升级。
- 工具权限当前仍是入口级 `AI_RUNTIME + VIEW`；真正执行工具时还需要结合工具类型、风险等级、项目数据范围、审批和下游服务二次校验。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、8 个测试用例全部通过，完成时间 `2026-05-13T22:24:16+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-13T22:24:28+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，新增/改动文件最高 `AgentSessionService.java` 344 行，`AgentRuntimeProperties.java` 288 行，低于 500 行约束。
- 已执行全项目 Java 文件行数扫描，全项目最高仍为既有 499 行文件，未引入新的超限文件。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 将会话工具绑定与工具目录打通：绑定时可选择严格模式，要求 toolCode 必须来自启用工具目录，并自动继承风险、只读、审批和目标服务信息。
2. 设计第一批工具适配器：先做 `datasource.metadata.read` 的只读适配器，再做 `quality.rule.suggest` 的规则建议草稿，最后做 `task.create.draft` 的审批型草稿创建。
3. 设计 Agent 工具调用审计事件：记录 sessionId、runId、toolCode、riskLevel、requiresApproval、tenantId、projectId、actorId、traceId、输入摘要和执行结果。
4. 引入审批/人工确认契约，保证 HIGH/CRITICAL 工具不能被模型直接自动执行。
5. 评估是否启动 Python AI 服务目录：用于真实模型推理、RAG/GraphRAG、工具选择提示生成和后续 LangGraph/OpenClaw 编排。

## 3.15 agent-runtime 会话工具绑定收紧与目录元数据继承（2026-05-13）

本阶段承接 3.14 的工具目录，把“会话绑定工具”从请求体直写升级为“工具目录优先、严格模式默认开启”。这一步非常关键：如果 Agent 会话允许前端或上游编排器随意传 `toolType/targetService/readOnly/allowedActions`，就可能伪造工具风险、伪造下游服务或绕过审批要求。商业化 Agent 不能让模型或调用方自己声明权限边界，必须由平台工具目录提供权威元数据。

已完成：
- `AgentRuntimeProperties` 新增 `strictToolRegistryBinding`，默认 `true`，并在 `agent-runtime/application.yml` 中显式配置。
- `AgentSessionService` 注入 `AgentToolRegistryService`，创建会话和追加工具绑定时会解析工具目录。
- 严格模式下，绑定工具必须来自启用的工具目录；未注册或禁用工具会被拒绝。
- 非严格模式下仍会优先继承工具目录元数据，只有目录不存在时才回退到请求体字段，便于本地研发临时实验。
- `AgentToolBindingRecord` 与 `AgentToolBindingView` 增加 `targetEndpoint/riskLevel/executionMode/requiresApproval/idempotent`，让绑定结果直接暴露工具治理语义。
- `AgentToolRegistryService` 新增 `findTool(...)` 与 `requireEnabledTool(...)`，区分“可选查找”和“执行/绑定前强制启用校验”。
- `AgentSessionServiceTest` 增加严格模式测试：未注册工具会被拒绝；请求体伪造的 `toolType/targetService/readOnly/allowedActions` 不会覆盖工具目录中的权威元数据。

设计意义：
- 会话绑定从“用户说这个工具是什么”变为“平台目录确认这个工具是什么”，降低工具伪造和权限绕过风险。
- 工具绑定视图开始携带风险等级、执行模式、审批要求和幂等性，后续 Agent Run 生成工具调用计划时可以直接判断哪些工具需要人工确认。
- 严格模式默认开启，符合生产安全基线；同时保留关闭开关，避免早期研发阶段完全阻塞临时工具验证。
- `requireEnabledTool(...)` 为下一阶段真实工具适配器提供统一入口，后续执行工具前也应先通过该方法确认工具仍处于启用状态。

当前边界：
- 工具绑定只校验工具目录启用状态，尚未校验操作者是否真的拥有该工具的业务执行权限。
- 绑定结果已经暴露风险和审批元数据，但 Agent Run 还没有按这些字段自动进入 `WAITING_HUMAN` 或审批流。
- 仍未执行真实下游工具；本阶段只收紧绑定契约。
- 未对 targetResourceId 做项目级可见性校验；后续 datasource/data-quality/task-management 工具适配器必须做二次校验。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、10 个测试用例全部通过，完成时间 `2026-05-13T22:30:58+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-13T22:31:12+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，最高 `AgentSessionService.java` 395 行，仍低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 开始设计 `AgentToolExecutionAudit` 或运行事件模型，先不执行真实工具，也要把工具绑定、风险、审批要求和 runId 关联起来。
2. 将 `Agent Run` 与工具风险打通：如果绑定工具包含 `requiresApproval=true` 或 `riskLevel=HIGH/CRITICAL`，运行计划应进入人工确认或审批语义。
3. 实现第一个真实只读工具适配器：`datasource.metadata.read`，从 datasource-management 读取元数据前做项目范围和工具权限校验。
4. 设计工具执行客户端抽象，避免每个工具适配器都手写 HTTP 调用、超时、重试、错误映射和审计。
5. 评估是否需要启动 Python AI 服务目录；Java 控制面已经具备模型路由、会话、工具目录、严格绑定，下一步可以开始承接真实模型侧编排。

## 3.16 agent-runtime 工具执行审计计划骨架（2026-05-13）

本阶段继续在真实工具执行前补齐审计证据链。上一轮已经把工具绑定收紧到工具目录，但如果 Agent Run 创建后没有任何工具计划记录，后续真实执行时仍然很难回答“这次 Run 原本准备调用哪些工具、哪些需要审批、风险等级是什么、traceId 是什么”。因此本轮新增工具执行审计计划：每次 Agent Run 创建后，会根据当前会话绑定工具生成一组工具审计记录，低风险工具进入 `PLANNED`，高风险或需要审批的工具进入 `WAITING_APPROVAL`。

已完成：
- 新增 `AgentToolExecutionState`，覆盖 `PLANNED/WAITING_APPROVAL/EXECUTING/SUCCEEDED/FAILED/SKIPPED/CANCELLED`。
- 新增 `AgentToolExecutionAuditView`，对外展示工具审计记录，包括 sessionId、runId、bindingId、toolCode、风险等级、审批要求、租户/项目、actorId、traceId、状态和说明。
- 新增 `AgentToolExecutionAuditRecord` 与 `AgentToolExecutionAuditMemoryStore`，作为第一阶段内存审计仓储。
- 新增 `AgentToolExecutionAuditService`，在 Agent Run 创建时根据会话工具绑定生成工具计划审计记录。
- `AgentSessionService.startRun(...)` 增加 traceId 传递，并在 run 创建后调用工具审计服务生成计划记录。
- 新增 `AgentToolExecutionAuditController`，暴露 `/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions` 与 `/api/agent/sessions/{sessionId}/runs/{runId}/tool-executions` 查询接口。
- `AgentSessionServiceTest` 增加工具审计计划测试，验证低风险 `datasource.metadata.read` 进入 `PLANNED`，高风险 `task.create` 进入 `WAITING_APPROVAL`，并保留 traceId。

设计意义：
- 工具调用不再是未来某个适配器的黑盒行为，而是从 Agent Run 创建阶段就有可查询的计划证据。
- 高风险/审批型工具在真实执行前已经进入 `WAITING_APPROVAL` 语义，为后续审批流、人工确认和前端风险提示打基础。
- 审计记录关联 sessionId、runId、bindingId、toolCode、tenantId、projectId、actorId、traceId，形成后续“用户请求 -> Agent Run -> 工具计划 -> 工具执行 -> 下游业务结果”的证据链。
- 当前使用内存仓储是阶段性选择；真实商业化环境应迁移到 MySQL 审计表、Kafka 事件流或专门审计服务。

当前边界：
- 审计记录当前只在 run 创建时生成计划状态，不会推进到 EXECUTING/SUCCEEDED/FAILED。
- 工具执行审计仍是内存态，服务重启会丢失，不能作为最终合规审计事实。
- 还没有审批单、人工确认接口、工具执行客户端和下游回写。
- 高风险判断当前基于 `requiresApproval=true` 或 `riskLevel=HIGH/CRITICAL`，后续可以叠加租户策略、项目策略、数据敏感等级和操作时间窗口。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、11 个测试用例全部通过，完成时间 `2026-05-13T22:36:14+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-13T22:36:27+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，最高 `AgentSessionService.java` 397 行，仍低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 将 Agent Run 与工具风险进一步打通：如果存在 `WAITING_APPROVAL` 工具，run 初始状态应进入 `WAITING_HUMAN` 或返回明确审批提示。
2. 设计审批/人工确认 API，允许用户确认或拒绝某个高风险工具计划。
3. 实现第一个真实只读工具适配器 `datasource.metadata.read`，并在执行前使用工具审计记录推进状态。
4. 设计工具执行客户端抽象，统一 HTTP 调用、超时、重试、错误映射、traceId 透传和审计状态推进。
5. 当 Java 控制面继续稳定后，启动 Python AI 服务目录，承接真实模型推理、RAG/GraphRAG 和工具选择编排。

## 3.17 agent-runtime Run 风险感知与人工确认状态联动（2026-05-13）

本阶段承接 3.16 的工具执行审计计划，把“高风险工具审计记录进入 `WAITING_APPROVAL`”进一步提升为“Agent Run 本身自动进入 `WAITING_HUMAN`”。这一步解决的是商业化 Agent 的核心安全问题：即使前端或上游编排请求没有显式传入 `requireHumanApproval=true`，只要会话绑定了高风险工具或审批型工具，平台控制面也必须主动拦截，不能让模型直接推进到真实工具执行。

已完成：
- `AgentToolExecutionAuditService` 新增公开业务规则 `requiresApprovalBeforeExecution(...)`，统一判断工具是否需要人工确认/审批。
- 该规则覆盖 `requiresApproval=true`、`riskLevel=HIGH`、`riskLevel=CRITICAL` 三类情况，作为当前阶段的安全基线。
- `AgentSessionService.startRun(...)` 在创建 Run 前检查会话工具绑定，只要存在高风险或审批型工具，Run 初始状态自动进入 `WAITING_HUMAN`。
- `AgentRunView.requireHumanApproval` 现在会反映“本次运行最终是否需要人工确认”，不仅仅依赖请求体字段。
- `AgentSessionService` 增加中文说明注释，解释为什么不能只信任请求体，以及高风险工具为什么必须由控制面主动拦截。
- `nextActions` 增加高风险工具确认提示，帮助前端和后续编排器知道下一步应先创建审批/确认节点。
- `message` 区分状态来源：来自工具风险自动拦截，或来自请求显式要求人工确认。
- `AgentSessionServiceTest` 增加断言：低风险只读工具保持 `PLANNING`，高风险 `task.create` 会让 Run 进入 `WAITING_HUMAN`，同时仍生成工具执行审计计划。

设计意义：
- Agent Runtime 不再只是记录工具风险，而是能用风险元数据影响运行状态机，形成“工具目录 -> 会话绑定 -> Run 状态 -> 工具审计”的闭环。
- 风险判断集中在审计服务中，避免会话服务和工具执行服务各自维护一套不一致的审批规则。
- 当前做法为后续审批 API、审批单、租户安全策略、数据敏感等级、操作时间窗口和前端风险确认弹窗预留了稳定入口。
- 该阶段仍不执行真实工具，符合“先把控制面、安全边界和审计链路打稳，再接入真实工具适配器”的演进节奏。

当前边界：
- `WAITING_HUMAN` 当前只是 Run 状态语义，还没有审批确认/拒绝 API。
- 高风险判断仍基于工具目录静态元数据，尚未叠加租户策略、项目策略、敏感数据等级、用户角色和操作时间窗口。
- 工具审计计划仍是内存态，暂不能作为最终合规审计事实。
- Run 进入 `WAITING_HUMAN` 后还没有恢复到 `PLANNING/RUNNING` 的状态推进接口。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、11 个测试用例全部通过，完成时间 `2026-05-13T22:41:20+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-13T22:41:35+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，最高 `AgentSessionService.java` 376 行，低于 500 行约束。
- 已执行全项目 Java 文件行数扫描，当前最高 438 行，低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 继续补齐审批/人工确认 API：允许用户确认或拒绝某个 Run 下的高风险工具计划，并记录确认人、确认时间、确认意见和审批结果。
2. 设计工具执行客户端抽象：统一处理 HTTP 调用、服务发现、超时、重试、错误映射、traceId 透传、幂等键和审计状态推进。
3. 实现第一个真实只读工具适配器 `datasource.metadata.read`，用于验证“低风险工具可计划、可审计、可安全执行”的完整链路。
4. 在 Java 控制面完成第一条工具执行闭环后，启动 Python AI 服务目录，进入真实模型推理、RAG/GraphRAG 和 LangGraph/OpenClaw 编排阶段。
5. 不再无限扩展 Java 局部功能；后续 Java 侧只补齐 Agent 工具闭环必要接口，随后切换到 AI Runtime/Python Agent 主线。

## 3.18 agent-runtime 高风险工具人工确认/拒绝入口（2026-05-13）

本阶段在 3.17 的 `WAITING_HUMAN/WAITING_APPROVAL` 语义基础上，补齐第一版人工决策入口。目标不是立刻做完整审批流引擎，而是先让高风险工具计划具备可治理的状态推进：用户或具备权限的角色可以确认某个工具计划，也可以拒绝该工具计划，审计记录会留下决策人、决策说明和决策时间。

已完成：
- 新增 `AgentToolExecutionDecisionRequest`，承载人工决策人 `operatorId` 与决策说明 `comment`。
- `AgentToolExecutionAuditRecord` 增加审批字段：`approvalOperatorId`、`approvalComment`、`approvalTime`。
- `AgentToolExecutionAuditRecord.approve(...)` 将工具计划从 `WAITING_APPROVAL` 推回 `PLANNED`，表示审批门通过但还没有真实执行。
- `AgentToolExecutionAuditRecord.reject(...)` 将工具计划从 `WAITING_APPROVAL` 推进到 `SKIPPED`，表示用户在执行前主动拒绝，不应统计为工具执行失败。
- `AgentToolExecutionAuditMemoryStore` 增加 `findById(...)`，支持按审计 ID 定位单条工具计划。
- `AgentToolExecutionAuditService` 增加 `approve(...)` 与 `reject(...)`，并校验审计记录必须属于当前 session/run，且必须处于 `WAITING_APPROVAL`。
- `AgentToolExecutionAuditController` 新增两个 PATCH 路由：
  - `/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/approve`
  - `/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/reject`
- gateway `route-metadata` 增加 `/api/agent/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/approve|reject`，动作语义为 `APPROVE`。
- 新增迁移脚本 `20260513_agent_runtime_tool_approval_policy.sql`，为项目负责人、运营人员、租户管理员补充高风险工具计划确认/拒绝权限；审计员仍保持只读，普通用户暂不默认拥有审批权。
- `AgentSessionServiceTest` 增加人工确认/拒绝状态流转测试，覆盖 `WAITING_APPROVAL -> PLANNED` 与 `WAITING_APPROVAL -> SKIPPED`。

设计意义：
- `WAITING_APPROVAL` 不再只是展示状态，而是拥有明确的人机协同决策入口。
- 确认后回到 `PLANNED`，保留真实执行前的二次校验空间，避免“审批通过 = 立即执行”的危险耦合。
- 拒绝使用 `SKIPPED` 而不是 `FAILED`，可以区分“工具执行失败”和“用户/策略决定不执行”，后续报表和事故复盘会更准确。
- 路由权限和数据库迁移同步补齐，避免新 API 绕过 gateway/permission-admin 的平台治理边界。

当前边界：
- 决策人当前来自请求体 `operatorId`，生产环境应优先从网关认证上下文解析，避免调用方伪造。
- 尚未持久化审批记录，内存态重启会丢失；后续应迁移到 MySQL 审计表或审批服务。
- Run 级别仍停留在 `WAITING_HUMAN`，工具级审批通过后尚未自动判断整个 Run 是否可以恢复到 `PLANNING`。
- 尚未支持多级审批、会签、超时自动拒绝、审批撤销、审批委托和租户策略表达式。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、12 个测试用例全部通过，完成时间 `2026-05-13T22:46:13+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-13T22:46:26+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，最高 `AgentSessionService.java` 376 行，低于 500 行约束。
- 已执行全项目 Java 文件行数扫描，当前最高 438 行，低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 设计 Run 级恢复规则：当某个 Run 下所有 `WAITING_APPROVAL` 工具都被确认或拒绝后，判断 Run 是否可从 `WAITING_HUMAN` 回到 `PLANNING`，或因关键工具被拒绝而终止。
2. 设计工具执行客户端抽象，统一处理服务发现、HTTP 调用、超时、重试、幂等键、错误映射、traceId 透传和审计状态推进。
3. 实现第一个真实只读工具 `datasource.metadata.read`，先跑通低风险工具的 `PLANNED -> EXECUTING -> SUCCEEDED/FAILED` 闭环。
4. 完成第一条工具闭环后，切换到 Python AI Runtime/Agent 目录，避免 Java 控制面继续局部内卷。

## 3.19 agent-runtime 工具人工决策后的 Run 级状态恢复（2026-05-14）

本阶段补齐 3.18 之后的状态机闭环：工具级审批状态变化后，Run 级状态也必须同步变化。否则会出现“工具已经确认/拒绝，但 Run 仍然卡在 `WAITING_HUMAN`”的产品体验和状态事实不一致问题。当前策略采用保守商业化基线：所有待审批工具都确认后，Run 从 `WAITING_HUMAN` 恢复到 `PLANNING`；只要有高风险工具被拒绝，Run 进入新的终态 `REJECTED`。

已完成：
- `AgentRunState` 新增 `REJECTED` 终态，用于表达“运行被人工拒绝”，区别于系统错误型 `FAILED`。
- `AgentRunState.isTerminal()` 将 `REJECTED` 纳入终态，保证被拒绝的 Run 不再阻塞同一会话发起新 Run。
- `AgentRunRecord` 新增 `resumePlanningAfterApproval(...)`，用于所有审批门通过后恢复到 `PLANNING`。
- `AgentRunRecord` 新增 `rejectAfterToolDecision(...)`，用于高风险工具被拒绝后将 Run 终止为 `REJECTED`。
- `AgentSessionService` 新增 `approveToolExecution(...)` 与 `rejectToolExecution(...)`，把工具级人工决策包进会话锁内处理，避免并发审批导致 Run 状态不一致。
- `AgentSessionService` 新增 `reconcileRunAfterToolDecision(...)`，集中实现工具状态到 Run 状态的回收逻辑。
- `AgentToolExecutionAuditController` 的 approve/reject 路由改为调用 `AgentSessionService`，确保每次工具决策都会联动 Run 级状态。
- `AgentSessionServiceTest` 增加断言：确认高风险工具后 Run 恢复 `PLANNING`；拒绝高风险工具后 Run 进入 `REJECTED`。

设计意义：
- Run 状态和工具审计状态开始形成双层状态机协同，前端、审计、后续编排器看到的事实一致。
- `REJECTED` 避免把人工安全拦截误记为 `FAILED`，有利于后续运营报表区分“系统失败”和“风险治理成功”。
- 人工决策处理放入会话锁，先保证内存态下的并发一致性；迁移数据库后应使用乐观锁或事务保证相同语义。
- 当前不引入复杂“可选工具/关键工具”判断，优先用保守策略保护生产安全，后续再通过工具目录字段扩展精细化编排。

当前边界：
- 只要有审批型工具被拒绝，整个 Run 会进入 `REJECTED`；未来应支持关键工具、可选工具、替代工具和局部跳过策略。
- Run 恢复到 `PLANNING` 后仍不会真实执行工具；还需要工具执行客户端抽象和真实工具适配器。
- `AgentSessionService.java` 已增长到 450 行，仍低于 500 行，但下一轮继续加工具执行能力前应拆出 Run 状态协调器或工具决策协调器。
- 审批决策仍是内存态，尚未落 MySQL 审计表。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、12 个测试用例全部通过，完成时间 `2026-05-14T19:03:25+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-14T19:03:40+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，最高 `AgentSessionService.java` 450 行，低于 500 行约束但已接近上限。
- 已执行全项目 Java 文件行数扫描，当前最高 `AgentSessionService.java` 450 行，低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 先拆出 Agent Run 状态协调器，避免后续真实工具执行继续堆高 `AgentSessionService`。
2. 设计工具执行客户端抽象，统一服务发现、超时、重试、幂等键、错误映射、traceId 透传和审计状态推进。
3. 实现第一个低风险只读工具 `datasource.metadata.read`，验证 `PLANNED -> EXECUTING -> SUCCEEDED/FAILED` 工具执行闭环。
4. 完成 Java 控制面第一条工具执行闭环后，切换到 Python AI Runtime/Agent 目录，推进模型推理、RAG/GraphRAG 和 LangGraph/OpenClaw 编排。

## 3.20 agent-runtime Run 状态协调器拆分（2026-05-14）

本阶段是 3.19 后的结构治理，不新增业务能力，目标是控制文件复杂度和耦合度。上一阶段 `AgentSessionService.java` 增长到 450 行，虽然仍低于 500 行约束，但如果继续把真实工具执行、状态推进、审批回收都塞进同一个服务，很快会形成新的“大 Service”。因此本轮先拆出 `AgentRunStateCoordinator`，专门承载 Run 初始状态、审批风险判断、nextActions/message 生成和工具决策后的 Run 状态回收。

已完成：
- 新增 `AgentRunStateCoordinator`，放在 `agent-runtime/service/session` 包下，作为 Run 状态与工具审计状态之间的协调组件。
- 将 `hasApprovalRequiredTool(...)` 从 `AgentSessionService` 移入协调器，统一判断会话工具是否要求审批。
- 将 Run 初始状态选择逻辑移入 `initialState(...)`，明确 `explicitHumanApproval || toolApprovalRequired` 时进入 `WAITING_HUMAN`。
- 将 dry-run `nextActions` 构建逻辑移入 `buildDryRunNextActions(...)`，保留中文学习型注释。
- 将 Run 创建消息构建逻辑移入 `buildRunCreatedMessage(...)`。
- 将 3.19 新增的 `reconcileRunAfterToolDecision(...)` 移入 `AgentRunStateCoordinator.reconcileAfterToolDecision(...)`。
- `AgentSessionService` 改为注入并调用 `AgentRunStateCoordinator`，只保留会话入口编排、工具绑定、Run 创建/取消和人工决策入口。
- 更新 `AgentSessionServiceTest` 的手工构造逻辑，为单元测试注入 `AgentRunStateCoordinator`。

设计意义：
- 避免 `AgentSessionService` 继续膨胀成万能服务，符合用户要求的解耦与单文件 500 行内约束。
- Run 状态相关规则从入口服务中分离，后续接真实工具执行、模型编排回调、审批回调时有明确落点。
- 该拆分为下一步“工具执行客户端抽象”和“真实只读工具适配器”降低了重构风险。

当前边界：
- `AgentRunStateCoordinator` 仍调用内存态审计服务；后续迁移 MySQL 后需要配合事务/乐观锁保证状态一致。
- 目前尚未把工具执行状态推进抽象出来，`EXECUTING/SUCCEEDED/FAILED` 仍未被真实使用。
- 该阶段仅做结构优化，不改变外部 API 契约。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、12 个测试用例全部通过，完成时间 `2026-05-14T19:06:54+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-14T19:07:05+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，最高 `AgentSessionService.java` 375 行，较 3.19 的 450 行明显下降。
- 已执行全项目 Java 文件行数扫描，当前最高 438 行，低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 设计工具执行客户端抽象，先不直接写某个具体工具，避免每个适配器重复处理 HTTP、超时、重试、幂等和审计推进。
2. 在客户端抽象稳定后，实现 `datasource.metadata.read` 只读工具适配器，作为第一条真实工具执行闭环。
3. 工具闭环完成后，将 Java 控制面阶段收束，切换到 Python AI Runtime/Agent 目录，推进模型编排与 RAG/GraphRAG。

## 3.21 agent-runtime 工具执行框架与 datasource.metadata.read 适配器（2026-05-14）

本阶段把 Agent 工具从“可计划、可审批、可查看审计”推进到“可执行”的第一版闭环。实现重点不是一次性做完所有工具，而是先抽象出可复用的工具执行框架：统一校验工具计划状态、推进审计状态、选择适配器、处理成功/失败结果，然后落第一条低风险只读工具 `datasource.metadata.read`。

已完成：
- `AgentRuntimeProperties` 新增 `toolServiceBaseUrls`，用于按 targetService 配置下游服务基础地址，避免工具适配器硬编码部署地址。
- `agent-runtime/application.yml` 新增 `datasource-management: http://localhost:8082` 本地直连配置，并将 `datasource.metadata.read.target-endpoint` 修正为 `/datasources/{datasourceId}/metadata/discover`。
- 新增 `AgentToolAdapter`，作为所有 Agent 工具适配器的统一接口。
- 新增 `AgentToolExecutionContext`，承载 session、run、audit、variables 和 traceId。
- 新增 `AgentToolExecutionOutcome`，统一表达工具适配器成功/失败结果。
- 新增 `AgentToolExecutionResultView`，作为工具执行 API 的响应视图，包含最新审计状态和工具输出。
- `AgentToolExecutionAuditRecord` 增加 `targetResourceId`、`executionStartTime`、`executionFinishTime`、`outputSummary`、`errorCode`，并新增 `startExecution(...)`、`succeed(...)`、`fail(...)` 状态推进方法。
- `AgentToolExecutionAuditView` 同步暴露目标资源 ID、执行时间、输出摘要和错误码。
- `AgentToolExecutionAuditService` 新增 `startExecution(...)`、`succeedExecution(...)`、`failExecution(...)`，要求只有 `PLANNED` 工具计划才能进入执行。
- 新增 `AgentToolExecutionService`，统一完成 `PLANNED -> EXECUTING -> SUCCEEDED/FAILED` 审计推进和适配器调用。
- 新增 `DatasourceMetadataReadToolAdapter`，实现 `datasource.metadata.read` 到 datasource-management `POST /datasources/{id}/metadata/discover` 的真实 HTTP 适配。
- `AgentSessionService` 新增 `executeToolExecution(...)`，拒绝终态 Run 和 `WAITING_HUMAN` Run 执行工具，避免绕过审批。
- `AgentToolExecutionAuditController` 新增 `PATCH /{sessionId}/runs/{runId}/tool-executions/{auditId}/execute`。
- gateway `route-metadata` 增加 Agent 工具执行入口，动作语义为 `EXECUTE`。
- 新增迁移脚本 `20260514_agent_runtime_tool_execute_policy.sql`，为普通用户、项目负责人、运营人员、租户管理员补充已规划工具执行权限。
- `AgentSessionServiceTest` 新增工具执行测试，通过测试适配器验证 `PLANNED -> EXECUTING -> SUCCEEDED` 框架语义。

设计意义：
- Agent Runtime 第一次具备真实工具执行框架，而不是只停留在目录、计划和审批状态。
- 工具适配器接口避免未来每个工具各自写 HTTP、审计和状态推进，降低重复代码和状态不一致风险。
- `datasource.metadata.read` 作为低风险只读工具，适合验证第一条真实链路，并为后续质量规则建议、同步任务规划和 RAG 上下文提供元数据输入。
- 执行 API 明确只执行 `PLANNED` 工具，`WAITING_APPROVAL` 工具必须先走人工确认，延续高风险工具安全边界。
- 下游服务地址通过配置注入，为后续从本地直连切换到 gateway、Nacos、OpenFeign 或服务网格保留替换空间。

当前边界：
- 本轮单元测试使用测试适配器模拟 datasource 元数据读取成功，不依赖本机 datasource-management 服务是否启动；生产链路需要启动 datasource-management 并保证目标数据源可用。
- `DatasourceMetadataReadToolAdapter` 当前同步调用 HTTP，适合短耗时只读工具；长耗时工具应改为异步任务或 Kafka 命令事件。
- 工具执行结果只在内存审计记录中保存摘要，完整大结果后续应进入 MinIO、MySQL 审计表或专门结果存储。
- 适配器当前只处理 `datasource.metadata.read`，尚未实现 `quality.rule.suggest` 和 `task.create.draft`。
- 还没有把工具执行成功/失败反推到 Run 终态；当前 Run 仍作为控制面容器继续保持可推进状态。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、13 个测试用例全部通过，完成时间 `2026-05-14T19:14:59+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-14T19:15:13+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，最高 `AgentSessionService.java` 399 行，低于 500 行约束。
- 已执行全项目 Java 文件行数扫描，当前生产代码最高 438 行，低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 为工具执行补充失败路径单元测试，覆盖无适配器、仍待审批、重复执行和适配器异常。
2. 让 datasource-management 暴露更适合 Agent 的轻量元数据快照接口，避免每次 Agent 读取都触发重型 discover。
3. 在 Java 控制面完成失败路径测试后，开始收束 Java 侧工作，准备切换到 Python AI Runtime/Agent 目录。
4. Python 侧优先搭建 Agent 编排骨架：模型路由客户端、工具选择 prompt、工具调用事件、RAG/GraphRAG 上下文注入。

## 3.22 agent-runtime 工具执行失败路径保护（2026-05-14）

本阶段补齐 3.21 工具执行框架的失败路径测试与一处状态保护。工具执行链路一旦接入真实下游服务，失败路径比成功路径更重要：没有适配器、工具仍待审批、重复执行、适配器异常都必须形成可解释状态，而不能让审计记录卡在半路。

已完成：
- 调整 `AgentToolExecutionService`，将适配器查找纳入执行 try/catch 范围。
- 当工具没有可用适配器时，审计记录会进入 `FAILED`，错误码为 `TOOL_ADAPTER_EXCEPTION`，避免停留在 `EXECUTING`。
- `AgentSessionServiceTest` 新增失败保护测试，覆盖：
  - Run 仍处于 `WAITING_HUMAN` 时拒绝执行工具，避免绕过审批；
  - 已经 `SUCCEEDED` 的工具计划不能重复执行，避免重复调用下游；
  - 没有适配器的 `quality.rule.suggest` 工具会进入 `FAILED`。

设计意义：
- 工具执行框架开始具备生产级失败可解释性，不会只覆盖 happy path。
- “审批未完成不能执行”和“已执行工具不能重复执行”这两条规则，是后续接真实工具前必须固化的安全基线。
- 没有适配器时写入 `FAILED`，能让前端、审计和运维明确看到原因，而不是看到一个永远执行中的记录。

当前边界：
- 适配器异常当前统一归类为 `TOOL_ADAPTER_EXCEPTION`，后续可细分为超时、下游 4xx、下游 5xx、权限失败、资源不存在等错误码。
- 重复执行当前直接拒绝；后续对于幂等工具可以设计“返回上次成功结果”的幂等读取语义。
- 失败后尚未反推 Run 级 `FAILED` 或部分失败状态；当前仍以工具审计状态为主。

验证：
- 已执行 `./mvnw -pl agent-runtime -am test`，2 个测试类、14 个测试用例全部通过，完成时间 `2026-05-14T19:17:17+08:00`。
- 已执行 `./mvnw compile`，Maven Reactor 全部 10 个模块编译成功，完成时间 `2026-05-14T19:17:27+08:00`。
- 已执行 agent-runtime Java 文件行数扫描，最高生产文件 `AgentSessionService.java` 399 行，低于 500 行约束。
- 已执行全项目 Java 文件行数扫描，当前测试文件最高 442 行、生产代码最高 438 行，均低于 500 行约束。
- 已执行 `git diff --check`，未发现需要修复的空白错误；仅保留 Windows LF/CRLF 提示。

下一步建议：
1. 收束 Java 控制面当前阶段：除非要补轻量元数据快照接口，否则不再继续扩展大量 Java Agent 功能。
2. 开始 Python AI Runtime/Agent 目录搭建，优先做模型路由客户端、工具选择计划、工具调用事件和可替换模型抽象。
3. Java 侧后续只作为控制面和工具网关继续演进，避免再次只围绕一个微服务无限优化。

## 3.23 Python AI Runtime/Agent 初始骨架（2026-05-14）

本阶段按照项目 skill 中“不要只围绕 Java 局部微服务无限优化”的要求，开始搭建独立的 Python AI Runtime。这个目录不是 demo 脚本，而是后续模型推理、Agent 编排、工具计划、RAG/GraphRAG、智能网关和 OpenClaw/LangGraph 风格状态流转的承载层。当前先保持核心逻辑不依赖外部包，确保在未安装 FastAPI、LangGraph、vLLM 等依赖时也能编译和单元测试。

已完成：
- 新增 `python-ai-runtime/README.md`，说明 Python 智能运行时与 Java 控制面的边界、当前能力、商业化设计原因和下一步演进方向。
- 新增 `python-ai-runtime/pyproject.toml`，定义 `datasmart-ai-runtime` 包，Python 版本要求为 `>=3.10`，并把 FastAPI/Uvicorn 放入可选 `api` 依赖，避免基础测试强依赖 Web 框架。
- 新增 `datasmart_ai_runtime.domain.contracts`，定义 `WorkloadType`、`ProviderType`、`ToolRiskLevel`、`ToolExecutionMode`、`ModelRoute`、`ToolDefinition`、`AgentRequest`、`ToolPlan`、`AgentPlan` 等核心领域契约。
- 新增 `default_model_routes()`，按主推理、治理问答、代码/Agent、Embedding、Rerank 拆分模型工作负载，不再沿用旧的 Qwen2 基线，而是使用 `Qwen3.5 / DeepSeek-V3.2 / Devstral 2 / 当前 Mistral` 等可替换新一代模型占位。
- 新增 `default_tool_registry()`，用 Python 侧影子契约描述 `datasource.metadata.read`、`quality.rule.suggest`、`task.create.draft` 三类工具，为后续从 Java `agent-runtime` 动态同步工具注册表预留接口。
- 新增 `ModelRouteRegistry`，按工作负载选择模型路由，并保留未来主备路由、灰度路由、成本优先路由和租户级路由策略扩展点。
- 新增 `ToolPlanner`，先用可解释、可测试的规则式规划生成工具计划，后续可替换为 LLM 规划器，但输出仍保持 `ToolPlan` 契约。
- 新增 `AgentOrchestrator`，以节点式状态流转串联 `receive_goal -> select_model_route -> build_context_placeholder -> plan_tools -> wait_human_approval/ready_for_control_plane_execution`。
- 新增 `api.py`，提供可选 FastAPI 入口 `create_app()` 和默认编排器构造函数 `build_default_orchestrator()`。
- 新增 `python-ai-runtime/tests/test_agent_orchestrator.py`，覆盖数据源元数据读取低风险计划、质量规则与任务创建审批计划、模型路由不使用 Qwen2 基线。

设计意义：
- 项目开始从 Java 控制面进入 AI Runtime 层，避免继续只在 `datasource-management`、`agent-runtime` 等局部模块里循环加功能。
- 模型路由按能力拆分，而不是把聊天、代码、Embedding、Rerank、多模态都绑定到一个模型，符合商业化平台后续成本、性能和效果优化需要。
- 工具计划与工具执行解耦：Python 层只生成计划和风险判断，真实执行仍交给 Java 控制面做权限、审批、审计、幂等和状态机。
- 当前规则式规划不是最终智能能力，但它为 LLM 规划器提供了安全基线和可回归测试，使后续接入大模型时不至于把审批、风险和工具契约交给 prompt 随意决定。
- `build_context_placeholder` 明确为 RAG/GraphRAG 预留节点，后续可接入元数据检索、权限事实检索、质量规则案例检索和知识图谱检索。

当前边界：
- 当前 Python Runtime 还不会真实调用模型，`ProviderType.DRY_RUN` 仅表示路由与编排骨架已存在。
- 当前工具注册表是 Python 侧静态默认值，后续应从 Java `agent-runtime` 工具注册表接口动态加载，避免双写配置漂移。
- 当前工具规划是关键词和变量驱动的规则式实现，后续需要引入 LLM 意图识别、结构化输出校验和策略约束。
- 当前 API 入口是可选 FastAPI 骨架，尚未接入认证、租户上下文、TraceId、限流、审计日志和 Kafka 事件。
- 当前没有实现 RAG/GraphRAG、向量库、Neo4j 查询、Embedding/Reranker Provider 和模型推理客户端。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，3 个测试用例全部通过。
- 本阶段未修改 Java 源码，因此未重新执行 Maven 编译；上一阶段 Java 全量编译已在 3.22 通过。

下一步建议：
1. 让 Python Runtime 从 Java `agent-runtime` 动态拉取工具注册表，减少 Python/Java 双写工具契约的风险。
2. 抽象模型 Provider 客户端，优先兼容 OpenAI-compatible、vLLM、SGLang 三类部署形态，并保留超时、重试、熔断和成本统计字段。
3. 建立 RAG/GraphRAG 上下文构建器，先支持数据源元数据、权限事实、质量规则案例三类上下文。
4. 将规则式 `ToolPlanner` 升级为“规则安全基线 + LLM 结构化规划器”的组合模式，高风险工具仍必须由规则层强制审批。
5. 规划智能网关层：后续需要支持多 Agent 路由、会话状态、工具事件流、WebSocket 推送、租户限流和审计回放。

## 3.24 Python AI Runtime 工具目录客户端与模型 Provider 抽象（2026-05-14）

本阶段承接 3.23 的 Python AI Runtime 骨架，补齐两条进入真实集成前必须存在的抽象：第一，Python 层不能长期维护静态工具清单，需要能从 Java `agent-runtime` 动态读取工具目录；第二，Agent 编排器不能直接写死某个模型 SDK 或 HTTP 调用细节，需要通过 Provider 抽象兼容 OpenAI-compatible、vLLM、SGLang、本地 dry-run 和未来企业内部模型网关。

已完成：
- `ToolDefinition` 扩展 `displayName/targetEndpoint/readOnly/requiresApproval/idempotent/timeoutMs/maxRetries/allowedActions` 等字段，用于承接 Java 工具目录的治理元数据。
- `ToolPlanner` 审批判断纳入 `tool.requiresApproval`，避免只看风险等级和执行模式而漏掉 Java 控制面显式要求审批的工具。
- 新增 `JavaAgentToolRegistryClient`，默认读取 Java `/agent-runtime/tools?enabledOnly=true`，解析 `PlatformApiResponse<List<AgentToolDefinitionView>>` 并映射为 Python `ToolDefinition`。
- 新增 `ToolRegistryClientError`，让后续 API 层可区分“工具目录不可用”和“Agent 规划失败”。
- 新增 `ModelMessage`、`ModelInvocationRequest`、`ModelInvocationResult`，作为模型调用统一契约，保留 route、messages、temperature、maxOutputTokens、traceId、latency、usage 和 errorCode 等字段。
- 新增 `ModelProvider` 协议、`DryRunModelProvider`、`OpenAICompatibleModelProvider`、`ModelProviderRegistry`，把模型调用细节从 Agent 编排逻辑中拆出去。
- `DryRunModelProvider` 用于本地开发和单元测试，让控制面、路由、工具契约、上下文和审批逻辑先稳定，再替换成真实模型调用。
- `OpenAICompatibleModelProvider` 实现最小 Chat Completions 请求形状，为 vLLM、SGLang 或企业内部 OpenAI-compatible 模型网关预留接入点。
- 新增 `test_tool_registry_client.py`，验证 Java 工具目录响应能正确映射为 Python 工具契约，并验证非成功响应会抛出清晰错误。
- 新增 `test_model_provider.py`，验证 dry-run 模型 Provider 能返回可解释结果，并继续使用新一代模型占位路由。
- `.gitignore` 新增 Python 缓存规则，忽略 `__pycache__`、`*.pyc`、`.pytest_cache`、`.mypy_cache`、`.ruff_cache`、`.venv`。

设计意义：
- 工具目录开始向“Java 控制面单一事实源”靠齐，后续 Python 不需要长期双写 `datasource.metadata.read`、`quality.rule.suggest`、`task.create.draft` 等工具定义。
- 工具风险、审批、只读、幂等、超时和重试字段都被纳入 Python 契约，为后续 LLM 规划器提供受控边界，而不是让模型自己猜工具能不能执行。
- 模型 Provider 与模型路由分离后，项目可以按租户、项目、任务类型、成本和 SLA 切换不同推理后端，不会把业务编排绑死在某个 SDK。
- Dry-run Provider 是商业化工程里的安全过渡层：先验证状态机、审计、权限、工具计划、上下文构建，再接真实模型，降低“模型先接上但平台治理没准备好”的风险。
- `.gitignore` 补齐 Python 缓存规则，避免 Python 子项目引入后污染 Git 状态。

当前边界：
- `JavaAgentToolRegistryClient` 当前使用标准库 `urlopen`，尚未接入 gateway 认证、租户上下文、服务发现、重试、熔断和本地缓存。
- 工具目录客户端目前只实现列表读取，尚未实现按 toolCode 拉取详情、按风险等级过滤、按工具类型过滤和缓存失效策略。
- `OpenAICompatibleModelProvider` 当前只实现最小非流式调用，尚未处理 API Key、流式输出、错误码映射、超时分级、重试、成本统计和敏感信息脱敏日志。
- `AgentOrchestrator` 还没有真实调用 `ModelProviderRegistry`，当前模型 Provider 是可插拔基础设施，下一步需要接入规划/意图识别链路。
- 当前仍未实现 RAG/GraphRAG 上下文构建器，`build_context_placeholder` 仍是预留节点。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，6 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 218 行，低于 500 行约束。
- 本阶段仅修改 Python Runtime、`.gitignore` 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 将 `JavaAgentToolRegistryClient` 接入 `build_default_orchestrator()` 的可选路径：本地开发默认静态工具，集成环境优先拉取 Java 工具目录，失败时可降级到缓存或默认清单。
2. 在 `AgentOrchestrator` 中引入模型 Provider 的 dry-run 调用节点，先让状态流转具备“模型意图识别/规划提示”位置，再逐步替换为真实模型。
3. 增加 RAG/GraphRAG 上下文构建器接口，先定义元数据、权限事实、质量规则案例三类 Context Block。
4. 为 OpenAI-compatible Provider 增加 API Key、错误码映射、超时/重试和 token usage 统计，这是进入真实模型调用前的最低生产要求。
5. 规划智能网关与 Python Runtime 的交互方式：同步 HTTP 适合短请求，长规划、长工具链和多 Agent 协作应考虑 Kafka 事件或 WebSocket 事件流。

## 3.25 Python AI Runtime 远程工具目录加载与模型意图节点（2026-05-14）

本阶段把 3.24 中“已有但尚未进入默认启动链路”的两个能力接入编排主路径：默认编排器现在可以按配置从 Java `agent-runtime` 拉取工具目录，并在失败时按策略回退到本地默认工具；`AgentOrchestrator` 状态流转新增 `invoke_model_intent` 节点，通过 `ModelProviderRegistry` 调用 dry-run 模型 Provider，先为后续真实 LLM 意图识别、规划提示和结构化输出预留稳定位置。

已完成：
- `AgentPlan` 新增 `modelIntentSummary` 语义字段，用于返回模型意图识别或 dry-run 模型节点的摘要结果。
- `AgentOrchestrator` 构造函数新增可选 `ModelProviderRegistry` 注入，避免编排器直接依赖某个具体模型 Provider。
- `AgentOrchestrator.plan(...)` 在 `select_model_route` 后新增 `invoke_model_intent` 状态节点，默认通过 dry-run Provider 生成可解释的模型意图摘要。
- `AgentOrchestrator` 新增 `_invoke_model_intent(...)`，把系统提示、用户目标、模型路由、traceId 组合成 `ModelInvocationRequest`，并对 Provider 异常做可解释降级。
- `api.py` 新增 `load_tool_registry(...)`，支持三种工具目录加载路径：显式注入客户端、通过 `DATASMART_AGENT_RUNTIME_BASE_URL` 或参数读取 Java 工具目录、回退到本地默认工具清单。
- `build_default_orchestrator(...)` 新增远程工具目录参数：`tool_registry_base_url`、`prefer_remote_tools`、`allow_remote_fallback`、`trace_id`、`tool_registry_client`。
- 新增 `test_api_bootstrap.py`，验证默认编排器可以使用注入的远程工具目录，并验证远程工具目录失败时可回退到默认工具。
- 更新 `test_agent_orchestrator.py`，验证 Agent 状态链路包含 `invoke_model_intent`，并验证 dry-run 模型摘要会返回到 `AgentPlan`。

设计意义：
- Python Runtime 开始真正具备“本地开发可独立运行、集成环境可优先使用 Java 控制面工具目录”的双模式启动能力。
- 工具目录失败不再只有“系统启动失败”一种选择，后续可以按环境策略区分：开发环境允许回退，生产环境可配置为严格失败。
- 模型调用节点进入 Agent 状态流转后，未来接入真实 LLM、结构化意图识别、Prompt 模板、RAG 上下文和多 Agent 协同时都有明确落点。
- dry-run 模型节点让当前系统继续保持可测试、无外部依赖，同时让前端/Java 控制面能看到“模型意图摘要”这一未来交互字段。
- Provider 异常先做可解释降级，是为了保留规则式工具规划的安全基线；未来生产环境可按租户 SLA 决定是否允许模型失败后继续规则降级。

当前边界：
- 远程工具目录加载当前没有缓存；如果 Java `agent-runtime` 短暂不可用，只能回退默认工具或失败，尚未支持“使用上一版成功工具快照”。
- `DATASMART_AGENT_RUNTIME_BASE_URL` 只是最小环境变量入口，尚未接入 Nacos、gateway 服务发现、认证 Token、租户上下文和请求签名。
- `invoke_model_intent` 当前使用 dry-run Provider，不会真正识别复杂意图，也不会影响工具规划结果。
- `modelIntentSummary` 当前只是摘要字符串，后续应升级为结构化意图对象，例如治理域、风险标签、候选工具、缺失参数、置信度。
- Provider 异常当前被降级为摘要文本，尚未纳入告警、指标、trace 或审计事件。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，8 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 219 行，低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 设计 `ContextBlock` 与 `ContextBuilder` 抽象，先把元数据、权限事实、质量规则案例作为 RAG/GraphRAG 的三类上下文块。
2. 将 `modelIntentSummary` 从字符串升级为结构化 `IntentAnalysis`，包括治理域、候选工具、风险标签、缺失参数和置信度。
3. 为远程工具目录增加本地缓存和严格模式：生产环境工具目录失败应可配置为阻断，开发环境允许回退默认工具。
4. 为 OpenAI-compatible Provider 增加 API Key、错误码映射、超时/重试和 usage 统计，准备进入真实模型调用。
5. 继续保持 Java 控制面收束，不在 Java 模块里无止境扩展；AI Runtime 下一阶段优先补上下文构建和智能网关交互契约。

## 3.26 Python AI Runtime RAG/GraphRAG 上下文构建契约（2026-05-14）

本阶段开始补齐 RAG/GraphRAG 的前置能力。当前没有直接接 Chroma、Neo4j 或 Java 元数据接口，而是先定义统一的上下文块契约和上下文构建器接口，并把 Agent 状态流中的 `build_context_placeholder` 升级为真实 `build_context` 节点。这样后续无论上下文来自向量召回、知识图谱、Java 控制面 API、MySQL 快照还是人工输入，都可以汇聚成同一种 `ContextBlock` 交给模型意图节点和工具规划器。

已完成：
- 新增 `domain/context.py`，定义 `ContextSourceType` 与 `ContextBlock`。
- `ContextSourceType` 先覆盖 `USER_OBJECTIVE`、`DATASOURCE_METADATA`、`PERMISSION_FACT`、`QUALITY_RULE_CASE`、`SYSTEM_POLICY`。
- `ContextBlock` 包含 `sourceType/title/content/relevanceScore/metadata`，用于承载上下文来源、展示标题、模型可读内容、相关性分数和机器可追溯元数据。
- 新增 `ContextBuilder` 协议，作为后续 GraphRAG、Java 控制面上下文、混合上下文构建器的统一接口。
- 新增 `DefaultContextBuilder`，当前基于 `AgentRequest` 规则式生成用户目标、租户/项目/操作者权限边界、数据源元数据检索需求、质量规则案例检索需求。
- `AgentPlan` 新增 `contextBlocks`，让 API 调用方、前端调试面板和后续 Java 控制面能看到本次 Agent 规划使用了哪些上下文。
- `AgentOrchestrator` 新增可选 `context_builder` 注入，并在 `select_model_route` 后执行 `build_context`。
- `invoke_model_intent` 现在会把前 5 个上下文块摘要拼入模型消息，使 dry-run/未来真实模型都能看到上下文依据。
- `build_context_placeholder` 状态节点被替换为真实 `build_context`。
- 新增 `test_context_builder.py`，验证默认上下文构建器能生成用户目标、权限事实、数据源元数据、质量规则案例上下文。
- 更新 `test_agent_orchestrator.py`，验证 AgentPlan 中包含数据源元数据上下文块。

设计意义：
- RAG/GraphRAG 不再只是文档里的规划项，而是进入 Agent 编排数据结构和状态流。
- 上下文构建被拆为独立服务，避免未来把 Chroma、Neo4j、Java HTTP、权限查询和质量案例查询全部塞进 `AgentOrchestrator`。
- 权限事实被作为默认上下文块固定下来，提醒后续模型和工具规划始终围绕租户、项目、操作者边界工作。
- 数据源元数据与质量规则案例先以“检索需求”形式存在，真实接入后可以自然替换为元数据快照、字段画像、历史规则和异常样本。
- `ContextBlock.metadata` 为后续审计、Trace、上下文来源回放和敏感信息脱敏预留机器可读依据。

当前边界：
- `DefaultContextBuilder` 仍是规则式占位实现，不会访问真实 Chroma、Neo4j、MySQL 或 Java 微服务。
- 上下文块尚未做 token 预算、排序、去重、截断、脱敏和敏感级别标注。
- `ContextSourceType.SYSTEM_POLICY` 暂未生成实际上下文，后续应承载租户策略、工具审批策略、模型使用策略和数据出境策略。
- `ToolPlanner` 当前还没有直接消费 `contextBlocks`，仍主要依赖请求变量和关键词规则。
- 模型意图节点只是把上下文摘要放入消息，尚未输出结构化 `IntentAnalysis`。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，10 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 222 行，低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 将 `modelIntentSummary` 升级为结构化 `IntentAnalysis`，让治理域、候选工具、风险标签、缺失参数和置信度可被程序读取。
2. 为 `ContextBlock` 增加敏感级别、来源 ID、过期时间和 token 估算字段，支持生产级上下文治理。
3. 增加 `HybridContextBuilder` 的雏形，把默认规则上下文与未来 Java/GraphRAG 检索结果组合、排序、截断。
4. 让 `ToolPlanner` 开始读取 `contextBlocks`，例如根据数据源上下文和质量案例上下文生成更准确的工具参数。
5. 设计智能网关事件契约，将上下文构建、模型意图识别、工具规划、审批等待等节点作为可推送事件暴露给前端。

## 3.27 Python AI Runtime 结构化 IntentAnalysis（2026-05-14）

本阶段把 3.26 中的人读型 `modelIntentSummary` 升级为可程序消费的结构化意图分析。字符串摘要仍然保留，用于兼容 API 展示和调试面板；新增的 `IntentAnalysis` 则面向工具规划、审批策略、前端缺参提示、审计回放和未来 LLM 结构化输出校验。

已完成：
- 新增 `domain/intent.py`，定义 `GovernanceDomain`、`IntentRiskTag`、`IntentAnalysis`。
- `GovernanceDomain` 先覆盖 `DATASOURCE`、`DATA_QUALITY`、`DATA_SYNC`、`TASK_MANAGEMENT`、`PERMISSION_ADMIN`、`KNOWLEDGE_QA`、`GENERAL_GOVERNANCE`。
- `IntentRiskTag` 先覆盖 `READ_ONLY`、`DRAFT_GENERATION`、`STATE_CHANGE`、`APPROVAL_REQUIRED`、`SENSITIVE_DATA`、`CROSS_SCOPE`。
- `IntentAnalysis` 包含 `summary/governanceDomains/candidateTools/riskTags/missingParameters/confidence/reasoning`。
- 新增 `IntentAnalyzer` 协议，为未来规则式、轻量分类模型、LLM 结构化输出、LLM + 规则校验组合预留统一接口。
- 新增 `RuleBasedIntentAnalyzer`，根据用户目标、结构化变量和上下文块识别治理域、候选工具、风险标签、缺失参数和置信度。
- `AgentPlan` 新增 `intentAnalysis` 字段，保留 `modelIntentSummary` 作为兼容摘要。
- `AgentOrchestrator` 新增可选 `intent_analyzer` 注入，并在 `build_context` 后新增 `analyze_intent` 状态节点。
- `modelIntentSummary` 现在会合并结构化意图摘要与模型 dry-run 节点摘要；结构化结果则通过 `intentAnalysis` 返回。
- 新增 `test_intent_analyzer.py`，覆盖质量 + 同步任务 + 审批风险，以及质量规则缺少 `datasourceId` 的缺参场景。
- 更新 `test_agent_orchestrator.py`，验证 `analyze_intent` 状态节点和 `IntentAnalysis` 治理域输出。

设计意义：
- 意图分析从“只给人看的自然语言摘要”升级为“控制面、规划器、审批系统和前端都能读取的结构化事实”。
- 工具选择前就能识别 `STATE_CHANGE`、`APPROVAL_REQUIRED`、`CROSS_SCOPE` 等风险，为后续更细粒度的审批策略打基础。
- `missingParameters` 让前端和 Agent 会话可以主动提示用户补充关键参数，而不是等工具执行时报错。
- `confidence` 为后续低置信度转人工确认、提示澄清问题或禁用自动工具规划预留策略入口。
- 结构化意图分析器独立于编排器，避免未来把关键词规则、模型分类、prompt 输出解析全部堆进 `AgentOrchestrator`。

当前边界：
- 当前 `RuleBasedIntentAnalyzer` 仍是规则式实现，不能替代真实 LLM 对复杂语义、跨句指代和行业术语的理解。
- `candidateTools` 目前只是建议，不会直接驱动 `ToolPlanner`，下一步应让规划器结合 `IntentAnalysis` 与 `ContextBlock` 生成更精准计划。
- `SENSITIVE_DATA` 标签尚未由规则触发，后续需要结合数据分类分级、字段敏感标签和权限事实上下文。
- `confidence` 是启发式分数，不代表模型概率；生产环境需要把模型置信度、规则命中和历史反馈综合起来。
- `modelIntentSummary` 仍保留字符串兼容字段，后续 API 版本稳定后可逐步鼓励调用方使用 `intentAnalysis`。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，12 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 224 行，低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 让 `ToolPlanner` 消费 `IntentAnalysis` 与 `ContextBlock`，从“关键词规划”升级为“意图 + 上下文规划”。
2. 为 `ContextBlock` 增加敏感级别、来源 ID、过期时间和 token 估算字段，支撑生产级上下文治理。
3. 为 `RuleBasedIntentAnalyzer` 增加敏感数据、跨项目、导出、写 SQL 等高风险规则。
4. 为 OpenAI-compatible Provider 增加 API Key、错误码映射、超时/重试和 usage 统计，准备进入真实模型调用。
5. 设计智能网关事件契约，把 `build_context/analyze_intent/invoke_model_intent/plan_tools` 输出为可推送事件。

## 3.28 Python AI Runtime 意图 + 上下文工具规划（2026-05-14）

本阶段把工具规划从“只看自然语言关键词和请求变量”升级为“结构化意图 + 上下文块 + 关键词兜底”的组合策略。这样 Agent 可以优先使用 `IntentAnalysis` 中的候选工具和风险标签，再从 `ContextBlock` 中补齐 datasourceId、businessGoal 等参数，最后才退回关键词规则，整体更接近真实商业化 Agent 的决策链。

已完成：
- `ToolPlanner.plan(...)` 新增可选参数 `intent_analysis` 与 `context_blocks`，旧调用方式仍保持兼容。
- 工具规划优先读取 `intentAnalysis.candidateTools`，支持“用户没有明确说质量/规则，但意图分析已识别出质量规则建议”的场景。
- `ToolPlanner` 新增 `_resolve_datasource_id(...)`，可从请求变量或 `DATASOURCE_METADATA` 上下文块 metadata 中解析 datasourceId。
- `ToolPlanner` 新增 `_resolve_business_goal(...)`，可从请求变量或 `QUALITY_RULE_CASE` 上下文块 metadata 中解析 businessGoal。
- `task.create.draft` 计划 payload 新增 `intentRiskTags` 与 `missingParameters`，把意图风险和缺参信息传递给 Java 控制面或前端确认链路。
- `AgentOrchestrator` 在 `plan_tools` 节点调用规划器时传入 `intent_analysis` 与 `context_blocks`。
- 新增 `test_tool_planner.py`，覆盖：
  - 使用意图候选工具触发质量规则建议，即使自然语言没有命中质量关键词；
  - 从上下文块补齐 datasourceId 和 businessGoal；
  - 任务草案工具携带意图风险标签和缺失参数。

设计意义：
- 工具规划开始消费上游结构化意图，不再只依赖关键词命中，减少真实用户表达多样性导致的漏规划。
- 上下文块开始参与参数补齐，为后续 GraphRAG/Java 控制面检索结果真正影响工具调用打通路径。
- 当上下文中包含 datasourceId 时，规划器会先规划 `datasource.metadata.read`，再规划 `quality.rule.suggest`，符合“先确认元数据，再生成规则”的真实治理流程。
- 高风险任务草案会携带 `intentRiskTags` 和 `missingParameters`，后续审批页面可以解释为什么要审批、还缺哪些参数。
- 规则兜底仍保留，保证 LLM 或意图分析器不可用时系统仍能给出安全、可解释的基础计划。

当前边界：
- `ToolPlanner` 仍是规则式计划器，尚未让 LLM 直接生成结构化工具计划。
- 上下文只用于参数补齐和触发部分工具，尚未进行上下文排序、token 预算和敏感字段脱敏。
- 缺失参数目前被传入 task payload，但不会阻断工具计划；后续应根据工具 schema 决定阻断、澄清或生成草案。
- `candidateTools` 当前完全信任意图分析器输出是否存在于工具注册表；不存在的工具会被自然忽略，尚未生成诊断事件。
- 质量规则建议仍只是工具计划，未接真实 data-quality 规则生成能力。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，14 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 224 行，`tool_planner.py` 178 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 为 `ContextBlock` 增加敏感级别、来源 ID、过期时间和 token 估算字段，支撑生产级上下文治理。
2. 为 `RuleBasedIntentAnalyzer` 增加敏感数据、跨项目、导出、写 SQL 等高风险规则，并让这些风险影响工具规划。
3. 增加工具 schema 参数校验层，对缺失参数区分“必须澄清”“允许草案”“可由上下文补齐”。
4. 为 OpenAI-compatible Provider 增加 API Key、错误码映射、超时/重试和 usage 统计，准备进入真实模型调用。
5. 设计智能网关事件契约，把上下文构建、意图分析、模型调用、工具规划、审批等待作为前端可订阅事件。

## 3.29 Python AI Runtime 上下文治理元数据增强（2026-05-14）

本阶段把 `ContextBlock` 从“普通上下文文本块”增强为具备生产治理语义的上下文载体。RAG/GraphRAG 在企业产品里不能只负责把内容拼进 prompt，还必须支持敏感级别、来源追溯、过期失效和 token 预算，否则后续很难做脱敏、审计、缓存、截断和成本控制。

已完成：
- `domain/context.py` 新增 `ContextSensitivityLevel`，覆盖 `PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED`。
- `ContextBlock` 新增 `sensitivityLevel`，用于后续模型准入、脱敏、跨租户缓存和审计策略。
- `ContextBlock` 新增 `sourceId`，用于记录上下文来源 ID，例如用户目标、权限事实、数据源元数据检索需求、质量规则案例检索需求。
- `ContextBlock` 新增 `expiresAt`，使用 UTC aware datetime 表达上下文过期时间。
- `ContextBlock` 新增 `tokenEstimate`，用于后续 prompt 预算、上下文排序、截断和模型调用成本预估。
- `DefaultContextBuilder` 新增统一 `_block(...)` 构造方法，集中设置敏感级别、来源 ID、过期时间和 token 估算。
- `DefaultContextBuilder` 为不同上下文设置不同治理策略：
  - 用户目标：`INTERNAL`，TTL 30 分钟；
  - 权限事实：`CONFIDENTIAL`，TTL 5 分钟；
  - 数据源元数据检索需求：`CONFIDENTIAL`，TTL 15 分钟；
  - 质量规则案例检索需求：`INTERNAL`，TTL 60 分钟。
- `DefaultContextBuilder` 新增 `_expires_at(...)`，统一生成 UTC 过期时间，避免跨时区歧义。
- `DefaultContextBuilder` 新增 `_estimate_tokens(...)`，先用字符数启发式估算 token，后续可替换为模型专用 tokenizer。
- `test_context_builder.py` 新增治理元数据测试，验证上下文块包含 sourceId、expiresAt、tokenEstimate，并验证权限事实和数据源上下文为 `CONFIDENTIAL`。

设计意义：
- 上下文开始具备“可治理”能力，而不仅是模型提示词片段。
- 敏感级别为后续敏感字段脱敏、模型准入、租户隔离和审批策略提供入口。
- 来源 ID 让上下文可以被审计回放：未来排查一次 Agent 规划时，可以追溯它看过哪些权限事实、元数据快照和规则案例。
- 过期时间让权限事实、元数据快照、系统策略等具备时效边界，避免模型使用已经过期的上下文做决策。
- token 估算为后续 RAG/GraphRAG 排序、截断、成本控制和长上下文模型选择打基础。

当前边界：
- `tokenEstimate` 当前是字符数启发式估算，不是模型 tokenizer 的真实 token 数。
- `expiresAt` 当前只生成不消费，后续需要在 HybridContextBuilder 或上下文聚合层过滤过期块。
- `sensitivityLevel` 当前只标注不执行脱敏，后续需要结合数据分类分级和字段敏感标签。
- `sourceId` 当前是规则构造的可读 ID，未来真实检索源应使用数据库主键、图谱节点 ID、向量文档 ID 或 Java 审计 ID。
- 尚未实现上下文排序、去重、截断、缓存和跨租户隔离策略。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，15 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 224 行，`tool_planner.py` 178 行，`context_builder.py` 175 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 增加 `HybridContextBuilder` 雏形，按相关性、敏感级别、过期时间和 token 预算组合上下文块。
2. 为 `RuleBasedIntentAnalyzer` 增加敏感数据、跨项目、导出、写 SQL 等高风险规则，并让这些风险影响工具规划。
3. 增加工具 schema 参数校验层，对缺失参数区分“必须澄清”“允许草案”“可由上下文补齐”。
4. 为 OpenAI-compatible Provider 增加 API Key、错误码映射、超时/重试和 usage 统计。
5. 设计智能网关事件契约，把上下文构建、意图分析、模型调用、工具规划、审批等待作为前端可订阅事件。

## 3.30 Python AI Runtime HybridContextBuilder 上下文组合治理（2026-05-14）

本阶段在 3.29 的上下文治理元数据基础上，新增 `HybridContextBuilder`。它不负责亲自检索 Chroma、Neo4j 或 Java API，而是作为多个上下文来源的统一聚合层，集中处理过期过滤、敏感级别准入、sourceId 去重、相关性排序和 token 预算截断。

已完成：
- 新增 `services/hybrid_context_builder.py`。
- 新增 `ContextSelectionPolicy`，定义 `maxTokens`、`allowedSensitivityLevels`、`keepAtLeastOne`。
- 新增 `HybridContextBuilder`，支持注入多个 `ContextBuilder` 来源，默认包装 `DefaultContextBuilder`。
- `HybridContextBuilder.build(...)` 实现上下文治理流水线：
  - 从多个 builder 收集上下文块；
  - 过滤已过期上下文；
  - 默认排除 `RESTRICTED` 敏感级别；
  - 按 `sourceId` 或内容指纹去重；
  - 按相关性、敏感级别、token 成本排序；
  - 按 token 预算截断。
- 去重策略会保留相关性更高、token 成本更低的上下文块。
- 排序策略在相关性相同的情况下优先选择敏感级别更低、token 更小的上下文。
- 新增 `test_hybrid_context_builder.py`，覆盖：
  - 过期上下文过滤；
  - 默认排除 `RESTRICTED` 上下文；
  - sourceId 去重并保留更高相关性块；
  - token 预算截断；
  - 同相关性下低敏上下文优先。
- `services/__init__.py` 导出 `ContextSelectionPolicy` 与 `HybridContextBuilder`。

设计意义：
- RAG/GraphRAG 开始具备真正的上下文治理聚合层，而不是把多个检索结果直接拼接给模型。
- 多来源上下文可以独立演进：默认规则上下文、Java 控制面上下文、GraphRAG 上下文、向量检索上下文都可通过同一协议接入。
- 过期过滤避免模型使用过期权限事实、旧元数据快照或过期系统策略。
- 敏感级别准入为模型安全边界打基础，默认不让 `RESTRICTED` 内容进入模型上下文。
- token 预算控制为后续真实模型调用的成本、延迟和上下文长度控制提供基础能力。

当前边界：
- `HybridContextBuilder` 当前尚未默认接入 `AgentOrchestrator`，下一步可替换默认上下文构建器或通过配置启用。
- 当前只做过滤、去重、排序、截断，不做真实脱敏。
- token 预算仍依赖 `ContextBlock.tokenEstimate` 的粗估值。
- 还没有接入真实 Java 控制面上下文、GraphRAG 上下文或向量检索上下文。
- 被过滤或截断的上下文尚未产生日志/事件，后续应进入智能网关事件流，便于前端和审计查看。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，19 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 224 行，`tool_planner.py` 178 行，`context_builder.py` 175 行，`hybrid_context_builder.py` 172 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 将 `HybridContextBuilder` 接入 `build_default_orchestrator()`，让默认 Agent 编排开始使用上下文组合治理。
2. 为 `RuleBasedIntentAnalyzer` 增加敏感数据、跨项目、导出、写 SQL 等高风险规则，并让这些风险影响工具规划。
3. 增加工具 schema 参数校验层，对缺失参数区分“必须澄清”“允许草案”“可由上下文补齐”。
4. 为 OpenAI-compatible Provider 增加 API Key、错误码映射、超时/重试和 usage 统计。
5. 设计智能网关事件契约，把上下文构建、意图分析、模型调用、工具规划、审批等待作为前端可订阅事件。

## 3.31 Python AI Runtime 默认接入 HybridContextBuilder（2026-05-14）

本阶段把 3.30 新增的 `HybridContextBuilder` 接入默认 Agent 编排启动链路。现在通过 `build_default_orchestrator()` 创建的编排器，会默认使用上下文组合治理，而不是直接使用单一 `DefaultContextBuilder`。这意味着普通本地开发和未来 API 服务入口都将统一经过过期过滤、敏感级别准入、去重、排序和 token 预算控制。

已完成：
- `api.py` 引入 `ContextSensitivityLevel`、`ContextSelectionPolicy`、`HybridContextBuilder`。
- 新增 `build_context_selection_policy(...)`，集中构造上下文选择策略。
- `build_context_selection_policy(...)` 支持显式 `context_max_tokens` 参数。
- `build_context_selection_policy(...)` 支持读取环境变量 `DATASMART_AI_CONTEXT_MAX_TOKENS`。
- `build_context_selection_policy(...)` 支持显式 `allowed_context_sensitivity_levels`，为高合规环境收紧上下文准入预留入口。
- `build_default_orchestrator(...)` 新增 `context_max_tokens` 与 `allowed_context_sensitivity_levels` 参数。
- `build_default_orchestrator(...)` 默认注入 `HybridContextBuilder(policy=contextPolicy)`。
- `test_api_bootstrap.py` 新增测试：
  - 极小 token 预算下默认编排器只保留一个最高优先级上下文块；
  - 只允许 `PUBLIC` 敏感级别时，默认上下文被过滤为空。

设计意义：
- 上下文治理从“独立组件可用”进入“默认编排链路生效”，后续所有默认 Agent 计划都会经过统一上下文选择策略。
- token 预算开始成为启动参数和环境变量能力，为不同模型上下文长度、租户套餐和成本策略预留配置入口。
- 敏感级别准入进入默认启动配置，为生产环境按合规等级控制模型可见上下文打基础。
- `AgentOrchestrator` 仍然只依赖 `ContextBuilder` 协议，不知道底层是默认构建器还是混合构建器，保持了解耦。

当前边界：
- `allowed_context_sensitivity_levels` 当前只支持 Python 参数注入，尚未提供环境变量字符串解析。
- `HybridContextBuilder` 默认只包装 `DefaultContextBuilder`，还没有接入真实 Java 控制面、GraphRAG 或向量检索来源。
- 被过滤、去重、截断的上下文尚未生成可观测事件；前端目前只能看到最终进入计划的 `contextBlocks`。
- token 估算仍是启发式，不是模型 tokenizer 精确值。
- 生产环境还需要结合租户策略、模型路由和用户角色动态生成上下文策略。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，21 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 224 行，`tool_planner.py` 178 行，`context_builder.py` 175 行，`agent_orchestrator.py` 173 行，`hybrid_context_builder.py` 172 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 为 `RuleBasedIntentAnalyzer` 增加敏感数据、跨项目、导出、写 SQL 等高风险规则，并让这些风险影响工具规划。
2. 增加工具 schema 参数校验层，对缺失参数区分“必须澄清”“允许草案”“可由上下文补齐”。
3. 为 `HybridContextBuilder` 增加上下文选择事件，记录哪些上下文被过滤、去重或截断。
4. 为 OpenAI-compatible Provider 增加 API Key、错误码映射、超时/重试和 usage 统计。
5. 设计智能网关事件契约，把上下文构建、意图分析、模型调用、工具规划、审批等待作为前端可订阅事件。

## 3.32 Python AI Runtime 高风险意图识别与工具规划风险传递（2026-05-14）

本阶段开始把 Agent Runtime 从“能理解治理任务”推进到“能识别高风险治理行为”。在真实商业化数据治理产品中，导出数据、访问敏感字段、跨项目/跨租户访问、执行写 SQL 或删除类操作，都不能只依赖后端接口最后兜底；Agent 在意图分析阶段就需要提前识别风险，并把风险标签传递给工具规划、审批、前端确认和审计链路。这样后续即使接入更强的 LLM，也不会让模型绕过规则层的安全边界。

已完成：
- `IntentRiskTag` 新增 `DATA_EXPORT`、`WRITE_SQL`、`CROSS_TENANT` 三类风险标签，用于表达数据导出、写入/破坏性 SQL、跨租户访问等商业化场景中的高危行为。
- `RuleBasedIntentAnalyzer` 新增 `_append_high_risk_tags(...)`，把高风险意图识别逻辑从普通领域判断中拆出，避免后续规则越来越多时污染主分析流程。
- 针对“导出、下载、excel、csv、pdf”等表达识别 `DATA_EXPORT`，并自动补充 `APPROVAL_REQUIRED`；当用户没有说明导出格式时，会把 `exportFormat` 加入缺失参数。
- 针对 `insert/update/delete/drop/truncate` 以及“写入、删除、更新、清空、执行 SQL、写 SQL”等表达识别 `WRITE_SQL`，并自动补充 `STATE_CHANGE` 与 `APPROVAL_REQUIRED`。
- 针对“身份证、手机号、银行卡、姓名、地址、邮箱、email、phone、id card、敏感”等表达识别 `SENSITIVE_DATA`，并自动补充 `APPROVAL_REQUIRED`。
- 针对“跨项目、其他项目、所有项目、全项目、cross project”等表达识别 `CROSS_SCOPE`，用于后续触发更严格的权限和审批策略。
- 针对“跨租户、其他租户、所有租户、全租户、cross tenant”等表达识别 `CROSS_TENANT` 与 `CROSS_SCOPE`，并自动补充 `APPROVAL_REQUIRED`。
- `ToolPlanner` 已通过既有 `IntentAnalysis` 入参把这些风险标签继续写入任务草案 payload 的 `intentRiskTags`，使审批页、审计页和后续工具执行链路可以解释“为什么这次计划需要人工确认”。
- 新增高风险意图测试，覆盖敏感数据导出、跨项目访问、写 SQL 状态变更，以及任务草案携带高风险标签的链路。

设计意义：
- 高风险识别前移到意图分析阶段，而不是等到工具执行阶段才发现风险，这能减少危险工具被错误规划、错误展示或错误自动执行的概率。
- 导出、敏感数据、跨范围访问、写 SQL 是企业数据治理平台最常见的事故来源，本阶段先把这些作为安全基线固化，后续再逐步扩展到批量授权、血缘删除、规则批量下线、任务强制重跑等场景。
- 风险标签进入工具规划 payload 后，前端可以展示更具体的确认文案，审批流可以基于标签选择审批人和审批等级，审计系统也可以基于标签做风险检索。
- 规则层仍然保留兜底权威：即使未来模型认为某个操作“看起来安全”，只要命中这些高风险规则，就必须进入审批或澄清链路。
- `_append_high_risk_tags(...)` 独立存在，方便后续把关键词规则替换或增强为策略引擎、分类分级服务、正则规则库、租户级策略配置，而不需要重写整个意图分析器。

当前边界：
- 当前仍是规则式关键词识别，还没有接入真实字段级分类分级、数据目录敏感标签、租户策略或 DLP 引擎。
- 风险标签目前会影响任务草案 payload，但尚未自动阻断工具计划，也没有真正创建 Java 控制面的审批单。
- `exportFormat` 等缺失参数只是被标记出来，尚未通过 schema 校验层区分“必须追问用户”还是“允许先生成草案”。
- 写 SQL 识别目前基于自然语言和常见 SQL 动词，尚未解析 SQL AST，也不能判断 WHERE 条件缺失、全表更新、DDL 变更等更细粒度风险。
- 跨项目/跨租户识别目前只来自用户文本，还没有结合操作者权限、项目边界、租户隔离策略做强校验。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，24 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 224 行，`intent_analyzer.py` 191 行，`tool_planner.py` 178 行，`context_builder.py` 175 行，`agent_orchestrator.py` 173 行，`hybrid_context_builder.py` 172 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 增加工具 schema 参数校验层，对缺失参数区分“必须澄清”“允许草案”“可由上下文补齐”，避免高风险任务在参数不完整时继续向执行链路推进。
2. 为 `HybridContextBuilder` 增加上下文选择事件，记录哪些上下文被过滤、去重或截断，便于智能网关和前端展示 Agent 的上下文取舍过程。
3. 为 OpenAI-compatible Provider 增加 API Key、错误码映射、超时/重试和 usage 统计，准备从 dry-run 进入真实模型调用。
4. 设计智能网关事件契约，把上下文构建、意图分析、模型调用、工具规划、审批等待作为前端可订阅事件，避免后续 Agent 执行变成黑盒。
5. 后续与 Java 控制面集成时，需要让审批策略真正消费 `intentRiskTags`，例如导出敏感数据走数据负责人审批，跨租户访问走平台管理员审批，写 SQL 走 DBA 或项目 Owner 审批。

## 3.33 Python AI Runtime 工具参数校验层（2026-05-14）

本阶段继续沿着 3.32 的高风险治理方向推进，把“工具计划参数是否完整”从 `ToolPlanner` 的局部判断中拆出来，形成独立的 `ToolParameterValidator`。商业化 Agent 产品不能只生成工具列表，还必须告诉用户和控制面：哪些参数已经足够执行，哪些参数只能生成草案，哪些参数必须先澄清，哪些参数理论上可以从上下文检索补齐。否则后续接入真实工具执行、审批流和智能网关时，很容易出现“计划看起来正确，但执行时才失败”的体验和安全问题。

已完成：
- `domain/contracts.py` 新增 `ToolParameterIssueAction`，覆盖 `MUST_CLARIFY`、`ALLOW_DRAFT`、`CAN_FILL_FROM_CONTEXT`。
- `domain/contracts.py` 新增 `ToolParameterIssue`，用于描述单个参数问题的字段名、期望类型、处理动作和中文说明。
- `domain/contracts.py` 新增 `ToolParameterValidationResult`，用于表达工具计划是否可执行、是否可创建草案、以及参数问题列表。
- `ToolPlan` 新增 `parameterValidation` 字段，工具规划结果现在可以携带参数校验结论。
- 新增 `services/tool_parameter_validator.py`，把参数校验从工具规划逻辑中解耦。
- `ToolParameterValidator` 兼容两种 schema 写法：旧版轻量 `{"datasourceId": "string"}`，以及未来可由 Java 控制面下发的扩展写法 `{"type": "string", "required": true, "resolution": "context_or_clarify"}`。
- `ToolParameterValidator` 会把 `None`、空字符串、空列表、空字典都识别为缺失，避免 Agent 为了保留字段结构填入空值后误判为可执行。
- `ToolPlanner` 支持注入 `ToolParameterValidator`，并在统一 `_build_plan(...)` 阶段为每个工具计划生成参数校验结果。
- `services/__init__.py` 导出 `ToolParameterValidator`，便于 API 层、智能网关或未来单独测试工具校验能力。
- 新增 `test_tool_parameter_validator.py`，覆盖同步工具缺参数必须澄清、草案工具缺参数允许草案、扩展 schema 标记可由上下文补齐。
- 更新 `test_tool_planner.py`，验证质量规则工具在缺少 `datasourceId` 时不会被误认为可执行，而是只能生成草案。

设计意义：
- 工具规划与参数校验正式解耦，避免后续每新增一个工具都把参数判断散落在 `ToolPlanner` 分支中。
- 缺失参数不再只有一个粗糙列表，而是拥有可执行决策、草案决策和中文解释，前端确认页、审批页、审计页都可以直接消费。
- `CAN_FILL_FROM_CONTEXT` 为 RAG/GraphRAG、Java 元数据检索、会话记忆和用户选择器预留了补齐入口，后续可以让 Agent 在追问用户前先尝试补上下文。
- `ALLOW_DRAFT` 支持真实产品里的渐进式体验：质量规则、同步任务、治理方案可以先形成草案，用户再补齐参数，而不是一开始就完全阻断。
- `MUST_CLARIFY` 保留安全底线：同步读取、真实执行、危险操作在关键参数缺失时不能进入执行链路。

当前边界：
- 当前校验的是必填与缺失状态，还没有实现完整 JSON Schema 类型校验、枚举校验、数组元素校验、嵌套对象校验。
- `ToolParameterValidationResult` 当前只影响计划输出，不会自动触发追问用户、重新检索上下文或创建审批阻断事件。
- 现有默认工具 schema 仍较简单，后续需要 Java 工具注册表提供更丰富的 required、resolution、enum、min/max、敏感参数等元数据。
- `CAN_FILL_FROM_CONTEXT` 只是语义标记，尚未接入真实上下文补齐工作流。
- 参数校验暂未结合角色权限、租户策略、风险标签和审批策略做联合决策。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，28 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 215 行，`intent_analyzer.py` 174 行，`tool_planner.py` 171 行，`agent_orchestrator.py` 153 行，`context_builder.py` 149 行，`hybrid_context_builder.py` 145 行，`tool_parameter_validator.py` 128 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。
- `C:\Users\Cui\.codex\skills\datasmart-govern-backend\references\current-repo-state.md` 本轮尝试写入时被外部审批/额度机制拒绝，3.32 与 3.33 尚待恢复权限后补记。

下一步建议：
1. 将 `ToolParameterValidationResult` 接入 `AgentOrchestrator` 的 `nextActions` 和 `responseSummary`，让用户直接看到“需要补哪些参数、为什么不能执行”。
2. 为 `HybridContextBuilder` 增加上下文选择事件，记录过滤、去重、截断原因，为智能网关事件流打基础。
3. 设计智能网关事件契约，把参数校验、上下文补齐、澄清问题、审批等待都变成可订阅状态。
4. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并与 Java 工具注册表保持一致。
5. 强化 OpenAI-compatible Provider，补齐 API Key、错误码映射、超时/重试、usage 统计和脱敏日志。

## 3.34 Python AI Runtime 参数校验结果接入 Agent 编排输出（2026-05-14）

本阶段把 3.33 新增的工具参数校验结果接入 `AgentOrchestrator`，让参数问题真正进入 Agent 状态流、摘要和下一步动作。这样前端或 Java 控制面不需要自己遍历工具计划猜测问题，而是可以直接看到“当前是等待审批、等待参数澄清、只能生成草案，还是可以进入控制面执行”。

已完成：
- `AgentOrchestrator.plan(...)` 在工具规划后统计 `parameterValidation.issues`。
- 当存在 `MUST_CLARIFY` 参数问题时，状态流新增 `clarify_missing_parameters`，表示必须先向用户澄清。
- 当存在非阻断参数问题时，状态流新增 `prepare_draft_with_missing_parameters`，表示当前只能生成带缺失项说明的草案。
- 只有在没有参数问题且不需要人工审批时，才进入 `ready_for_control_plane_execution`，避免缺参计划被误标记为可执行。
- `_build_response_summary(...)` 改为接收完整 `ToolPlan` 列表，摘要中会体现参数问题数量和审批状态。
- `_build_next_actions(...)` 改为结合参数问题和审批状态生成下一步动作。
- 新增 `_has_parameter_issues(...)`、`_requires_parameter_clarification(...)`、`_build_parameter_next_actions(...)`，让编排状态判断保持清晰，避免主流程堆积细节。
- 参数问题下一步动作最多展示前三个，其余完整问题仍保留在每个 `ToolPlan.parameterValidation.issues` 中，兼顾会话摘要简洁性和结构化详情完整性。
- 更新 `test_agent_orchestrator.py`，验证缺少 `datasourceId` 的质量规则计划不会进入 `ready_for_control_plane_execution`，而是进入 `prepare_draft_with_missing_parameters` 并在 `nextActions` 中提示补齐参数。

设计意义：
- 参数校验不再只是工具计划内部字段，而是成为 Agent 状态机的一部分，后续智能网关可以把它作为事件推送给前端。
- “缺参”“审批”“可执行”三个状态被拆开表达，避免把所有非正常状态都粗暴归为审批或失败。
- 前端会话可以直接展示补参建议，降低用户不知道下一步该做什么的摩擦。
- Java 控制面未来可以根据状态节点决定是否创建审批单、澄清任务、草案记录或执行审计，而不是依赖文本摘要。
- 该实现没有把参数问题处理塞进工具规划分支，仍保持 `AgentOrchestrator` 负责状态编排、`ToolPlanner` 负责计划、`ToolParameterValidator` 负责校验的边界。

当前边界：
- 当前只是把参数问题展示到摘要和下一步动作，还没有真正生成“澄清问题”交互，也没有自动触发上下文补齐。
- `nextActions` 是文本建议，不是可执行命令；后续智能网关需要定义结构化事件和动作类型。
- 参数问题最多在摘要动作中展示前三个，完整列表需要前端读取 `toolPlans[].parameterValidation.issues`。
- 状态节点仍是字符串 tuple，后续应升级为包含时间、输入摘要、输出摘要、事件类型、严重级别的结构化事件。
- 还没有把参数校验结果写入 Java agent-runtime 审计表或审批流。

验证：
- 已执行 `python -m compileall python-ai-runtime/src python-ai-runtime/tests`，Python 源码编译检查通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，29 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `contracts.py` 215 行，`agent_orchestrator.py` 206 行，`intent_analyzer.py` 174 行，`tool_planner.py` 171 行，`context_builder.py` 149 行，`hybrid_context_builder.py` 145 行，`tool_parameter_validator.py` 128 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。
- `C:\Users\Cui\.codex\skills\datasmart-govern-backend\references\current-repo-state.md` 本轮仍受外部审批/额度限制，3.32、3.33、3.34 尚待恢复权限后统一补记。

下一步建议：
1. 设计智能网关结构化事件契约，把 `stateTrace` 从字符串升级为可订阅事件对象，覆盖上下文构建、意图分析、参数校验、审批等待、执行状态。
2. 为 `HybridContextBuilder` 增加上下文选择事件，记录过滤、去重、截断原因，作为智能网关事件流的第一批数据源。
3. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并推动 Java 工具注册表下发同构 schema。
4. 增加上下文补齐工作流：当参数标记为 `CAN_FILL_FROM_CONTEXT` 时，优先触发元数据、图谱或向量检索，而不是立即追问用户。
5. 强化 OpenAI-compatible Provider，补齐 API Key、错误码映射、超时/重试、usage 统计和脱敏日志。

## 3.35 Python AI Runtime 智能网关事件契约与上下文选择事件（2026-05-18）

本阶段开始把 Agent Runtime 的内部状态推进为“智能网关可订阅、前端可展示、审计可回放”的结构化事件。此前 `stateTrace` 只是字符串节点序列，适合单元测试和简单调试，但真实商业化产品需要知道每个节点发生了什么、为什么上下文被过滤、哪些工具需要审批、哪些参数缺失、这些事件属于哪个租户/项目/操作者。3.35 先定义统一事件契约，并让 `HybridContextBuilder` 与 `AgentOrchestrator` 产出第一批结构化事件。

已完成：
- 新增 `domain/events.py`，定义 Agent Runtime 结构化事件契约。
- 新增 `AgentRuntimeEventType`，覆盖 `CONTEXT_COLLECTED`、`CONTEXT_FILTERED`、`CONTEXT_DEDUPLICATED`、`CONTEXT_TRUNCATED`、`CONTEXT_SELECTED`、`INTENT_ANALYZED`、`TOOL_PLANNED`、`TOOL_PARAMETER_VALIDATED`、`APPROVAL_WAITING`。
- 新增 `AgentRuntimeEventSeverity`，覆盖 `INFO`、`WARNING`、`ERROR`、`AUDIT`，用于前端高亮、审计归档和告警分级。
- 新增 `AgentRuntimeEvent`，字段包含事件类型、阶段、中文说明、严重级别、租户 ID、项目 ID、操作者 ID、机器可读属性和 UTC 创建时间。
- `AgentPlan` 新增 `runtimeEvents`，使一次 Agent 计划可以同时返回结构化事件流雏形。
- `domain/__init__.py` 导出事件契约，并补齐参数校验相关契约导出。
- `HybridContextBuilder` 新增 `last_events()`，用于读取最近一次上下文构建产生的事件。
- `HybridContextBuilder.build(...)` 现在会记录上下文收集、过滤、去重、截断、最终选择事件。
- 上下文过滤事件会记录 `reason=expired` 或 `reason=sensitivity_not_allowed`。
- 上下文去重事件会记录 `duplicate_replaced` 或 `duplicate_lower_priority`。
- token 预算截断事件会记录 `token_budget_exceeded`，预算过小时保留首个上下文会记录 `kept_over_budget`。
- `AgentOrchestrator` 会把上下文事件合并进 `AgentPlan.runtimeEvents`。
- `AgentOrchestrator` 新增意图分析、工具规划、参数校验、审批等待事件。
- 当参数缺失时，会产生 `TOOL_PARAMETER_VALIDATED` warning 事件；当需要审批时，会产生 `APPROVAL_WAITING` audit 事件。
- 更新 `test_hybrid_context_builder.py`，验证上下文过滤、去重、截断事件产生并携带原因。
- 更新 `test_agent_orchestrator.py`，验证计划结果包含上下文选择、意图分析、工具规划、参数校验和审批等待事件。

设计意义：
- `stateTrace` 仍保留为轻量状态序列，但 `runtimeEvents` 开始承担智能网关事件流的正式契约角色。
- 前端未来可以基于事件类型展示“正在构建上下文、正在分析意图、参数需要补齐、等待审批”等实时进度。
- 审计系统可以基于 `tenantId/projectId/actorId/eventType/severity` 检索 Agent 行为，满足企业客户对可追溯性的要求。
- 上下文治理不再是黑盒，过期、敏感级别不允许、重复、超预算等原因都可以被事件记录。
- `AgentRuntimeEvent` 的 `attributes` 保留机器可读扩展字段，未来可逐步接入 WebSocket、Kafka、数据库审计表或 Grafana 指标。
- 事件契约没有绑定具体通信方式，因此可以同时适配同步 HTTP 返回、WebSocket 流式推送、Kafka 异步事件和离线审计回放。

当前边界：
- `HybridContextBuilder.last_events()` 当前保存最近一次构建事件，生产环境更适合引入请求级 `EventRecorder`，避免单例并发时事件互相覆盖。
- `runtimeEvents` 当前随 `AgentPlan` 一次性返回，还没有真正通过 WebSocket 或 Kafka 流式推送。
- `AgentRuntimeEvent` 尚未包含统一 `requestId/runId/sessionId` 字段；后续进入智能网关时应补齐这些关联 ID。
- 事件属性尚未标准化成严格 schema，不同事件目前使用各自 attributes。
- 事件还没有持久化到 Java 控制面审计表，也没有接入权限过滤、脱敏和租户隔离。
- `compileall` 在当前 Windows 沙箱中写入 `.pyc` 缓存时遇到 `WinError 5`，已改用不写字节码的 AST 语法检查和单元测试进行验证。

验证：
- 已执行 `python -B -c "... ast.parse ..."`，对 `python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 27 个 Python 文件完成 AST 语法检查。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，29 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 313 行，`agent_orchestrator.py` 302 行，`contracts.py` 217 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 引入请求级 `RuntimeEventRecorder`，替代 `HybridContextBuilder.last_events()`，让所有节点向同一个事件收集器写事件。
2. 为智能网关设计 WebSocket/Kafka 事件传输协议，补齐 `requestId/runId/sessionId/sequence`，支持前端实时订阅和断线续传。
3. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并推动 Java 工具注册表下发同构 schema。
4. 增加上下文补齐工作流：当参数标记为 `CAN_FILL_FROM_CONTEXT` 时，优先触发元数据、图谱或向量检索。
5. 继续强化 OpenAI-compatible Provider，补齐 API Key、错误码映射、超时/重试、usage 统计和脱敏日志。

## 3.36 Python AI Runtime 请求级 RuntimeEventRecorder（2026-05-18）

本阶段把 3.35 的“结构化事件契约”继续推进为请求级事件收集机制。上一阶段 `HybridContextBuilder.last_events()` 适合验证上下文治理事件，但它本质上是组件级最近一次缓存；如果未来 builder 作为单例被多个请求并发复用，就可能出现事件覆盖或串流。3.36 新增 `RuntimeEventRecorder`，由 `AgentOrchestrator` 为每次计划请求创建独立 recorder，并把 recorder 传给支持事件的上下文构建器，让一次请求内所有节点写入同一条事件流。

已完成：
- `AgentRuntimeEvent` 新增 `requestId`、`runId`、`sessionId`、`sequence` 字段。
- 新增 `services/runtime_event_recorder.py`，定义请求级 `RuntimeEventRecorder`。
- `RuntimeEventRecorder.record(...)` 会统一填充 tenantId、projectId、actorId、requestId、runId、sessionId，并生成递增 sequence。
- `RuntimeEventRecorder.events()` 返回不可变事件快照，避免调用方误改内部列表。
- `ContextBuilder` 协议扩展为 `build(request, event_recorder=None)`，保持事件能力可选，兼容旧构建器。
- `DefaultContextBuilder.build(...)` 接受可选 `event_recorder`，当前不强制写事件。
- `HybridContextBuilder.build(...)` 接受可选 `event_recorder`，并优先通过 recorder 写入上下文收集、过滤、去重、截断、选择事件。
- `HybridContextBuilder` 内部上下文来源调用兼容两种形式：`build(request, event_recorder)` 与旧版 `build(request)`。
- `AgentOrchestrator.plan(...)` 在请求开始时生成 requestId、runId，并从 variables 中读取 sessionId/session_id。
- `AgentOrchestrator` 不再从 `HybridContextBuilder.last_events()` 读取事件，而是直接读取 `RuntimeEventRecorder.events()`。
- `AgentOrchestrator` 的意图分析、工具规划、参数校验、审批等待事件统一通过 recorder 写入，保证 sequence 连续。
- `services/__init__.py` 导出 `RuntimeEventRecorder`。
- 新增 `test_runtime_event_recorder.py`，验证 recorder 能填充请求上下文、关联 ID、sequence 和 attributes。
- 更新 `test_agent_orchestrator.py`，验证 `runtimeEvents` 中所有事件 requestId 等于 `AgentPlan.requestId`，sequence 从 1 连续递增，并能透传 sessionId。

设计意义：
- 事件从“某个组件最近一次运行结果”升级为“请求级事件流”，更接近真实智能网关、WebSocket 和 Kafka 的事件模型。
- `requestId` 适合同步 HTTP 追踪，`runId` 适合长任务、重试和恢复执行，`sessionId` 适合多轮对话和断线续传。
- `sequence` 为前端断线重连、事件补发、审计回放排序提供基础，不再只依赖 createdAt。
- `RuntimeEventRecorder` 不绑定具体传输方式，后续可以同时适配同步返回、流式推送、Kafka topic 和审计落库。
- `ContextBuilder` 的 recorder 参数是可选能力，不会破坏已有本地 builder、测试 builder 或未来第三方上下文插件。

当前边界：
- `RuntimeEventRecorder` 当前仍是内存收集器，还没有接 WebSocket、Kafka、数据库或日志系统。
- `runId` 当前由 `AgentOrchestrator` 每次规划生成，尚未和 Java agent-runtime 的 run/approval/execution 记录打通。
- `sessionId` 当前从 `AgentRequest.variables` 读取，后续应提升为正式请求字段，由智能网关统一生成和管理。
- `HybridContextBuilder.last_events()` 为兼容测试仍保留，但默认编排主链路已经改为 recorder。
- 事件 attributes 仍是各节点自定义字典，尚未形成事件级严格 schema。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 29 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，31 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行，`agent_orchestrator.py` 284 行，`contracts.py` 217 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 设计智能网关 WebSocket/Kafka 事件传输协议，定义 topic/channel、事件 envelope、断线续传、ack 和重放策略。
2. 将 `AgentRuntimeEvent.attributes` 按事件类型拆成更严格的 schema，减少前后端对自由字典的隐式依赖。
3. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并推动 Java 工具注册表下发同构 schema。
4. 增加上下文补齐工作流：当参数标记为 `CAN_FILL_FROM_CONTEXT` 时，优先触发元数据、图谱或向量检索。
5. 继续强化 OpenAI-compatible Provider，补齐 API Key、错误码映射、超时/重试、usage 统计和脱敏日志。

## 3.37 Python AI Runtime 智能网关事件传输 Envelope 协议（2026-05-18）

本阶段在 3.36 请求级 `RuntimeEventRecorder` 基础上，继续补齐智能网关事件传输协议。`AgentRuntimeEvent` 解决了“单条事件是什么”，`RuntimeEventRecorder` 解决了“一次请求内如何收集事件”，但 WebSocket、Kafka、HTTP 响应和审计回放还需要统一 envelope 描述“事件如何传输、是否需要确认、从哪个 sequence 开始回放、是否还有更多数据”。3.37 先实现领域契约和 envelope 构建服务，为后续真正接入 Java gateway、Python API 或 Kafka Producer 打基础。

已完成：
- 新增 `domain/event_transport.py`，定义 Agent Runtime 事件传输领域契约。
- 新增 `RuntimeEventChannel`，覆盖 `HTTP_RESPONSE`、`WEBSOCKET`、`KAFKA`、`AUDIT_LOG`。
- 新增 `RuntimeEventDeliveryMode`，覆盖 `SNAPSHOT`、`LIVE`、`REPLAY`。
- 新增 `RuntimeEventAckMode`，覆盖 `NONE`、`CLIENT_ACK`、`BROKER_ACK`。
- 新增 `RuntimeEventEnvelope`，字段包含 envelopeId、schemaVersion、channel、deliveryMode、ackMode、requestId、runId、sessionId、sequenceFrom、sequenceTo、replayFromSequence、hasMore、events、attributes、createdAt。
- 新增 `services/runtime_event_transport.py`，定义 `RuntimeEventTransportBuilder`。
- `RuntimeEventTransportBuilder.build_snapshot(...)` 支持构建同步 HTTP 快照 envelope，默认不需要 ack。
- `RuntimeEventTransportBuilder.build_live(...)` 支持构建 WebSocket 实时增量 envelope，默认使用 `CLIENT_ACK`，并标记 `hasMore=true`。
- `RuntimeEventTransportBuilder.build_replay(...)` 支持按 `after_sequence` 过滤事件，用于断线续传和审计回放。
- `RuntimeEventTransportBuilder.build_kafka_audit(...)` 支持构建 Kafka 审计 envelope，默认 topic 为 `datasmart.agent-runtime.events`，ack 模式为 `BROKER_ACK`，partitionKey 默认使用 runId/requestId。
- `domain/__init__.py` 导出事件传输契约。
- `services/__init__.py` 导出 `RuntimeEventTransportBuilder`。
- 新增 `test_runtime_event_transport.py`，覆盖 HTTP snapshot、WebSocket live、sequence replay、Kafka audit 四类 envelope 语义。

设计意义：
- 事件传输协议与真实网络发送解耦，后续 Java gateway 或 Python API 都可以复用同一 envelope 语义。
- HTTP、WebSocket、Kafka、审计回放不再各自定义事件字段，减少前端和控制面的适配成本。
- `SNAPSHOT/LIVE/REPLAY` 明确区分一次性返回、实时推送和断线续传，符合真实企业产品会话和任务看板需求。
- `NONE/CLIENT_ACK/BROKER_ACK` 明确确认策略，方便后续做前端 ack、Kafka broker ack、失败重发和事件去重。
- `sequenceFrom/sequenceTo/replayFromSequence/hasMore` 为断线重连、分页回放和审计检索提供基础。
- `attributes` 保留 topic、channelName、clientId、partitionKey 等传输层扩展字段，避免污染单条事件本身。

当前边界：
- 当前只实现 envelope 构建，不做真实 WebSocket 连接管理、Kafka Producer 发送或数据库审计落库。
- `build_replay(...)` 当前从内存事件集合中过滤，生产环境需要结合事件存储或 Kafka offset 做持久化回放。
- envelope 内默认假设事件来自同一 request/run/session；如果后续需要批量混合多请求事件，应先按 requestId 分组。
- Kafka topic、partitionKey、ack 失败重试策略当前只是协议字段，尚未接入实际中间件。
- 事件 attributes 仍是自由字典，下一步需要按事件类型逐步收敛为严格 schema。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 32 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，35 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行，`agent_orchestrator.py` 284 行，`contracts.py` 217 行，`runtime_event_transport.py` 154 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 将 `AgentPlan.runtimeEvents` 包装成 `RuntimeEventEnvelope` 返回给 Python API 层，为同步 HTTP 响应建立统一 envelope 输出。
2. 设计 WebSocket 订阅请求契约，包含 sessionId/runId/afterSequence/eventTypes/clientId，支撑实时订阅和断线续传。
3. 将 `AgentRuntimeEvent.attributes` 按事件类型拆成更严格 schema，减少前后端对自由字典的隐式依赖。
4. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并推动 Java 工具注册表下发同构 schema。
5. 增加上下文补齐工作流：当参数标记为 `CAN_FILL_FROM_CONTEXT` 时，优先触发元数据、图谱或向量检索。

## 3.42 Python AI Runtime WebSocket 订阅会话状态机（2026-05-23）

本阶段在 3.39-3.41 的事件订阅、replay API 和事件存储基础上，补齐实时智能网关的连接生命周期语义。真实商用产品不能只把 WebSocket 当成“长连接消息管道”，还需要明确 subscribe、ack、heartbeat、unsubscribe、reconnect、replay 的状态流转，否则前端刷新、弱网重连、多标签页、服务端扩容和审计排查都会出现边界不清的问题。

已完成：
- `domain/event_transport.py` 新增 `RuntimeEventConnectionState`，定义 `CONNECTING`、`ACTIVE`、`STALE`、`CLOSED`。
- `domain/event_transport.py` 新增 `RuntimeEventControlMessageType`，定义 `SUBSCRIBE`、`ACK`、`HEARTBEAT`、`UNSUBSCRIBE`、`RECONNECT`。
- `domain/__init__.py` 导出新增连接状态和控制消息枚举。
- 新增 `services/runtime_event_session.py`，实现不绑定具体 WebSocket 框架的 `RuntimeEventSessionManager`。
- 新增 `RuntimeEventSessionSnapshot`，作为服务端对外暴露的订阅会话稳定视图。
- 新增 `RuntimeEventSessionError`，为后续 API/网关错误码映射预留领域异常入口。
- `RuntimeEventSessionManager.subscribe(...)` 支持建立订阅、生成订阅计划，并在 `includeSnapshot=True` 时从 `RuntimeEventStore` 构建 replay envelope。
- `RuntimeEventSessionManager.acknowledge(...)` 支持客户端确认 lastSequence，并忽略倒退的旧 ack。
- `RuntimeEventSessionManager.heartbeat(...)` 支持心跳保活，并允许心跳携带 lastSequence 做轻量 ack。
- `RuntimeEventSessionManager.mark_stale_sessions(...)` 支持按心跳超时把 `ACTIVE` 会话标记为 `STALE`，为弱网重连保留恢复窗口。
- `RuntimeEventSessionManager.reconnect(...)` 支持基于最近 ack 或显式 afterSequence 重新进入 `ACTIVE`，并构建断线续传 replay envelope。
- `RuntimeEventSessionManager.unsubscribe(...)` 支持主动关闭订阅，并记录 closeReason。
- `services/__init__.py` 导出 `RuntimeEventSessionManager`、`RuntimeEventSessionSnapshot`、`RuntimeEventSessionError`。
- 新增 `test_runtime_event_session.py`，覆盖订阅回放、ack 单调推进、心跳更新、心跳超时、重连回放和取消订阅后拒绝 ack。

设计意义：
- 实时事件能力从“有 replay envelope 和 store”推进到“有连接生命周期状态机”，后续实现真实 WebSocket handler 时不会把状态流转散落在路由函数里。
- ack 成为断线续传的正式服务端状态，前端刷新或断网后可以从 lastSequence 之后继续接收事件。
- `STALE` 状态让服务端可以区分“暂时心跳超时、允许重连”和“已经关闭、必须拒绝”的不同处理策略。
- 会话管理器只依赖 `RuntimeEventTransportBuilder` 与 `RuntimeEventStore` 抽象，不绑定 FastAPI、Java Gateway、Redis 或 Kafka，后续替换传输实现时成本较低。
- 订阅会话快照可自然演进为运维管理视图，例如当前在线订阅数、异常连接、客户端来源、最后 ack 序号和关闭原因。

当前边界：
- 当前仍未启动真实 WebSocket server，尚未处理网络连接对象、发送队列、背压、并发写、连接关闭回调和鉴权上下文。
- 会话表当前是进程内内存字典，多实例部署不共享；生产环境需要 Redis/数据库/网关集群会话同步能力。
- `CONNECTING` 枚举已定义但当前 `subscribe(...)` 直接返回 `ACTIVE`，后续接入真实握手、鉴权、限流和首包 replay 时可补充中间态。
- 尚未实现 per-tenant 订阅配额、eventTypes 白名单、最大 replay 窗口、最大连接数和异常连接清理策略。
- 尚未把 ack 持久化到 `RuntimeEventStore` 或独立 checkpoint store；进程重启后仍需要客户端携带 afterSequence 才能可靠续传。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 36 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime\tests`，49 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行、`agent_orchestrator.py` 284 行、`runtime_event_session.py` 264 行、`api.py` 242 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 为 `RuntimeEventStore` 增加 Redis Stream/Kafka 适配器设计文档或接口预留，明确 offset、TTL、分区键、幂等键和 ack checkpoint。
2. 增加 WebSocket API handler 雏形，把 subscribe、ack、heartbeat、unsubscribe、reconnect 控制消息接入 `RuntimeEventSessionManager`。
3. 增加订阅鉴权与租户隔离策略，限制用户只能订阅自己有权限的 session/run/request。
4. 将 `AgentRuntimeEvent.attributes` 按事件类型拆成更严格 schema，减少前后端对自由字典的隐式依赖。
5. 逐步切换到 AI 模型与 Agent 能力的下一条主线，例如模型 provider 健壮性、工具 schema 治理或上下文补齐工作流，避免长期只打磨实时事件局部能力。

## 3.43 Python AI Runtime 实时事件控制消息接入 API 雏形（2026-05-23）

本阶段把 3.42 的订阅会话状态机向 API/网关入口推进一步。3.42 已经有 `RuntimeEventSessionManager`，但真实前端或 Java Gateway 不会直接调用 Python 方法，而是发送 JSON 控制消息。本阶段新增控制消息领域对象、控制处理器和同步 API helper，让 `subscribe/ack/heartbeat/reconnect/unsubscribe` 能通过统一协议进入状态机，为后续真实 WebSocket handler 打基础。

已完成：
- `domain/event_transport.py` 新增 `RuntimeEventControlMessage`，统一描述实时事件控制消息。
- `RuntimeEventControlMessage` 支持 `messageType`、`subscriptionId`、`request`、`lastSequence`、`afterSequence`、`reason`、`attributes` 等控制字段。
- `domain/__init__.py` 导出 `RuntimeEventControlMessage`。
- 新增 `services/runtime_event_control.py`。
- 新增 `RuntimeEventControlHandler`，把控制消息分发到 `RuntimeEventSessionManager`。
- 新增 `RuntimeEventControlMessageError`，为后续 API 错误码映射预留入口。
- 新增 `control_message_from_payload(...)`，兼容 `type/messageType/message_type`、camelCase 与 snake_case 字段。
- `RuntimeEventControlHandler.handle(...)` 统一返回 `accepted/messageType/subscription` 响应结构。
- `api.py` 新增 `build_event_control_response(payload, session_manager)`，用于 HTTP、WebSocket、命令行测试复用同一控制协议。
- FastAPI `create_app()` 初始化 `RuntimeEventSessionManager(event_store=event_store)`。
- FastAPI 新增 `/agent/events/control` 同步控制入口，支持 subscribe、ack、heartbeat、reconnect、unsubscribe。
- 新增 `test_runtime_event_control.py`，覆盖订阅控制消息返回 replay、ack 与 heartbeat 更新同一订阅、reconnect 按 afterSequence 回放、unsubscribe 关闭订阅。

设计意义：
- 控制消息协议从“状态机方法调用”升级为“可由网关/前端发送的 JSON 契约”，更接近真实产品入口。
- HTTP 控制入口不是最终形态，但能在没有真实 WebSocket 环境时先完成协议联调和自动化测试。
- 后续 WebSocket handler 可以只负责收发消息，把收到的 JSON 交给 `build_event_control_response(...)`，避免路由层重复实现状态机。
- `control_message_from_payload(...)` 集中处理字段兼容，避免前端 SDK、Java Gateway、Python API 各自出现命名漂移。
- 响应结构已经包含订阅快照、ack 序号、连接状态、关闭原因和 replayEnvelope，便于前端构建会话进度条和断线恢复 UI。

当前边界：
- `/agent/events/control` 是同步 HTTP 控制入口，不是最终 WebSocket 长连接；它主要服务协议验证、联调和管理场景。
- 当前控制处理器没有做鉴权、租户隔离、订阅配额、eventTypes 白名单或最大 replay 窗口限制。
- 控制消息错误当前仍以 Python 异常表达，FastAPI 路由尚未映射成统一错误响应体。
- ack 仍保存在进程内会话管理器中，尚未落入 Redis/数据库 checkpoint，进程重启后无法恢复服务端 ack 状态。
- 尚未实现真正的 live push、发送队列、背压、慢客户端剔除、心跳定时器和连接关闭回调。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 38 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime\tests`，53 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行、`agent_orchestrator.py` 284 行、`api.py` 272 行、`runtime_event_session.py` 264 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 为控制消息补统一错误响应映射，把格式错误、订阅不存在、订阅已关闭、心跳超时分别转成可前端处理的错误码。
2. 增加真实 WebSocket handler 雏形，复用 `build_event_control_response(...)` 处理首条 subscribe 和后续 ack/heartbeat/reconnect/unsubscribe。
3. 增加订阅鉴权与租户隔离策略，限制用户只能订阅自己有权限的 session/run/request。
4. 为 ack checkpoint 设计 Redis/数据库持久化接口，解决多实例和进程重启后的断线续传问题。
5. 在完成 WebSocket 最小闭环后，切换一段工作到模型 provider 健壮性或工具 schema 治理，继续保持项目整体演进节奏。

## 3.44 Python AI Runtime 实时事件订阅授权边界（2026-05-23）

本阶段把 3.43 的控制消息入口再往前推进了一步：控制消息能进状态机还不够，商业化产品必须明确“谁有权订阅什么范围”。因此本阶段新增实时事件订阅授权上下文和授权器，并把 tenant/project/actor/roles 信息引入订阅请求与控制入口。这样后续接 permission-admin、Java Gateway、服务账号或审计员视图时，实时事件链路不会继续以匿名方式暴露。

已完成：
- `domain/event_transport.py` 的 `RuntimeEventSubscriptionRequest` 新增 `tenant_id`、`project_id`、`actor_id`、`roles` 字段。
- `services/runtime_event_authorization.py` 新增 `RuntimeEventAccessContext`，描述认证后的访问上下文。
- `RuntimeEventAccessContext` 支持 `tenant_id`、`project_id`、`actor_id`、`roles`、允许访问的 session/run/request 范围、平台管理员/租户管理员/审计员标记。
- 新增 `RuntimeEventAuthorizationDecision`，把授权结果结构化为 `allowed/reason/effective_scope`。
- 新增 `RuntimeEventSubscriptionAuthorizer`，集中处理平台管理员、租户边界、项目边界、显式 session/run/request 范围和审计员只读逻辑。
- `services/runtime_event_control.py` 的控制处理器支持注入授权器，并在 `SUBSCRIBE` 前先执行授权判断。
- `RuntimeEventControlHandler.handle(...)` 现在支持接收 `access_context`，使 HTTP/WebSocket/测试工具都可以在同一入口传入认证结果。
- `RuntimeEventControlHandler._snapshot_to_response(...)` 额外返回 `tenantId`、`projectId`、`actorId`、`roles`，便于前端和审计界面展示订阅归属。
- `api.py` 新增 `_access_context_from_payload(...)`，兼容 `accessContext` / `authorization` payload。
- `api.py` 新增 `_control_error_code(...)`，把控制错误归一化为可前端处理的错误码。
- `build_event_control_response(...)` 现在会捕获控制消息错误，并返回 `accepted=false` 的错误结构，而不是直接把异常抛给调用方。
- FastAPI `/agent/events/control` 现在具备“先授权、再状态流转、再返回结构化结果”的最小闭环。
- 新增 `test_runtime_event_control.py` 授权拒绝测试，验证 tenant 不匹配时会返回 `EVENT_CONTROL_NOT_AUTHORIZED`。

设计意义：
- 实时订阅从“能连上”升级为“能在正确范围内连上”，开始接近商用产品的治理边界。
- `RuntimeEventAccessContext` 为后续接 Java Gateway 登录态、permission-admin 项目成员和审计员只读视图提供统一授权输入。
- 错误响应不再只是异常，而是可被前端、网关和测试工具稳定消费的结构化错误码。
- 订阅请求携带 tenant/project/actor/roles 后，事件流能够自然落到租户、项目和操作者维度，方便审计和隔离。

当前边界：
- 当前授权器还是显式上下文授权模型，尚未直接对接登录认证、JWT claim、permission-admin 服务或统一身份中心。
- 审计员/管理员/普通用户的更细权限矩阵仍可继续演进，例如 session 级、任务级、操作级的差异化能力。
- `build_event_control_response(...)` 目前返回的是同步错误结构，尚未统一成 HTTP status code + error body 或 WebSocket error frame。
- `RuntimeEventSubscriptionAuthorizer` 主要覆盖订阅边界，尚未处理消息级权限，例如是否允许 ack、是否允许重连更老的 sequence、是否允许回放历史事件。
- ack checkpoint 仍然停留在内存会话管理器，后续还需要独立持久化以支持多实例恢复。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 39 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，54 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行、`api.py` 327 行、`agent_orchestrator.py` 284 行、`runtime_event_session.py` 264 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 为实时事件控制消息补更细的错误映射和 WebSocket 风格错误帧，覆盖缺少 subscriptionId、订阅不存在、心跳超时等场景。
2. 进一步把订阅授权和 permission-admin 项目成员/角色体系打通，形成真实租户可用的订阅隔离。
3. 增加真正的 WebSocket handler 雏形，复用 `build_event_control_response(...)` 处理首条 subscribe 和后续消息。
4. 为 ack checkpoint 设计 Redis/数据库持久化接口，解决多实例和重启恢复问题。
5. 在实时事件最小闭环稳定后，切换一段工作到模型 provider 健壮性或工具 schema 治理，继续保持整体项目推进。

## 3.45 Python AI Runtime WebSocket 帧编排雏形（2026-05-23）

本阶段在 3.44 的订阅授权边界基础上，把实时事件链路再往真实 WebSocket 入口推进一步。3.44 解决了“谁能订阅什么”；3.45 解决“订阅控制消息在 socket 上应如何拆成可发送的帧”。真实 WebSocket handler 往往不只发送一条 JSON，而是要把控制响应和 replay/event envelope 分开发送，这样前端能明确区分“会话状态变化”和“事件内容”。因此本阶段新增 WebSocket 帧编排适配层，并将其挂接到 API 里的 WebSocket 雏形路由。

已完成：
- 新增 `services/runtime_event_websocket.py`。
- 新增 `RuntimeEventWebSocketFrameType`，定义 `CONTROL_RESPONSE`、`EVENT_ENVELOPE`、`ERROR`。
- 新增 `RuntimeEventWebSocketFrame`，统一描述 WebSocket 下行帧的类型与 payload。
- 新增 `build_websocket_frames_from_control_response(...)`，把一次控制响应拆成 1 个或多个 WebSocket 帧。
- `build_websocket_frames_from_control_response(...)` 会在 `accepted=false` 时直接返回 `ERROR` 帧。
- `build_websocket_frames_from_control_response(...)` 会在 `accepted=true` 时先返回 `CONTROL_RESPONSE` 帧，再根据 replayEnvelope 额外返回 `EVENT_ENVELOPE` 帧。
- 新增 `frames_to_payloads(...)`，把内部帧结构归一化为可直接 `send_json(...)` 的 payload。
- `api.py` 新增 `build_event_websocket_payloads(...)`，把控制消息转换为 WebSocket 可发送的 payload 序列。
- FastAPI `create_app()` 新增 `/agent/events/ws` WebSocket 雏形路由。
- `/agent/events/ws` 连接建立后按消息循环接收 JSON 控制消息，并复用同一套控制/授权/状态机/帧编排逻辑发送响应。
- 新增 `test_runtime_event_websocket.py`，验证 subscribe 会被拆成控制帧 + replay 帧，授权失败会变成 error 帧。

设计意义：
- WebSocket 入口第一次具备了“控制响应”和“事件内容”分帧发送的能力，而不是把所有内容塞进一个大 JSON。
- `RuntimeEventWebSocketFrame` 让前端、Java Gateway 和测试工具可以更清晰地区分 frame 类型，后续扩展 live push 时也更容易。
- WebSocket handler 现在只承担消息循环和 socket 收发，真正的协议判断、授权与状态流转仍集中在服务层，避免路由变成上帝函数。
- 当前还未实现真正的后台 live 推送，但框架已经为 replay/snapshot 混合发送打好基础。

当前边界：
- `/agent/events/ws` 仍是最小雏形，没有实现完整的认证握手、心跳调度、后台 live 推送队列、慢客户端背压或断开回调细分。
- `WebSocket` 路由当前依赖 FastAPI/Starlette 的可选运行时，核心测试仍以纯函数 helper 为主。
- 帧编排当前只覆盖控制响应与 replay envelope，尚未处理异步 live 事件追加帧。
- 错误帧仍是结构化 JSON，但尚未统一成更严格的错误码协议和前端 SDK 常量。
- ack checkpoint 仍停留在内存会话管理器，尚未持久化，进程重启后无法完全恢复断线续传状态。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 41 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，56 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `api.py` 360 行、`hybrid_context_builder.py` 358 行、`agent_orchestrator.py` 284 行、`runtime_event_session.py` 264 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 继续把实时事件链路往 live push 推进，设计后台事件追加到 WebSocket 帧发送的统一出口。
2. 为控制消息和 WebSocket 帧补更严格的错误码协议，方便前端 SDK 统一处理失败。
3. 为 ack checkpoint 设计 Redis/数据库持久化接口，解决多实例与重启恢复问题。
4. 在 WebSocket 最小闭环稳定后，切换到模型 provider 健壮性或工具 schema 治理，继续保持项目整体推进。

## 3.38 Python AI Runtime 同步 HTTP Plan Response 接入 RuntimeEventEnvelope（2026-05-18）

本阶段把 3.37 定义的事件传输 envelope 接入 Python API 层。此前 `/agent/plans` 路由直接把 `AgentPlan` 转成 dict 返回，虽然包含 `runtimeEvents`，但缺少明确的传输语义：调用方不知道这批事件是快照、实时增量还是回放，也不知道是否需要 ack。3.38 新增 `build_plan_response(...)`，让同步 HTTP 响应以 `plan + eventEnvelope` 的形式返回，先把 HTTP snapshot 模式打通。

已完成：
- `api.py` 引入 `RuntimeEventTransportBuilder`。
- 新增 `build_plan_response(request, orchestrator, event_transport_builder=None)`。
- `build_plan_response(...)` 会先调用 `orchestrator.plan(request)` 生成 `AgentPlan`。
- `build_plan_response(...)` 会把 `plan.runtimeEvents` 包装成 `RuntimeEventEnvelope`。
- 同步 HTTP 响应默认使用 `RuntimeEventChannel.HTTP_RESPONSE`、`RuntimeEventDeliveryMode.SNAPSHOT`、`RuntimeEventAckMode.NONE`。
- envelope attributes 中增加 `responseShape=agent_plan_with_event_envelope` 与 `transportHint`，用于向前端/Java 网关说明当前响应形态。
- FastAPI `/agent/plans` 路由改为复用 `build_plan_response(...)`。
- 更新 `test_api_bootstrap.py`，验证响应同时包含 `plan` 与 `eventEnvelope`。
- 测试覆盖 requestId 一致性、snapshot 通道、ack 策略、sequence 范围、envelope events 与 plan runtimeEvents 一致性。

设计意义：
- Python API 层开始使用统一事件传输协议，不再裸返回事件列表。
- 当前同步 HTTP 与未来 WebSocket/Kafka 都使用同一 envelope 语义，降低前端和 Java 网关后续适配成本。
- `build_plan_response(...)` 不依赖 FastAPI，可被单元测试、命令行、Kafka Consumer 或未来 Java 调用适配器复用。
- `plan.runtimeEvents` 暂时保留，保证调试和兼容；`eventEnvelope` 则作为面向传输协议的正式入口。
- 同步 HTTP snapshot 是智能网关事件化的低风险落地点：不需要先实现长连接，也能让调用方适配 envelope。

当前边界：
- `eventEnvelope` 当前只在 HTTP response helper 中构建，尚未接入真实 WebSocket live 或 Kafka audit 发送。
- `build_plan_response(...)` 返回的是 `asdict(...)` 结果，后续接 FastAPI Pydantic DTO 时需要进一步规范 JSON 字段命名和枚举序列化。
- envelope 与 plan 中仍同时包含事件，存在一定冗余；后续可根据前端兼容情况决定是否只保留 envelope。
- 事件 attributes 仍是自由字典，尚未按事件类型拆分严格 schema。
- 当前 API 层仍是可选 FastAPI 入口，尚未和 Java gateway、鉴权、限流、租户上下文打通。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 32 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，36 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行，`agent_orchestrator.py` 284 行，`contracts.py` 217 行，`api.py` 161 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 设计 WebSocket 订阅请求契约，包含 sessionId、runId、afterSequence、eventTypes、clientId，支撑实时订阅和断线续传。
2. 为 Python API 增加 `/agent/events/replay` 或等价 helper，用 envelope replay 模式按 sequence 回放事件。
3. 将 `AgentRuntimeEvent.attributes` 按事件类型拆成更严格 schema，减少前后端对自由字典的隐式依赖。
4. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并推动 Java 工具注册表下发同构 schema。
5. 增加上下文补齐工作流：当参数标记为 `CAN_FILL_FROM_CONTEXT` 时，优先触发元数据、图谱或向量检索。

## 3.39 Python AI Runtime WebSocket 订阅请求契约与事件筛选（2026-05-18）

本阶段继续推进智能网关实时事件能力，但仍然不急于直接启动 WebSocket 服务。真正商业化实现前，必须先统一“前端如何表达订阅需求、服务端如何按 session/run/request 和 sequence 筛选事件、如何只订阅某些事件类型”。因此 3.39 在事件传输契约中新增订阅请求和订阅计划，并在 `RuntimeEventTransportBuilder` 中实现订阅 replay envelope 构建逻辑。

已完成：
- `domain/event_transport.py` 新增 `RuntimeEventSubscriptionRequest`。
- `RuntimeEventSubscriptionRequest` 字段包含 `clientId`、`sessionId`、`runId`、`requestId`、`afterSequence`、`eventTypes`、`includeSnapshot`。
- `domain/event_transport.py` 新增 `RuntimeEventSubscriptionPlan`，用于表达服务端接受订阅后的通道、ack 策略和扩展属性。
- `RuntimeEventTransportBuilder` 新增 `build_subscription_plan(...)`。
- `build_subscription_plan(...)` 默认使用 WebSocket 通道和 `CLIENT_ACK`，并根据 sessionId/runId/requestId/clientId 生成 channelName。
- `RuntimeEventTransportBuilder` 新增 `build_subscription_replay(...)`。
- `build_subscription_replay(...)` 支持按 `afterSequence`、sessionId、runId、requestId、eventTypes 过滤事件，并返回 WebSocket replay envelope。
- 新增 `_matches_subscription(...)`，集中封装订阅匹配逻辑，避免后续 WebSocket 服务和 replay 服务重复实现筛选规则。
- `domain/__init__.py` 导出 `RuntimeEventSubscriptionRequest` 与 `RuntimeEventSubscriptionPlan`。
- 更新 `test_runtime_event_transport.py`，覆盖订阅计划 channelName/clientId、按 sequence 与 eventTypes replay、按 runId 筛选 replay。

设计意义：
- 前端实时订阅不再是“打开连接后全量推所有事件”，而是可以明确订阅某个 session、run 或 request。
- `afterSequence` 让断线续传语义进入协议层，前端可以从最后确认的 sequence 后继续接收事件。
- `eventTypes` 让前端或审计工具可以只订阅关键事件，例如审批等待、参数缺失、错误事件，降低高频事件噪声。
- `RuntimeEventSubscriptionPlan` 为后续加入鉴权、限流、最大回放窗口、租户隔离、订阅配额预留服务端决策位置。
- 当前仍只做协议和筛选，不绑定具体 WebSocket 框架，后续 Java gateway 或 Python API 都可以按同一契约实现。

当前边界：
- 当前没有真正的 WebSocket server、连接管理、心跳、断线重连和客户端 ack 存储。
- `includeSnapshot` 字段已进入请求契约，但当前服务尚未实现“订阅建立时先推 snapshot 再推 live”的组合流程。
- 事件 replay 当前仍基于内存事件集合，生产环境需要接事件存储、Kafka offset 或审计表。
- 订阅鉴权、租户隔离、eventTypes 白名单、最大 replay 窗口尚未实现。
- 如果同时传 sessionId、runId、requestId，当前语义是全部条件都必须匹配；后续文档和前端 SDK 需要明确这一点。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 32 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，39 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行，`agent_orchestrator.py` 284 行，`runtime_event_transport.py` 226 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 为 Python API 增加 `/agent/events/replay` 或等价 helper，用订阅请求构建 replay envelope，先打通同步 replay 查询。
2. 设计 WebSocket 服务端连接生命周期：subscribe、ack、heartbeat、unsubscribe、reconnect、replay。
3. 将 `AgentRuntimeEvent.attributes` 按事件类型拆成更严格 schema，减少前后端对自由字典的隐式依赖。
4. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并推动 Java 工具注册表下发同构 schema。
5. 增加上下文补齐工作流：当参数标记为 `CAN_FILL_FROM_CONTEXT` 时，优先触发元数据、图谱或向量检索。

## 3.40 Python AI Runtime 同步事件 Replay API Helper（2026-05-18）

本阶段把 3.39 的订阅请求契约接入 Python API 层，先打通同步 replay 查询能力。真正的 WebSocket 断线续传需要服务端持久化事件并保存客户端 ack，但在实现长连接之前，先提供 `build_event_replay_response(...)` 可以让调用方用相同订阅请求和 envelope 协议验证“从 afterSequence 之后回放哪些事件”。这一步是实时网关落地前的低风险过渡层。

已完成：
- `api.py` 引入 `RuntimeEventSubscriptionRequest`、`AgentRuntimeEvent`、`AgentRuntimeEventType`、`AgentRuntimeEventSeverity`。
- 新增 `build_event_replay_response(subscription_request, events, event_transport_builder=None)`。
- `build_event_replay_response(...)` 复用 `RuntimeEventTransportBuilder.build_subscription_replay(...)`，返回 `eventEnvelope`。
- FastAPI 新增 `/agent/events/replay` 路由雏形。
- `/agent/events/replay` 当前接收 `subscription` 与 `events` 两部分 payload。
- 新增 `_subscription_request_from_payload(...)`，兼容 camelCase 与 snake_case 字段，并转换 eventTypes。
- 新增 `_runtime_event_from_payload(...)`，把 API payload 转换为 `AgentRuntimeEvent`。
- 更新 `test_api_bootstrap.py`，覆盖 `build_event_replay_response(...)` 按 sessionId、afterSequence、eventTypes 回放事件。

设计意义：
- 同步 HTTP replay 能力先于 WebSocket server 落地，方便前端、Java 网关和测试工具先适配 replay envelope。
- `/agent/events/replay` 与未来 WebSocket 重连使用同一 `RuntimeEventSubscriptionRequest`，避免同步查询和实时订阅协议漂移。
- API 层兼容 camelCase 与 snake_case，降低前端 TypeScript 与 Python dataclass 之间的字段命名摩擦。
- 当前路由允许调用方传入 events，是为了在事件存储未落地时先验证协议；生产环境应改为服务端按 session/run 从事件存储加载。
- replay helper 不依赖 FastAPI，可被单元测试、命令行、Java 调用适配器或未来 Kafka Consumer 复用。

当前边界：
- `/agent/events/replay` 当前不会从 Redis/MySQL/Kafka/审计表读取事件，只筛选调用方传入的事件集合。
- `_runtime_event_from_payload(...)` 当前不解析 createdAt，审计时间线回放后续需要补 ISO datetime 解析。
- API payload 解析仍是轻量 dict 适配，尚未引入 Pydantic DTO、字段校验、错误码和权限校验。
- replay 查询尚未限制最大回放窗口、事件数量、租户边界和 eventTypes 白名单。
- 当前仍未实现 WebSocket subscribe/ack/heartbeat/unsubscribe/reconnect 生命周期。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 32 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，40 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行，`agent_orchestrator.py` 284 行，`api.py` 231 行，`runtime_event_transport.py` 226 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 设计 WebSocket 服务端连接生命周期：subscribe、ack、heartbeat、unsubscribe、reconnect、replay。
2. 为 replay API 增加事件存储抽象，例如 `RuntimeEventStore`，先提供内存实现，再对接 Redis/Kafka/MySQL。
3. 将 `AgentRuntimeEvent.attributes` 按事件类型拆成更严格 schema，减少前后端对自由字典的隐式依赖。
4. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并推动 Java 工具注册表下发同构 schema。
5. 增加上下文补齐工作流：当参数标记为 `CAN_FILL_FROM_CONTEXT` 时，优先触发元数据、图谱或向量检索。

## 3.41 Python AI Runtime RuntimeEventStore 抽象与内存事件回放（2026-05-23）

本阶段补齐 3.40 的关键边界：replay API 不应长期依赖调用方把 events 原样传回来，而应逐步由服务端按 session/run/request 保存和查询事件。3.41 新增 `RuntimeEventStore` 协议和 `InMemoryRuntimeEventStore`，并让 API 层可以在生成 plan response 时写入事件、在 replay response 时从 store 查询事件。当前仍是内存实现，但接口已经为 Redis Stream、Kafka、MySQL 审计表等生产存储预留替换点。

已完成：
- 新增 `services/runtime_event_store.py`。
- 新增 `RuntimeEventStore` 协议，定义 `append_many(...)` 与 `replay(...)`。
- 新增 `InMemoryRuntimeEventStore`，支持内存追加、订阅条件 replay、容量上限裁剪和 snapshot 调试。
- `InMemoryRuntimeEventStore` 使用 `RLock` 做轻量线程保护，避免本地 API 并发时读写列表出现明显竞态。
- `InMemoryRuntimeEventStore.replay(...)` 支持按 sessionId、runId、requestId、afterSequence、eventTypes 筛选事件。
- `services/__init__.py` 导出 `InMemoryRuntimeEventStore`。
- `build_plan_response(...)` 新增可选 `event_store` 参数；传入 store 时会把 `plan.runtimeEvents` 写入服务端事件存储。
- `build_event_replay_response(...)` 新增可选 `event_store` 参数；传入 store 时优先从 store 查询 replay 事件。
- FastAPI `create_app()` 初始化一个 `InMemoryRuntimeEventStore`，`/agent/plans` 会写入该 store。
- `/agent/events/replay` 在 payload 未传 events 时，会从内存 store 查询事件；如果调用方显式传 events，则保持 3.40 的兼容路径。
- 新增 `test_runtime_event_store.py`，覆盖按 session/sequence/eventType replay，以及容量超限时裁剪最早事件。
- 更新 `test_api_bootstrap.py`，验证 `build_plan_response(..., event_store=store)` 写入事件后，可以通过 `build_event_replay_response(..., event_store=store)` 回放。

设计意义：
- replay 能力从“客户端回传事件再筛选”升级为“服务端存储事件再回放”的架构雏形。
- `RuntimeEventStore` 让事件存储成为可替换组件，避免 API 层直接依赖内存列表、Redis、Kafka 或 MySQL 的某一种实现。
- 内存实现为本地开发和单元测试提供无依赖闭环，后续生产环境可以替换为 Redis Stream、Kafka、MySQL/ClickHouse 或对象存储归档。
- `maxEvents` 容量裁剪让开发环境不会无限增长内存，同时在注释中明确生产环境应改为 TTL、冷热分层或租户配额。
- API 层同时支持 store replay 与显式 events replay，保证 3.40 已有协议兼容。

当前边界：
- `InMemoryRuntimeEventStore` 只适合本地开发和单进程测试，进程重启会丢失事件，多实例之间不共享。
- 事件存储尚未实现 TTL、租户配额、分页、批量删除、审计归档和持久化索引。
- replay 查询尚未做权限校验、租户隔离强校验、最大回放窗口和 eventTypes 白名单。
- store 当前没有独立错误类型，后续接 Redis/Kafka/MySQL 时需要区分存储不可用、超时、权限不足和数据过期。
- Java agent-runtime 还没有消费这些 Python runtime event，也没有把 runId 与 Java 审计/审批记录打通。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 34 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，43 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `hybrid_context_builder.py` 358 行，`agent_orchestrator.py` 284 行，`api.py` 242 行，`runtime_event_transport.py` 226 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 设计 WebSocket 服务端连接生命周期：subscribe、ack、heartbeat、unsubscribe、reconnect、replay。
2. 为 `RuntimeEventStore` 增加 Redis Stream 或 Kafka 适配器设计文档/接口预留，明确 offset、TTL、分区键和幂等键。
3. 将 `AgentRuntimeEvent.attributes` 按事件类型拆成更严格 schema，减少前后端对自由字典的隐式依赖。
4. 扩展默认工具 schema，加入 `resolution`、`required`、`sensitive`、`approvalReason` 等元数据，并推动 Java 工具注册表下发同构 schema。
5. 增加上下文补齐工作流：当参数标记为 `CAN_FILL_FROM_CONTEXT` 时，优先触发元数据、图谱或向量检索。

## 3.46 Python AI Runtime 实时事件 ack checkpoint 恢复（2026-05-23）

本阶段继续把实时事件链路往“可恢复”推进。3.45 已经把控制消息拆成 WebSocket 帧，但 ack 仍然只停留在进程内会话状态中，一旦服务重启，前端必须重新从头对接。3.46 新增 checkpoint 存储抽象，把订阅的最后确认序号、连接状态、心跳时间和关闭原因保存下来，并让 `reconnect` 能在新的会话管理器里恢复订阅。

已完成：
- 新增 `services/runtime_event_checkpoint_store.py`。
- 新增 `RuntimeEventSubscriptionCheckpoint`，作为轻量订阅恢复凭据。
- 新增 `RuntimeEventCheckpointStore` 协议，定义 `save/load/delete`。
- 新增 `InMemoryRuntimeEventCheckpointStore`，为本地开发和单元测试提供 checkpoint 存储闭环。
- `RuntimeEventSessionManager` 新增 `checkpoint_store` 依赖。
- `subscribe/ack/heartbeat/reconnect/mark_stale_sessions/unsubscribe` 都会把最新 snapshot 持久化到 checkpoint store。
- `RuntimeEventSessionManager._restore_record(...)` 支持从 checkpoint 恢复会话，并重新构建订阅计划和 replay envelope。
- `snapshot(subscription_id)` 和 `_require_record(...)` 在本地内存没有记录时，也可以回查 checkpoint store 并恢复会话记录。
- `api.py` 的 `create_app()` 初始化了 `InMemoryRuntimeEventCheckpointStore`，让同步 API、控制入口和 WebSocket 雏形共享同一份恢复状态。
- 新增 `test_runtime_event_checkpoint_store.py`，验证新的会话管理器在重启后仍能通过同一个 `subscriptionId` 继续 reconnect，并按最后 ack 回放后续事件。

设计意义：
- ack 不再只是“客户端知道自己收到哪一步”，而是可以作为服务端恢复依据保存下来。
- `RuntimeEventCheckpointStore` 为后续 Redis hash、数据库 checkpoint 表或多实例网关共享状态预留了明确接口。
- `reconnect` 的恢复路径变得现实：即便进程重启，仍可从 checkpoint 恢复最重要的订阅元数据并继续回放。
- 控制消息、会话状态机、WebSocket 帧编排和 checkpoint 恢复现在形成了一条完整的最小实时链路。

当前边界：
- 当前 checkpoint 仍然是内存实现，进程重启后依旧会丢失；这里只是把接口和恢复逻辑固定下来。
- `RuntimeEventSubscriptionCheckpoint` 目前保存的是恢复所需最小字段，后续如果要做更强审计，还可增加最后访问 IP、客户端版本或来源渠道。
- `reconnect` 恢复路径依赖客户端仍然持有 subscriptionId；如果客户端连 subscriptionId 都丢失，就需要另外的恢复入口。
- 尚未实现真正的 Redis/数据库持久化适配器，生产化仍需后续替换。
- live push、慢客户端背压和异步事件追加仍未落地。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 43 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，57 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `api.py` 378 行、`hybrid_context_builder.py` 358 行、`runtime_event_session.py` 334 行、`agent_orchestrator.py` 284 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 把 checkpoint 存储替换为 Redis 或数据库持久化实现，验证多实例恢复路径。
2. 继续推进 live push 事件出口，让事件从 `RuntimeEventRecorder`/store 进入 WebSocket 帧。
3. 为控制消息和 WebSocket 帧补更细的错误码和前端约定，继续提高协议可操作性。
4. 在实时事件最小闭环真正稳定后，切换到模型 provider 健壮性或工具 schema 治理，继续保持项目整体推进。

## 3.47 Python AI Runtime Redis checkpoint 适配器与 live push 事件出口（2026-05-23）

本阶段继续把实时事件链路往更接近商用产品的形态推进。前一阶段已经把 checkpoint、WebSocket 帧编排和会话恢复补齐，但 checkpoint 仍然只是内存实现，live push 也还只是调度层。3.47 在此基础上增加 Redis 风格 checkpoint 适配器，并把实时事件 live push 调度器接到 `/agent/plans` 与 WebSocket 消息循环中，形成“新事件生成 -> 进入 hub -> 推送到活跃订阅队列 -> WebSocket drain”的完整出口。

已完成：
- `services/runtime_event_checkpoint_store.py` 新增 `RedisRuntimeEventCheckpointStore`。
- `RedisRuntimeEventCheckpointStore` 只依赖 Redis-like 客户端的 `get/set/delete` 接口，不强绑定具体第三方包，便于本地 fake client 测试和生产客户端接入。
- `RedisRuntimeEventCheckpointStore` 支持 `ttl_seconds`，方便后续把 checkpoint 当作可过期恢复凭据而不是永久状态。
- Redis checkpoint 采用 JSON 序列化，显式保存 request、state、lastAckSequence、时间线和 closeReason，便于跨语言兼容。
- 新增 `services/runtime_event_live_push.py`。
- 新增 `RuntimeEventLivePushHub`，负责把新产生的事件按订阅范围分发到活跃连接的出站队列。
- `RuntimeEventLivePushHub.publish(...)` 会根据会话状态、tenant/project/session/run/request 范围和 lastAckSequence 筛选真正需要 live push 的事件。
- `RuntimeEventLivePushHub.drain_payloads(...)` 为 WebSocket handler 提供统一的可发送 payload 出口。
- `api.py` 的 `build_plan_response(...)` 新增可选 `live_push_hub` 参数，规划事件生成后会同步进入 live push 通路。
- `create_app()` 初始化 `InMemoryRuntimeEventCheckpointStore` 与 `RuntimeEventLivePushHub`，让同步 API、WebSocket 雏形和恢复状态共用同一条实时链路。
- `/agent/events/ws` 现在具备后台轮询出站队列的雏形，可以把 live push hub 中的事件帧周期性发送给当前连接。
- 新增 `test_runtime_event_live_push.py`，验证匹配订阅可以收到 live envelope，非匹配订阅不会收到事件。
- 新增 `test_runtime_event_redis_checkpoint_store.py`，验证 fake Redis client 下的 checkpoint 保存、恢复和 TTL 参数行为。

设计意义：
- 实时事件链路从“协议可表达”继续走向“事件真的能从生成点送到连接出口”。
- Redis checkpoint 适配器把内存恢复逻辑向生产化接口推进了一步，后续可以平滑接 Redis/Redis Cluster。
- live push hub 作为统一事件出口，让 HTTP 规划请求、未来 Kafka Consumer 或其他异步任务都能复用同一条分发路径。
- WebSocket 入口终于不只是“能处理控制消息”，而是具备了把新事件持续推给连接的调度骨架。

当前边界：
- `RedisRuntimeEventCheckpointStore` 仍然是适配器抽象，需要真实 Redis client 才能进入生产；当前测试只验证 fake client 路径。
- live push 目前仍是轮询式雏形，尚未实现真正的高性能 push、背压控制、订阅配额和多连接广播优化。
- `/agent/events/ws` 仍然不是完整生产级 WebSocket 服务，认证握手、慢客户端剔除、流控和异常恢复还需后续补齐。
- live push hub 当前依赖计划/请求级事件进入；真正异步节点回调、Kafka 消费或跨服务事件还需要后续接入。
- ack checkpoint 目前已经可以恢复，但还没有换成真正的 Redis/数据库持久化实现。

验证：
- 已执行不写字节码的 AST 语法检查，`python-ai-runtime/src` 与 `python-ai-runtime/tests` 下 46 个 Python 文件全部通过。
- 已执行 `python -m unittest discover -s python-ai-runtime/tests`，60 个测试用例全部通过。
- 已执行 Python 文件行数扫描，当前最高 `api.py` 428 行、`hybrid_context_builder.py` 358 行、`runtime_event_session.py` 334 行，均低于 500 行约束。
- 本阶段仅修改 Python Runtime 和文档，未修改 Java 源码，因此未重新执行 Maven 编译。

下一步建议：
1. 把 checkpoint 持久化替换成真实 Redis 或数据库实现，验证服务重启后的订阅恢复。
2. 把 live push hub 进一步接到真正的异步事件源，例如任务回调、Kafka Consumer 或 runtime 事件总线。
3. 为 WebSocket 增加更明确的错误帧、心跳与慢客户端治理策略。
4. 在实时链路达到生产可用边界后，及时切回模型 provider、tool schema 或其他核心模块，避免单一路线长期霸占主线。

## 3.48 Java Backend 当前收口与下一阶段路线（2026-05-23）

本阶段的重点不应该再是“无限放大单一模块”，而是先把已经推进的基础能力收口，再把主线切回整个产品版图。当前 Java 侧已经完成了 `platform-common` 的兼容性修正，并在 `data-sync` 内抽出了更清晰的查询支持与数据权限支持，但 `data-sync` 里仍存在一批历史遗留的语法/编码不规范文件，说明这个模块还没到可以放心停手的状态。

已确认的现状：
- `platform-common` 已修复 Java 8 兼容问题，避免基础公共包阻塞整体编译。
- `data-sync` 已开始拆分查询与数据范围支持，整体结构比之前更清楚。
- Maven 编译现在能推进到 `data-sync`，说明前面的公共层问题已经被跨过去了。
- `data-sync` 的剩余失败点主要来自旧 DTO/mapper 等文件里的历史格式问题，不是新抽出的支持类本身。
- 当前 Java 代码库里仍然同时存在 `data-quality`、`datasource-management`、`permission-admin`、`task-management`、`gateway` 等多个模块，产品不应该只盯着 `data-sync` 一条线。
- 当前本机 Maven 运行环境仍是 Java 8，而项目目标是 Java 21；所以任何 record、文本块和现代集合 API 的编译结果都不可靠，必须先把本地工具链切到 JDK 21，再做最终验证。
- 已经使用 `C:\Users\Cui\.jdks\temurin-21.0.10` 重新执行 `data-sync` 编译与测试，结果全部成功，说明该模块当前已经回到“可稳定演进”的基础状态。
- 进一步使用同一 JDK 21 工具链执行了全量 Maven `compile` 与 `test -DskipITs`，`gateway`、`permission-admin`、`task-management`、`datasource-management`、`data-sync`、`data-quality`、`agent-runtime`、`observability` 都已通过基础验证。

下一步推荐路线：
1. 停止继续无限打磨 `data-sync`，把它作为“已收口模块”保留，后续只做必要修复和新增需求。
2. 立刻切换重心到 `permission-admin` 与 `gateway` 的联动，补齐权限判定、路由策略、项目成员关系和网关决策闭环。
3. 再往后推进 `task-management`、`data-quality`、`datasource-management` 的联动，让任务、质检、同步三条业务链真正串起来，而不是只做单点 CRUD。
4. 基础业务层稳定后，尽快把更多精力放回 agent / 模型 / 智能网关层，避免基础业务层长期占满主线。

优化建议：
- 每个模块都要先做“能稳定运行的最小闭环”，再做增强，不要一开始就把所有场景摊平。
- 所有 Impl、Controller、DTO、Support 类继续坚持拆分，单文件尽量控制在 500 行以内。
- 注释继续保持中文，而且要写清楚“为什么这么设计”“这个方法解决什么场景”“有哪些边界和后续扩展点”。
- 遇到历史遗留坏文件时，优先做批量修复和结构整理，不要反复在一个点上做局部精修。
- 每完成一个子阶段，就把 `current-repo-state.md` 和本文件同步一次，保证长任务不会失忆。

## 3.49 permission-admin 与 gateway 项目成员授权缓存失效闭环（2026-05-23）

本阶段开始按 3.48 的路线切入 `permission-admin` 与 `gateway` 联动，不再继续无限打磨单一业务模块。当前补齐的是一个真实商业系统很容易踩坑的链路：`PROJECT` 数据范围不仅依赖路由策略，也依赖项目成员授权集合。如果项目成员关系变化后 gateway 仍然复用旧的 `authorizedProjectIds` 判定缓存，就可能导致用户看到过期项目范围。

已完成：
- 扩展 `PermissionPolicyChangedEvent`，新增项目成员授权变更相关字段，例如 membershipId、memberActorId、projectId、workspaceId、projectRole、grantSource、membershipEnabled。
- 新增 `PermissionProjectMembershipChangedEventPublisher`，项目成员授权新增、更新、启用、禁用后会写入 permission outbox。
- `PermissionProjectMembershipServiceImpl` 在成功变更项目成员授权后发布 PROJECT_MEMBERSHIP_* 事件。
- `GatewayAuthorizationDecisionCache` 新增 actor 级缓存失效能力，可以按 tenantId + actorId 精准清理缓存。
- `GatewayPermissionPolicyChangedEventConsumer` 识别 PROJECT_MEMBERSHIP_* 事件，优先按成员 actor 清理缓存，字段缺失时再退化为租户或全量清理。
- `gateway` 增加测试依赖和 `GatewayAuthorizationDecisionCacheTest`，验证项目成员变化只清理目标 actor 缓存，不误伤同租户其他用户。
- `PermissionProjectMembershipServiceImplTest` 更新为验证项目成员变更事件发布。

设计意义：
- 网关授权缓存不再只对“路由策略变化”敏感，也能对“项目成员授权变化”敏感。
- `PROJECT` 数据范围的 `authorizedProjectIds` 从 permission-admin 到 gateway 再到业务模块形成了更完整的一致性闭环。
- actor 级失效比租户级失效更适合高并发租户，减少无谓回源 permission-admin 的压力。
- 项目成员事件复用 permission outbox，保持数据库事务与消息投递之间的一致性。

验证：
- 已执行 `mvn -pl gateway,permission-admin -am test -DskipITs`，相关模块测试通过。
- 已执行全量 `mvn test -DskipITs`，整个 Java 主链测试通过。
- 测试过程中仅出现 Mockito 动态 agent 的 JDK 未来兼容性 warning，不影响当前结果。

下一步建议：
1. 继续推进 `permission-admin` 与 `gateway` 的联动，补“策略变更事件消费后的观测指标”和“缓存失效管理端点权限保护”。
2. 开始把权限结果与 `task-management`、`data-quality` 的实际业务查询进一步串起来，验证 PROJECT/TENANT/SELF 范围是否真正影响列表和操作。
3. 为 gateway 授权链路增加更接近生产的失败策略测试，例如 permission-admin 超时、返回非法响应、影子模式拒绝等。
4. 在权限主链稳定后，切到任务/质检/数据源联动，继续保持项目整体推进，而不是只停留在权限域。

## 3.50 gateway 授权失败策略测试闭环（2026-05-23）

本阶段延续 3.49 的 `permission-admin` 与 `gateway` 主链联动，但没有继续新增权限业务表，而是先补齐生产级网关最容易出事故的策略测试。企业产品里，权限中心不可用、策略误配、灰度观察和强制拦截的行为必须非常明确；否则上线后会在“为了可用性放行”和“为了安全性拒绝”之间摇摆，导致事故处理没有依据。

已完成：
- 新增 `GatewayAuthorizationFilterTest`，为网关授权过滤器建立直接单元测试。
- 覆盖 permission-admin 允许访问时，gateway 放行并透传数据范围 Header 的场景。
- 覆盖强制模式下 permission-admin 明确拒绝时，gateway 返回 403 且不继续调用下游 chain 的场景。
- 覆盖影子模式下 permission-admin 拒绝但 gateway 继续放行的灰度观察场景。
- 覆盖 permission-admin 异常时 fail-open 继续放行的场景。
- 覆盖 permission-admin 异常时 fail-closed 返回 403 的场景。
- 覆盖 `/actuator/**` 等公开路径绕过 permission-admin 的场景。
- 测试文件保持详细中文注释，说明每个策略背后的业务风险、适用场景和商业化意义。

设计意义：
- `gateway` 授权不再只靠代码阅读判断行为，而是有测试固定 fail-open、fail-closed、shadow mode、forced deny 的策略语义。
- 数据范围 Header 透传进入回归测试，后续重构过滤器或缓存时，不容易误删 `X-DataSmart-Data-Scope-*` 与 `X-DataSmart-Authorized-Project-Ids`。
- 公开路径绕过被测试覆盖，保证健康检查和网关契约说明不会因为权限中心故障一起不可用。
- 这一步没有扩大权限域功能面，而是提高已有主链的可靠性，符合“不要无限堆局部功能，要把关键闭环做稳”的推进原则。

验证：
- 已执行 `mvn -pl gateway -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"`，gateway 相关测试通过。
- 已执行全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"`，10 个 Maven 模块全部通过。
- 新增测试文件 265 行，低于 500 行控制线。
- 测试日志中出现的 permission-admin 异常栈是 fail-closed 用例刻意模拟并由过滤器记录的 error 日志，不代表测试失败。
- 测试过程中仍出现 Mockito 动态 agent 的 JDK 未来兼容性 warning，不影响当前结果，但后续可统一在构建配置中处理。

下一步推荐路线：
1. 优先把权限数据范围落到 `task-management` 与 `data-quality` 的真实查询和操作里，验证 PROJECT/TENANT/SELF 不只是网关 Header。
2. 给 gateway 授权缓存补可观测指标，例如命中、未命中、actor 级失效、事件消费失败和 permission-admin 调用耗时。
3. 为 `PermissionAdminDecisionClient` 增加更细粒度客户端测试或拆出响应解包支持类，覆盖非法 envelope、空 data、非 0 code 等返回形态。
4. 权限主链再完成一个落地闭环后，应切到任务、质检、数据源联动，避免权限域继续长期占据全部主线。

## 3.51 task-management 项目范围落地与模块联动建议（2026-05-23）

本阶段把任务主链从“租户 + 负责人”继续推进到“项目授权范围 + 任务归属校验”的产品形态，避免业务模块只吃到网关 Header 却不真正理解项目可见性。这一步是为了让 `PROJECT` 范围在任务域里真正生效，而不是停留在鉴权层面的表面透传。

已完成：
- `task-management` 已消费 gateway 透传的 `X-DataSmart-Authorized-Project-Ids`。
- `TaskActorContext` 已纳入项目范围相关字段，能显式判断是否启用了 PROJECT 收口。
- `TaskDataScopeSupport` 已完成创建、列表、队列、任务归属校验四类收口逻辑。
- `TaskController`、`TaskQueueOperationsController`、`TaskAdminOperationsController` 已统一通过请求上下文获取器取值，减少重复解析。
- 新增的项目范围测试已经为后续继续演进提供回归保护。

这一步的产品意义：
- 让任务列表、队列和强控动作都遵守同一套项目边界，而不是不同接口各写各的判断。
- 让授权项目集合为空时的行为明确化，避免“默认展示全部”这种商用系统里很危险的退化。
- 让 task-management 成为其他业务模块复制数据范围模式的参考模板。

下一步推荐路线：
1. 横向推进 `data-quality`，把相同的数据范围语义接到质量规则、检测记录和报告查询里。
2. 再推进 `data-sync`，把项目授权、模板可见性、同步任务操作统一到同一套范围语义上。
3. 给 `gateway` 补授权链路的观测指标和异常治理，保证范围透传不是“黑盒成功”。
4. 继续收敛过长的 `ServiceImpl`，但以模块联动为优先，不要让结构优化吞掉产品推进节奏。

## 3.52 gateway 授权链路可观测性闭环启动（2026-05-23）

这一步不是再往 gateway 里塞一个“技术装饰”，而是让入口层能被稳定度量。对于真正商用的平台，授权链路一旦出现缓存击穿、远程授权变慢、拒绝率异常升高，必须能立刻从指标里看出来。

已完成：
- 新增 `GatewayAuthorizationMetrics`，把公开路径绕过、缓存命中、缓存未命中、最终决策结果和远程授权耗时写成指标。
- `GatewayAuthorizationFilter` 已开始接入该指标记录器。
- `gateway` 补上 `spring-boot-starter-actuator`，让管理端点和指标链路具备真正落地条件。
- 新增 `GatewayAuthorizationMetricsTest`，验证指标在关键事件上能正确记账。

产品意义：
- 这一步让 gateway 从“只负责拦截请求”进化到“还能解释自己为什么这么拦、拦得快不快”。
- 后续可以继续把这些指标接到面板和告警上，形成真正的入口层治理能力。

下一步推荐路线：
1. 先单独收敛 gateway 和 task-management 当前残留的 Maven 解析异常，重新跑通构建。
2. 把 gateway 指标接到实际监控面板和告警规则，别让它只停留在代码里。
3. 入口层稳定后，切回 `data-quality` 或 `data-sync`，继续推进业务模块联动，保持项目整体平衡。

## 3.53 data-quality PROJECT 范围 Controller 回归闭环（2026-05-23）

本阶段把 `data-quality` 的项目范围能力从“已有支撑逻辑”推进到“Controller 层有回归保护”。这一步看起来不像新增大功能，但对商业化产品很关键：真实系统里权限漏洞经常不是出在某个 support 类写错，而是出在新增路由、报表查询或详情接口没有把范围上下文继续传下去。

已完成：
- 修正 `DataQualityControllerProjectScopeTest` 与 `QualityReportController.pageReports(...)` 当前方法签名不一致的问题。
- 清理测试中的无效导入，降低后续维护和学习阅读成本。
- 覆盖 PROJECT 范围下授权项目集合为空时，质量规则列表直接返回空页。
- 覆盖通过规则 ID 查询详情时，如果规则属于未授权项目，必须拒绝访问。
- 覆盖质量报告查询时，Controller 必须把 `QualityProjectVisibility` 传递到 service 层。

产品意义：
- `data-quality` 已开始跟 `task-management` 使用同一类数据范围语义，项目边界不再只停留在 gateway Header。
- 质量规则、报告、异常样本未来都会成为治理控制台和 AI 分析上下文的重要入口，因此必须提前防止跨项目读取。
- Controller 回归测试把“列表空收口、详情强拒绝、报表下传范围”三个典型场景固定下来，后续拆分路由时不容易回退。

验证：
- 已执行 `mvn -pl data-quality -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"`，`data-quality` 相关 9 个测试通过。
- 已执行全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"`，10 个 Maven reactor 模块全部通过。
- 测试期间仍出现 Mockito 动态 agent 的未来兼容性 warning，当前不阻塞，但建议后续作为构建治理项处理。

下一步推荐路线：
1. 优先推进 `data-sync` PROJECT 范围落地，把同步模板、同步任务、任务详情和敏感操作接入同一套授权项目语义。
2. 再补 `data-quality` 报告明细、异常样本、异常聚合和清洗任务入口的范围回归测试，让质检域形成更完整的权限闭环。
3. Java 主链完成这一轮业务范围闭环后，应把更多注意力切到 `agent-runtime`、Python AI Runtime、智能网关、模型 provider 和工具 schema，防止项目继续只在 Java CRUD 与权限细节里打转。

## 3.54 data-sync PROJECT 范围写入安全闭环（2026-05-23）

本阶段按 3.53 的推荐路线切到 `data-sync`，重点不是继续扩展一个庞大的同步执行器，而是补一条更靠近商业安全边界的链路：PROJECT 范围下，用户创建同步模板时必须写入授权项目，不能把模板写到未授权项目，也不能创建无项目归属模板来绕过项目治理。

已完成：
- `SyncDataScopeSupport` 新增 `validateProjectWritable(...)`，统一表达写入类动作的项目范围校验。
- `DataSyncServiceImpl.createTemplate(...)` 在数据库 insert 前调用写入校验。
- PROJECT 范围下 `projectId` 为空会被拒绝，避免同步模板脱离项目归属。
- PROJECT 范围下 `projectId` 不在 `authorizedProjectIds` 中会被拒绝，避免越权创建同步模板。
- 删除 `DataSyncServiceImpl` 内部重复的私有项目过滤 helper，继续把范围语义收口到 support 组件。
- `SyncDataScopeSupportTest` 补充写入安全测试。
- 新增 `DataSyncServiceImplProjectScopeTest`，验证越权写入不会触发 `templateMapper.insert(...)`，授权写入会正常落库并写审计。

产品意义：
- 同步模板是数据移动链路的起点，后续可能触发全量同步、增量同步、CDC、回放、补数、离线导出等动作，因此它比普通查询更需要前置写入校验。
- 这一步让 `data-sync` 从“读范围安全”推进到“写范围安全”，更接近真实商用系统的权限边界。
- 校验放在 Service 层而不只放 Controller 层，可以覆盖未来 Agent 工具调用、批量导入、内部调度和管理端入口。

验证：
- 已执行 `mvn -pl data-sync -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"`，`data-sync` 18 个测试通过。
- 已执行全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"`，10 个 Maven reactor 模块全部通过。
- 重点文件行数仍满足 500 行约束：`DataSyncServiceImpl.java` 449 行，`SyncDataScopeSupport.java` 369 行，新增测试 137 行。

下一步推荐路线：
1. Java 主链再做 1 个小闭环即可暂时收口，例如补 `data-sync` 执行记录/checkpoint/错误样本的 PROJECT 范围回归测试，或者补跨模块 Header 契约测试。
2. 随后应切到 `agent-runtime`、Python AI Runtime、智能网关和模型 provider 抽象，开始把项目从“业务治理底座”推进到“智能治理平台”。
3. 后续性能需求需要主动补齐：同步模板和任务列表的分页上限、项目授权集合过大时的 SQL 方案、同步执行队列高并发认领、checkpoint 写入吞吐和租约恢复扫描频率，都应逐步进入设计和测试。

## 3.55 data-sync 执行追踪 PROJECT 范围闭环（2026-05-23）

本阶段兑现 3.54 的“再做 1 个小闭环即可暂时收口”路线，把 `data-sync` 的 PROJECT 范围继续覆盖到执行追踪数据。执行记录、checkpoint、错误样本和审计流水虽然是只读查询，但它们会暴露任务运行轨迹、恢复边界、失败样本、traceId、错误摘要和操作者信息，因此在商业产品中同样需要严格项目边界。

已完成：
- `pageExecutions(...)` 在横向查询时按 `SyncExecution.projectId` 做 PROJECT 范围收口。
- `pageCheckpoints(...)` 在横向查询时按 `SyncCheckpoint.projectId` 做 PROJECT 范围收口。
- `pageErrorSamples(...)` 在横向查询时按 `SyncErrorSample.projectId` 做 PROJECT 范围收口。
- `pageAuditRecords(...)` 在横向查询时按 `SyncAuditRecord.projectId` 做 PROJECT 范围收口。
- 新增 `resolveQueryVisibility(...)`，统一处理“任务详情页带 taskId 查询”和“运营台不带 taskId 横向查询”两种形态。
- 扩展 `DataSyncServiceImplProjectScopeTest`，验证未授权 taskId 会在查询子表前被拒绝，覆盖 execution、checkpoint、error sample、audit 四条链路。

产品意义：
- `data-sync` 的权限范围现在覆盖了定义对象、写入入口和运行事实数据，已经具备阶段性收口条件。
- 子表冗余 `projectId` 的设计开始真正发挥价值，后续全局执行历史、错误样本检索和审计导出不必为了权限范围强制 join 任务表。
- 这一步为未来真实执行器、CDC、补数、回放和离线导出打下安全边界，避免高敏感运行数据被当成普通日志随意查询。

验证：
- 已执行 `mvn -pl data-sync -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"`，`data-sync` 22 个测试通过。
- 已执行全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"`，10 个 Maven reactor 模块全部通过。
- 重点文件仍低于 500 行：`DataSyncServiceImpl.java` 465 行，`DataSyncServiceImplProjectScopeTest.java` 279 行。

下一步推荐路线：
1. Java 业务权限链路本阶段先收口，不继续无止境打磨 `data-sync`。
2. 下一步切向 `agent-runtime`、Python AI Runtime、智能网关和模型 provider 抽象，让项目从“治理业务底座”继续走向“智能治理平台”。
3. 智能化主线优先关注工具 schema 治理、Agent 会话状态、模型可替换抽象、事件回放、权限审计和长任务恢复。

## 3.56 前沿 AI Agent 技术雷达纳入长期路线（2026-05-23）

本阶段新增一个项目级目标：DataSmart Govern 不只按当前 PRD 做功能，还要持续跟踪 AI Agent 工具生态的快速演化，并把适合商业数据治理平台的能力逐步融入架构。重点方向包括工具调用协议、Skill、长短期记忆、模型网关、KV Cache / Prefix Cache、智能网关、多智能体协作协议、Agent 可观测性与评测。

已完成：
- 新增 [AI Agent 技术雷达](D:/Desktop/DataSmart-Govern/DataSmartGovernBackend/docs/ai-agent-technology-radar.md)。
- 初版技术雷达覆盖 MCP、A2A、Agent Memory、Skill、模型网关、KV Cache / Prefix Cache。
- 每个趋势都已经映射到 DataSmart 的落地动作，例如工具 schema、MCP-style descriptor、Agent Card、分层记忆、模型路由、cache policy。
- 更新 `datasmart-govern-backend` skill，新增 `Frontier AI Agent Trend Tracking` 规则。

产品意义：
- 项目演进不再只围绕传统数据治理后台，而是开始对齐当前 AI Agent 平台化趋势。
- 以后做 `agent-runtime`、Python AI Runtime、智能网关、模型 provider、工具调用和记忆能力时，必须先关注最新生态变化，避免设计刚完成就落后。
- 趋势跟踪本身被制度化，后续每个里程碑都可以回到技术雷达判断“是否值得吸收，如何商业化落地”。

下一步推荐路线：
1. 优先切入 `agent-runtime` 工具 schema 治理，把工具调用从内部 DTO 升级为可导出、可审计、可审批的工具描述。
2. 同步检查 Python AI Runtime 的 tool planner、parameter validator 和 model router，让它们消费统一 schema。
3. 设计 Agent 记忆分层，将会话、任务事件、质量异常、同步事故、审批历史沉淀为可检索但受权限保护的记忆。
4. 后续再推进模型网关的 provider fallback、budget、latency、cachePolicy、prefix cache 指标预留。

## 3.57 Python AI Runtime 接入 Java MCP-style 工具描述符（2026-05-23）

本阶段把上一节的趋势规划向运行时推进了一步：Java `agent-runtime` 已经提供 MCP-style tool descriptor，Python AI Runtime 现在优先消费这个 descriptor，而不是只依赖旧版 `/agent-runtime/tools` 展示型清单。这样工具调用治理开始形成“Java 控制面定义工具事实，Python 编排层按统一契约规划工具”的闭环。

已完成：
- `ToolDefinition` 扩展治理字段，包含 schema 版本、descriptor 类型、协议提示、工具类型、租户/项目范围、敏感字段、记忆写入策略和缓存策略。
- `JavaAgentToolRegistryClient` 增加 `list_tool_descriptors(...)`，默认访问 `/agent-runtime/tools/descriptors`。
- `load_tool_registry(...)` 优先读取 descriptor；旧客户端或旧环境仍可回退到 `list_tools(...)`，避免版本不一致时阻断运行。
- `ToolParameterValidator` 支持 `CAN_FILL_FROM_CONTEXT`、`SYSTEM_INJECTED`、`DERIVED` 等参数解析策略，减少不必要的用户追问。
- `ToolPlan` 增加 `governance_hints`，把敏感字段、项目范围、记忆策略和缓存范围带到计划结果中，为审批、审计、前端确认页和模型网关治理预留字段。
- 本地默认工具清单也补齐了 descriptor 级别的治理信息，保证不启动 Java 服务时 Python 单测仍能覆盖真实产品语义。

产品意义：
- 工具调用开始从“能调用”升级到“可治理地调用”，这比单纯增加更多工具更重要。
- `cachePolicy` 与 `memoryWritePolicy` 已经进入运行时计划，为后续 prefix cache / KV cache 和 Agent 分层记忆提供了边界信息。
- Python Runtime 不再把工具 schema 当自由字典处理，而是开始消费 Java 控制面下发的强治理契约。
- 这一步仍然克制：暂不实现完整 MCP Server，先把内部工具契约打稳，避免为了追协议热点而牺牲产品闭环。

验证：
- Java 全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，10 个 reactor 模块全部成功。
- Python `python -m compileall src tests` 通过。
- Python `python -m unittest discover -s tests` 通过，63 个测试成功。
- Python 重点文件均低于 500 行，符合当前解耦与文件规模约束。

下一步推荐路线：
1. 继续智能化主线，但不要马上做大而全协议服务；优先落地 Agent 记忆分层的领域契约。
2. 设计 `AgentSkillDescriptor`，把工具依赖、审批策略、记忆策略和示例工作流封装成可发现能力包。
3. 推进模型网关治理雏形，补 provider health、fallback、budget、latency tier、cache policy 与 prefix cache 指标。
4. Java 业务主链保持维护，但不再无止境追加局部权限细节，除非它直接服务 Agent 工具执行安全闭环。

## 3.58 Python AI Runtime Agent 记忆分层与 Skill 能力包契约（2026-05-23）

本阶段继续从前沿 Agent 趋势回到产品落地：不急着实现完整 MCP Server、A2A Server 或真实向量库，而是先建立 Agent 记忆与 Skill 的内部领域契约。原因是记忆和 Skill 会同时影响 Python 编排、Java 控制面、工具审批、智能网关、模型缓存和未来插件市场，必须先把语义和边界定稳。

已完成：
- 新增 `AgentMemoryType`，覆盖短期记忆、语义记忆、情节记忆、程序记忆和资源记忆。
- 新增 `AgentMemoryScope`，区分 session、project、tenant、global，避免记忆能力突破租户/项目隔离。
- 新增 `AgentMemoryPlan`，表达本次请求需要检索哪些记忆、工具结果允许写入哪些记忆、是否需要审批、保留期和隐私说明。
- 新增 `AgentSkillDescriptor`、`AgentSkillSelection`、`AgentSkillPlan`，把 Skill 建模为带工具依赖、权限要求、记忆依赖、风险等级和审批策略的能力包。
- 新增 `AgentMemoryPlanner`，根据治理域、风险标签、上下文和工具计划生成记忆计划。
- 新增 `AgentSkillRegistry`，用治理域、候选工具和关键词选择默认 Skill。
- `AgentOrchestrator` 已接入 `select_skills` 与 `plan_memory` 状态节点，`AgentPlan` 现在返回 `skill_plan` 与 `memory_plan`。
- 默认 Skill 覆盖数据源画像分析、质量规则设计、受控任务创建、权限边界解释。

产品意义：
- Agent 记忆不再被简化为聊天记录，而是开始面向真实数据治理任务沉淀“知识、事件、流程、资源”。
- Skill 不再只是提示词，而是具备权限、工具、记忆、审批和示例工作流的可治理能力单元。
- 敏感、跨范围、导出类意图会让记忆计划收紧到会话范围、缩短保留期并要求审批保护，这符合商业化合规边界。
- 当前仍是轻量实现，但已经为 Chroma、Neo4j、Redis、MinIO、Java Skill 控制面、MCP prompt/resource 和 A2A Agent Card 留出接口空间。

验证：
- Python `python -m compileall src tests` 通过。
- Python `python -m unittest discover -s tests` 通过，66 个测试成功。
- Python 重点文件均低于 500 行，最高 `api.py` 421 行。
- 本阶段未改 Java 源码，因此未重新跑 Maven；上一阶段 Java 全量测试已通过。

下一步推荐路线：
1. 将 Skill 描述契约同步到 Java `agent-runtime`，补 `/agent-runtime/skills/descriptors`，让 Java 控制面成为 Skill 管理事实源。
2. 为 `AgentMemoryPlan` 增加真实检索服务接口雏形，先做内存实现和测试，再接 Chroma/Neo4j/Redis。
3. 推进模型网关治理：provider health、fallback、budget、latency tier、cache policy 与 prefix cache 指标。
4. 保持节奏，不把所有精力都压在 Python 智能层；当内部契约稳定后，再横向回到 Java 控制面和网关联动。

## 3.59 Java agent-runtime Skill descriptor 控制面（2026-05-23）

本阶段把 Python 侧已经形成的 Skill 领域契约同步回 Java `agent-runtime`，让 Java 控制面成为 Skill 治理事实源。这样后续 Python Runtime、智能网关、前端 Skill 市场、审批系统、审计系统和 A2A Agent Card 适配层都可以消费同一份 Java descriptor，而不是各自维护能力包。

已完成：
- 新增 `AgentSkillRegistryProperties`，独立绑定 `datasmart.agent-runtime.skill-registry`，避免继续膨胀 `AgentRuntimeProperties`。
- 新增 `AgentSkillDescriptorView`、`AgentSkillGovernanceDescriptorView`、`AgentSkillMemoryDescriptorView`。
- 新增 `AgentSkillRegistryService`，支持 Skill descriptor 转换、启用过滤、治理域过滤和风险等级过滤。
- 新增 `AgentSkillRegistryController`，提供 `/agent-runtime/skills/descriptors`、`/api/agent/skills/descriptors` 和单个 Skill descriptor 查询。
- `application.yml` 新增 4 个默认 Skill：数据源画像分析、质量规则设计、受控任务创建、权限边界解释。
- 新增 `AgentSkillRegistryServiceTest`，固定 Java Skill descriptor 契约。

产品意义：
- Java 控制面现在不仅能管理工具 descriptor，也能管理 Skill descriptor，智能化能力开始具备统一事实源。
- Skill 与工具保持分层：工具是可调用动作，Skill 是组织工具、记忆、审批和审计的能力包。
- `AGENT_CARD_STYLE` 为未来 A2A Agent Card 适配预留方向，但当前不直接追完整协议，避免过早复杂化。
- 配置类独立拆分，继续遵守单文件 500 行以内和职责解耦要求。

验证：
- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 20 个测试成功。
- 全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，10 个 Maven reactor 模块全部成功。
- agent-runtime Java 文件行数扫描未发现超过 500 行文件。

下一步推荐路线：
1. Python AI Runtime 增加 Java Skill descriptor 客户端，优先拉取 `/agent-runtime/skills/descriptors`，失败后回退本地默认 Skill。
2. 继续推进记忆检索接口雏形，先做内存实现与测试，再选择 Chroma/Neo4j/Redis 接入点。
3. 开始模型网关治理：provider health、fallback、budget、latency tier、cache policy 与 prefix cache 指标。
4. 维持项目整体平衡，智能化主线继续推进，但不要长时间忽略 Java 控制面、gateway、权限与任务底座。

## 3.60 Python AI Runtime Skill descriptor 消费与记忆检索接口雏形（2026-05-24）

本阶段承接 3.59，把 Java `agent-runtime` 的 Skill descriptor 控制面接回 Python AI Runtime，并补齐 Agent 记忆从“规划”到“实际检索报告”的第一步。实现时仍然保持克制：不直接绑定 Chroma、Neo4j 或 Redis，而是先让领域契约、检索接口、租户/项目/会话隔离和编排状态节点稳定下来。

已完成：
- Python AI Runtime 新增 Java Skill descriptor 客户端，支持优先读取 `/agent-runtime/skills/descriptors`，失败后回退本地默认 Skill。
- `build_default_orchestrator(...)` 支持注入远程 Skill registry client，默认编排器可以消费 Java 控制面下发的 Skill。
- 新增记忆检索领域对象：`AgentMemoryRecord`、`AgentMemoryRetrievalResult`、`AgentMemoryRetrievalReport`。
- 新增 `AgentMemoryRetriever` 协议和 `InMemoryAgentMemoryRetriever`，先用可测试的内存实现固定范围隔离和排序语义。
- `AgentOrchestrator` 新增 `retrieve_memory` 状态节点，`AgentPlan` 返回 `memory_retrieval_report`，运行事件新增 `MEMORY_RETRIEVED`。
- 将事件 replay/control/WebSocket payload helper 从 `api.py` 拆到 `api_events.py`，解决 `api.py` 超过 500 行的问题。

产品意义：
- Skill 的“Java 事实源 -> Python 编排消费”链路已经闭合，后续前端 Skill 市场、审批系统和 A2A Agent Card 适配层可以围绕同一份 descriptor 演进。
- 记忆检索开始具备商业化安全边界：检索器先按 tenant/project/session 过滤，再做相关性匹配，避免相似度召回导致跨项目泄漏。
- `memory_retrieval_report` 把实际召回结果、跳过原因和检索器属性返回给调用方，为前端解释、审计回放、召回质量评估和模型上下文注入预留接口。
- 当前内存检索器只是最小实现，但它已经把 Chroma/Neo4j/Redis/MySQL/MinIO 未来各自负责什么留出了清晰替换点。

验证：
- Python `python -m compileall src tests` 通过。
- Python `python -m unittest discover -s tests` 通过，74 个测试成功。
- Python 行数扫描通过，最高 `hybrid_context_builder.py` 398 行、`runtime_event_session.py` 396 行、`api.py` 375 行，均低于 500 行。
- 本阶段未修改 Java 源码，未重复执行 Maven；上一阶段 Java 全量测试已通过。

下一步推荐路线：
1. 进入模型网关治理雏形，优先补 provider health、fallback、budget、latency tier、cache scope、prefix cache 指标契约。
2. 记忆方向下一步只做适配边界和写入审批契约，不建议立刻把完整 Chroma/Neo4j/Redis 平台都实现完。
3. 智能化主线再推进 1-2 个闭环后，要回看 Java gateway、permission-admin、task-management 与 Agent 工具执行安全的联动，避免 Python 智能层孤岛化。
4. 继续按前沿 AI Agent 技术雷达做趋势校准，但只吸收能服务 DataSmart 商业化目标的技术能力。

## 3.61 Python AI Runtime 模型网关治理契约雏形（2026-05-24）

本阶段开始补齐模型网关治理能力。趋势上，vLLM/SGLang 等推理栈越来越重视 prefix/cache-aware serving、调度和路由，LiteLLM 这类网关也把 fallback、budget、usage、provider 抽象作为重要能力。DataSmart 不应直接锁定某个网关产品，而应该先沉淀自己的治理契约，再决定接 LiteLLM、自研 Java 控制面、vLLM/SGLang 或企业内部网关。

已完成：
- `ModelRoute` 新增 fallback、延迟等级、成本等级、缓存范围和健康检查路径字段。
- 新增 `ModelLatencyTier`、`ModelCostTier`、`ModelCacheKeyScope`，其中缓存范围覆盖 `GLOBAL_SAFE`、`TENANT_SAFE`、`PROJECT_SAFE`、`SESSION_ONLY`、`NO_CACHE`。
- 新增模型网关领域契约：Provider 健康快照、路由请求上下文、预算策略、预算决策、路由决策。
- `ModelRouteRegistry` 新增 `candidate_routes_for(...)`，支持同一工作负载多个候选路由。
- 新增 `ModelGatewayGovernanceService`，支持预算预评估、健康状态过滤、fallback、延迟等级偏好和缓存范围决策。
- 新增内存版健康注册表和预算台账，作为后续 Redis/MySQL/Prometheus/Java 控制面的可替换占位。
- 新增测试覆盖 Provider fallback、预算不足阻断、交互式延迟优先、敏感请求显式禁用缓存。

产品意义：
- 模型调用开始具备商业化治理面：不再只有“能不能调通模型”，还开始考虑“哪个 Provider 健康、是否超预算、是否需要 fallback、缓存能复用到什么范围”。
- `cache_key_scope` 为 prefix cache / KV cache 治理打基础，避免未来把项目上下文或敏感会话错误复用到其他租户/项目。
- 预算策略先按 token 建模，后续可以映射到真实账单、套餐、租户配额和超额告警。
- 路由治理与 Provider 调用解耦，后续接入 OpenAI-compatible、vLLM、SGLang、LiteLLM 或自研网关时不需要重写 Agent 编排逻辑。

验证：
- 不写字节码的 AST 语法检查通过，覆盖 59 个 Python 文件。
- Python `python -m unittest discover -s tests` 通过，78 个测试成功。
- Python 行数扫描通过，最高 `hybrid_context_builder.py` 398 行、`runtime_event_session.py` 396 行、`api.py` 375 行，均低于 500 行。
- `compileall` 因 Windows `__pycache__` 写入权限出现 `WinError 5`，已用 AST 解析替代语法检查。
- 本阶段未修改 Java 源码，因此未重复跑 Maven。

下一步推荐路线：
1. 把 `ModelGatewayGovernanceService` 接入 `AgentOrchestrator` 的模型调用前置节点，让 AgentPlan 能看到模型网关决策摘要。
2. 补调用后 usage 回写和预算扣减，形成“调用前评估、调用后记账”的最小成本闭环。
3. 然后再评估是否需要 Java agent-runtime/gateway 提供模型策略控制面，而不是现在就实现完整模型网关产品。
4. 模型网关再推进 1-2 个闭环后，回到 Agent 工具执行安全和 Java 控制面的跨模块联动，继续保持整体节奏。

## 3.62 Python AI Runtime 模型网关治理进入 Agent 编排主链（2026-05-24）

本阶段把 3.61 的模型网关治理从独立服务接入 `AgentOrchestrator`。这一步的重点不是新增某个模型，而是让 Agent 每次模型调用前都能形成可解释治理决策：预算是否允许、Provider 是否健康、是否需要 fallback、缓存范围应该是什么、如果无法调用模型应如何降级。

已完成：
- `AgentPlan` 新增 `model_gateway_decision`，并允许 `selected_route` 在预算阻断或无可用 Provider 时为 `None`。
- Agent 状态流新增 `route_model_gateway`，位于上下文构建之后、模型意图节点之前。
- 运行事件新增 `MODEL_GATEWAY_ROUTED`，记录选中 Provider、选中模型、fallback、预算、缓存范围和候选数量。
- `invoke_model_intent` 在无可用模型路由时返回可解释降级摘要，规则式意图分析和工具规划仍可继续工作。
- `ModelGatewayGovernanceService` 新增调用后 usage 记录入口，内存预算台账可查询已使用 tokens。
- 新增 `model_gateway_context.py`，把 token 估算、延迟等级、缓存范围、fallback 开关解析从编排器拆出。

产品意义：
- 模型不可用、预算不足或缓存禁用不再是黑盒错误，而是会进入 AgentPlan 和事件流，便于前端提示、审计回放和运营排障。
- 成本治理开始形成闭环：调用前预算预评估，调用后按 provider usage 回写台账。
- 模型网关治理与 Provider 调用仍保持解耦，未来接 LiteLLM、vLLM、SGLang 或 Java 控制面都可以复用同一套决策对象。
- 编排器拆分后保持 404 行，避免 Python 智能层也出现“大 Impl / 大 Orchestrator”问题。

验证：
- 不写字节码的 AST 语法检查通过，覆盖 60 个 Python 文件。
- Python `python -m unittest discover -s tests` 通过，79 个测试成功。
- Python 行数扫描通过，最高 `agent_orchestrator.py` 404 行、`hybrid_context_builder.py` 398 行、`runtime_event_session.py` 396 行，均低于 500 行。
- 本阶段未修改 Java 源码，因此未重复跑 Maven。

下一步推荐路线：
1. 再补一个模型网关小闭环：API 响应/前端确认页展示预算状态、fallback 说明、缓存范围和降级原因。
2. 随后把模型网关阶段暂时收口，切回 Agent 工具执行安全与 Java 控制面/gateway 鉴权联动。
3. 中期再设计 Java 侧模型策略控制面，包括租户预算、项目预算、Provider 健康上报、手动熔断、模型路由配置和审计导出。

## 3.63 Python AI Runtime 模型网关 API 治理摘要（2026-05-24）

本阶段为模型网关补上 API 展示闭环。此前内部 `AgentPlan.model_gateway_decision` 已经有完整治理决策，但它更像服务内部对象，不适合前端确认页或 Java 控制面直接依赖。本阶段新增扁平化 `modelGatewayGovernance` 响应块，让预算、fallback、缓存范围、降级原因和推荐动作可以直接展示或写审计。

已完成：
- 新增 `api_model_gateway.py`，负责把 `ModelGatewayRoutingDecision` 转换为 API 友好的治理摘要。
- `build_plan_response(...)` 顶层返回新增 `modelGatewayGovernance`。
- 摘要字段覆盖 `available`、`selectedProvider`、`selectedModel`、`selectedHealthStatus`、`fallbackUsed`、`budgetAllowed`、`estimatedTokens`、`remainingTokens`、`cacheKeyScope`、`candidateProviders`、`governanceNotes`、`displaySummary`、`recommendedActions`。
- 默认响应测试验证治理摘要存在且模型可用。
- 预算阻断测试验证预算不足时模型不可用、没有选中模型，并返回预算相关动作。

产品意义：
- 前端不用解析内部 dataclass，就能展示“当前模型是否可用、是否使用 fallback、预算是否足够、缓存范围是否保守”。
- Java 控制面未来可直接把这份摘要写入 Agent run 审计记录，形成模型治理可追溯证据。
- 模型网关阶段已经具备契约、主链接入、usage 记账、事件记录和 API 展示摘要，适合阶段性收口。

验证：
- 不写字节码的 AST 语法检查通过，覆盖 61 个 Python 文件。
- Python `python -m unittest discover -s tests` 通过，80 个测试成功。
- Python 行数扫描通过，最高 `agent_orchestrator.py` 404 行、`hybrid_context_builder.py` 398 行、`runtime_event_session.py` 396 行、`api.py` 380 行，均低于 500 行。
- 本阶段未修改 Java 源码，因此未重复跑 Maven。

下一步推荐路线：
1. 模型网关阶段先收口，切回 Agent 工具执行安全和 Java `agent-runtime` 控制面联动。
2. 重点设计 Python `AgentPlan` 如何提交给 Java 形成 run、tool execution、approval、audit 记录。
3. 同时检查 gateway/permission-admin 的权限透传如何保护 Agent 工具执行入口。

## 3.64 Java agent-runtime 接入 Python AgentPlan 控制面（2026-05-24）

本阶段把 3.63 的 Python `modelGatewayGovernance`、`memoryPlan`、`memoryRetrievalReport` 和 `ToolPlan` 真正带回 Java 控制面。实现原则是：Python AI Runtime 只负责生成计划和解释，Java `agent-runtime` 负责会话边界、Run 状态、工具白名单、审批等待和审计证据。

已完成：
- 新增 `/agent-runtime/plan-ingestions` 与 `/api/agent/plan-ingestions`。
- Java 可接收 Python AgentPlan 快照，并创建受控 `AgentSession`、`AgentRun` 和工具审计计划。
- 工具计划必须通过 Java 工具目录校验，未知工具直接拒绝。
- Python ToolPlan 的 `reason`、`arguments`、`governanceHints`、`parameterValidation` 已进入工具审计视图。
- 模型网关治理摘要、记忆计划和记忆检索报告已进入 Run 变量，便于后续前端确认页、审批单和审计回放使用。
- 高风险或显式审批工具会让 Run 进入 `WAITING_HUMAN`，对应工具审计进入 `WAITING_APPROVAL`，避免状态不一致。
- 新增单元测试覆盖低风险接入、高风险审批、未知工具拒绝。

产品意义：
- 智能化主线从“Python 能生成计划”推进到“Java 能治理计划”，跨运行时产品闭环开始成形。
- 该阶段没有让 Python 直接执行工具，避免绕过 permission-admin、gateway、审批和审计。
- 计划接入接口为后续 Agent 计划确认页、人工审批、事件回放、服务账号策略、异步 Kafka 接入奠定了统一 runId/auditId 基础。

验证：
- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过。
- agent-runtime 当前测试 23 个用例通过。
- 新增核心文件均低于 500 行，未继续膨胀 `AgentSessionService`。

下一步推荐路线：
1. 补 gateway/permission-admin 对 `/api/agent/plan-ingestions` 的服务账号权限、限流、审计和 fail 策略。
2. 设计 Python AgentPlan 的 Kafka 异步接入版本，加入 requestId 幂等、重复消息去重和 DLQ 处理。
3. 继续强化工具执行入口：必须校验审批状态、项目范围、工具参数 schema、幂等键和下游健康，不能只凭 auditId 执行。
4. 接下来不要继续无限深挖 AgentPlan 接入，推进 1 个权限/网关安全闭环后，应开始把真实工具适配器落到 `datasource-management`、`data-quality`、`task-management` 等业务模块。

## 3.65 gateway/permission-admin AgentPlan 接入口服务账号保护与限流治理（2026-05-24）

本阶段补齐 3.64 留下的入口安全缺口。`/api/agent/plan-ingestions` 虽然挂在 HTTP API 下，但产品语义上是 Python AI Runtime 向 Java `agent-runtime` 提交计划的内部服务协议，不应被普通用户、项目负责人、运营人员或租户管理员直接调用。因此本轮把它纳入 gateway 本地内部端点保护和 permission-admin 策略事实源双重治理。

已完成：
- `gateway` 新增内部服务端点保护器，对 `/api/agent/plan-ingestions` 默认要求 `SERVICE_ACCOUNT`。
- 内部端点保护支持可选内部 Token 和本地固定窗口限流，默认 `120/min`。
- 非服务账号直接在 gateway 层返回 403，超过限流返回 429 并写入 `Retry-After`。
- gateway 路由元数据把 `POST /api/agent/plan-ingestions` 映射为 `AI_RUNTIME + INGEST_PLAN`，避免继续按普通 POST 推断为 CREATE。
- `permission-admin` 增加 `INGEST_PLAN` 动作，并在初始化 SQL 中只允许 `SERVICE_ACCOUNT` 执行该动作。
- 普通人类角色对 AgentPlan 直连接入口显式拒绝，防止“直接构造计划”绕过正常 Agent 会话链路。
- 新增 `GatewayAuthorizationErrorWriter` 与 `InternalServiceEndpointProperties`，把错误响应写出和内部端点配置从大类中拆出。

产品意义：
- AgentPlan ingestion 被明确定位为机器到机器的内部控制面协议，而不是普通用户 API。
- gateway 负责快速拒绝和限流，permission-admin 负责策略事实、角色动作和数据范围语义，职责边界更清晰。
- 这一步为后续 Kafka 异步接入、服务账号签名、mTLS、工具执行审批和审计回放提供了更安全的入口基线。
- 代码结构也同步收口：`GatewayAuthorizationFilter` 和 `GatewayAuthorizationProperties` 都重新低于 500 行，避免安全能力落地时制造新的维护债。

验证：
- `mvn -pl gateway,permission-admin -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过。
- 全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，10 个 Maven reactor 模块全部成功。
- gateway 测试 11 个、permission-admin 测试 9 个、agent-runtime 测试 23 个均通过。

下一步推荐路线：
1. 进入 Python AgentPlan 的 Kafka 异步接入设计，补 `idempotencyKey`、消息重试、重复消费去重、DLQ 和回放。
2. 或者先强化 Java 工具执行入口，要求审批状态、项目范围、参数 schema、幂等键全部通过后才能执行真实工具。
3. 完成其中一个控制面闭环后，开始把真实工具适配器连接到 `datasource-management`、`data-quality`、`task-management`，避免 Agent 主线只停在计划和审计层。

## 3.66 agent-runtime AgentPlan 接入幂等键与重复提交保护（2026-05-24）

本阶段继续沿 Agent 控制面可靠性推进，但保持克制：没有直接上 Kafka Consumer 和新中间件，而是先把当前 HTTP 接入口和未来 Kafka 异步接入口都需要的幂等语义固定下来。这样后续从 HTTP 迁移到 Kafka 时，不需要重新设计“重复提交如何处理”。

已完成：
- `IngestAgentPlanRequest` 新增 `idempotencyKey`，并兼容 `pythonRequestId` 作为兜底去重键。
- 新增 `AgentPlanIngestionIdempotencySupport`，负责生成去重键、计算请求指纹、回放首次成功结果、拒绝同 key 不同 payload。
- 新增内存版幂等 store/record，先固定领域语义，后续可替换为 MySQL 唯一索引、Redis 原子占位或 Kafka Consumer 状态表。
- `AgentPlanIngestionService` 在创建会话、Run 和工具审计前先检查幂等记录；重复请求返回同一个 runId/auditId。
- Run 变量保留 `idempotencyKey`，方便后续审计回放和异步消费排障。
- 新增测试覆盖重复提交回放和幂等键冲突拒绝。

产品意义：
- Python Runtime、智能网关或未来 Kafka Consumer 的重试不会制造重复 Run、重复审批单或重复工具执行风险。
- 幂等键正式成为 AgentPlan ingestion 的业务契约字段，后续前端确认页、审计系统、Kafka DLQ 和事件回放都可以围绕它关联。
- 当前实现没有牺牲结构质量：主接入服务仍低于 500 行，幂等逻辑独立拆分，便于后续升级为分布式实现。

验证：
- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 25 个测试成功。
- 全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，10 个 Maven reactor 模块全部成功。

下一步推荐路线：
1. 进入 Kafka 异步 AgentPlan 接入：topic/envelope/partition key/retry/DLQ/replay。
2. 或者先做真实工具执行安全：审批状态、项目范围、参数 schema、工具适配器健康、执行幂等。
3. 我建议下一步做“工具执行安全 + 第一个真实业务工具适配器”，因为这样可以让 Agent 从计划层进入真实业务价值层，而不是继续只打磨接入协议。

## 3.67 agent-runtime 工具执行前置安全守卫（2026-05-24）

本阶段开始把 Agent 主线从“计划接入和审计”推向“真实工具执行安全”。项目里已有 `datasource.metadata.read` 适配器和工具执行入口，但执行入口不能只凭 `auditId` 直接调用下游服务。商业化 Agent 平台里，工具执行前必须重新校验控制面链路、项目范围、参数完整性和审批事实。

已完成：
- 新增 `AgentToolExecutionGuard`，集中承载工具执行前安全规则。
- 执行前校验 session/run/audit 是否同链路，避免跨会话或跨 Run 执行。
- 执行前校验 tenant/project/workspace/actor 是否一致，避免跨项目、跨 actor 调用工具。
- 执行前检查 `parameterValidation.missingFields`，参数仍缺失时拒绝执行。
- 非只读工具必须有人工审批人，否则拒绝执行。
- `AgentToolExecutionAuditService` 新增只读式 `requirePlannedExecutionAudit(...)`，让守卫可以在状态变更前运行。
- 执行链路改为“读取 PLANNED 审计 -> 守卫校验 -> 推进 EXECUTING -> 调用适配器”。
- 新增守卫单测，固定参数缺失、非只读未审批和已审批写工具三类行为。

产品意义：
- Agent 工具执行不再只是技术演示，而是开始具备企业级执行门禁。
- 参数缺失或审批缺失不会把审计状态错误推进到 EXECUTING，减少运营排障和前端状态误导。
- 该守卫后续可复用到 datasource、data-quality、task-management、permission、asset、compliance 等所有工具适配器。

验证：
- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 28 个测试成功。
- 全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，10 个 Maven reactor 模块全部成功。

下一步推荐路线：
1. 做强 `datasource.metadata.read` 与 `datasource-management` 的受控元数据发现契约，让第一个真实工具链路可解释、可测、可扩展。
2. 然后接 `data-quality` 质量规则草案工具或 `task-management` 受控任务创建工具。
3. Kafka 异步 AgentPlan 接入可以随后推进，但当前更建议先让 Agent 工具链进入真实业务价值层。

## 3.68 agent-runtime datasource.metadata.read 真实工具契约增强（2026-05-24）

本阶段没有继续扩大 AgentPlan 接入协议，而是按路线把第一条真实业务工具 `datasource.metadata.read` 做强。该工具用于让 Agent 在受控边界下读取数据源结构，为后续质量规则草案、同步模板建议、资产目录沉淀和 RAG 上下文提供真实业务输入。

已完成：

- `DatasourceMetadataReadToolAdapter` 从“裸 Map 拼请求 + 原样塞响应”调整为只做工具执行编排、HTTP 调用和平台 Header 透传。
- 新增 `DatasourceMetadataReadRequest`，作为 agent-runtime 到 datasource-management 的本地 JSON 契约，避免微服务之间直接依赖对方 Controller DTO。
- 新增 `DatasourceMetadataReadRequestFactory`，从 Python ToolPlan 的 `planArguments` 读取过滤条件和开关，并在 Java 控制面做安全裁剪。
- Agent 侧 `maxTables` 上限裁剪为 100，`maxColumnsPerTable` 上限裁剪为 300，避免模型规划出过大的元数据扫描请求。
- 当前强制关闭 `includeSampleRows`，因为样本数据需要更高等级的审批、脱敏、敏感识别和访问审计，不应作为第一版 Agent 工具默认能力。
- 新增 `DatasourceMetadataReadResponseMapper`，把 datasource-management 的统一响应转换为稳定工具结果，并生成 `summary`。
- 工具摘要包含表数量、字段数量、是否截断、缓存命中、发现耗时和 warnings，便于前端、审计和后续编排优先消费。
- 新增适配器测试，覆盖参数裁剪、项目 Header 透传、样本行降级、成功摘要生成和下游业务错误映射。

产品意义：

- Agent 不再只停留在计划、审批和审计控制面，开始有第一条可解释、可测试、可扩展的真实业务工具链路。
- 该模式可以复制到 `data-quality.rule.draft`、`task.create`、`data-sync.template.suggest`：每条工具都应具备参数治理、下游契约、响应摘要和稳定错误码。
- 这一步也提醒后续不能让模型直接拼接口；Python 可以规划，Java 必须裁剪参数、传递项目边界、记录审计并处理下游错误。

验证：

- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过。
- agent-runtime 当前测试 30 个成功。

下一步路线：

1. 进入 `data-quality.rule.draft` 真实工具，让 Agent 基于 datasource 元数据生成质量规则草案，但先不直接落成启用规则。
2. 随后接 `task-management` 受控任务创建工具，写操作必须经过审批、幂等和项目范围校验。
3. 暂不继续无限打磨 datasource 元数据读取；大元数据分页、对象存储引用和样本行脱敏可作为中期增强。

## 3.69 agent-runtime/data-quality 质量规则草案建议工具闭环（2026-05-24）

本阶段把上一阶段的元数据读取结果向真实治理建议推进：新增 `quality.rule.suggest` 工具和 data-quality 草案建议接口。当前只生成规则草案，不直接写库、不启用、不调度任务。

已完成：

- `data-quality` 新增 `/quality-rules/suggestions`，返回质量规则草案建议。
- 新增草案请求、草案项、草案响应 DTO，字段对齐现有规则创建入口，便于后续保存为 DRAFT。
- 新增 `QualityRuleSuggestionSupport`，第一版基于元数据确定性生成建议：主键唯一性、关键字段完整性、金额/数量类字段有效性。
- `DataQualityController` 对草案接口继续执行 PROJECT 数据范围校验，避免 Agent 为未授权项目生成规则建议。
- `agent-runtime` 新增 `QualityRuleSuggestToolAdapter`，可执行 `quality.rule.suggest`。
- Agent 侧新增请求工厂和响应映射器，负责 datasourceId/businessGoal/metadata 参数治理、maxSuggestions 裁剪、下游响应摘要和错误码归一化。
- `agent-runtime` 配置新增 `data-quality` 工具下游地址。

产品意义：

- Agent 已形成第一条只读/草案型业务链路：先读数据源元数据，再生成质量规则草案。
- 当前保持“草案优先”，避免模型或 Agent 直接创建生产规则，符合质量治理的人工确认、审批和版本管理方向。
- 确定性规则引擎可作为模型不可用时的兜底能力，后续可叠加 Python AI Runtime、数据画像、业务术语和历史质量报告。

验证：

- `mvn -pl agent-runtime,data-quality -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过。
- 全量 `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过。

下一步路线：

1. 补 Agent 工具输出引用/工具链上下文，让 `quality.rule.suggest` 能稳定消费 `datasource.metadata.read` 的输出。
2. 再接 `task.create.draft` 或 task-management 受控任务创建工具，写操作必须审批、幂等和项目范围校验。
3. data-quality 的草案保存、批量确认、审批、版本化很重要，但建议稍后再做，先继续把 Agent 工具链推进到任务草稿。 

## 3.70 agent-runtime Run 内工具输出引用与工具链上下文（2026-05-24）

本阶段补齐 Agent 多工具编排中的关键缺口：同一 Run 中，后续工具可以读取前序工具的成功输出。此前 `quality.rule.suggest` 需要 Python ToolPlan 显式携带 metadata，现在可以自动读取 `datasource.metadata.read` 的输出。

已完成：

- 新增 `AgentToolExecutionOutputRecord`，记录成功工具输出的 sessionId、runId、auditId、toolCode、output 和创建时间。
- 新增 `AgentToolExecutionOutputStore`，当前以内存方式保存 Run 内工具输出。
- `AgentToolExecutionService` 在工具执行成功后自动保存结构化输出。
- `QualityRuleSuggestRequestFactory` 在 planArguments 不带 metadata 时，会从同一 Run 最近一次成功的 `datasource.metadata.read` 输出中提取 `metadata`。
- 新增测试覆盖“后续工具自动读取前序工具输出”的链路。

产品意义：

- Agent 工具从孤立调用升级为可编排链路，形成 `metadata read -> rule suggest` 的真正上下文传递。
- 后续 `task.create.draft` 可以继续读取质量规则草案，生成待审批任务草稿，而不需要模型复制大 JSON。
- 该能力是未来 MCP/A2A 风格工具链、事件回放、工作流 DAG 和 Agent 记忆写入的基础。

当前边界：

- 当前是内存仓储，生产应迁移到数据库/Redis/EventStore/对象存储引用。
- 当前按最近一次 toolCode 输出隐式引用；复杂工作流需要显式 `outputRef/jsonPath`。
- 大结果不能长期放内存，后续应改为摘要 + MinIO/审计归档引用。

验证：

- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 33 个测试成功。

下一步路线：

1. 做 `task.create.draft` 最小闭环，但仅生成任务草稿/待审批，不直接调度执行。
2. 同时设计显式 `outputRef/jsonPath` 协议，避免复杂工作流长期依赖隐式最近输出。
3. 后续再回到 data-quality 的草案保存、批量确认、审批和版本化，不在当前阶段继续局部深挖。

## 4.02 Python AI Runtime Agent 主链 streaming 工具调用治理（2026-05-27）

本阶段继续推进 Phase 4 的 AI 与智能体能力，不新增传统 Java 业务面，而是把 Python AI Runtime 的模型工具调用能力接入到更接近真实 Agent 产品的流式主链中。目标是让模型不仅能一次性返回 `tool_calls`，也能像真实 OpenAI-compatible streaming 一样，通过多个 chunk 返回 `tool_call_deltas`，再由平台聚合、治理和审计。

已完成：

- `AgentModelIntentNode` 优先尝试 Provider `stream(...)`，并保留非流式 `invoke(...)` 兼容路径。
- streaming 路径会把 `ModelInvocationChunk.tool_call_deltas` 交给 `ModelToolCallDeltaAggregator`，按 index 聚合为完整 `ModelToolCall`。
- 聚合后的工具调用复用非流式 `_govern_model_tool_calls(...)`，确保流式和非流式都遵守同一套工具可见性、参数、风险、审批和 runtime event 规则。
- `AgentModelIntentNodeResult` 增加 chunk/delta/assembly issue 计数，为后续 Agent 可观测性和模型工具调用质量指标预留字段。
- 支持 `streamModelIntent` / `stream_model_intent` 请求级开关，便于灰度、排障和私有模型网关兼容。

产品意义：

- AI 主链从“模型能提出完整工具调用”推进到“模型能流式提出工具调用片段并被平台治理”，更接近 Codex / Claude Code 类 Agent 的实时体验。
- 这一步仍然坚持边界：Python 只做模型推理、工具调用候选治理和计划输出；Java `agent-runtime` 仍负责审批、审计、幂等、真实工具执行和业务状态。
- 该能力会服务后续智能网关、WebSocket 实时事件、工具调用诊断、模型工具质量评估和多步 Agent loop。

下一步路线：

1. 设计 `tool_call_id -> tool result message -> next model turn` 的工具结果回填契约，形成真正多步 Agent loop。
2. 增加 streaming 聚合 issue 的 runtime event，提升实时诊断能力。
3. 打通 Python ToolPlan 到 Java `agent-runtime` 的执行/审批链路，避免 AI 主线长期只停在计划层。

## 4.03 Python AI Runtime 工具结果回填模型契约（2026-05-27）

本阶段继续推进 Phase 4 的多步 Agent loop。4.02 已经让模型能以 streaming 方式提出工具调用候选，本阶段补齐工具执行之后的下一轮模型输入契约：Java `agent-runtime` 执行或审批工具后，Python Runtime 应如何把结果按 `tool_call_id` 回填给模型。

已完成：

- `ModelMessage` 支持 `tool_call_id`、`name` 和 assistant `tool_calls`。
- 新增 `ModelToolResultFeedbackBuilder`，生成 `assistant(tool_calls)` + `tool(tool_call_id=result)` 消息包。
- 新增工具结果状态 `SUCCEEDED/FAILED/REJECTED/WAITING_APPROVAL/SKIPPED`，让模型能理解执行成功、失败、拒绝和审批等待。
- 回填内容只包含摘要、审计引用、输出引用和允许进入模型的结构化字段；敏感字段会脱敏。
- OpenAI-compatible Provider 已能把 tool result messages 序列化进下一轮 Chat Completions 请求。

产品意义：

- DataSmart 已具备多步 Agent loop 的协议骨架：模型提出工具调用，Java 执行工具，Python 把结果回填模型继续推理。
- 这一步仍坚持“Python 不直接执行工具”的边界，避免 AI 层绕过 Java 的权限、审批、幂等和审计控制。
- 工具结果以结构化摘要和 outputRef 回填，避免把大结果、敏感样本、SQL 或任务 payload 原样喂给模型。

下一步路线：

1. 将结果回填构建器接入 `AgentModelIntentNode`，先做最小模拟 loop。
2. 再设计 Python 与 Java `agent-runtime` 的工具结果查询/回调接口。
3. 增加工具结果回填 runtime events 和指标，跟踪缺失 callId、脱敏字段、回填耗时和二轮模型调用状态。

## 4.04 Python AI Runtime Agent 主链模拟工具反馈二轮推理闭环（2026-05-27）

本阶段把 4.03 的“工具结果回填消息契约”真正接入 Agent 主链。实现重点不是让 Python 直接执行工具，而是先让主链具备可测试、可观测、可替换的二轮 loop 形态：模型提出 tool_calls，平台治理为 ToolPlan，模拟 Java 控制面返回工具执行反馈，Python 再把反馈作为 role=tool 消息交给模型进行第二轮总结。

已完成：

- 新增 `ModelToolExecutionFeedbackProvider` 协议，把“工具执行反馈来自哪里”抽象出来，后续可以替换为 Java `agent-runtime` 查询接口、回调事件或 Kafka 结果流。
- 新增 `SimulatedModelToolExecutionFeedbackProvider`，用于在 Java 真实结果回传接口完成前，稳定验证 Agent loop 的消息顺序、状态语义和事件轨迹。
- `AgentModelIntentNode` 已在非流式与 streaming 两条路径中接入工具反馈构建、tool result message 生成和二轮模型调用。
- 二轮模型调用显式关闭工具选择，避免最小闭环阶段出现模型反复继续调用工具的无限循环。
- 新增 `TOOL_RESULT_FEEDBACK_BUILT` 与 `MODEL_SECOND_TURN_COMPLETED` 运行事件，便于前端实时进度、审计回放和排障诊断识别二轮 loop 节点。

产品意义：

- AI 主链已经具备“模型提议工具 -> 平台治理 -> 工具反馈回填 -> 模型继续推理”的最小闭环，这是向 Codex / Claude Code 类 Agent 工具能力靠近的关键一步。
- 反馈 Provider 是明确替换点，后续接 Java 真实工具执行结果时，不需要推翻模型消息契约和 Agent 主链结构。
- 当前设计继续坚持商业化边界：Python 不绕过 Java 执行业务工具，审批、幂等、审计、权限和真实副作用仍由 Java 控制面负责。

当前边界：

- 工具反馈仍是模拟值，不代表真实 Java 工具执行结果。
- 二轮模型调用当前是非 streaming invoke；后续如果前端要展示二轮 token 流，需要补 second-turn streaming。
- 当前只做一轮工具反馈和一轮二次推理；真正商用 Agent loop 还需要最大步数、预算上限、工具并发策略、失败重试、人工接管和循环检测。
- 尚未实现 Python 与 Java `agent-runtime` 的工具结果查询、回调或事件订阅接口。

下一步路线：

1. 设计 Python Runtime 与 Java `agent-runtime` 的工具执行结果查询/回调契约，优先覆盖 `runId/auditId/toolCallId/status/outputRef/safeResult`。
2. 给二轮 loop 增加可观测指标：反馈构建耗时、二轮模型耗时、缺失 callId、审批等待数量、拒绝数量和模拟反馈占比。
3. 设计多步 Agent loop 状态机，但先限制最大步数和预算，避免为了“更智能”引入不可控循环。
4. 在 AI 主链再推进 1-2 个闭环后，回看 Java gateway、agent-runtime 和 task/data-quality 的真实执行闭环，保证项目不是只停留在 Python 智能层。

## 4.05 Java agent-runtime 工具结果查询与 Python 真实反馈 Provider（2026-05-27）

本阶段把 4.04 的模拟反馈向真实控制面推进了一步。实现重点是先建立稳定、只读、可回退的结果查询契约，而不是一次性实现完整自动执行流水线。这样 Python 可以在具备 Java 审计引用时读取真实工具结果，在引用缺失或 Java 暂不可用时继续安全回退模拟反馈。

已完成：

- Java `agent-runtime` 新增工具结果快照查询能力，路径为 `/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/result`。
- `AgentToolExecutionAuditService` 新增单条审计查询方法，校验 sessionId/runId/auditId 归属，避免跨会话读取。
- `AgentToolExecutionService` 新增只读 `getResult(...)`，返回 `AgentToolExecutionResultView(audit, output)`，不触发执行、不改变状态。
- `AgentSessionService` 与 `AgentToolExecutionAuditController` 已暴露结果查询入口，供前端、审计和 Python Runtime 使用。
- Python AI Runtime 新增 `JavaAgentRuntimeToolFeedbackClient`，可解析 Java 平台统一响应并映射为 `ToolExecutionFeedback`。
- Python 新增 `JavaAgentRuntimeToolFeedbackProvider`，优先根据 ToolPlan governance hints 中的 `agentRuntimeSessionId/agentRuntimeRunId/agentRuntimeAuditId` 查询 Java；缺少引用或失败时回退模拟 Provider。
- `build_default_orchestrator(...)` 支持通过 `DATASMART_AGENT_RUNTIME_TOOL_FEEDBACK_ENABLED=true` 启用远程工具反馈 Provider。

产品意义：

- Agent 主链从“只能模拟工具结果”推进为“具备读取 Java 真实工具执行结果的桥梁”。
- Java 查询接口只读且按 session/run/audit 三元组定位，符合审计、安全和可回放要求。
- Python Provider 仍保持可替换和可回退，避免因为 Java 服务暂不可用导致模型主链整体不可用。
- 该设计为后续工具执行回调、Kafka 结果事件、WebSocket 实时状态和多步 Agent 状态机打基础。

当前边界：

- ToolPlan 目前还不会自动带上 Java `agentRuntimeAuditId`，因此真实查询需要后续把 Java ingestion 返回的 toolAudits 与模型 tool_call_id 做关联。
- 目前是同步查询/轮询思路，尚未实现 Java 主动回调 Python 或 Kafka 工具结果事件。
- 当前 Python Provider 对 `PLANNED/EXECUTING` 会作为非成功反馈处理，还没有引入 RUNNING 状态或等待策略。
- 仍未实现自动“提交计划、审批/执行、轮询结果、继续多步 loop”的完整状态机。

下一步路线：

1. 在 Python AgentPlan ingestion client 中保存 Java 返回的 session/run/audit 映射，并把 auditId 回写到 ToolPlan governance hints 或独立 controlPlaneReferences。
2. 设计工具结果事件流：Java 执行完成后发布 Kafka/WebSocket 事件，Python 可订阅或按 runId replay，而不是只靠同步轮询。
3. 为 Agent loop 增加最大步数、预算、超时和人工接管策略，然后再允许模型在二轮后继续提出下一组工具调用。
4. 补 gateway 路由与权限元数据，确保结果查询接口只允许服务账号、项目成员、审批人或审计员访问。

## 4.06 Python AgentPlan 接入 Java 控制面与 auditId 映射（2026-05-28）

本阶段把 4.05 的“Java 工具结果查询 Provider”向前补了一环：Python Runtime 现在可以把生成好的 `AgentPlan` 主动提交给 Java `agent-runtime` 的计划接入口，解析 Java 返回的 session/run/toolAudits，并按模型 `modelToolCallId` 把真实 `auditId` 写回 `ToolPlan.governance_hints`。这一步的目标不是立刻自动执行所有工具，而是让 Agent 计划、Java 审计事实和后续工具结果回填之间建立稳定映射。

已完成：

- 新增 `agent_plan_ingestion_client.py`，负责将 Python `AgentRequest + AgentPlan` 转换为 Java `IngestAgentPlanRequest`。
- 接入请求会携带 tenant/project/workspace/actor、任务目标、状态轨迹、模型网关治理摘要、记忆计划、记忆检索报告和工具计划。
- 工具计划序列化时保留 `toolCode`、风险等级、执行模式、是否需要人工审批、参数、治理提示和参数校验结果。
- 参数校验结果会显式输出 `canExecute`、`canCreateDraft`、`missingFields` 和 `issues`，让 Java 执行守卫可以阻断缺参数工具，而不是信任模型直接执行。
- Java 响应解析会从 `toolAudits[*].governanceHints.modelToolCallId` 中建立 `modelToolCallId -> auditId` 映射。
- `AgentPlanIngestionResult.attach_to_plan(...)` 会返回新的 `AgentPlan`，把 `agentRuntimeSessionId`、`agentRuntimeRunId`、`agentRuntimeAuditId`、`agentRuntimeAuditState` 注入对应 ToolPlan。
- `build_plan_response(...)` 支持注入 `plan_ingestion_client`，并在 HTTP 响应中返回 `controlPlaneIngestion` 摘要。
- `create_app()` 支持通过 `DATASMART_AGENT_RUNTIME_PLAN_INGESTION_ENABLED=true` 和 `DATASMART_AGENT_RUNTIME_BASE_URL` 启用计划接入。
- 新增 `test_agent_plan_ingestion_client.py`，覆盖计划序列化、参数缺失治理、Java 响应映射、平台错误和 tenant/project 数字化契约。

产品意义：

- Agent 主链从“Python 生成计划，Java 可查询结果”推进为“Python 生成计划后可以先进入 Java 控制面形成审计事实”。
- 4.05 的真实反馈 Provider 后续可以使用 ingestion 返回的 auditId，而不是依赖外部手工写入 governance hints。
- Java 仍然是执行、审批、审计、幂等和权限事实源，Python 只负责模型编排、计划提交和结果回填准备，边界没有被打破。
- 这更接近 Codex / Claude Code 类 Agent 的核心形态：模型提出工具调用意图，平台控制面治理和执行，结果再以结构化消息回到模型上下文。

当前边界：

- 当前接入发生在 `build_plan_response(...)` 之后，尚未把“接入 Java、触发执行、等待结果、二轮反馈”编排成一个完整自动状态机。
- Java 执行完成后的结果仍主要依赖同步查询，尚未接入 Kafka/WebSocket 回调事件流。
- tenantId/projectId 在 Java ingestion 契约中仍要求可转换为 Long，后续如要支持字符串租户编码，需要增加统一 ID 映射层。
- 当前仍只做一轮工具反馈和一轮二次推理，多步 Agent loop 还需要最大步数、预算、超时、人工接管和循环检测保护。

验证：

- `python -m unittest discover -s python-ai-runtime\tests` 通过，155 个测试成功。
- Python AST 语法检查通过，覆盖 86 个 Python 文件。
- 文件规模检查：`agent_plan_ingestion_client.py` 358 行、`api.py` 449 行、`services/__init__.py` 219 行、新增测试 186 行，均低于 500 行约束。
- 本阶段未修改 Java 源码，因此未重新执行 Maven。

下一步路线：

1. 把计划接入与真实反馈 Provider 串成受控执行阶段：先提交 Java ingestion，再根据审计状态决定等待审批、触发执行或查询结果。
2. 设计 Java 工具结果事件流：执行状态变化通过 Kafka/WebSocket 推送，Python 和前端按 runId 订阅或 replay。
3. 为 Agent loop 增加最大工具步数、最大 token/成本预算、单工具超时、全局超时、循环检测和人工接管。
4. 补 gateway 对 `/api/agent/plan-ingestions` 与结果查询接口的服务账号、项目成员、审批人、审计员权限矩阵。

## 4.07 Python AgentPlan 接入后的 Java 控制面反馈快照（2026-05-28）

本阶段没有急着把 Agent 改成全自动多步执行，而是在 4.06 的 `auditId` 映射之后补一层“控制面反馈快照”。原因是商业化 Agent 不能假设计划接入 Java 后工具就已经成功执行：真实状态可能是等待审批、排队执行、执行失败、跳过、部分成功或暂时查询不到。因此本阶段先把“Java 当前反馈是什么、是否具备二轮推理条件、下一步应该等待审批还是继续”整理成稳定 API 摘要。

已完成：

- 新增 `agent_control_plane_feedback.py`，定义 `AgentControlPlaneFeedbackCollector`。
- 收集器会从 ToolPlan governance hints 中读取 `modelToolCallId`，构造轻量 `ModelToolCall`，复用现有 `ModelToolExecutionFeedbackProvider` 查询 Java 反馈。
- 新增 `AgentControlPlaneFeedbackItem`，面向单个工具暴露 toolName、status、summary、auditId、runId、outputRef、errorCode。
- 新增 `AgentControlPlaneFeedbackSnapshot`，面向一次 AgentPlan 暴露 expectedToolCallCount、feedbackCount、missingToolCallIds、statusCounts、secondTurnEligible、recommendedActions。
- `secondTurnEligible` 明确只代表“反馈完整性满足二轮基础条件”，不代表一定自动调用模型；等待审批会阻断，失败/拒绝/跳过可以作为工具结果语义交给模型解释。
- `build_plan_response(...)` 支持注入 `control_plane_feedback_collector`，在 plan ingestion 成功并写回 auditId 后返回 `controlPlaneFeedback`。
- `create_app()` 支持在 `DATASMART_AGENT_RUNTIME_TOOL_FEEDBACK_ENABLED=true` 时创建控制面反馈收集器。
- 新增 `test_agent_control_plane_feedback.py`，覆盖成功反馈、等待审批阻断、反馈缺失诊断，以及 API 响应暴露 `controlPlaneFeedback`。

产品意义：

- Agent 主链从“拿到 Java auditId”推进到“能读取 Java 当前工具状态并形成产品化反馈摘要”。
- 前端确认页、审计回放、调试面板和后续智能网关可以直接展示控制面状态，而不需要自己理解 Java 审计 DTO。
- 这一步继续保持 Python/Java 边界：Python 不触发执行、不推进审批、不直接修改业务状态，只读取控制面事实并做摘要。
- 为后续真正的受控多步 Agent loop 打基础：只有当反馈完整、无等待审批、预算和步数允许时，才进入二轮模型或继续下一组工具。

当前边界：

- 当前只做同步快照，不做长轮询、Kafka 订阅或 WebSocket 事件驱动。
- 当前不自动触发 Java 工具执行，仍依赖 Java 控制面已有状态。
- 当前不在 API 层触发二轮模型推理，只返回 `secondTurnEligible` 和建议动作。
- 等待审批、失败重试、取消、人工接管、最大步数和预算策略仍需后续状态机统一治理。

验证：

- `python -m unittest discover -s python-ai-runtime\tests` 通过，159 个测试成功。
- Python AST 语法检查通过，覆盖 88 个 Python 文件。
- 文件规模检查：`agent_control_plane_feedback.py` 268 行、`api.py` 469 行、`services/__init__.py` 227 行、新增测试 171 行，均低于 500 行约束。
- 本阶段未修改 Java 源码，因此未重新执行 Maven。

下一步路线：

1. 增加受控 Agent loop 策略对象：最大工具步数、最大二轮次数、最大 token/成本预算、单工具等待超时、全局超时、等待审批阻断和人工接管。
2. 把 `controlPlaneFeedback.secondTurnEligible` 接入一个独立的二轮推理编排器，而不是继续把逻辑塞进 `api.py`。
3. 设计 Java 工具状态事件流：执行完成、失败、等待审批、审批通过、取消等状态通过 Kafka/WebSocket 推送。
4. 随后回看 gateway 权限矩阵，保护 plan ingestion、feedback/result query、event replay 这些 AI 控制面入口。

## 4.08 Python 受控 Agent Loop 策略对象（2026-05-28）

本阶段继续推进 AI Agent 主链的商业化保护，而不是直接把 `secondTurnEligible` 接到自动模型循环。真实 Codex / Claude Code 类 Agent 虽然看起来可以持续调用工具，但企业数据治理产品必须先有 loop 守卫：最大步数、二轮次数、token/成本预算、超时、等待审批阻断、人工接管、取消等条件必须能被统一判断和审计。

已完成：

- 新增 `agent_loop_control_policy.py`，定义受控 Agent loop 策略与决策模型。
- 新增 `AgentLoopControlPolicy`，覆盖 maxToolSteps、maxSecondTurns、maxToolCallsPerTurn、maxTotalTokens、globalTimeoutSeconds、toolWaitTimeoutSeconds、requireHumanTakeoverOnApproval、allowFailedFeedbackSecondTurn、allowSkippedFeedbackSecondTurn。
- 新增 `AgentLoopControlState`，表达当前 loop 的工具步数、已完成二轮次数、已消耗 token、预估下一轮 token、总耗时、等待控制面耗时、人工接管、取消等运行态。
- 新增 `AgentLoopControlAction`，将下一步动作明确为 allowSecondTurn、waitForControlPlane、waitForApproval、requireHumanTakeover、stopStepLimit、stopBudgetExceeded、stopTimeout、stopNoWork。
- 新增 `AgentLoopControlPolicyEvaluator`，消费 4.07 的 `AgentControlPlaneFeedbackSnapshot`，输出 `AgentLoopControlDecision`。
- 策略评估顺序采用“硬阻断优先”：取消、人工接管、全局超时、步数上限、二轮上限、预算上限先拦截，再判断反馈缺失、等待审批、失败/跳过策略，最后才允许二轮。
- `build_plan_response(...)` 支持可选 `loop_control_evaluator`，在 `controlPlaneFeedback` 生成后同步返回 `agentLoopControl` 摘要。
- `create_app()` 在启用控制面反馈收集器时默认创建 `AgentLoopControlPolicyEvaluator`。
- 新增 `test_agent_loop_control_policy.py`，覆盖允许二轮、等待审批人工接管、反馈缺失等待、等待超时、预算超限、二轮次数超限、严格租户策略阻断失败反馈。

产品意义：

- DataSmart 的 Agent loop 开始具备“可治理的自主性”，不是让模型无限继续，而是让模型每一步都必须通过策略闸门。
- `agentLoopControl` 可以直接服务前端、智能网关和审计面板，让用户看到为什么继续、为什么等待、为什么停止。
- 策略对象与 API、模型 Provider、Java 查询解耦，后续可以替换为租户策略、Java 控制面配置、灰度开关或运维熔断规则。
- 这一步为下一阶段的独立二轮推理编排器打基础，避免把多步 Agent loop 写成隐藏在 `api.py` 里的副作用。

当前边界：

- 当前策略只做内存评估，尚未从 Java 控制面、Nacos、Redis 或租户配置中心加载策略。
- 当前只输出决策，不真正触发二轮模型推理。
- 当前没有接入实时事件流，因此等待控制面反馈仍依赖调用方重新请求或后续 replay。
- `api.py` 已到 480 行，下一轮如继续扩 API 行为，应优先拆分 Agent plan response helper，避免突破 500 行约束。

验证：

- `python -m unittest discover -s python-ai-runtime\tests` 通过，166 个测试成功。
- Python AST 语法检查通过，覆盖 90 个 Python 文件。
- 文件规模检查：`agent_loop_control_policy.py` 316 行、`agent_control_plane_feedback.py` 268 行、`api.py` 480 行、`services/__init__.py` 239 行、新增策略测试 130 行，均低于 500 行约束。
- 本阶段未修改 Java 源码，因此未重新执行 Maven。

下一步路线：

1. 拆分 `api.py` 中的 Agent plan response 组装逻辑，先把文件规模风险压下来。
2. 新增独立二轮推理编排器，消费 `controlPlaneFeedback + agentLoopControl`，只在策略允许时构造 tool result messages 并调用模型。
3. 为 `agentLoopControl` 增加 runtime event，让前端和审计能看到 loop 被允许、等待、停止或人工接管的原因。
4. 设计 Java 工具状态事件流与 gateway 权限矩阵，把控制面反馈从同步快照演进为实时可恢复的 Agent 运行时。

## 4.09 Python AI Runtime Agent Plan Response 组装解耦（2026-05-28）

本阶段没有继续盲目堆 Agent 新能力，而是先处理 4.08 暴露出的结构性风险：`api.py` 已经接近 500 行，并同时承担 FastAPI 路由、运行时依赖装配、AgentPlan 响应组装、事件 envelope、Java 控制面接入、反馈快照和 loop 决策摘要。若继续把二轮推理、实时事件或长期记忆接入写进去，后续会很快变成难维护的大文件。

已完成：
- 新增 `python-ai-runtime/src/datasmart_ai_runtime/api_plan_response.py`，集中承载同步 HTTP AgentPlan 响应组装。
- `api.py` 删除旧的 `build_plan_response(...)` 实现，改为从新模块导入，保持原有调用入口兼容。
- 新模块负责 `plan -> controlPlaneIngestion -> controlPlaneFeedback -> agentLoopControl -> eventEnvelope -> response` 的响应组合链路。
- 事件存储、WebSocket live push、Kafka publisher、Java plan ingestion、控制面反馈快照和 loop 决策仍然全部通过显式参数注入，不在默认路径制造隐藏副作用。
- 将 `_build_control_plane_feedback(...)`、`_publish_plan_events(...)`、`_build_base_response(...)` 拆成小函数，便于后续把二轮推理编排器接在策略允许之后，而不是继续膨胀 API 路由层。

设计意义：
- `api.py` 回归“HTTP 应用创建、路由声明、依赖装配”职责，避免 API 层吞掉 Agent 业务编排。
- AgentPlan 响应组装成为独立协议适配层，后续同一套响应语义可以服务 REST、Gateway、WebSocket snapshot 或调试入口。
- 这一步为 Codex / Claude Code 风格的多轮 Agent 做了必要铺垫：二轮推理、工具结果回填、长期记忆写入和事件推送都应进入独立编排器，而不是塞进 FastAPI route。
- 文件规模回到安全区，符合“单文件尽量低于 500 行”的工程约束。

验证：
- `python -m unittest discover -s python-ai-runtime\tests` 通过，166 个测试成功。
- Python AST 语法检查通过，覆盖 91 个 Python 文件。
- 文件规模检查：`api.py` 404 行、`api_plan_response.py` 149 行、`agent_loop_control_policy.py` 316 行、`agent_control_plane_feedback.py` 268 行，均低于 500 行约束。
- 本阶段未修改 Java 源码，因此未重新执行 Maven。

下一步路线：

1. 新增独立二轮推理编排器，消费 `controlPlaneFeedback + agentLoopControl`，只在策略允许时构造 tool result messages 并调用模型。
2. 为 `agentLoopControl` 增加 runtime event，让前端、审计和智能网关能看到 loop 被允许、等待、停止或转人工的原因。
3. 设计 Java 工具状态事件流，让执行完成、失败、等待审批、审批通过和取消通过 Kafka/WebSocket 主动驱动 Python 与前端。
4. 之后再回看长期记忆写入与模型网关 KV/prefix cache 策略，避免 Agent 越做越像只会同步 HTTP 调用的 demo。

## 4.10 Python AI Runtime 受控二轮推理编排器（2026-05-28）

本阶段把 4.08 的 loop policy 和 4.09 的响应组装边界真正接到“二轮模型推理”上，但仍然保持商业化安全边界：只有 Java 控制面反馈完整、loop policy 明确允许、模型路由可用、tool result messages 完整时，才触发第二轮模型调用。该二轮调用不再暴露 tools，并强制 `tool_choice="none"`，避免当前阶段形成无限工具递归。

已完成：
- 新增 `agent_second_turn_orchestrator.py`，负责受控二轮推理编排。
- 新增 `agent_second_turn_events.py`，负责二轮相关 runtime event 构建和 sequence 续接，避免编排器文件贴近 500 行。
- `AgentControlPlaneFeedbackItem` 保留内部安全结果摘要，并新增 `to_tool_feedback()`，用于把控制面反馈转换成模型回填消息；API 摘要仍不暴露完整 result。
- 新增事件类型 `agent_loop_control_decided` 和 `model_second_turn_skipped`，并纳入实时事件可见性策略。
- `api_plan_response.build_plan_response(...)` 支持显式注入 `second_turn_orchestrator`，在 plan ingestion、控制面反馈和 loop 决策之后执行，并返回 `agentSecondTurn` 摘要。
- `create_app()` 支持通过 `DATASMART_AGENT_RUNTIME_SECOND_TURN_ENABLED=true` 开启受控二轮推理，默认关闭，避免 API 响应隐藏触发额外模型调用。
- 新增 `test_agent_second_turn_orchestrator.py`，覆盖策略允许时调用模型、策略阻断时跳过、反馈消息不完整时跳过。

设计意义：
- Agent 主链从“能判断是否允许二轮”推进到“能在策略允许时真正执行受控二轮推理”。
- 二轮推理独立于 FastAPI route 和响应组装层，后续可继续升级为多步 loop、事件驱动 loop 或 LangGraph/OpenClaw 风格运行图。
- loop 决策、二轮跳过和二轮完成进入 runtime event，前端、审计、WebSocket replay 和智能网关可以看到为什么继续或为什么停止。
- 二轮工具结果仍来自 Java 控制面反馈，Python 不绕过 Java 执行真实业务工具，符合治理产品的审批、审计和幂等边界。

当前边界：
- 当前二轮仍是同步 invoke，不是 streaming token 输出。
- 当前最多是“控制面反馈 -> 一次二轮总结”，尚未进入真正多步工具循环。
- 当前 Java 工具状态反馈仍主要是同步快照，尚未由 Kafka/WebSocket 状态事件主动驱动。
- 当前二轮上下文先使用计划摘要、用户目标和 tool result messages，后续可继续加入长期记忆、显式 outputRef/jsonPath 和更精细的 token 预算估算。
- 本阶段未修改 Java 源码，因此未重新执行 Maven。

验证：
- `python -m unittest discover -s python-ai-runtime\tests` 通过，169 个测试成功。
- Python AST 语法检查通过，覆盖 94 个 Python 文件。
- 文件规模检查：`api.py` 415 行、`api_plan_response.py` 165 行、`agent_second_turn_orchestrator.py` 323 行、`agent_second_turn_events.py` 180 行、`agent_control_plane_feedback.py` 300 行，均低于 500 行约束。

下一步路线：

1. 设计 Java 工具状态事件流，让工具执行完成、失败、等待审批、审批通过和取消可以通过 Kafka/WebSocket 主动驱动 Python 重新评估 loop。
2. 为二轮推理增加 streaming 输出和 WebSocket token/summary 事件，让体验更接近 Codex / Claude Code。
3. 将 loop policy 从内存默认值升级为租户/项目可配置策略，包括最大步数、预算、模型成本、失败重试和人工接管。
4. 开始回看长期记忆写入与模型网关 KV/prefix cache 策略，让多轮 Agent 不只是多调一次模型，而是具备可恢复、可控成本的长期任务能力。

## 4.11 Java agent-runtime 工具执行状态事件发布契约（2026-05-28）

本阶段开始落实 4.10 之后的 Java 工具状态事件流。重点不是一次性完成完整实时闭环，而是先建立稳定的出站事件契约和发布端口：工具计划创建、审批、执行、成功、失败等状态变化都可以被发布为结构化事实，后续 Python Runtime、gateway WebSocket、审计中心和可观测性模块可以围绕同一事件源演进。

已完成：
- 新增 `AgentToolExecutionEventPublisher` 端口，避免核心审计服务直接依赖 Kafka。
- 新增 `AgentToolExecutionStateChangedEvent`，定义 `agent-tool-execution-event.v1` 契约，覆盖租户、项目、工作区、操作者、session、run、audit、tool、状态流转、审批、输出摘要、错误码和治理提示。
- 新增 `KafkaAgentToolExecutionEventPublisher`，启用后将事件序列化为 JSON 字符串投递到 `datasmart.agent-runtime.tool-execution-events`。
- 新增 `NoopAgentToolExecutionEventPublisher`，默认无副作用运行，保护本地开发和单元测试。
- 新增 `AgentToolExecutionEventProperties`，通过 `datasmart.agent-runtime.tool-execution-events` 管理开关、topic 和 source。
- `AgentToolExecutionAuditService` 在初始计划、审批通过、拒绝、开始执行、成功、失败后发布状态事件；发布失败只告警不回滚状态。
- 新增事件发布测试，覆盖状态顺序、审批/拒绝、安全 attributes、发布失败 fail-open。

设计意义：
- 工具执行从同步查询 DTO 继续升级为事件事实流，是类 Codex / Claude Code Agent 的关键基础能力。
- Java 控制面仍是执行、审批、审计、幂等事实源，Python Runtime 未来只订阅事实并决定是否继续推理，不绕过 Java 执行业务工具。
- 默认不发布完整工具入参和审批备注原文，避免把用户目标、敏感字段或数据源细节扩散到 Kafka/WebSocket 等通用通道。
- 当前采用 fail-open 保证状态机可用；商业化强一致版本应继续升级为事务 outbox。

当前边界：
- Kafka 发布默认关闭，生产启用前需要 topic、ACL、保留时间、告警、死信、重放和消费侧幂等。
- 当前没有实现 Java outbox、Python 事件订阅、gateway WebSocket 下游推送，仍是“事件源已建立，实时闭环待接入”。
- `AgentToolExecutionAuditService` 已到 434 行，后续继续增加持久化或 outbox 时应拆服务，避免超过 500 行。

验证：
- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 58 个测试成功。
- `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 全仓通过。
- 测试需使用 JDK 21：`C:\Users\Cui\.jdks\temurin-21.0.10`，当前默认 Maven 环境仍会落到 Java 8。

下一步路线：

1. 先补事件投影/replay 能力，让 Python Runtime 和前端能按 runId 查询或订阅工具状态。
2. 打通 gateway `/api/agent/events/ws` 与 Java 工具状态事件，让用户实时看到等待审批、执行中、成功、失败和跳过。
3. 将 Python 受控二轮编排器从同步反馈快照升级为“等待事件/重放事件后再评估 loop”。
4. 设计事务 outbox，解决状态变更成功但 Kafka 投递失败的生产可靠性问题。

## 4.12 Java agent-runtime 工具状态事件投影与 replay 底座（2026-05-28）

本阶段把 4.11 的 Java 工具状态事件接入已有 Agent runtime event 投影体系，而不是为工具事件另建一套查询链路。产品目标是让 Python 模型规划事件、Java 工具审批/执行事件、后续 gateway WebSocket replay 都能围绕同一条运行时事件时间线协作。

已完成：
- 新增 `AgentToolExecutionEventSink`，将事件发布扩展点拆成 Kafka、投影、未来 outbox、WebSocket、审计中心等可组合 sink。
- 新增 `DefaultAgentToolExecutionEventPublisher`，统一构造一次 `AgentToolExecutionStateChangedEvent` 后扇出给所有 sink，避免多出口 payload 不一致。
- 将 Kafka 发布器重构为 `KafkaAgentToolExecutionEventSink`，Kafka 从“唯一 publisher”降级为可选下游出口，仍默认关闭。
- 新增 `AgentToolExecutionEventProjectionSink`，把 Java 工具状态事件写入已有 `AgentRuntimeEventProjectionStore`，支持按 runId/sessionId 等维度 replay。
- `NoopAgentToolExecutionEventPublisher` 改为非 Spring Bean，仅用于显式无副作用单元测试，避免多个 publisher Bean 冲突。
- `AgentRuntimeEventVisibilitySupport` 将 `tool_execution` 纳入 BASIC 可见标记，让普通用户可以看到脱敏后的工具执行进度。

设计意义：
- Java 工具状态从“可发布事件”推进为“控制面可查询、可回放事件”，为前端实时进度、断线重连补偿和 Python 事件驱动 loop 打基础。
- 组合 publisher + sink 模式符合解耦和文件规模约束，后续新增 outbox/WebSocket/审计中心不需要继续修改 `AgentToolExecutionAuditService`。
- 投影 attributes 只写安全白名单字段，不写完整工具入参、审批备注原文或工具完整输出，保证事件可见性和敏感上下文保护并存。

当前边界：
- 当前投影仍是 JVM 内存热窗口，不能替代长期审计和多实例共享查询。
- 尚未打通 gateway `/api/agent/events/ws` 下游推送，也尚未让 Python Runtime 等待/订阅这些事件。
- 事务 outbox 仍未实现，生产可靠性下一步需要补齐。

验证：
- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 61 个测试成功。
- `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 全仓通过。

下一步路线：

1. 打通 gateway `/api/agent/events/ws` 与 runtime-event 投影/工具状态事件，形成实时推送 + replay 补偿。
2. 让 Python 受控二轮编排器支持等待 Java 工具状态事件，再重新评估 loop 是否继续。
3. 设计事务 outbox 与持久化投影，补齐生产环境事件可靠性和长期审计能力。
4. 事件闭环稳定后，继续推进长期记忆写入、模型网关 KV/prefix cache、Skill 工具市场和多 Agent 协作。

## 4.13 Python AI Runtime Java runtime-event replay 桥接（2026-05-28）

本阶段把 Java `agent-runtime` 的 runtime-event 投影接入 Python AI Runtime 的 HTTP replay 与 WebSocket subscribe/reconnect 链路。产品目标是先让前端和智能网关能在同一个 replay envelope 中恢复“Python 模型编排事件 + Java 工具执行状态事件”，而不是让两条时间线各查各的。

已完成：
- 新增可插拔 `RuntimeEventReplaySource` 和 `RuntimeEventReplayCoordinator`，后续 Java HTTP、Kafka、Redis Stream、审计库都可以按同一协议接入。
- 新增 `JavaAgentRuntimeEventReplayClient`，把 Java `AgentRuntimeEventProjectionView` 映射成 Python `AgentRuntimeEvent`。
- WebSocket 订阅状态机、HTTP replay helper、运行时组件组装层和 diagnostics 均支持外部 replay source。
- `create_app()` 支持通过 `DATASMART_AGENT_RUNTIME_EVENT_REPLAY_ENABLED=true` 与 `DATASMART_AGENT_RUNTIME_BASE_URL` 打开 Java replay 桥。
- Python runtime event 类型新增 `agent.tool_execution.state_changed`，并允许普通用户看到脱敏后的 `tool_execution` 进度事件。

设计意义：
- Agent 实时体验从“只看 Python 编排轨迹”升级为“模型规划 + 工具执行事实”合并回放，离 Codex / Claude Code 类 Agent 的可恢复执行轨迹更近一步。
- replay source 抽象让智能网关不绑定某一个存储或协议，未来可以逐步升级为 Redis/Kafka/outbox/审计库，而不推翻 WebSocket 协议。
- Java 查询失败采用 fail-open，订阅仍成功并在 envelope attributes 中返回 `externalReplayErrors`，方便运维诊断。

当前边界：
- 当前不是实时主动推送 Java 事件，只是 subscribe/reconnect/replay 时查询 Java 热投影。
- 外部事件缺少统一 sequence 时使用临时 replay sequence，生产级严格续传需要统一事件游标或持久化 outbox。
- Java 投影仍是 JVM 热窗口，长期审计、多实例共享和重启恢复仍需持久化方案。

验证：
- `python -m unittest discover -s python-ai-runtime\tests` 通过，175 个测试成功。
- Python AST 语法检查通过。
- 关键文件均低于 500 行：Java replay client 221 行、replay coordinator 137 行、session manager 345 行、runtime components 408 行、api.py 382 行。

下一步路线：

1. 让受控二轮推理从“同步快照判断”升级为“等待/replay 工具状态事件后重新评估 loop”。
2. 推进 Java 事务 outbox 与持久化投影，补齐生产可靠性、重放和长期审计。
3. 统一 Python/Java runtime event sequence 或游标，支撑严格断线续传、前端去重和多实例一致性。
4. 在事件闭环达到阶段稳定后，切入长期记忆、模型网关 KV/prefix cache、Skill 工具市场和多 Agent 协作。

## 4.14 Python AI Runtime runtime-event 驱动的受控 loop 决策（2026-05-28）

本阶段把 4.13 的 Java runtime-event replay 继续接入 Agent loop 决策链路。目标是让 replay 到的工具状态事件不只用于前端展示，也能补齐/刷新控制面反馈快照，再由 loop policy 判断是否允许二轮推理、等待控制面、等待审批或转人工。

已完成：
- 新增 `AgentRuntimeEventFeedbackBridge`，在同步反馈收集器和 loop policy 之间合并 replay 事件。
- 新增 `AgentRuntimeEventFeedbackAugmentation`，对外输出 replayed、derived、replaced、externalErrors 和 effectiveSecondTurnEligible 等诊断摘要。
- API 响应组装顺序调整为：plan ingestion -> 同步 feedback snapshot -> runtime-event feedback augment -> loop policy -> second turn orchestrator。
- `create_app()` 支持 `DATASMART_AGENT_RUNTIME_EVENT_LOOP_FEEDBACK_ENABLED=true` 开启事件驱动 loop 反馈桥。
- API 响应新增可选 `runtimeEventFeedback`，说明本次 loop 决策是否使用 replay 事件补齐或刷新了反馈。
- 新增测试覆盖终态事件补齐缺失反馈、执行中事件不提前放行、成功事件刷新等待反馈、API 组装层在 loop policy 前应用事件反馈。

设计意义：
- 工具状态事件从“运行进度可见性”升级为“Agent 控制流事实”，让 DataSmart 的 Agent 主链更接近真实可恢复工具型 Agent。
- 同步结果查询提供完整安全结果，runtime-event replay 提供可恢复状态事实，二者通过快照合并进入统一 loop policy。
- Python 仍不执行工具、不推进审批，只消费 Java 控制面事实，继续保持企业治理边界。

当前边界：
- 当前是请求时 replay 后重新评估，不是后台事件驱动 worker。
- 事件派生反馈只能提供安全状态摘要，不能替代完整工具输出。
- 统一 sequence、事务 outbox、持久化投影仍需后续推进。

验证：
- `python -m unittest discover -s python-ai-runtime\tests` 通过，179 个测试成功。
- Python AST 语法检查通过。
- 关键文件均低于 500 行：事件反馈桥 404 行、响应组装 152 行、api.py 392 行。

下一步路线：

1. 优先补 Java 工具事件事务 outbox/持久化投影，提升事件可靠性和长期审计能力。
2. 设计 Python/Java runtime event 统一游标，支撑严格断线续传和多源事件排序。
3. 将请求时 replay 决策升级为后台事件触发的 loop 恢复机制。
4. 事件链路稳定后，切到长期记忆、KV/prefix cache、Skill 工具市场和多 Agent 协作。

## 4.15 Java agent-runtime 工具执行事件 outbox 底座（2026-05-28）

本阶段补齐 Java 工具状态事件的可靠性底座。产品目标不是继续扩展更多工具，而是先降低“状态已变更但事件丢失”的生产风险，为后续真实 Codex/Claude Code 类 Agent 的可恢复工具链路打基础。

已完成：
- 新增工具执行事件 outbox 配置、状态枚举、记录模型、诊断摘要、仓储接口和内存实现。
- 新增 outbox sink，优先捕获统一工具状态事件，再让 Kafka/projection 等下游继续处理。
- 新增 outbox 查询与诊断 API，支持按 runId/status 查看待投递、失败、阻断等状态。
- 新增 MySQL outbox 表迁移脚本，明确生产持久化方案、唯一键、重试索引和租户/项目查询索引。
- application.yml 补充详细 outbox 配置注释，并清理重复基础配置块。

当前边界：
- 当前是内存 outbox，不是生产持久化；JVM 重启和多实例场景仍需要 MySQL store。
- 当前没有后台 dispatcher，PENDING/FAILED 不会自动投递；Kafka sink 仍保留直接投递。
- 工具审计状态本身仍待落库，下一步应实现状态变更与 outbox INSERT 的同事务提交。

验证：
- agent-runtime 模块测试通过，65 个测试成功。
- 全仓 Maven 测试通过。

下一步路线：
1. 工具审计状态 MySQL 化 + 数据库版 outbox store。
2. outbox dispatcher 与投递失败重试/死信/人工补偿。
3. Python/Java runtime event 统一 sequence/cursor。
4. 事件可靠性稳定后，转向长期记忆、模型网关缓存治理、Skill 市场和多 Agent 协作。

## 4.16 Java agent-runtime 工具执行审计持久化契约（2026-05-28）

本阶段没有继续堆更多 Agent 工具，而是先把工具审计状态从内存实现中解耦出来。产品目标是让工具计划、审批、执行、成功、失败这些状态未来可以落到 MySQL，并与 outbox 进入同一事务边界，支撑真实生产环境的恢复、审计和 replay。

已完成：
- 新增 `AgentToolExecutionAuditStore` 端口，`AgentToolExecutionAuditMemoryStore` 成为默认内存适配器。
- 新增 `datasmart.agent-runtime.persistence` 配置，默认 `audit-store=memory`，预留数据库启用开关。
- 工具状态流转后改为先保存审计状态，再发布状态事件。
- 新增 `agent_tool_execution_audit` MySQL 迁移脚本，明确工具审计生产表结构与索引。
- 新增测试固定保存顺序和保存失败不发布事件的可靠性语义。

当前边界：
- 当前仍是内存运行时仓储，MySQL 表已定义但未接入运行时代码。
- 状态与 outbox 仍未形成同一事务，下一步需要数据库版 audit store 与 outbox store。

验证：
- agent-runtime 模块测试通过，67 个测试成功。
- 全仓 Maven 测试通过。

下一步路线：
1. 实现数据库版 `AgentToolExecutionAuditStore`，保持本地 memory 默认。
2. 实现数据库版 outbox store，并把状态更新与 outbox INSERT 放入同一事务。
3. 实现 outbox dispatcher 和失败补偿。
4. 稳定事件耐久性后，推进统一 sequence/cursor、长期记忆、模型网关缓存治理和 Skill 市场。

## 4.17 Java agent-runtime MySQL 工具执行审计仓储（2026-05-28）

本阶段把 4.16 的持久化契约继续推进为可启用的 MySQL 仓储。为了不让 agent-runtime 默认启动依赖数据库，本阶段没有引入 MyBatis-Plus starter，而是采用条件化 Hikari + 手写 JDBC：只有显式打开 `audit-store=mysql` 和 `database-enabled=true` 时才启用。

已完成：
- 新增 `mysql-connector-j` 与 `HikariCP` 依赖。
- 新增 `AgentRuntimeJdbcPersistenceConfiguration`，条件化创建 `agentRuntimeJdbcDataSource`。
- 新增 `JdbcAgentToolExecutionAuditStore`，实现工具审计 upsert、批量事务保存、按 auditId 查询和按 session/run 查询。
- 扩展 `datasmart.agent-runtime.persistence.jdbc` 配置，支持环境变量覆盖数据库连接。
- `AgentToolExecutionAuditRecord` 增加包内恢复方法，用于从数据库还原历史审批和执行状态。

当前边界：
- 默认仍使用 memory，不会自动连 MySQL。
- 当前尚未实现数据库版 outbox store，也还没有把 audit 与 outbox 放入同一事务。
- 当前没有真实 MySQL 集成测试，下一步需要补测试容器或本地集成验证策略。

验证：
- agent-runtime 模块测试通过，67 个测试成功。
- 全仓 Maven 测试通过。
- 新增关键文件均低于 500 行。

下一步路线：
1. 数据库版 `AgentToolExecutionEventOutboxStore`。
2. 审计状态 + outbox 同事务提交。
3. outbox dispatcher 与失败补偿。
4. 统一 sequence/cursor 后，切向长期记忆、模型网关缓存治理、Skill 市场和多 Agent 协作。

## 4.18 Java agent-runtime MySQL 工具执行事件 outbox 仓储（2026-05-28）

本阶段把工具执行事件 outbox 从内存热窗口推进到可启用的 MySQL store。这样工具审计状态和事件箱都具备数据库路径，下一步才能实现真正的事务 outbox。

已完成：
- 新增 `persistence.outbox-store` 配置，默认 `memory`。
- JDBC 连接池条件支持 audit/outbox 任一 MySQL store 启用。
- 内存 outbox store 增加条件注册，避免 MySQL store 启用时 Bean 冲突。
- 新增 `JdbcAgentToolExecutionEventOutboxStore`，支持 append、查询、可投递列表、状态流转和 diagnostics。
- 数据库 append 使用 outbox_id/event_id 唯一键幂等去重。

当前边界：
- 默认仍是 memory，没有真实 MySQL 集成测试。
- audit 与 outbox 还没进入同一事务。
- dispatcher 尚未实现，PENDING/FAILED 不会自动投递。

验证：
- agent-runtime 模块测试通过，67 个测试成功。
- 全仓 Maven 测试通过。
- 新增关键文件均低于 500 行。

下一步路线：
1. 审计状态 + outbox append 同事务提交。
2. outbox dispatcher 与失败重试/补偿。
3. MySQL audit/outbox 集成测试。
4. 统一 sequence/cursor，随后推进长期记忆、模型网关缓存治理、Skill 市场和多 Agent 协作。

## 4.19 Java agent-runtime 工具审计与 outbox 同事务边界（2026-05-28）

本阶段没有继续扩展更多工具，而是补上商业化 Agent 工具链路的关键一致性缺口：在双 MySQL 配置下，工具审计状态保存与 outbox 事件写入现在可以复用同一条 JDBC 事务连接。

已完成：
- 新增 `AgentRuntimeJdbcConnectionManager`，提供事务内连接复用与失败 rollback。
- MySQL audit store 与 MySQL outbox store 改为通过连接管理器获取连接。
- `AgentToolExecutionAuditService` 在 `audit-store=mysql + outbox-store=mysql + database-enabled=true` 时包裹状态保存和事件发布。
- 新增必达 sink 语义，事务 outbox 失败时抛出专用异常，普通 Kafka/WebSocket/projection 仍保持 fail-open。
- 新增 outbox `required-for-state-commit` 配置与相关测试。

当前边界：
- 仍未实现 outbox dispatcher，PENDING/FAILED 事件不会自动投递。
- 仍缺真实 MySQL 集成测试与统一 sequence/cursor。
- 当前事务边界只覆盖同一 JVM 同一线程的同步调用链。

验证：
- agent-runtime 模块测试通过，71 个测试成功。
- 全仓 Maven 测试通过。
- 关键 Java 文件均低于 500 行。

下一步路线：
1. outbox dispatcher 与失败重试/补偿。
2. MySQL audit/outbox 集成测试或测试容器。
3. Python/Java runtime event 统一 sequence/cursor。
4. dispatcher 稳定后转向长期记忆、模型网关 KV/prefix cache、Skill 市场和多 Agent 协作。

## 4.20 Java agent-runtime 工具事件 outbox dispatcher（2026-05-28）

本阶段把事务 outbox 从“可靠写入事件凭据”推进到“后台投递和失败重试”。它不是新增 Agent 工具，而是补商业化运行所需的事件恢复能力。

已完成：
- 新增 `AgentToolExecutionEventOutboxDispatchTarget`，作为 dispatcher 下游目标扩展点。
- 新增 `AgentToolExecutionEventOutboxDispatcher`，支持 PENDING/FAILED 拉取、PUBLISHING 领取、PUBLISHED 成功、FAILED 重试。
- 新增 Kafka dispatch target，等待 broker ack 后才标记发布成功。
- 新增条件化调度配置，默认关闭 dispatcher，生产通过 `dispatcher-enabled` 显式启用。
- 扩展 outbox dispatcher 配置，包括 batchSize、fixedDelay、maxAttempts、retry backoff、Kafka send timeout 等。

当前边界：
- 当前仍是单 JVM 最小 dispatcher，没有多实例抢占锁和 stale PUBLISHING 恢复。
- 当前只有 Kafka 目标，WebSocket、审计中心、长期事件库目标还未实现。
- 当前没有真实 MySQL/Kafka 集成测试，仍需后续补测试容器或本地集成验证。

验证：
- agent-runtime 模块测试通过，75 个测试成功。
- 全仓 Maven 测试通过。
- 新增关键 Java 文件均低于 500 行。

下一步路线：
1. dispatcher 多实例安全领取与 stale PUBLISHING 恢复。
2. dead-letter/BLOCKED 人工补偿入口。
3. WebSocket/审计中心 dispatch target 与统一 sequence/cursor。
4. 事件链路稳定后，切向长期记忆、模型网关缓存治理、Skill 市场和多 Agent 协作。
## 4.21 Java agent-runtime outbox dispatcher 领取竞态与 BLOCKED 保护（2026-05-28）

本阶段继续补齐 Agent 工具执行事件的生产可靠性，但刻意控制在 dispatcher 安全边界这一小块，不再继续扩展新的工具类型或业务功能。商业化 Agent 系统里，工具执行事件不仅要“能发出去”，还要解决多实例 worker 并发扫描、坏消息无限重试、运维侧无法判断是否需要人工补偿等问题。因此本阶段重点是让 outbox dispatcher 从基础投递器升级为具备状态机保护的后台投递组件。

已完成：
- `AgentToolExecutionEventOutboxStore` 新增 `markBlocked(...)`，把“可继续自动重试的 FAILED”和“已经停止自动重试、需要人工处理的 BLOCKED”明确区分。
- `AgentToolExecutionEventOutboxRecord` 新增实例级 `markBlocked(...)`，保留当前尝试次数，清空下一次自动重试时间，并记录阻断原因。
- 内存版 outbox store 的 `markPublishing(...)` 改为条件领取：只有 `PENDING` 或已到达 `nextRetryAt` 的 `FAILED` 记录允许进入 `PUBLISHING`，已经处于 `PUBLISHING`、`PUBLISHED`、`BLOCKED` 的记录不会被重复领取。
- JDBC 版 outbox store 的核心状态更新改为条件更新：`markPublishing` 只从可投递状态领取，`markPublished` 和 `markFailed` 只允许从 `PUBLISHING` 推进，`markBlocked` 允许从 `PENDING/PUBLISHING/FAILED` 进入 `BLOCKED`，但不会覆盖已经完成的 `PUBLISHED`。
- dispatcher summary 新增 `blocked` 计数。当某条事件领取后的 `attemptCount` 超过 `dispatcherMaxAttempts` 时，不再继续写回 `FAILED` 等待重试，而是直接标记为 `BLOCKED`。
- `application.yml` 和配置属性注释补充中文说明：最大尝试次数达到后代表自动修复价值下降，后续应由运维台提供重新入队、忽略、导出排查和修复后补偿能力。
- 补充 dispatcher 单元测试，覆盖并发领取保护、超过最大尝试次数进入 BLOCKED、成功投递、失败重试、未到重试时间跳过和无目标保护等场景。

设计意义：
- 条件更新是多实例 dispatcher 的第一层生产保护。它不依赖额外的分布式锁组件，而是直接利用 outbox 记录自身的状态机，避免多个 worker 同时把同一条事件投递给 Kafka 或后续事件中心。
- BLOCKED 状态把坏消息从自动重试队列中显式移出，避免契约错误、权限错误、下游配置错误或不可恢复数据错误造成 Kafka、MySQL、线程池和告警系统的资源风暴。
- dispatcher summary 能区分 failed 和 blocked，后续接入 Prometheus、运维台或告警中心时可以采用不同处理策略：FAILED 关注自动恢复速度，BLOCKED 关注人工排障和补偿闭环。
- 本阶段继续遵守低耦合和文件规模约束，关键 Java 文件均保持在 500 行以内，没有把新逻辑继续堆进大型 Impl。

当前边界：
- 尚未实现 stale `PUBLISHING` 超时恢复。如果 worker 在领取后崩溃，记录仍可能长时间停留在 `PUBLISHING`，下一步需要补 `lockedAt/lockExpireAt/workerId` 或基于更新时间的恢复扫描。
- 尚未提供 BLOCKED 人工补偿 API。真实运维台还需要支持查询阻断事件、重新入队、忽略、导出排障包、追加处理备注和审计留痕。
- 尚未引入真实 MySQL/Kafka 集成测试；当前主要通过内存 store 状态机和全仓单元测试保护语义，数据库 SQL 仍需要后续集成验证。
- 尚未完成统一 sequence/cursor。dispatcher 安全领取解决的是“谁能投递、如何避免重复投递”，但还没有解决多来源事件严格排序、断线续传和前端去重问题。

验证：
- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 77 个测试成功。
- `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 全仓通过，gateway、permission-admin、task-management、datasource-management、data-sync、data-quality、agent-runtime、observability 均成功。
- 本机默认 Maven 当前会使用 Java 8，验证 Java 21 语法时需要临时设置 `JAVA_HOME=C:\Users\Cui\.jdks\temurin-21.0.10`；后续建议固定 Maven toolchain 或项目级 JDK 说明，避免误用 Java 8 造成假失败。

下一步路线：
1. 补 stale `PUBLISHING` 恢复与 worker 领取诊断，避免后台 worker 崩溃后事件永久卡住。
2. 补 BLOCKED 人工补偿 API 与运维审计闭环，形成真实生产环境可处理的异常事件治理能力。
3. 完成 outbox 生产保护后，控制 Java outbox 深挖范围，转向统一 sequence/cursor、长期记忆写入、模型网关 KV/prefix cache、Skill 工具市场和多 Agent 协作。

## 4.22 Java agent-runtime stale PUBLISHING 恢复与 JDK 21 构建固定（2026-05-28）

本阶段完成两条收口工作：一条是继续补齐 outbox dispatcher 的生产可靠性，避免 worker 崩溃后事件永久卡在 `PUBLISHING`；另一条是修复本机默认 Maven 使用 Java 8 导致 Java 21 语法误报的问题，让项目构建环境更稳定。

已完成：
- `AgentToolExecutionEventOutboxStore` 新增 `recoverStalePublishing(...)`，用于把超时未完成的 `PUBLISHING` 记录恢复为 `FAILED`，并设置 `nextRetryAt=now` 让 dispatcher 后续可以补偿重试。
- 内存版 outbox store 实现 stale 恢复，保留 attemptCount，避免运维侧丢失“曾被 worker 领取过”的诊断事实。
- JDBC 版 outbox store 实现基于 `update_time` 的轻量 stale 恢复，不立即强制引入 workerId/lockExpireAt 字段，降低本阶段迁移风险。
- 新增 `JdbcAgentToolExecutionEventOutboxRecordMapper`，把 JDBC 字段映射、参数绑定和 ResultSet 还原从 Store 中拆出，避免 `JdbcAgentToolExecutionEventOutboxStore` 再次逼近 500 行。
- dispatcher 每轮先执行 stale 恢复，再扫描可投递记录；summary 新增 `recovered` 计数，便于后续接入指标和告警。
- outbox MySQL 建表脚本补充 `idx_agent_tool_event_outbox_status_update(status, update_time, id)`，支撑按状态和更新时间扫描 stale `PUBLISHING`。
- 根 `pom.xml` 接入 `maven-toolchains-plugin:3.2.0`，在 `validate` 阶段自动选择 `[21,22)` JDK，避免本机 Maven 运行在 Java 8 时编译/测试误报。
- 新增 `docs/development-jdk21.md`，说明 JDK 21 要求、Toolchains 自动发现、`~/.m2/toolchains.xml` 兜底配置和常见误区；README 增加入口说明。
- 补充 dispatcher 测试，覆盖 stale `PUBLISHING` 被恢复后立即重新投递并成功发布的场景。

设计意义：
- stale 恢复解决的是 outbox dispatcher 的“半投递卡死”风险。商业化系统不能只考虑投递失败，还要考虑 worker 在发送过程中崩溃、进程重启、节点宕机、网络长时间挂起等不返回结果的场景。
- 本阶段选择基于 `update_time` 作为轻量领取时间，是为了不在当前阶段扩大表结构变更和实体字段改造范围；它能先解决“永久卡住”问题，但后续多实例运维台仍应升级为显式 `workerId/lockedAt/lockExpireAt`。
- `recovered` 计数为后续 Prometheus、运维台和告警提供了独立信号：如果 recovered 持续升高，说明 worker 崩溃、下游卡顿或 timeout 配置过小，需要专项排查。
- Toolchains 固定让“项目需要 JDK 21”成为构建契约，而不是依赖每次手工设置 `JAVA_HOME`。这对长期协作、CI/CD 和新机器接入很重要。

当前边界：
- stale 恢复可能造成重复投递，因此下游 Kafka 消费者、WebSocket 推送、审计中心和未来长期事件库必须继续按 `eventId/outboxId` 做幂等。
- 当前没有记录 workerId，无法在诊断页精确显示是哪台实例领取了事件；后续需要补显式租约字段和 worker 心跳。
- 当前没有 BLOCKED 人工补偿 API，仍需要下一阶段补重新入队、忽略、导出、备注和审计闭环。
- Toolchains 自动发现依赖本机存在可发现的 JDK 21；如果某台机器发现失败，需要按 `docs/development-jdk21.md` 配置用户级 `toolchains.xml`。

验证：
- 未手动设置 `JAVA_HOME`，直接执行 `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 78 个测试成功。
- 构建日志确认 `maven-compiler-plugin` 和 `maven-surefire-plugin` 均使用 `Toolchain ... JDK[C:\Users\Cui\.jdks\temurin-21.0.10]`。
- `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 全仓通过，所有 Java 微服务模块均成功。
- 关键 Java 文件行数均低于 500 行：`JdbcAgentToolExecutionEventOutboxStore.java` 401 行，`JdbcAgentToolExecutionEventOutboxRecordMapper.java` 221 行，`AgentToolExecutionEventOutboxDispatcher.java` 213 行，`InMemoryAgentToolExecutionEventOutboxStore.java` 328 行。

下一步路线：
1. 补 BLOCKED 人工补偿 API 和运维审计闭环，结束 outbox 生产保护的最后一块核心短板。
2. 补 workerId/lockedAt/lockExpireAt 显式租约字段，作为后续多实例 dispatcher 运维台能力，而不是继续用 update_time 推断。
3. outbox 收口后，转入统一 sequence/cursor、长期记忆、模型网关 KV/prefix cache、Skill 工具市场和多 Agent 协作，避免 Java 局部继续无限细化。

## 4.23 Java agent-runtime outbox BLOCKED 人工补偿 API 与权限闭环（2026-05-28）

本阶段把 Agent 工具执行事件 outbox 从“自动投递与阻断诊断”推进到“可人工恢复、可人工归档、可追加处理备注、可被权限中心独立识别”的运维闭环。它是 Java outbox 可靠性链路的阶段性收口，不再继续无边界扩展局部细节，后续主线应转向智能网关、长期记忆、模型调用治理和工具/Skill 能力。

已完成：
- outbox 状态新增 `IGNORED`，用于表达人工确认无需继续投递，避免把“人工忽略”误记为“系统投递成功”。
- outbox record/store/service/controller 全链路新增：
  - `requeue`：FAILED/BLOCKED -> PENDING；
  - `ignore`：FAILED/BLOCKED -> IGNORED；
  - `notes`：追加最近人工处理备注，不改变投递状态。
- 新增 `AgentToolExecutionEventOutboxOperationService`，集中处理 outbox 是否存在、状态是否合法、reason 是否必填、操作者解析和结构化备注生成。
- gateway route metadata 显式拆分 outbox 权限动作：`VIEW_OUTBOX_EVENTS`、`DIAGNOSE`、`REQUEUE_OUTBOX`、`IGNORE_OUTBOX`、`ANNOTATE_OUTBOX`。
- permission-admin 动作枚举、初始化脚本和迁移脚本补齐 outbox 策略：
  - AUDITOR 只读查看；
  - OPERATOR 可查看、诊断、重新入队、追加备注；
  - OPERATOR 默认禁止 ignore；
  - AUDITOR 禁止写；
  - SERVICE_ACCOUNT 禁止人工处理；
  - 平台管理员继续通过全局策略保留 break-glass 能力。
- 新增 outbox operation service 测试与 gateway outbox 授权语义测试，并把 gateway 测试拆分到独立文件，避免单文件超过 500 行。

设计意义：
- requeue、ignore、notes 的业务风险不同，必须拆成独立 action。这样权限中心才能区分“重放历史事件”“人工归档异常事件”“记录排障备注”，而不是只看到模糊的 POST=CREATE。
- 默认不给 OPERATOR ignore，是为了把“停止自动补偿”这类高风险动作保留给平台管理员或未来审批流。
- 当前阶段用 lastError 保存最近人工处理说明，先保证排障可用；下一阶段如果要做完整事故复盘，应增加 outbox_operation_audit 表。

当前边界：
- 尚未实现独立 outbox 操作审计表和审批单关联。
- 尚未加入 idempotency key，重复提交主要依赖状态条件更新保护。
- outbox 查询还没有按 gateway 数据范围强制过滤，暂不开放给普通用户。
- 还没有 workerId/lockedAt/lockExpireAt 显式租约字段和真实 MySQL/Kafka 集成测试。

验证：
- `mvn -pl gateway,permission-admin,agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过。
- 拆分 gateway 测试后，`mvn -pl gateway -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过。
- `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 全仓通过。
- 关键文件均保持在 500 行以内。

下一步路线：
1. 优先进入统一 sequence/cursor，让 Python Runtime、Java outbox、gateway WebSocket、前端 replay 和审计回放共享稳定游标。
2. 并行推进长期记忆能力：session memory、tenant/project memory、记忆写入审批、检索权限、遗忘策略。
3. 推进智能网关/OpenClaw 类能力：模型 Provider 抽象、KV/prefix cache、工具调用治理、模型降级、成本/延迟指标。
4. 推进 Agent 工具/Skill 市场：工具注册、schema、风险等级、审批策略、执行沙箱和可观测性。

## 4.24 AI Runtime 统一 replaySequence/sourceCursors 第一阶段（2026-05-28）

本阶段从 Java outbox 收口正式转向 AI 主线事件基础设施。目标是让 Python Runtime、Java 控制面投影、gateway WebSocket、前端 replay 和未来审计回放共享稳定游标语义：展示层继续使用 envelope sequence 做 ack，外部事件源则使用自己的 `sourceCursors` 做增量读取。

已完成：
- Java runtime-event projection 新增 `replaySequence`，与 producer `sequence` 明确分离。
- Java 投影查询支持 `afterSequence`，按 replaySequence 过滤断线续传事件。
- Python 订阅请求新增 `source_cursors/sourceCursors`，HTTP replay、WebSocket 控制消息和 Redis checkpoint 均支持该字段。
- Python Java replay client 使用 Java source cursor 下推查询，不再把展示层 afterSequence 误传给 Java。
- Python replay coordinator 会把外部 source 最新 cursor 写回 envelope attributes 的 `sourceCursors`，并在必要时把外部事件重映射为 envelope sequence。
- 补充 Java 与 Python 测试，覆盖 replaySequence 暴露、source cursor 下推、HTTP/WebSocket replay 回传和 Redis checkpoint 恢复。

设计意义：
- 解决“一个全局 afterSequence 同时承担展示 ack 与多源读取 cursor”带来的漏读、重复读和顺序混乱风险。
- 为 Codex/Claude Code 类 Agent 的长期运行、断线恢复、工具事件流、审计回放和多实例 Runtime 接管打基础。
- 保持前端协议可扩展：后续新增长期记忆事件、模型调用事件、工具市场事件或审计中心事件时，只需接入新的 source cursor，不必推翻 replay envelope。

当前边界：
- Java replaySequence 仍是内存热窗口游标，生产级需要迁移到 MySQL、Redis Stream、Kafka compacted topic 或专用 cursor 表。
- 尚未建设统一全局事件日志，当前仍由 Python replay coordinator 做轻量多源合并。
- sourceCursors 需要前端/SDK 下一次 reconnect 原样带回，尚未实现 gateway/SDK 自动续传。

验证：
- `python -m unittest discover -s python-ai-runtime\tests` 通过，181 个 Python 测试成功。
- `mvn -pl agent-runtime -am test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 通过，agent-runtime 84 个测试成功。
- `mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"` 全仓通过。
- 关键文件均保持在 500 行以内，Maven Toolchains 继续使用 JDK 21。

下一步路线：
1. 进入长期记忆能力：记忆候选、写入审批、session/tenant/project 作用域、检索权限和遗忘策略。
2. 推进智能网关/OpenClaw 类能力：Provider 抽象、模型降级、KV/prefix cache、成本/延迟指标。
3. 推进 Agent 工具/Skill 市场：schema、风险等级、审批策略、执行沙箱和工具调用观测。
4. 在事件链路稳定后，补生产级持久化事件日志和前端/gateway SDK 的 sourceCursors 自动续传。

## 4.25 AI Runtime 长期记忆写入候选与审批治理第一阶段（2026-05-28）

本阶段把 AI 主线从“记忆检索规划”推进到“记忆写入治理”。当前没有直接写 Chroma/Neo4j，而是先建立长期记忆写入前的候选、审批、拒绝和事件可见性闭环，避免企业数据治理场景中的敏感工具结果未经治理就进入长期上下文。

已完成：
- 新增记忆写入候选领域模型，覆盖候选状态、审批动作、候选摘要、审批决策和候选报告。
- 新增 `AgentMemoryWriteGovernanceService`，根据 `AgentMemoryPlan`、`ToolPlan`、Java 控制面反馈生成候选。
- 候选策略支持 DRAFT、PENDING_APPROVAL、APPROVED、REJECTED、IGNORED，当前只推进候选和决策，不直接持久化长期记忆。
- `build_plan_response(...)` 支持显式注入记忆写入治理服务，返回 `memoryWriteProposal` 并追加 runtime events。
- 新增 `memory_write_candidate_proposed` 与 `memory_write_decision_recorded` 事件类型。
- 新增测试覆盖候选生成、敏感审批、跳过策略、审批/拒绝和 API 响应接入。

产品意义：
- 这是长期记忆商业化前必须具备的安全闸门。Agent 记忆不是聊天历史缓存，而是会影响未来推理、工具选择和业务建议的治理资产。
- 先做候选审批，再接 Chroma/Neo4j，能避免把敏感字段、事故记录、导出路径、SQL 草案和审批上下文扩散到不可控记忆层。
- 该能力后续应与 permission-admin、审计中心、数据分级分类、脱敏策略和租户记忆策略联动。

当前边界：
- 候选暂存在 Python 内存中，尚未持久化。
- 尚未接入真实审批 API、权限动作和审批单。
- 尚未把 APPROVED 候选写入 Chroma/Neo4j/MinIO/MySQL。
- 尚未实现遗忘、过期清理、归档、索引重建和记忆质量评估。

验证：
- `python -m unittest discover -s python-ai-runtime\tests` 通过，187 个 Python 测试成功。

下一步路线：
1. 做记忆候选持久化与审批 API，接入 permission-admin 权限和审计。
2. 设计 APPROVED 候选异步写入 Chroma/Neo4j/MinIO/MySQL 的 worker。
3. 补记忆检索权限、遗忘策略和租户/项目/会话隔离压测。
4. 并行推进智能网关 KV/prefix cache 治理，避免长期记忆和推理缓存边界混淆。

## 4.26 AI Runtime 记忆写入候选存储与审批 API 第一阶段（2026-05-28）

本阶段把 4.25 的记忆写入候选从一次响应里的临时对象推进为可跨请求查询、可审批、可替换存储的运行时资源。仍然不直接写 Chroma/Neo4j，而是先补候选 store 和 HTTP 管理入口。

已完成：
- 新增 `AgentMemoryWriteCandidateStore` 协议与线程安全内存实现。
- `AgentMemoryWriteGovernanceService` 改为依赖可注入 store，并提供 `list_candidates(...)`。
- 新增 `api_memory_write.py`，注册候选列表、详情、审批通过和拒绝接口。
- `create_app()` 创建应用级记忆写入治理服务，`/agent/plans` 生成的候选可被后续 API 查询和审批。
- 新增测试验证候选过滤、路由注册和审批接口契约，同时保持 FastAPI 为可选依赖。

产品意义：
- 长期记忆候选开始成为“可管理资源”，后续审批台、审计台和异步写入 worker 都可以围绕候选 ID 协作。
- store 抽象让后续替换为 MySQL、Java memory-service 或审批中心时，不需要推翻治理服务。
- API 路由独立拆分，避免 `api.py` 再次膨胀。

当前边界：
- 当前 store 仍是内存实现，多实例和重启不可恢复。
- 当前 API 尚未接 gateway/permission-admin 鉴权，也没有审批单号、幂等 key 和操作审计表。
- 当前没有 APPROVED 候选的异步写入 worker。

验证：
- `python -m unittest discover -s python-ai-runtime\tests` 通过，189 个 Python 测试成功。
- Python AST 扫描通过，104 个 Python 文件解析成功。

下一步路线：
1. 接入 permission-admin 与 gateway 权限动作。
2. 设计 MySQL 候选表和操作审计表。
3. 实现 APPROVED 候选异步写入 Chroma/Neo4j/MinIO/MySQL。
4. 补遗忘、归档、过期清理和索引重建能力。
## 4.27 AI Runtime 记忆写入候选权限治理（2026-05-28）

本阶段把长期记忆写入候选从“Python Runtime 可查询资源”继续推进为“受平台权限中心保护的治理资源”。核心不是提前接入向量库，而是先确认谁能查看候选、谁能批准候选进入长期记忆、谁能拒绝候选写入，避免后续 Chroma/Neo4j/MySQL 写入 worker 被无权限调用链绕过。

已完成：
- permission-admin 动作枚举新增 `VIEW_MEMORY_WRITE_CANDIDATES`、`APPROVE_MEMORY_WRITE`、`REJECT_MEMORY_WRITE`。
- gateway route metadata 新增记忆候选列表/详情/批准/拒绝路径，并把 approve/reject 放在通配查询规则之前，避免授权语义被泛化。
- permission-admin 初始化 SQL 和迁移脚本新增候选权限策略，默认允许审计员/运营人员查看、项目负责人决策、服务账号禁止人工决策。
- gateway 新增专项测试固定四类路径到权限动作的映射，避免后续重构回退成普通 `VIEW` 或 `CREATE`。

产品意义：
- 长期记忆会影响 Agent 后续检索、规划和建议，不能当成普通缓存或普通日志处理。
- “批准写入”和“拒绝写入”都是审计事实，必须单独建模，后续才能扩展审批流、拒绝原因统计、数据分级联动和记忆质量评估。
- 当前策略采用保守商业化默认值：查看和决策分离，人工责任链优先，机器身份不默认批准长期记忆。

下一步：
1. 设计长期记忆候选 MySQL 表、操作审计表、幂等 key、版本号和审批单号。
2. 将 Python 内存 store 替换为可持久化 store 或 Java memory-service 客户端。
3. 在权限和持久化稳定后，再实现 APPROVED 候选异步写入 Chroma/Neo4j/MinIO/MySQL。

## 4.28 AI Runtime 记忆写入候选持久化地基（2026-05-28）

本阶段补齐长期记忆候选的关系型持久化契约：候选主表、操作审计表、候选版本号、幂等键和 Python DB-API store。它解决的是“审批资源不能只活在 Python 内存里”的问题，而不是立即把候选写入向量库。

已完成：
- 领域模型新增 `candidateVersion` 与 `idempotencyKey`。
- Python 新增 `SqlAgentMemoryWriteCandidateStore`，支持候选保存、查询、列表、状态更新和决策审计。
- MySQL 迁移脚本和初始化脚本新增 `agent_memory_write_candidate` 与 `agent_memory_write_candidate_audit`。
- 新增 sqlite3 单元测试验证关系型 store 语义，无需引入额外依赖。

产品意义：
- 审批台、审计台、异步写入 worker 可以围绕同一候选 ID 协作。
- 版本号为并发审批和重试保护打基础。
- 幂等键为消息重试、Runtime 重启恢复和 Java memory-service 回调去重打基础。
- 操作审计让批准/拒绝都成为可追溯事实，为后续合规报表和记忆质量评估做准备。

下一步：
1. 增加 Python Runtime 可选 MySQL store 启用配置和健康检查。
2. 补 API 错误语义和分页 cursor。
3. 设计 APPROVED 候选异步写入 worker，再分阶段接 Chroma/Neo4j/MinIO/MySQL。

## 4.29 AI Runtime 记忆写入候选 Store 运行时配置（2026-05-28）

本阶段把长期记忆候选 SQL store 从“已有实现”推进到“Runtime 可配置启用”。Python AI Runtime 现在可以通过环境变量选择 `in-memory`、`sqlite` 或 `mysql` 作为候选 store，并通过诊断接口确认真实实现、是否持久化、是否发生 fail-open 回退。

已完成：
- 新增 `memory_write_components.py` 作为记忆候选 store 组装层，避免 `api.py` 和治理服务继续膨胀。
- `create_app()` 已使用可配置 store 创建 `AgentMemoryWriteGovernanceService`。
- 新增 `/agent/memory/write-candidates/diagnostics`，输出脱敏诊断信息。
- 新增测试覆盖默认内存、SQLite 持久化语义、MySQL fail-open 回退和生产快速失败策略。

产品意义：
- 这一步让长期记忆候选具备可部署形态，而不是只在测试中验证 SQL store。
- 开发环境继续轻量，生产环境可以选择 fail-closed，避免候选审批事实因内存回退而丢失。
- 后续审批台、异步写入 worker 和审计台可以围绕同一持久化候选 store 继续演进。

下一步：
1. 补候选 API 错误语义和分页 cursor。
2. 设计 APPROVED 候选异步写入 worker。
3. 并行规划遗忘/归档/检索权限与智能网关模型路由能力，避免长期记忆局部无限扩展。

## 4.30 AI Runtime 记忆写入候选 API 分页与错误语义（2026-05-28）

本阶段补齐长期记忆候选审批台 API 的分页和结构化错误契约。候选列表现在支持 `cursor`，响应包含 `pageInfo.hasMore` 与 `pageInfo.nextCursor`；错误响应从字符串升级为包含 `errorCode/message/statusCode` 的结构化 detail。

已完成：
- 新增 `api_memory_write_pagination.py`，封装 `createdAt + candidateId` 稳定 cursor。
- `GET /agent/memory/write-candidates` 新增 `cursor` 参数和 `pageInfo` 响应。
- 候选不存在、非法状态、非法 cursor、缺少 operatorId、审批冲突和版本冲突都映射为稳定错误码。
- 新增测试覆盖 cursor 翻页和结构化错误。

产品意义：
- 审批台可以持续翻页，而不是只能查看一次性列表。
- 前端、gateway、日志和告警可以基于机器可读错误码处理问题，避免依赖中文字符串。
- 该阶段仍是 API 契约层第一步，后续大规模深分页应把 cursor 下沉到 MySQL 查询。

下一步：
1. 长期记忆线可继续做 APPROVED 候选异步写入 worker。
2. 或按全局节奏切回智能网关模型路由、工具/Skill 执行闭环和 Agent 工作区隔离。

## 4.31 AI Runtime Agent 工作空间上下文第一阶段（2026-05-28）

本阶段从长期记忆候选局部优化切回 Agent 平台主线，补齐 Python Runtime 的工作空间上下文。同步计划响应现在会返回 `agentWorkspace`，用于表达本次 Agent 运行属于哪个租户、项目、工作空间或会话，以及后续缓存、记忆、产物输出应使用哪些 namespace。

已完成：
- 新增 `AgentWorkspaceIsolationLevel`，支持 `PROJECT`、`WORKSPACE`、`SESSION`。
- 新增 `AgentWorkspaceContext` 与 `AgentWorkspaceContextBuilder`。
- `build_plan_response(...)` 返回 `agentWorkspace` 摘要，包括 `workspaceKey`、`cacheNamespace`、`memoryNamespace`、`artifactNamespace`。
- 新增测试覆盖三种隔离等级和计划响应契约。

产品意义：
- 工作空间是工具执行、文件输出、长期记忆、模型缓存和审计追踪的共同隔离边界。
- Python Runtime 与 Java agent-runtime 的 workspace key 语义开始对齐。
- 当前只生成逻辑 namespace，不创建真实资源，保持实现轻量且可演进。

下一步：
1. 将 workspace 信息写入 ToolPlan governance hints。
2. 设计工具/Skill 输出引用规范，例如 `workspace://`、`memory://`、`minio://`。
3. 用 workspace namespace 约束模型 prefix/KV cache 和长期记忆写入。

## 4.32 AI Runtime ToolPlan 工作空间治理提示（2026-05-28）

本阶段把 `agentWorkspace` 从顶层响应摘要下沉到每个 `ToolPlan.governance_hints`。这样 Java 控制面、工具审计、执行器、事件 replay、长期记忆 worker 和产物引用解析器即使只拿到单个工具计划，也能知道本次工具调用属于哪个 workspace。

已完成：
- `AgentWorkspaceContext` 新增 `to_governance_hints()`。
- `build_plan_response(...)` 在 Java plan ingestion 前为每个 ToolPlan 写入 workspace hints。
- 新增测试验证 ToolPlan hints 包含 `workspaceKey`、`workspaceIsolationLevel`、`memoryNamespace`、`artifactNamespace`。

产品意义：
- 工作空间从展示字段升级为执行控制面协议字段。
- 后续工具输出、模型缓存、长期记忆和审计事件可以继承同一隔离边界。
- 这为 `workspace://`、`memory://`、`minio://` 输出引用规范打基础。

下一步：
1. 设计工具/Skill 输出引用协议。
2. Java 侧增加 workspace 一致性校验。
3. 将 `cacheNamespace` 接入模型网关缓存治理。

## 4.33 AI Runtime Agent 资源引用协议第一阶段（2026-05-28）

本阶段新增统一 Agent 资源引用协议，用来表达工具输出、工作空间产物、长期记忆、MinIO 对象和 Java agent-runtime 审计引用。它不替换 Java 现有 `fromTool/path/outputRef`，而是在兼容旧字段的基础上增加 `resourceReference/outputReference` 结构。

已完成：
- 新增 `AgentResourceReferenceKind` 与 `AgentResourceReference`。
- `ToolPlanner` 的前序工具输出引用新增 `resourceReference`。
- 模型工具结果回填保留 `outputRef`，同时新增结构化 `outputReference`。
- 新增资源引用测试，并更新工具规划与模型回填测试。

产品意义：
- 工具产物不再只是字符串，可以表达类型、URI、workspace、jsonPath 和上下文准入策略。
- 为 `workspace://artifact/...`、`memory://candidate/...`、`minio://bucket/key` 统一 resolver 打基础。
- 未来模型上下文过滤可以基于 `contextPolicy` 判断哪些资源只能审计、哪些可以摘要进入模型。

下一步：
1. 实现轻量资源引用 resolver，先做类型识别、workspace 校验和上下文准入判断。
2. Java 侧逐步支持 `resourceReference` 字段。
3. 将资源引用协议接入模型二轮上下文过滤和工具/Skill 输出产物管理。

## 4.34 AI Runtime Agent 资源引用治理解析器第一阶段（2026-05-28）

本阶段把 4.33 的资源引用协议推进到治理解析阶段。新增的 `AgentResourceReferenceResolver` 不负责读取真实资源，而是负责在读取前完成类型、workspace 和模型上下文准入判断。

已完成：
- 新增 `AgentResourceContextPolicy`，区分 `model_full_allowed`、`model_summary_allowed`、`audit_only`、`download_only`、`forbidden_for_model`。
- 新增 `AgentResourceReferenceResolution`，统一返回治理决策、问题码、模型上下文准入结果和后续 resolver hint。
- 支持领域对象、dict payload 和旧式 URI 字符串三种输入。
- 默认阻断 workspace 缺失、workspace 不一致、外部未知引用和缺失 URI 的资源。
- 新增测试覆盖工作空间一致性、模型摘要准入、审计专用资源、外部 URI 阻断和 payload 兼容。

产品意义：
- 资源引用开始具备“可治理门禁”，而不是只有 URI 字符串。
- 模型上下文、下载接口、审计台、长期记忆和未来 Skill Runtime 可以共用同一套准入语义。
- 当前先固定安全边界，不急于读取 MinIO、Chroma、Neo4j 或 Java 审计结果，避免把所有读取能力耦合到一个大解析器里。

下一步：
1. 将 resolver 接入模型二轮上下文过滤，防止审计/下载专用资源进入模型。
2. Java 侧兼容消费 `resourceReference/outputReference`。
3. 在 workspace 校验稳定后，再落地真实 workspace artifact、MinIO、memory 和 Java audit 读取器。

## 4.35 AI Runtime 模型工具结果回填接入资源准入过滤（2026-05-28）

本阶段把资源引用 resolver 接入模型工具结果回填链路。现在 role=tool 消息在携带结构化 `result` 进入二轮模型前，会先校验输出引用的 workspace 和 `contextPolicy`。

已完成：
- `ToolExecutionFeedback` 增加 `output_workspace_key` 与 `output_context_policy`。
- `ModelToolResultFeedbackBuilder` 在构造 role=tool 消息前调用 `AgentResourceReferenceResolver`。
- role=tool payload 新增 `outputReferenceResolution`，用于暴露治理决策、问题码和 resolver hint。
- `audit_only`、`download_only`、`forbidden_for_model` 或 workspace 不一致的资源会保留摘要和引用，但不会回填结构化 `result`。
- Agent 主链模拟二轮和受控二轮编排器都会把当前 `workspaceKey` 传给 builder。
- 补充单元测试覆盖摘要准入、审计专用阻断和 workspace 不一致阻断。

产品意义：
- 工具结果不再只依赖调用方自觉脱敏，而是在模型消息构建层统一执行资源准入。
- 没有明确声明可进入模型的输出默认按 `audit_only` 处理，更符合企业数据治理产品的安全默认值。
- 即使 result 被裁剪，模型仍能看到工具状态、summary、auditId、runId、outputRef 和治理说明，便于解释为什么需要审批、下载或审计查看。

下一步：
1. Java 结果查询 API 返回 workspace/contextPolicy，让真实控制面反馈也能声明资源准入策略。
2. 将 `outputReferenceResolution` 写入 runtime event 诊断，便于前端和审计台展示阻断原因。
3. 继续补 JSONPath/字段级过滤，而不是长期停留在“整个 result 放行或置空”的粗粒度。

## 4.36 AI Runtime 资源准入决策事件化（2026-05-29）

本阶段把工具结果资源准入判断沉淀到 runtime event。前端、审计台和运维排障现在可以通过 `TOOL_RESULT_FEEDBACK_BUILT` 事件看到资源是否被阻断、是否允许进入模型上下文，以及对应 issue code。

已完成：
- `ToolExecutionFeedbackMessageBundle` 新增 `resource_resolution_summaries`。
- `ModelToolResultFeedbackBuilder` 在构造 role=tool 消息时同步生成事件友好的资源准入摘要。
- Agent 主链模拟二轮和受控二轮编排器都把资源准入统计写入 `TOOL_RESULT_FEEDBACK_BUILT` 事件。
- 事件属性包含资源诊断数量、强阻断数量、模型上下文阻断数量和资源引用摘要。
- 补充测试验证 message bundle 和 runtime event 中的资源准入诊断。

产品意义：
- “result 为什么为空”开始变成可解释事件，而不是隐藏在后端逻辑里。
- 审计台可以区分正常策略生效的 `audit_only` 与异常性的 workspace 越界、外部 URL 阻断。
- 事件只记录引用和治理决策，不记录工具结果原文，避免 runtime event 成为新的敏感数据扩散通道。

下一步：
1. 补字段级/JSONPath 级上下文过滤。
2. Java 结果查询 API 返回 workspace/contextPolicy/outputReference。
3. 为 WebSocket/Kafka replay 设计资源准入事件和 URI 脱敏规则。

## 4.37 AI Runtime 工具结果字段级模型上下文过滤（2026-05-29）

本阶段把工具结果上下文治理从资源级推进到字段级。现在工具 result 可以按路径白名单、黑名单、敏感遮蔽和大小限制进入模型，而不是只能整体放行或整体置空。

已完成：
- 新增 `ModelResultContextFilter`，支持顶层字段、点号嵌套路径和 `tables[].name` 这类列表通配路径。
- `ToolExecutionFeedback` 新增 include/exclude/sensitive 路径和字符串、列表、深度限制配置。
- `ModelToolResultFeedbackBuilder` 在资源准入通过后执行字段级过滤，role=tool payload 新增 `resultFilterReport`。
- `TOOL_RESULT_FEEDBACK_BUILT` 事件新增字段级过滤统计与 `resultFilters` 诊断摘要。
- 模拟反馈、控制面反馈对象和 Java 工具反馈客户端都预留字段级策略透传。
- 新增/扩展测试覆盖路径选择、敏感遮蔽、黑名单删除、大小截断、事件诊断和 Java governanceHints 映射。

产品意义：
- 模型可以看到安全摘要字段，例如表数量、字段名、规则数量，而不会看到样本值、连接串、原始 SQL 或过长列表。
- 字段级报告让前端和审计台能解释“哪些字段被遮蔽/删除/截断”，提升生产排障和合规审计可解释性。
- 当前保持轻量路径子集，避免过早引入复杂 JSONPath 依赖，后续可由 permission-admin 和数据分级分类生成更完整策略。

下一步：
1. Java 结果查询 API 返回字段级上下文策略。
2. 策略来源接入工具 schema、permission-admin 字段权限和数据分级分类。
3. 资源/字段过滤事件接入 WebSocket/Kafka replay，并补 URI/path 脱敏规则。

## 4.38 AI Runtime 智能网关 prefix/KV cache 治理计划（2026-05-29）

本阶段把 AI 主线从工具结果过滤切回智能模型网关，补齐 prefix/KV cache 的治理计划契约。当前实现不保存 prompt，也不直接操作真实 KV cache，而是在模型路由决策中输出 `cachePlan`，说明本次请求是否允许缓存、按哪个租户/项目/会话边界缓存、建议 TTL 是多少，以及禁用原因。

已完成：
- 新增模型网关缓存计划对象与计划器。
- 模型网关决策、API 响应和 `MODEL_GATEWAY_ROUTED` 事件均能暴露缓存治理摘要。
- 支持 `GLOBAL_SAFE`、`TENANT_SAFE`、`PROJECT_SAFE`、`SESSION_ONLY`、`NO_CACHE` 五类范围。
- 会话级缓存缺少 `sessionId` 时默认禁用，避免为了命中率扩大复用边界。

产品路线判断：
- 短期应把 `cachePlan` 作为智能网关协议字段透传给 Provider 适配层，而不是立刻深挖复杂缓存存储。
- 中期应把缓存治理与 workspace、permission-admin、数据分级分类、工具 schema 和模型上下文选择统一。
- 长期应补缓存命中率、prefill token 节省、延迟改善、跨模型兼容性和高并发下的缓存污染防护。

下一步建议：
1. 模型网关继续做请求 metadata 透传和指标预留。
2. 并行推进 Java agent-runtime 工具执行闭环。
3. 启动 Skill/Tool 市场治理，避免 AI 部分只停留在模型调用与上下文过滤。

## 4.39 AI Runtime 模型 Provider metadata 透传 cachePlan（2026-05-29）

本阶段把 4.38 的 `cachePlan` 从“治理摘要”推进到“Provider 调用协议”。Agent 首轮模型意图、模拟二轮、受控二轮都会把模型网关治理 metadata 传给 Provider；OpenAI-compatible Provider 会把 metadata 写入请求体 `metadata.datasmart`，并把关键缓存字段写入 `X-DataSmart-*` Header。

产品意义：
- 智能网关现在可以在请求入口看到租户、项目、workload、session 和缓存计划。
- 后续 LiteLLM/vLLM/SGLang/企业网关可以基于 Header 或 body metadata 做 prefix/KV cache、限流、审计和指标归因。
- metadata 只白名单透传非敏感治理字段，不包含 prompt、工具结果、SQL、样本值或连接密钥。

下一步建议：
1. 为 Provider metadata 增加受信网关开关，避免对第三方外部模型服务发送过多内部治理标签。
2. 设计模型网关指标：缓存命中率、prefill token 节省、fallback 次数、二轮推理成本、Provider 错误率。
3. 将重心切回 Java agent-runtime 工具执行闭环和 Skill/Tool 市场治理，避免缓存方向继续吞掉整体节奏。

## 4.40 Java agent-runtime 工具结果批量反馈查询（2026-05-29）

本阶段切回 Agent 工具执行闭环，补齐多工具 Agent 的控制面反馈效率。Java 新增按 run 批量查询工具执行结果的只读接口，Python Java-feedback Provider 会优先使用批量接口，失败或不兼容时回退逐个 auditId 查询。

产品意义：
- 避免 Python 二轮推理前对每个工具逐个查询 Java 控制面，降低 N+1 HTTP 请求带来的延迟和连接压力。
- 批量查询仍然只读，不触发审批、执行或状态推进，符合 Java 控制面作为事实源的边界。
- 新增独立 `AgentToolExecutionResultQueryService`，避免继续膨胀 `AgentSessionService`。

下一步建议：
1. 设计 run 级工具执行策略，而不是贸然自动执行所有 PLANNED 工具。
2. 补 ToolPlan DAG/依赖边，让批量反馈能按节点、分组和依赖关系表达状态。
3. 把批量反馈与 runtime-event replay/WebSocket 结合，减少同步轮询。

## 4.41 Java agent-runtime Run 级工具执行策略预检（2026-05-29）

本阶段补齐工具自动执行前的只读 preflight。Java 控制面现在可以按 Run 汇总每个工具的执行策略，明确哪些工具可进入同步自动执行候选，哪些等待审批、参数补全、异步执行器、失败复核或终态阻断。

已完成：
- 新增 `AgentRunToolExecutionDecision`，把工具审计状态翻译成编排器可理解的下一步决策。
- 新增 `AgentRunToolExecutionPolicyService`，统一解释 Run 状态、工具状态、执行模式、参数校验、审批要求和幂等性。
- 新增 `AgentRunToolExecutionPolicyView` 与 `AgentRunToolExecutionPolicyItemView`，返回 Run 级汇总和单工具原因/推荐动作。
- 新增只读接口 `/tool-executions/execution-policy`，同时支持 `/agent-runtime/...` 与 `/api/agent/...` 双路径。
- 新增单元测试覆盖同步候选、审批等待、参数缺失、异步工具、非幂等失败和 Run 终态阻断。

产品意义：
- 避免从“工具计划已生成”直接跳到“全部自动执行”的危险路径，先把自动化边界、人类介入点和失败策略显式化。
- 前端、Python Runtime 和未来自动执行器可以共用同一个策略结果，减少多端重复写业务 if/else。
- 这一步让 agent-runtime 更接近商用 Agent 控制面：不是只会调用工具，而是能解释为什么某个工具现在应该执行、等待、重试或阻断。

下一步建议：
1. 基于 policy 做受控同步自动执行器第一阶段，只执行低风险、只读、参数完整、Run 非终态的 `AUTO_EXECUTABLE` 工具。
2. 设计 `ASYNC_TASK` 到 task-management/Kafka command 的下发协议，避免长耗时工具占用 HTTP 线程。
3. 把策略预检接入 Python Runtime 的二轮推理准备阶段，使模型能向用户解释审批、参数和失败阻断原因。
4. 补 ToolPlan DAG/依赖边，为多工具并发、顺序执行、失败跳过和回滚策略打基础。

## 4.42 Java agent-runtime 受控同步工具自动执行器（2026-05-29）

本阶段在 4.41 policy preflight 之上新增第一版受控同步自动执行器。它只执行服务端二次筛选后的低风险、只读、幂等、无需审批的同步工具，并提供 dryRun、auditId 白名单和单批次数量上限。

已完成：
- `AgentRuntimeProperties` 增加 `syncAutoExecutionEnabled` 与 `maxSyncAutoExecutionsPerRun`。
- 新增 `AgentRunToolAutoExecutionService`，先读 policy，再按 LOW/readOnly/idempotent/requiresApproval=false 进行更严格筛选。
- 新增 `POST /tool-executions/auto-execute-sync` 路由，支持 `/agent-runtime/...` 与 `/api/agent/...` 双路径。
- 新增自动执行请求、响应、单项结果 DTO，返回执行前 policy、每个工具动作、跳过原因和执行结果。
- 新增测试覆盖安全候选执行、dryRun 不改状态、服务端上限、auditId 白名单和风险/审批阻断。

产品意义：
- Agent Runtime 已具备从“工具计划审计”到“策略预检”再到“低风险同步工具执行”的最小闭环。
- 自动执行器比 policy 更保守，避免未来策略扩展后执行入口不受控扩大范围。
- dryRun 和 maxExecutions 使前端、Python Runtime 和运营人员可以先预览再执行，符合商用系统渐进自动化原则。

下一步建议：
1. Python Runtime 接入 policy + auto-execute-sync，形成“预检、执行安全候选、批量取结果、二轮推理”的闭环。
2. 设计 `ASYNC_TASK` 到 task-management/Kafka command 的任务化执行协议。
3. 补 ToolPlan DAG/依赖边，解决多工具执行顺序、并发组、失败跳过和补偿问题。
4. 将自动执行入口接入 gateway/permission-admin 权限动作和租户级开关。

## 4.43 Python Runtime 接入 Java 工具策略与受控同步自动执行（2026-05-29）

本阶段把 Java `execution-policy` 与 `auto-execute-sync` 接入 Python 工具反馈 Provider。Python 在批量读取 Java 工具结果前，可以显式启用受控自动执行，让低风险同步工具先执行，再把结果回填给二轮模型。

已完成：
- Java feedback client 新增 policy 查询和 auto-execute-sync 调用。
- 新增 execution contracts 模块，解析 policy 与自动执行批次摘要。
- Provider 从 client 文件拆出，并在启用开关时执行“policy -> auto execute -> batch results”。
- API 环境变量新增 `DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_ENABLED/DRY_RUN/MAX`。
- 补充单测覆盖 policy 解析、自动执行响应解析和 Provider 调用顺序。

产品意义：
- Python AI Runtime 与 Java agent-runtime 的工具闭环更接近真实 Agent：不只是轮询结果，而是能在安全边界内推动低风险工具执行。
- 自动执行默认关闭，通过环境变量灰度启用；真正执行规则仍在 Java 控制面，Python 只缩小 auditId 范围。
- 同时完成 client/provider/contracts 拆分，避免工具反馈链路继续变成 700+ 行大文件。

下一步建议：
1. 将自动执行批次摘要写入 runtime event，增强前端可见性和审计可解释性。
2. 启动 `ASYNC_TASK` 到 task-management/Kafka command 的任务化执行设计。
3. 补 ToolPlan DAG，避免多工具自动执行缺少依赖与并发控制。
4. 把自动执行开关升级为租户、项目、工具级策略，而不是长期依赖全局环境变量。
