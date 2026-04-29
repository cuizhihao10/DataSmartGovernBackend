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
