# DataSmart-Govern Pro 企业级多智能体数据治理平台

## 项目文档（AI辅助开发专用）

**文档版本**：V2.0.0

**修订日期**：2026-03-21

**核心用途**：引导AI辅助开发工具（如Cursor、GitHub Copilot、豆包编程助手）理解项目设计标准、模块边界、技术方案，高效完成开发任务，无需提供具体代码实现，聚焦「架构设计、技术选型、需求定义」。

**适配风格**：OpenClaw企业级多智能体架构，贴合数据治理业务场景，明确各环节开发约束与设计要求。

**数据库目标架构说明**：项目已决定从 MySQL 渐进迁移到 PostgreSQL。PostgreSQL 将作为 Java 业务事实、
Agent 长期记忆、pgvector 语义检索和未来 LangGraph durable state 的目标数据库；MySQL 仅在尚未完成
服务级迁移时临时保留。迁移原则、顺序和验收门禁见
[MySQL 到 PostgreSQL 渐进迁移路线](docs/postgresql-migration-roadmap.md)。

**构建环境说明**：本项目固定使用 JDK 21。若本机 `mvn -v` 默认显示 Java 8，请先阅读 [docs/development-jdk21.md](docs/development-jdk21.md)，项目根 `pom.xml` 已配置 Maven Toolchains 自动选择 JDK 21，避免 Java 21 语法被旧 JDK 误判失败。

**本地闭环联调说明**：当前项目已进入“能力收敛、链路闭环”阶段。若需要按最小商业化链路验证 Keycloak、gateway、permission-admin、task-management、data-sync 与 datasource-management 是否能够串起来，请阅读 [docs/local-e2e-closure-runbook.md](docs/local-e2e-closure-runbook.md)；如果遇到 Docker 镜像拉取、Windows `MySQL80` 占用 `3306`、MySQL 初始化或迁移登记问题，请优先阅读 [docs/local-e2e-docker-troubleshooting.md](docs/local-e2e-docker-troubleshooting.md)。启动真实联调前，建议先使用 [scripts/local-e2e-docker-image-cache.ps1](scripts/local-e2e-docker-image-cache.ps1) 通过国内镜像前缀预拉取 Compose 镜像并重新打标准 tag；如果 Windows 本机 `MySQL80` 已占用 `3306`，请叠加 [docker-compose.local-e2e.yml](docker-compose.local-e2e.yml) 将项目 MySQL 暴露到 `13306`。随后使用 [scripts/local-e2e-environment-readiness.ps1](scripts/local-e2e-environment-readiness.ps1) 判断 Docker、MySQL 凭据、端口和 Python Runtime API 依赖是否具备继续启动条件，再用 [scripts/local-mysql-migration-governance.ps1](scripts/local-mysql-migration-governance.ps1) 检查或登记本地 MySQL 增量迁移，最后使用 [scripts/local-e2e-smoke-check.ps1](scripts/local-e2e-smoke-check.ps1) 做只读 smoke check。上述脚本不会创建任务、不会触发 worker loop、不会读取或写入真实业务数据，避免已有数据卷 schema 与当前代码漂移或环境未就绪被误判为业务代码问题。

**当前智能网关进度补充**：`permission-admin` 已新增 Agent 工具预算策略评估入口，可按角色、租户套餐、workspace 风险和 worker backlog 生成 Python Runtime 可消费的 `toolCallBudget`；`agent-runtime` 已新增 Skill 可见性快照 runtime event 专用查询入口，可把 Python Runtime 的 `SKILL_VISIBILITY_SNAPSHOT_RECORDED` 纳入 Java 控制面 replay/index 视图；Python Runtime 现在会把 Skill Publication Manifest 的 `contentFingerprint` 绑定到 `intelligentGatewayGovernance.skillManifest`、`skillVisibility.manifestBinding` 和可回放事件 attributes，Java 专用投影视图也能按 Manifest 绑定状态与来源聚合；独立 Skill 可见性快照索引已具备 `memory/postgresql|jdbc` 目标实现，`mysql` 仅保留为迁移期兼容别名；索引链路已新增低基数 Micrometer 指标、只读诊断接口、Prometheus 告警规则和 projection duplicate 场景下的幂等补物化能力；gateway 已为 `/api/agent/plans` 生成会话级 READY Skill 准入缓存上下文，并通过 HMAC 签名传递给 Python Runtime，Python 侧只缓存 Skill admission decision，不缓存用户 prompt、完整 AgentPlan、模型输出或工具结果；Python Runtime 已接入官方 MCP Python SDK 1.x 出站 Client，支持受控 Streamable HTTP/stdio、真实 `initialize`、`tools/list`、`tools/call`、工具命名空间和 admission 闸门，详见 [出站 MCP Client 接入说明](docs/mcp-client-integration.md)；长期记忆已切换到 PostgreSQL/pgvector 目标路径，并保留 Chroma 兼容适配边界。

---

## 文档修订记录

|版本号|修订日期|修订内容|修订人|
|---|---|---|---|
|V2.0.0|2026-03-21|新增OpenClaw风格多智能体架构梳理，补充各模块AI智能体相关设计需求、技术方案，适配AI辅助开发引导|项目研发组|
|V1.0.0|2026-03-10|梳理项目基础架构、核心模块、技术栈，明确开发规范与部署要求|项目研发组|
---

## 一、项目核心概述（AI开发引导重点）

### 1.1 项目定位与边界（AI需明确）

- 定位：**企业级多智能体数据全生命周期治理平台**，面向零售/制造/金融行业，替代人工80%重复数据治理工作，实现全流程智能化闭环。

- 核心边界：聚焦「数据接入→质量校验→ETL自动化→资产映射→合规脱敏→运维告警」，不涉及前端UI细节开发（仅明确接口交互规范），不涉及第三方系统源码开发（仅明确集成方案）。

- AI辅助开发核心目标：基于既定架构与技术方案，辅助完成各模块的逻辑实现、接口开发、智能体协同逻辑，遵循设计需求与规范，减少重复编码。

### 1.2 核心技术栈约束（AI必须遵循）

#### 后端技术栈（固定，不可随意变更）

- 基础环境：JDK 21（Eclipse Temurin）、Spring Boot 3.5.11

- 微服务架构：Spring Cloud 2023.0.3、Spring Cloud Alibaba 23.0.1.2

- 事件驱动：Kafka 3.6.x、Spring Cloud Stream

- 可观测性：Spring Boot Actuator、Prometheus 2.50.x、Grafana 10.4.x

- 数据存储：MySQL 8.0+（业务数据）、Redis 7.2+（缓存/会话）、Neo4j 5.20+（知识图谱）、Chroma（向量存储）、MinIO（文件存储）

#### AI智能体技术栈（固定，OpenClaw风格）

- 运行时：LangGraph、OpenClaw Runtime

- 大模型：Qwen2-7B/Qwen2-VL（开源，可微调）

- 推理优化：vLLM 0.6+（AWQ量化）

- 技能插件：Python 3.10+、LangChain 0.3+

- 多智能体协同：LangGraph状态流转、OpenClaw工作区机制

#### 前端技术栈（仅明确接口交互，AI无需开发前端）

- 核心框架：React 18、TypeScript 5.x、Ant Design 5.x

- 交互方式：RESTful API（同步）、WebSocket（实时会话/日志）

### 1.3 核心设计原则（AI开发需遵循）

1. 解耦原则：Java业务层与Python AI层通过Kafka/gRPC异步解耦，智能体与技能插件解耦，支持插件热更新；

2. 隔离原则：智能体独立工作区、独立权限，数据与操作隔离，保障合规性；

3. 可扩展原则：技能插件支持自定义开发、一键注册，智能体支持动态扩容；

4. 可观测原则：全链路日志、指标监控，智能体运行状态、任务执行进度可追溯；

5. 兼容性原则：所有技术选型需适配Spring Boot 3.5.11与JDK 21，避免版本冲突。

---

## 二、整体架构设计（AI开发核心参考）

### 2.1 分层架构（自上而下，明确各层职责与依赖）

|层级|核心职责|技术方案|设计需求|
|---|---|---|---|
|前端展示层|提供用户交互界面、智能体会话入口、任务进度可视化、监控面板|React 18+TypeScript+Ant Design，WebSocket实时通信|1. 接口请求需符合RESTful规范；2. 实时会话需通过WebSocket与智能体网关交互；3. 监控面板需对接Grafana接口|
|网关接入层|会话路由、权限校验、多渠道接入、请求限流|Nginx 1.24+、智能体网关（Spring Boot实现）|1. 支持智能体路由（根据用户需求匹配对应智能体）；2. 实现RBAC权限校验（区分用户/智能体权限）；3. 支持限流、熔断，避免高并发压垮后端|
|Java核心业务层|业务逻辑处理、任务管理、数据持久化、第三方系统集成|Spring Boot 3.5.11、JDK 21虚拟线程、MyBatis-Plus 3.5.5|1. 用虚拟线程提升任务并发吞吐量；2. 任务管理需支持断点续行、失败重试；3. 所有业务接口需统一异常处理、日志记录|
|智能体运行时层|智能体生命周期管理、技能插件调度、工作区隔离、状态同步|LangGraph、OpenClaw Runtime|1. 支持8个专项智能体协同调度；2. 技能插件可一键注册、调用；3. 实现工作区隔离与状态持久化；4. 支持智能体会话上下文保持|
|Python AI算法层|大模型推理、多智能体逻辑、技能插件实现、GraphRAG检索|Qwen2-7B、vLLM、LangChain、GraphRAG|1. 推理延迟需降低70%+（vLLM优化）；2. GraphRAG需构建数据治理知识图谱；3. 技能插件需符合OpenClaw规范，支持输入输出校验|
|中间件/存储层|数据存储、消息传递、缓存、文件存储|MySQL、Redis、Kafka、Neo4j、Chroma、MinIO|1. 数据库需支持分表分库（预留扩展）；2. Kafka消息需保证幂等性、可靠性；3. 向量存储需支持高效检索（适配GraphRAG）|
|基础设施层|容器化部署、环境一致性、运维自动化|Docker、Docker Compose、Ubuntu 22.04|1. 所有组件需容器化，支持一键部署；2. 配置文件需区分开发/测试/生产环境；3. 支持日志挂载、数据持久化|
### 2.2 核心组件交互流程（AI需理解的协同逻辑）

1. 用户通过前端发起数据治理需求（自然语言/表单）；

2. 网关接入层校验权限，路由会话到对应智能体；

3. 智能体运行时调用对应技能插件，执行具体任务（如数据接入、质量校验）；

4. 技能插件调用Python AI算法层，完成大模型推理、规则生成等操作；

5. Java核心业务层处理数据持久化、任务状态更新，通过Kafka与AI层同步状态；

6. 结果通过网关返回前端，智能体运行日志、任务进度实时推送至前端；

7. 反思优化智能体定期复盘任务，优化技能插件与智能体逻辑。

---

## 三、各核心模块详细设计（AI辅助开发重点）

### 3.1 网关接入层 - 智能体网关模块

#### 模块定位

作为用户与智能体的交互入口，负责会话路由、权限控制、多渠道接入，是整个平台的“入口中枢”。

#### 架构设计

- 核心组件：会话管理器、智能体路由器、权限校验器、多渠道适配器

- 依赖模块：Java核心业务层（权限中心）、智能体运行时层（智能体状态）

- 交互对象：前端展示层、Java核心业务层、智能体运行时层

#### 技术方案

1. 会话管理：基于Redis实现会话状态存储，支持多轮对话上下文保持；

2. 智能体路由：基于用户查询语义分析，自动匹配最优专项智能体；

3. 权限校验：集成Spring Security，基于RBAC模型校验用户/智能体权限；

4. 多渠道接入：支持Web UI、企业微信、钉钉，通过适配器统一接口规范。

#### 设计需求

1. 会话路由响应时间≤100ms，支持每秒1000+会话请求；

2. 会话状态需持久化，用户刷新页面后上下文不丢失；

3. 权限校验需细粒度（如智能体仅能访问自身工作区资源）；

4. 支持会话超时自动销毁，避免资源浪费。

### 3.2 Java核心业务层（4个核心模块）

#### 模块1：任务管理模块

##### 模块定位

负责全平台任务的创建、调度、监控、复盘，是Java层与智能体层的核心衔接模块。

##### 架构设计

- 核心组件：任务生成器、任务调度器、任务监控器、任务复盘器

- 依赖模块：虚拟线程池、Kafka消息队列、MySQL（任务存储）、Redis（任务缓存）

- 交互对象：智能体网关、智能体运行时层、可观测性模块

##### 技术方案

1. 任务调度：基于JDK 21虚拟线程，替代传统线程池，提升并发吞吐量；

2. 任务通信：通过Kafka与智能体运行时层异步通信，传递任务指令与执行结果；

3. 任务监控：实时采集任务执行状态，异常时触发告警（对接运维告警智能体）；

4. 任务复盘：调用反思优化智能体，定期分析任务执行结果，优化任务流程。

##### 设计需求

1. 支持任务断点续行（任务失败后可从失败节点继续执行）；

2. 任务调度吞吐量提升50%+（相比传统线程池）；

3. 任务执行日志需完整记录，支持追溯；

4. 支持任务优先级设置（高优先级任务优先调度）。

#### 模块2：数据源管理模块

##### 模块定位

负责多源异构数据源的接入、连接管理、元数据采集，为后续数据治理提供数据基础。

##### 架构设计

- 核心组件：数据源连接器、元数据采集器、连接池管理器、数据源监控器

- 依赖模块：MySQL（数据源配置存储）、智能体运行时层（数据源接入智能体）

- 交互对象：数据质量模块、ETL开发模块、数据源接入智能体

##### 技术方案

1. 多源接入：支持JDBC/ODBC、MySQL、Hive、MongoDB、FTP等多种数据源；

2. 元数据采集：通过数据源接入智能体，自动采集表结构、字段定义、业务口径；

3. 连接管理：基于Druid连接池，实现连接复用、超时回收；

4. 状态监控：实时监控数据源连接状态，异常时触发告警。

##### 设计需求

1. 数据源接入支持一键配置，无需手动编写连接代码；

2. 元数据采集准确率≥99%，支持定时更新；

3. 连接池支持动态扩容，适配高并发数据采集需求；

4. 支持数据源权限控制（不同用户仅能访问授权数据源）。

#### 模块3：数据质量模块

##### 模块定位

负责数据质量规则生成、异常数据检测、清洗方案推荐，保障数据准确性、完整性。

##### 架构设计

- 核心组件：规则生成器、异常检测器、清洗方案生成器、质量报告生成器

- 依赖模块：MySQL（质量规则存储）、Neo4j（规则关联）、数据质量智能体

- 交互对象：数据源管理模块、ETL开发模块、合规脱敏模块

##### 技术方案

1. 规则生成：通过数据质量智能体，基于业务需求自动生成质量校验规则；

2. 异常检测：定时执行质量校验，识别空值、重复值、异常值等问题；

3. 清洗方案：针对异常数据，由智能体推荐最优清洗方案，支持手动调整；

4. 质量报告：自动生成数据质量报告，展示校验结果、异常详情、优化建议。

##### 设计需求

1. 规则生成支持自定义扩展，适配企业特定业务场景；

2. 异常检测延迟≤5分钟（针对千万级数据量）；

3. 清洗方案需可执行（如生成SQL清洗脚本）；

4. 质量报告支持导出（Excel/PDF格式）。

#### 模块4：可观测性模块

##### 模块定位

负责全平台的指标监控、日志收集、告警通知，实现问题快速定位与运维自动化。

##### 架构设计

- 核心组件：指标采集器、日志收集器、告警管理器、监控面板适配器

- 依赖模块：Prometheus（指标存储）、Grafana（监控面板）、Kafka（日志传输）、运维告警智能体

- 交互对象：所有模块（采集各模块指标/日志）、前端展示层（监控面板）

##### 技术方案

1. 指标采集：通过Spring Boot Actuator采集Java后端指标，通过自定义采集器采集AI层、中间件指标；

2. 日志收集：集中收集各模块日志，按模块/级别分类存储，支持日志检索；

3. 告警管理：基于预设规则触发告警，通过运维告警智能体推送至指定渠道（企业微信/钉钉）；

4. 监控面板：对接Grafana，展示系统健康状态、任务执行进度、智能体运行状态。

##### 设计需求

1. 指标采集频率≤10秒，日志收集延迟≤1分钟；

2. 支持自定义告警规则（如CPU使用率≥80%触发告警）；

3. 监控面板支持自定义配置，重点指标可视化；

4. 支持告警分级（普通/紧急），紧急告警需立即推送。

### 3.3 智能体运行时层（OpenClaw风格，核心模块）

#### 模块定位

实现OpenClaw风格多智能体的生命周期管理、协同调度、技能插件调用、工作区隔离，是AI驱动的核心层。

#### 架构设计

- 核心组件：智能体管理器、技能插件市场、工作区管理器、状态同步器、共享记忆总线

- 依赖模块：Python AI算法层（大模型/技能实现）、Java核心业务层（任务/权限）、Kafka/Redis（状态同步）

- 交互对象：智能体网关、Python AI算法层、Java核心业务层

#### 技术方案

1. 智能体管理：长期蓝图支持8个专项智能体的创建、启动、停止、扩容，维护智能体生命周期；当前可验收交付的真实 specialist roster 是六个专业 Agent，具体边界见下方“当前可验收的六专业 Agent 受治理闭环”；

2. 技能插件市场：集中管理工具与技能，支持插件注册、卸载、升级，实现技能复用；

3. 工作区隔离：为每个智能体分配独立工作区，存储数据、日志、输出结果，支持父子工作区继承；

4. 状态同步：通过共享记忆总线（Redis+Kafka），实现智能体间状态同步与数据共享；

5. 协同调度：基于LangGraph实现智能体协同工作流，支持任务拆解、交叉验证、结果复盘。

#### 设计需求

1. 智能体支持动态扩容，单智能体可支持100+并发请求；

2. 技能插件需符合OpenClaw规范，支持输入输出校验，可独立调试；

3. 工作区需实现权限隔离，智能体仅能访问自身工作区资源；

4. 智能体状态需持久化，重启后可恢复之前的任务状态；

5. 多智能体协同需避免死锁，支持超时机制（单任务超时时间可配置）。

#### 8个专项智能体设计（长期目标蓝图，当前交付范围见下）

|智能体名称|核心职责|技术方案|设计需求|
|---|---|---|---|
|总控调度智能体|需求解析、任务拆解、智能体协调、进度监控、结果汇总|Qwen2-7B微调（任务规划方向）、LangGraph状态流转|1. 需求解析准确率≥95%；2. 任务拆解需合理，分配至对应专项智能体；3. 支持实时监控任务进度|
|数据源接入智能体|多源数据接入、元数据采集、连接测试、连接维护|Qwen2-VL（多模态元数据提取）、JDBC/ODBC工具|1. 支持主流数据源接入；2. 元数据采集完整（表结构、字段口径）；3. 连接异常可自动重试|
|数据质量智能体|质量规则生成、异常数据检测、清洗方案推荐、质量复盘|Qwen2-7B微调（数据质量方向）、GraphRAG（规则检索）|1. 规则生成贴合业务需求；2. 异常检测准确率≥98%；3. 清洗方案可执行|
|ETL开发智能体|自然语言转ETL脚本、脚本调试、性能优化、脚本发布|Qwen2-7B微调（SQL/Spark代码生成）、代码调试工具|1. 脚本生成准确率≥96%；2. 支持脚本性能优化；3. 脚本可直接发布执行|
|数据资产智能体|数据字典生成、表关系图谱构建、业务口径映射、资产检索|Qwen2-7B微调（知识图谱方向）、Neo4j（图谱存储）|1. 表关系图谱构建准确；2. 业务口径映射清晰；3. 资产检索响应≤500ms|
|合规脱敏智能体|敏感数据识别、分级分类、脱敏方案生成、合规审计|Qwen2-7B微调（敏感数据识别方向）、脱敏算法库|1. 敏感数据识别准确率≥99%；2. 脱敏方案符合合规要求；3. 支持脱敏审计日志|
|运维告警智能体|指标监控、异常告警、自动恢复、运维复盘|Qwen2-7B微调（运维方向）、Prometheus指标采集|1. 告警响应≤10秒；2. 支持简单故障自动恢复；3. 定期生成运维复盘报告|
|反思优化智能体|任务复盘、规则优化、智能体能力迭代、技能插件升级|Qwen2-7B微调（反思学习方向）、DPO微调|1. 复盘报告需有针对性优化建议；2. 支持智能体能力迭代；3. 可自动优化技能插件参数|

#### 3.3.1 当前可验收的六专业 Agent 受治理闭环

长期八专项表是产品扩展蓝图，不代表八个角色都已进入本轮可执行交付。当前工作区的真实 specialist roster 为：

|角色|当前职责|副作用边界|
|---|---|---|
|`KNOWLEDGE_AGENT`|RAG、历史案例和证据引用|只读检索与低敏证据摘要|
|`DATASOURCE_AGENT`|在当前项目授权范围内消歧并发现源/目标数据源|只读目录和元数据入口，不读取连接凭据|
|`DATA_SYNC_AGENT`|读取两端元数据并生成同步配置与生命周期 ToolPlan 草案|结果必须进入 Java ToolPlan bridge，不直接保存或执行任务|
|`PRECHECK_AGENT`|在 Java 已返回可信 `taskId`/`executionId` 后执行确定性预检查并解释结果|仅在 bridge 后的独立只读复核波次运行，不改变任务或数据|
|`RECOVERY_AGENT`|读取失败事实，自主决定是否检索 RAG，并提出受治理的恢复方案|首次授权范围内的低风险动作可有界自动执行；高风险、越权、证据不足或预算耗尽必须停在审批/人工关注|
|`MONITOR_AGENT`|在 Java 已返回可信资源定位后读取任务、执行和进度状态并生成低敏观察摘要|仅在 bridge 后的独立只读复核波次运行，不提供 retry/stop/replay 写操作|

闭环顺序是 `Master/主编排 -> durable checkpoint -> SpecialistCoordinator -> 依赖波次与低敏 handoff -> specialist turn -> Java ToolPlan/权限/审批/outbox/worker -> feedback -> PRECHECK/MONITOR 后置复核 -> second turn/replay`。每个 specialist turn 都按 session/run、角色、状态、范围、checkpoint 和 handoff/bridge 引用写入 Java `agent-runtime` durable fact；该事实不包含 prompt、SQL、工具参数、凭据、样本和模型原文。`DATA_SYNC_AGENT` 与 `RECOVERY_AGENT` 的 handoff 只形成 Java 可治理的 ToolPlan 或审批候选，Java 控制面才负责权限、审批、outbox、worker receipt 和执行反馈；只有反馈给出可信资源定位后，才运行后置的只读 `PRECHECK_AGENT`/`MONITOR_AGENT`。

Recovery 的每项建议独立经过工具白名单和可验证配置校验。某一只读预览动作缺参时记录 `RECOVERY_ACTION_INPUT_INCOMPLETE` 并跳过该动作，不阻断同批其他完整只读预览；若没有任何完整动作，闭环明确停在补参等待态，绝不创建不可执行的 Java ToolPlan。首次确认时已经固化 Autopilot 授权的任务，可在作用域、动作白名单、风险、证据、幂等、循环和总时长预算同时满足时自动执行低风险恢复；高风险、越权或不确定恢复始终在用户审批或 `ATTENTION_REQUIRED` 前停止。

角色按依赖条件 fail-closed 装配：`DATASOURCE_AGENT` 和 `MONITOR_AGENT` 需要对应 Java 控制面地址，`DATA_SYNC_AGENT`、`PRECHECK_AGENT` 和 `RECOVERY_AGENT` 还需要真实的 `agent_reasoning` 模型路由；dry-run provider 不能伪装成六 Agent 已上线。

2026-08-10 的最终复验已取代此前 Provider 401 阻塞记录。Python Runtime 全量为 `1099 passed`（仅一条 Starlette/TestClient 弃用警告）；JDK 21 Maven Reactor 为 `1323 tests / 0 failures / 0 errors / 9 skipped`，其中 Agent Runtime 为 `596` 个测试；六 Agent PowerShell 聚合、异名对象映射和进程退出码回归均通过。真实 Success 请求 `six-agent-success-type-normalized-20260810112629` 创建任务 `91`、执行 `2245`，worker `SUCCEEDED` 且读写 `20/20`；18 项检查无失败，仅保留按需 RAG 未触发的 1 项预期 warning。真实 Recovery 请求 `six-agent-recovery-rag-durable-20260810214832` 获得 2 条 grounded citation、2 条 durable evidence reference、1 个 Java 只读 preview，后置 PRECHECK/MONITOR 均为 `EXECUTED`，11 项检查无失败和 warning，进程退出码为 `0`。独立数据库审计确认本轮审批、审批确认、提交事实和异步命令 outbox 均为 `0`；该历史 Recovery 场景只执行了 8 个 `LOW/readOnly/SUCCEEDED` Java 工具审计，没有自动创建恢复计划、批准或执行恢复副作用。它不能证明也不能否定随后加入的 Autopilot 有界执行源码，不能被重述为 Autopilot Docker E2E。执行入口、证据口径和仍然开放的生产加固项见 [本地端到端闭环 Runbook](docs/local-e2e-closure-runbook.md) 与 [最终收敛交付清单](docs/final-convergence-delivery-checklist.md)。

#### 3.3.2 2026-08-11 Autopilot 恢复控制面边界

Autopilot 是首次人工授权后运行的有界无人值守低风险恢复控制面。用户在首次确认 Agent Run 时可选提交 `autopilotPolicy`；Java 会在任何工具副作用前把不可替换的 `autopilotAuthorization` 快照写入该 Run。快照绑定 tenant/application/project、用户/actor/Agent/delegation、根 session/run、有效期、恢复轮数和总时长预算，以及 SHA-256 策略摘要；后续 Run 不能替换或扩大这份授权。默认上限为 `5` 轮、`120` 分钟，分别限制在 `1-10` 轮和 `5-1440` 分钟；自动风险上限固定为 `LOW`。

"平台开放工具"在这里表示工具已注册且可治理，不表示模型拥有任意调用权。Recovery 模型动作必须确定性映射到已注册工具，并继续经过 tenant/project 范围、可见性、`allowed_actions`、参数 schema、风险、审批、预算和 Java 控制面检查；Python Runtime 不创建审批、不写业务数据、不派发 worker。Python 只可受限委派最小只读工具；任何可能改变状态的 Recovery 都必须回到 Java。首次授权盒内只有 `RETRY_EXECUTION`，以及已复核持久 preview、精确 selector 和回执的 `APPLY_QUARANTINE`，可进入 Java/data-sync 的有界执行分支；其它动作、高风险或越权建议仍走 Java handoff/人工审批。

`RECOVERY_AGENT` 由模型根据错误新颖度、诊断覆盖度、已有引用和置信度自主选择 `SEARCH` 或 `SKIP`。缺少 grounded 证据时选择 `SEARCH`，本轮只生成只读 `SEARCH_RECOVERY_KNOWLEDGE`，取得 durable RAG 证据后才在下一轮重新评估恢复；`SKIP` 不会绕过任何工具、授权、schema、风险、预算或审批门禁。兼容旧 Provider 的 `AUTO` 会在无 grounded 引用时归一为 `SEARCH`，已有引用时归一为 `SKIP`。

授权层允许预先声明 `RETRY_EXECUTION`、`APPLY_QUARANTINE`、`RECONNECT_DATASOURCE`、`RESUME_FROM_CHECKPOINT`、`REPLAY_FAILED_SHARDS`、`REFRESH_METADATA`；高风险 `CHANGE_SCHEMA`、`CHANGE_CREDENTIAL`、`DELETE_DATA`、`OVERWRITE_TARGET`、`EXPAND_DATA_SCOPE` 始终进入 `WAITING_APPROVAL`。data-sync 的低风险资格白名单包含 `RETRY_EXECUTION`、`APPLY_QUARANTINE`、`RESUME_FROM_CHECKPOINT` 和 `REPLAY_FAILED_SHARDS`，但当前 Agent Runtime 实际执行分支只接受 `RETRY_EXECUTION` 与满足 preview/selector/receipt 门禁的 `APPLY_QUARANTINE`；其余即使得到 `AUTO_APPROVED` 也会因没有执行器停在 `ATTENTION_REQUIRED`，不能因出现在策略 JSON 中就获得无人值守执行资格。预算/截止时间耗尽、同一错误连续三次、证据或幂等指纹缺失、置信度低于 `0.70` 等情况进入 `ATTENTION_REQUIRED`；作用域、授权或动作不匹配则 `REJECTED`。

当前源码已经把 `AUTO_APPROVED` 接到 V20-V25 PostgreSQL durable 控制面：V20 case/授权快照与 receipt、V21 trigger outbox、V22 consumer-result、V23 sidecar-compensation、V24 quarantine-receipt，以及 V25 的 `SEARCH`/`SKIP`、策略、证据数量和 evidence-ID digest 低敏投影。后续链路包括 Kafka 触发、Agent Runtime 消费、Python Recovery 规划、Java 证据与双策略复核、data-sync 同幂等键失败对象重试或 preview 约束的 quarantine、worker 处理和最终 receipt 收敛。状态机允许 `AUTO_APPROVED -> RECOVERY_STARTED -> RECOVERED`；安全拒绝或失败则收敛到 `ATTENTION_REQUIRED`。该事实不意味着模型可以任意执行，单条 `AUTO_APPROVED` 也不是副作用或成功证据：自动动作仍限于首次授权范围内的低风险白名单，高风险和越权动作不会进入 worker。

普通同步规划的结构化意图只决定 RAG 是否作为 `knowledge.rag.query` 候选工具向模型开放；模型可调用或跳过，规则兜底不得在模型选择跳过后补写 RAG ToolPlan。Recovery 已由模型按诊断事实自主选择 `SEARCH`/`SKIP`，并由 V25 持久化其低敏决策投影。repository workspace 仅指 `workspace.text.search`/文件工具使用的受控、allowlist 文件系统搜索根，不能与产品的 tenant/application/project 层级，或历史 `workspaceId` 数据隔离概念混为一谈；本轮不启用 Elasticsearch 或 Web Search 作为自治恢复依赖。

2026-08-17 的 RAG 基准包含 356 份中文异构原文件和 752 条黄金用例，覆盖 DOCX、XLSX、Markdown、TXT、JSON、JSONL、CSV、LOG、SQL。Word 文档按用户、管理员、部署、运维、专项 Runbook、安全、产品、测试、事故和接口职责独立建模；接口参考从当前 Java Controller 与 FastAPI 路由扫描 475 条真实 REST/SSE/WebSocket 合同；Excel 和结构化资料同样按主题使用专属工作表与 Schema，不再把失败诊断复制到无关资料。Manifest 同时校验原文件与提取文本哈希，citation 始终保留原始文件 URI。

最终离线词法基线指纹为 `910973f644344e81e4681364af18fb02763248ed48049df27efe2735883663f3`，97,379 个 chunk、752 条用例执行错误数为 `0`、范围泄漏率为 `0`；Recall@K 为 `0.758427`，MRR 为 `0.703652`，引用精确率为 `0.457537`。除 MRR、范围隔离和过期证据抑制外，其余多项严格质量门禁仍未通过，因此只能表述为“语料职责、异构加载和评测基础设施已验证”，不能表述为生产 RAG 质量验收。旧 188/308 数据集上的 BGE-M3/Reranker 与 313-chunk pgvector 结果只作历史对照，新 356/752 基线仍需重新执行真实语义评测和持久化性能评测。详见 [RAG 黄金集与硅基流动 BGE 评测 Runbook](docs/rag-evaluation-siliconflow-runbook.md)。

2026-08-10 的六 Agent Recovery 历史 E2E 已证明只读 preview 后存在后置 `PRECHECK_AGENT`/`MONITOR_AGENT` durable turn，但该场景没有执行 Autopilot 写动作。2026-08-13 源码已补齐写动作后的固定复核接线：data-sync 必须返回与原事件同一 `taskId/executionId` 且状态为 `QUEUED/RETRYING` 的强类型 receipt；Java 随后调用 Python 内部 post-action verification，Python 以稳定 checkpoint/turn ID 运行 PRECHECK/MONITOR，并通过既有 Java fact sink 幂等登记两条 durable fact。角色缺失、Specialist 失败、fact sink/HTTP/checkpoint 失败都会抛回 Kafka 有界重试，不会把未复核副作用确认为成功。该源码合同已通过 Python 全量 `1150 passed / 1 skipped` 和 Agent Runtime 全量 `693 passed`；真实 Docker/Kafka/Provider/worker E2E 仍须在重建环境后完成，不能把模块回归重述为生产无人值守验收。
### 3.4 Python AI算法层（核心支撑模块）

#### 模块定位

为智能体运行时层提供AI能力支撑，包括大模型推理、技能插件实现、GraphRAG检索、推理优化。

#### 架构设计

- 核心组件：大模型推理引擎、GraphRAG检索引擎、技能插件实现、推理优化器

- 依赖模块：vLLM、Qwen2-7B/Qwen2-VL、LangChain、Neo4j、Chroma

- 交互对象：智能体运行时层、中间件/存储层

#### 技术方案

1. 大模型推理：基于vLLM实现Qwen2-7B/Qwen2-VL推理优化，开启AWQ量化，降低显存占用、提升推理速度；

2. GraphRAG检索：构建「数据标准-业务场景-表结构-字段口径」知识图谱，提升检索准确率；

3. 技能插件实现：基于LangChain封装工具与技能，实现原子操作与复杂业务逻辑的组合；

4. 推理优化：通过DPO微调，用业务反馈数据优化模型检索偏好与生成效果。

#### 设计需求

1. 大模型推理延迟≤300ms（单条请求），单GPU支持100+并发；

2. GraphRAG检索准确率提升35%+（相比平铺式向量检索）；

3. 技能插件需独立可调试，支持输入输出参数校验；

4. 支持模型微调（DPO），可通过业务反馈数据迭代模型能力；

5. 算法服务需支持高可用，故障时可自动切换备用节点。

### 3.5 中间件/存储层（支撑模块）

#### 模块定位

为整个平台提供数据存储、消息传递、缓存、文件存储支撑，保障系统稳定运行。

#### 各组件设计需求（AI开发需适配）

|组件|技术方案|设计需求|
|---|---|---|
|MySQL 8.0+|存储业务数据（任务、用户、权限、数据源配置等）|1. 支持分表分库（预留扩展）；2. 开启主从复制，保障数据可靠性；3. 支持索引优化，查询延迟≤100ms|
|Redis 7.2+|缓存（会话、任务状态、热点数据）、会话存储|1. 支持持久化（RDB+AOF）；2. 缓存命中率≥95%；3. 支持分布式锁，避免并发冲突|
|Kafka 3.6.x|异步消息传递（Java层与AI层、智能体间通信）|1. 消息可靠性≥99.99%；2. 支持消息幂等性，避免重复消费；3. 支持消息分区，提升并发处理能力|
|Neo4j 5.20+|存储数据治理知识图谱（表关系、业务口径等）|1. 图谱查询延迟≤500ms；2. 支持批量导入与更新；3. 支持复杂关系查询|
|Chroma|向量存储（智能体记忆、RAG检索向量）|1. 向量检索响应≤300ms；2. 支持向量更新与删除；3. 适配Qwen2系列模型的向量输出|
|MinIO|文件存储（元数据文档、质量报告、ETL脚本等）|1. 支持文件上传/下载/删除；2. 支持权限控制；3. 支持文件备份与恢复|
### 3.6 基础设施层（部署支撑模块）

#### 模块定位

实现平台的容器化部署、环境一致性、运维自动化，降低部署与运维成本。

#### 技术方案

1. 容器化部署：所有组件（Java后端、AI算法服务、中间件）均容器化，通过Docker Compose实现一键部署；

2. 环境隔离：区分开发、测试、生产环境，配置文件独立，避免环境冲突；

3. 运维自动化：支持容器启停、日志挂载、数据持久化、版本更新；

4. 服务器环境：基于Ubuntu 22.04，支持GPU（用于AI推理）与CPU部署。

#### 设计需求

1. Docker Compose配置需完整，支持一键启动所有组件；

2. 数据持久化需可靠（中间件数据、业务数据、智能体工作区数据）；

3. 支持日志挂载到宿主机，方便问题排查；

4. 部署流程简单，新手可快速完成部署。

---

## 四、开发规范（AI辅助开发必须遵循）

### 4.1 通用规范

1. 代码命名规范：严格遵循之前约定（Java大驼峰、Python下划线、常量全大写等）；

2. 注释规范：所有模块、类、核心方法需添加注释（作者、功能、输入输出），复杂逻辑需添加行内注释；

3. 异常处理：所有接口、方法需统一异常处理，避免程序崩溃，异常信息需清晰（便于排查）；

4. 日志规范：日志按级别（INFO/WARN/ERROR）分类，包含模块名、操作人、操作内容、时间戳，避免冗余；

5. 版本控制：Git提交信息需符合规范（`<type>(<scope>): <subject>`），避免无意义提交。

### 4.2 智能体开发规范（OpenClaw风格）

1. 智能体命名：ID为小写字母+连字符（如`master-agent`），名称需明确角色；

2. 技能插件规范：需包含元数据（名称、描述、输入输出 schema），实现统一的执行接口；

3. 工作区规范：智能体仅能访问自身工作区，敏感数据需加密存储，日志统一输出到工作区日志目录；

4. 智能体协同规范：避免循环依赖，协同流程需清晰，支持超时机制与异常降级。

### 4.3 接口规范

1. RESTful API规范：HTTP方法（GET查询、POST创建、PUT更新、DELETE删除），路径用小写字母+连字符，参数命名规范；

2. 接口返回格式：统一返回JSON，包含code（状态码）、message（提示信息）、data（返回数据）；

3. 接口权限：所有接口需校验权限（用户/智能体），未授权接口禁止访问；

4. 接口兼容性：接口变更需兼容旧版本，避免破坏性更新。

---

## 五、AI辅助开发引导（核心，告诉AI如何配合）

### 5.1 AI辅助开发范围（明确AI该做什么）

1. 模块逻辑实现：基于各模块的架构设计、技术方案、设计需求，辅助编写核心逻辑（不写冗余代码）；

2. 接口开发：遵循接口规范，辅助编写接口代码、请求参数校验、异常处理；

3. 智能体协同逻辑：基于LangGraph与OpenClaw Runtime，辅助实现智能体协同工作流；

4. 技能插件开发：遵循技能插件规范，辅助实现工具与技能的封装；

5. 配置文件编写：辅助编写application.yml、Docker Compose等配置文件，适配技术栈版本；

6. 问题排查：基于常见问题，辅助排查开发中的技术问题（如依赖冲突、接口报错）。

### 5.2 AI开发约束（明确AI不能做什么）

1. 不偏离既定技术栈：禁止使用未约定的技术、框架、版本；

2. 不编写冗余代码：避免重复编码、无效注释、无用逻辑；

3. 不修改核心架构：严格遵循分层架构、模块职责，不随意变更组件交互逻辑；

4. 不忽略设计需求：所有开发需满足各模块的设计需求（如性能、权限、隔离性）；

5. 不编写前端代码：仅关注后端、AI层、中间件相关开发，不涉及前端UI实现。

### 5.3 开发优先级引导（AI需按优先级辅助开发）

1. P0（核心必做）：智能体网关、总控调度智能体、任务管理模块、基础中间件部署；

2. P1（重要功能）：8个专项智能体核心逻辑、技能插件市场、可观测性模块；

3. P2（优化功能）：智能体反思优化、推理优化、多渠道接入；

4. P3（扩展功能）：自定义技能插件开发、多租户支持、高级监控面板。

---

## 六、常见设计问题与解决方案（AI开发参考）

|问题类型|常见问题|解决方案（AI可参考）|
|---|---|---|
|智能体相关|智能体协同死锁|优化工作流，避免循环依赖，添加超时机制，设置任务最大执行时间|
|智能体相关|技能插件调用失败|检查插件元数据与输入输出格式是否匹配，排查插件依赖，查看插件运行日志|
|智能体相关|工作区冲突|确保每个智能体使用独立工作区ID，工作区路径唯一，避免资源竞争|
|Java后端相关|虚拟线程不生效|确认application.yml中虚拟线程配置正确，JDK版本为21，@Async注解使用正确|
|Java后端相关|Kafka消息丢失|开启Kafka消息持久化，实现消息幂等性，添加消息重试机制，检查消费者组配置|
|AI算法相关|推理延迟过高|确认vLLM配置正确（开启AWQ量化），检查GPU显存占用，优化模型输入长度|
|AI算法相关|GraphRAG检索准确率低|优化知识图谱构建逻辑，增加向量检索的上下文关联，使用DPO微调模型|
|部署相关|容器启动失败|检查Docker Compose配置，确认端口未占用，检查镜像版本与环境兼容性|
|接口相关|接口权限校验失败|检查用户/智能体权限配置，确认接口请求携带正确的权限令牌，排查权限校验逻辑|
---

## 七、文档使用说明（AI辅助开发指引）

1. 开发前：AI需先阅读本文档，明确项目架构、技术栈、模块职责、设计需求，避免偏离方向；

2. 开发中：AI需对照对应模块的“技术方案”“设计需求”，辅助编写代码，遵循开发规范；

3. 开发后：AI需对照“常见问题”，辅助排查代码中的潜在问题，确保符合设计要求；

4. 扩展开发：新增功能（如自定义技能插件）时，AI需遵循本文档的设计原则、规范，确保与现有架构兼容。
## 容器化交付入口

项目已提供基础设施 Compose、应用层 Compose overlay、共享 Java/Python 多阶段 Dockerfile 和可重复交付检查。完整说明见 [全平台容器化交付说明](docs/containerized-application-deployment.md)。
