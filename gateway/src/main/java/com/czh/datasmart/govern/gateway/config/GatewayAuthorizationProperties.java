/**
 * @Author : Cui
 * @Date: 2026/04/25 23:20
 * @Description DataSmart Govern Backend - GatewayAuthorizationProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关授权配置。
 *
 * <p>这个配置类用于控制 gateway 是否调用 permission-admin 做路由级权限判定。
 * 它和 {@link GatewayContextProperties} 的职责不同：
 * 1. GatewayContextProperties 负责“请求上下文如何生成和传播”；
 * 2. GatewayAuthorizationProperties 负责“是否把请求交给权限中心判定，以及判定失败时如何处理”。
 *
 * <p>为什么不直接默认强制开启？
 * 当前 gateway 已接入 OIDC/JWT Resource Server 作为认证中心，但 permission-admin 的授权策略、审计记录、
 * 服务账号策略和本地开发依赖并不一定总是同时启动。如果默认强制授权，开发者只启动某个业务模块时会遇到
 * 所有请求 403 或权限中心不可用导致 503，这会拉高学习和联调成本。
 * 因此当前默认 enabled=false、shadowMode=true，适合先观察权限矩阵；生产环境应切换为 enabled=true、
 * shadowMode=false、failOpenOnError=false，让“已认证身份”继续接受 permission-admin 的强制授权。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.gateway.authorization")
public class GatewayAuthorizationProperties {

    /**
     * 是否启用 gateway -> permission-admin 的路由级授权判定。
     *
     * <p>false：网关只补齐上下文，不调用权限中心；
     * true：网关会在转发业务请求前调用 permission-admin 的 evaluate 接口。
     */
    private boolean enabled = false;

    /**
     * 是否启用影子模式。
     *
     * <p>影子模式会调用 permission-admin 并记录判定结果，但不会真正拦截请求。
     * 这对生产化迁移非常重要：可以先观察权限矩阵是否会误杀正常流量，再切到强制拦截。
     */
    private boolean shadowMode = true;

    /**
     * 权限中心不可用或调用异常时是否放行。
     *
     * <p>开发环境可以设置 true，避免权限中心未启动时影响联调；
     * 生产环境建议设置 false，因为权限中心不可用时继续放行会扩大越权风险。
     */
    private boolean failOpenOnError = true;

    /**
     * permission-admin 判定接口地址。
     *
     * <p>当前默认使用本地直连地址，避免 WebClient 对 lb:// 服务发现支持不完整时影响编译和本地启动。
     * 后续如果引入 Spring Cloud LoadBalancer WebClient，可以把它改成 lb://permission-admin/permissions/evaluate。
     */
    private String decisionEndpoint = "http://localhost:8085/permissions/evaluate";

    /**
     * 调用 permission-admin 的超时时间。
     *
     * <p>网关位于所有请求的关键路径上，不能无限等待权限中心。
     * 商业化目标里，远程权限判定 P95 应尽量控制在 80ms 以内；这里先给 500ms 作为开发期保守上限。
     */
    private Duration timeout = Duration.ofMillis(500);

    /**
     * 未携带角色上下文时使用的默认角色。
     *
     * <p>当前还没有 JWT claim 映射，因此本地开发可以用默认普通用户角色完成基础链路。
     * 接入真实认证后，应由认证过滤器写入 X-DataSmart-Actor-Role，而不是长期依赖默认值。
     */
    private String defaultActorRole = "ORDINARY_USER";

    /**
     * 未携带租户上下文时使用的默认租户。
     *
     * <p>0 表示平台默认策略范围。真实多租户上线后，不应把所有业务请求都归到 0。
     */
    private Long defaultTenantId = 0L;

    /**
     * 未携带操作者 ID 时使用的匿名操作者 ID。
     *
     * <p>0 代表系统无法识别具体用户。生产环境应尽量避免出现 0，因为这会降低审计可追溯性。
     */
    private Long anonymousActorId = 0L;

    /**
     * 永远跳过授权判定的路径。
     *
     * <p>健康检查、网关契约说明、静态诊断接口通常不应依赖权限中心，否则权限中心故障时连健康状态都无法查看。
     */
    private List<String> publicPathPatterns = new ArrayList<>(List.of(
            "/actuator/**",
            "/gateway/contracts/**",
            "/auth/**",
            "/api/auth/**"
    ));

    /**
     * 路由授权元数据列表。
     *
     * <p>为什么需要这层配置？
     * 早期网关可以简单地用“路径前缀 + HTTP 方法”推断资源类型和动作，例如：
     * /api/datasource/** + GET -> DATASOURCE + VIEW。
     * 但真实商业产品很快会遇到更复杂的动作语义：
     * 1. POST /api/task/{id}/retry 不是 CREATE，而是 RETRY 或 EXECUTE；
     * 2. POST /api/task/{id}/cancel 不是 CREATE，而是 CANCEL；
     * 3. GET /api/datasource/{id}/export 可能是 EXPORT，不只是 VIEW；
     * 4. PUT /api/permission/policies/{id}/approve 可能是 APPROVE，不只是 UPDATE；
     * 5. 同一模块下还可能区分普通业务动作、管理员动作、审计查看动作和服务账号动作。
     *
     * <p>本字段先提供“模块前缀级”的可配置元数据，解决硬编码问题；
     * 后续可以演进为“具体端点级 / 按钮级 / 管理动作级”的元数据中心，甚至由 permission-admin 统一维护。
     *
     * <p>顺序很重要：列表越靠前优先级越高。
     * 因此未来如果新增类似 /api/task/{taskId}/retry 这类更细粒度规则，应放在 /api/task/** 之前。
     */
    private List<RouteAuthorizationMetadata> routeMetadata = defaultRouteMetadata();

    /**
     * 内部服务端点保护配置。
     *
     * <p>有些接口虽然走 `/api/**`，但产品语义上并不应该直接开放给普通人类用户。
     * 例如 `/api/agent/plan-ingestions` 是 Python AI Runtime 把 AgentPlan 提交给 Java 控制面的内部桥梁：
     * 如果普通用户可以直接构造请求调用，就可能绕过“前端会话入口 -> 智能网关 -> Python Runtime -> Java 控制面”的正规链路。
     *
     * <p>这里先在 gateway 做第一道保护：
     * 1. 要求调用方角色必须是服务账号；
     * 2. 可选要求内部 Token Header；
     * 3. 做本地固定窗口限流，避免模型异常、重试风暴或恶意调用把 agent-runtime 打满。
     *
     * <p>注意：这不是替代 permission-admin。
     * gateway 本地保护负责“快速拦截明显不该进来的调用”，permission-admin 仍负责策略事实、审计记录和数据范围透传。
     */
    private List<InternalServiceEndpointProperties> internalServiceEndpoints = defaultInternalServiceEndpoints();

    /**
     * 授权判定缓存配置。
     *
     * <p>为什么网关需要缓存权限判定？
     * 一旦 gateway 开启强制授权，每个业务请求都会先调用 permission-admin。
     * 如果没有缓存，高并发场景下 permission-admin 会成为所有模块的公共瓶颈：
     * 1. 热点页面的列表查询会重复判定同一角色、同一路径、同一动作；
     * 2. 数据源、任务、质量、审计等模块的请求都会争抢同一个权限中心；
     * 3. 权限中心短暂抖动会放大成全平台接口延迟抖动。
     *
     * <p>当前先实现“本地 TTL 缓存”作为第一阶段：
     * 优点是简单、无外部依赖、编译和本地启动稳定；
     * 缺点是多网关实例之间不会自动共享缓存，也不会自动感知权限变更。
     * 后续商业化应继续接 Redis 分布式缓存、Kafka 权限变更事件和本地快照失效机制。
     */
    private AuthorizationCacheProperties cache = new AuthorizationCacheProperties();

    /**
     * 构造默认路由授权元数据。
     *
     * <p>这里用代码保留一份默认值，是为了即使 application.yml 没有配置 route-metadata，
     * 网关也能继续保持与当前模块边界一致的基础授权语义。
     */
    private static List<RouteAuthorizationMetadata> defaultRouteMetadata() {
        List<RouteAuthorizationMetadata> defaults = new ArrayList<>();
        defaults.add(route("/api/datasource/**", "DATASOURCE", "数据源、连接器、元数据与同步控制面"));
        defaults.add(route("/api/agent/plan-ingestions", "AI_RUNTIME",
                "Python AI Runtime 向 Java agent-runtime 提交 AgentPlan 的内部控制面入口", Map.of("POST", "INGEST_PLAN")));
        defaults.add(route("/api/agent/events/ws", "AI_RUNTIME",
                "Agent Runtime 实时事件 WebSocket 订阅入口，用于订阅 run/session 进度、断线续传、ack 和 heartbeat",
                Map.of("GET", "SUBSCRIBE")));
        defaults.add(route("/api/agent/tool-execution-events/outbox/diagnostics", "AI_RUNTIME",
                "Agent 工具执行事件 outbox 诊断接口，用于运维查看 pending、publishing、failed、blocked 和 ignored 等状态分布",
                Map.of("GET", "DIAGNOSE")));
        defaults.add(route("/api/agent/tool-execution-events/outbox/{outboxId}/requeue", "AI_RUNTIME",
                "Agent 工具执行事件 outbox 人工重新入队入口，属于恢复类高风险运维动作，不能按普通 POST CREATE 授权",
                Map.of("POST", "REQUEUE_OUTBOX")));
        defaults.add(route("/api/agent/tool-execution-events/outbox/{outboxId}/ignore", "AI_RUNTIME",
                "Agent 工具执行事件 outbox 人工忽略入口，表示管理员确认该事件无需继续自动投递，必须独立审计",
                Map.of("POST", "IGNORE_OUTBOX")));
        defaults.add(route("/api/agent/tool-execution-events/outbox/{outboxId}/notes", "AI_RUNTIME",
                "Agent 工具执行事件 outbox 人工备注入口，用于记录排障判断、客户确认或恢复前置条件",
                Map.of("POST", "ANNOTATE_OUTBOX")));
        defaults.add(route("/api/agent/tool-execution-events/outbox/**", "AI_RUNTIME",
                "Agent 工具执行事件 outbox 查询接口，用于排查工具状态事件是否进入待投递事件箱以及是否发生堆积或阻断",
                Map.of("GET", "VIEW_OUTBOX_EVENTS")));
        defaults.add(route("/api/agent/memory/write-candidates/{candidateId}/approve", "AI_RUNTIME",
                "Agent 长期记忆写入候选批准入口；批准后候选未来可以进入向量库、图谱库或对象存储，因此必须区别于普通 POST CREATE",
                Map.of("POST", "APPROVE_MEMORY_WRITE")));
        defaults.add(route("/api/agent/memory/write-candidates/{candidateId}/reject", "AI_RUNTIME",
                "Agent 长期记忆写入候选拒绝入口；拒绝会形成治理事实和审计依据，不能退化为普通 UPDATE 或 APPROVE",
                Map.of("POST", "REJECT_MEMORY_WRITE")));
        defaults.add(route("/api/agent/memory/write-candidates/**", "AI_RUNTIME",
                "Agent 长期记忆写入候选查询入口；用于审批台、审计台和运维台查看哪些工具结果准备进入长期记忆",
                Map.of("GET", "VIEW_MEMORY_WRITE_CANDIDATES")));
        defaults.add(route("/api/agent/runtime-events/diagnostics", "AI_RUNTIME",
                "Agent Runtime 运行时事件消费诊断接口，用于运维查看 Kafka consumer、投影窗口和拒绝原因统计",
                Map.of("GET", "DIAGNOSE")));
        defaults.add(route("/api/agent/runtime-events/replay/acks", "AI_RUNTIME",
                "Agent Runtime 事件回放客户端 ack 游标入口，用于确认 WebSocket/HTTP replay 已消费到的 replaySequence",
                Map.of("GET", "VIEW_EVENTS", "POST", "ACK_EVENTS")));
        defaults.add(route("/api/agent/runtime-events/**", "AI_RUNTIME",
                "Agent Runtime 运行时事件投影查询接口，用于查看 run/session/request 维度的 Agent 执行事件",
                Map.of("GET", "VIEW_EVENTS")));
        defaults.add(route("/api/agent/sessions/{sessionId}/runs/{runId}/tool-executions/dag-confirmations/**", "AI_RUNTIME",
                "Agent DAG selected-node 确认记录审计查询接口，用于查看人工确认、dry-run 指纹、策略版本和 outbox 证据",
                Map.of("GET", "VIEW_TOOL_CONFIRMATIONS")));
        defaults.add(route("/api/agent/sessions/{sessionId}/runs/{runId}/tool-executions/dag-confirmations", "AI_RUNTIME",
                "Agent DAG selected-node 确认记录审计列表接口，用于按 run 查看确认历史和授权证据",
                Map.of("GET", "VIEW_TOOL_CONFIRMATIONS")));
        defaults.add(route("/api/agent/sessions/{sessionId}/runs/{runId}/tool-executions/dag-selected-node-outbox/enqueue", "AI_RUNTIME",
                "DAG 已确认选中节点异步入箱入口，只允许推进经过 dry-run 指纹复核的节点", Map.of("POST", "ENQUEUE_SELECTED_ASYNC_TOOL")));
        defaults.add(route("/api/agent/sessions/{sessionId}/runs/{runId}/tool-executions/async-command-outbox/enqueue", "AI_RUNTIME",
                "兼容 Run 级异步命令批量入箱入口，生产应收口为内部补偿或管理员动作", Map.of("POST", "ENQUEUE_RUN_ASYNC_TOOLS")));
        defaults.add(route("/api/agent/**", "AI_RUNTIME",
                "Agent Runtime 模型、工具、Skill、会话、Run、计划接入和工具审计控制面"));
        defaults.add(route("/api/sync/sync-templates/*/validate", "SYNC_TEMPLATE",
                "data-sync 同步模板校验接口，校验源端、目标端、字段映射和写入策略是否可运行", Map.of("POST", "VALIDATE")));
        defaults.add(route("/api/sync/sync-tasks/*/run", "SYNC_TASK",
                "data-sync 同步任务手动运行接口，属于显式触发执行动作", Map.of("POST", "RUN")));
        defaults.add(route("/api/sync/sync-tasks/*/executions/*/**", "SYNC_EXECUTION",
                "data-sync 执行器回调接口，通常由服务账号调用，需要区别于人工任务操作", Map.of("POST", "CALLBACK")));
        defaults.add(route("/api/sync/sync-tasks/*/attention/acknowledge", "SYNC_OPERATION",
                "data-sync 人工介入确认接手动作", Map.of("POST", "ACKNOWLEDGE")));
        defaults.add(route("/api/sync/sync-tasks/*/attention/resolve", "SYNC_OPERATION",
                "data-sync 人工介入解决动作", Map.of("POST", "RESOLVE")));
        defaults.add(route("/api/sync/sync-tasks/*/attention/rerun", "SYNC_OPERATION",
                "data-sync 人工介入后重跑动作", Map.of("POST", "RETRY")));
        defaults.add(route("/api/sync/sync-tasks/*/attention/cancel", "SYNC_OPERATION",
                "data-sync 人工介入取消动作", Map.of("POST", "CANCEL")));
        defaults.add(route("/api/sync/sync-tasks/*/attention/archive", "SYNC_OPERATION",
                "data-sync 人工介入归档动作", Map.of("POST", "ARCHIVE")));
        defaults.add(route("/api/sync/sync-tasks/*/attention/incidents", "SYNC_OPERATION",
                "data-sync 人工介入创建事故动作", Map.of("POST", "CREATE")));
        defaults.add(route("/api/sync/sync-tasks/*/attention/**", "SYNC_OPERATION",
                "data-sync 人工介入处理接口，包含确认、解决、重跑、取消、归档和创建事故等高风险动作"));
        defaults.add(route("/api/sync/sync-incidents/*/acknowledge", "SYNC_INCIDENT",
                "data-sync 事故确认接手动作", Map.of("POST", "ACKNOWLEDGE")));
        defaults.add(route("/api/sync/sync-incidents/*/assign", "SYNC_INCIDENT",
                "data-sync 事故分派负责人动作", Map.of("POST", "ASSIGN")));
        defaults.add(route("/api/sync/sync-incidents/*/resolve", "SYNC_INCIDENT",
                "data-sync 事故解决动作", Map.of("POST", "RESOLVE")));
        defaults.add(route("/api/sync/sync-incidents/*/close", "SYNC_INCIDENT",
                "data-sync 事故关闭动作", Map.of("POST", "CLOSE")));
        defaults.add(route("/api/sync/sync-incidents/**", "SYNC_INCIDENT",
                "data-sync 事故工作台接口，用于事故查看、接手、分派、解决和关闭"));
        defaults.add(route("/api/sync/sync-executions/claim", "SYNC_EXECUTION",
                "data-sync 执行器认领下一条 execution 的协议入口", Map.of("POST", "CLAIM")));
        defaults.add(route("/api/sync/sync-executions/*/heartbeat", "SYNC_EXECUTION",
                "data-sync 执行器续租和上报轻量进度的协议入口", Map.of("POST", "HEARTBEAT")));
        defaults.add(route("/api/sync/sync-executions/*/defer", "SYNC_EXECUTION",
                "data-sync 执行器因容量或窗口限制延迟回队列的协议入口", Map.of("POST", "DEFER")));
        defaults.add(route("/api/sync/sync-executions/recover-expired-leases", "SYNC_EXECUTION",
                "data-sync 过期租约恢复入口，属于运维恢复动作", Map.of("POST", "RECOVER")));
        defaults.add(route("/api/sync/sync-executions/**", "SYNC_EXECUTION",
                "data-sync 执行租约接口，用于执行器认领、心跳、延期和过期租约恢复"));
        defaults.add(route("/api/sync/sync-templates/**", "SYNC_TEMPLATE",
                "data-sync 同步模板接口，用于配置源端、目标端、同步模式、字段映射和写入策略"));
        defaults.add(route("/api/sync/**", "SYNC_TASK",
                "data-sync 同步任务接口，用于同步任务创建、查询、运行、执行历史和审计查询"));
        defaults.add(route("/api/task/operations/**", "TASK_OPERATION",
                "任务队列、死信、延期、执行器租约等运营工作台接口，必须区别于普通任务查看接口进行授权"));
        defaults.add(route("/api/task/task-drafts/**", "TASK_DRAFT",
                "任务草稿、审批和转换真实任务入口；草稿不会直接进入执行队列"));
        defaults.add(route("/api/task/**", "TASK", "全平台任务编排、调度、重试、取消和执行记录"));
        addDataQualityRouteMetadata(defaults);
        defaults.add(route("/api/permission/**", "SYSTEM_SETTING", "角色、菜单、路由策略、数据范围和平台管理能力"));
        defaults.add(route("/api/observability/**", "AUDIT_LOG", "审计、日志、指标、告警和运维视角"));
        return defaults;
    }

    /**
     * 补齐 data-quality 模块的细粒度授权语义。
     *
     * <p>为什么不继续只保留 `/api/quality/** -> QUALITY_RULE`？
     * 数据质量模块现在已经不再只是“规则 CRUD”：它包含治理总览、质量报告、异常工作台、执行器诊断、
     * 手动触发检测以及 worker 回调等多类入口。真实商业产品里，这几类入口的风险等级完全不同：
     * 1. 治理总览通常是低敏聚合视图，适合项目负责人、审计员和运营人员查看；
     * 2. 质量规则会影响后续所有检测结果，属于配置变更能力；
     * 3. 异常工作台接近业务问题定位，后续可能扩展样本查看、导出和清洗任务创建；
     * 4. 执行器入口会改变执行状态或暴露 worker 运行信息，不能和普通查看权限混在一起；
     * 5. 执行器回调是机器协议，原则上只应由 SERVICE_ACCOUNT 调用。
     *
     * <p>本方法把这些业务边界提前沉淀为 gateway 的默认路由元数据。即使 `application.yml` 暂时没有覆盖配置，
     * 本地默认值也能向 permission-admin 发送清晰的 resourceType/action，避免权限中心只能看到模糊的
     * `QUALITY_RULE + CREATE`。</p>
     *
     * @param defaults 默认路由元数据集合，调用方会按插入顺序匹配，越具体的规则必须越靠前。
     */
    private static void addDataQualityRouteMetadata(List<RouteAuthorizationMetadata> defaults) {
        defaults.add(route("/api/quality/quality-rules/governance/overview", "QUALITY_GOVERNANCE",
                "数据质量治理总览低敏聚合入口，用于查看规则、报告、执行和异常的项目级态势。",
                Map.of("GET", "VIEW")));
        defaults.add(route("/api/quality/quality-rules/remediation-tasks", "QUALITY_ANOMALY",
                "质量异常治理任务创建入口，用于把低敏异常聚合转成 task-management 治理/复核任务；它是异常处置动作，不是质量规则创建。",
                Map.of("POST", "CREATE_REMEDIATION_TASK")));
        defaults.add(route("/api/quality/quality-rules/reports/*/anomalies", "QUALITY_ANOMALY",
                "按报告查看质量异常明细或聚合结果，风险高于普通报告摘要，因此单独映射到异常资源。",
                Map.of("GET", "VIEW")));
        defaults.add(route("/api/quality/quality-rules/anomalies/**", "QUALITY_ANOMALY",
                "质量异常工作台入口，用于查看异常聚合、异常列表和后续清洗任务线索。",
                Map.of("GET", "VIEW")));
        defaults.add(route("/api/quality/quality-rules/reports/**", "QUALITY_REPORT",
                "质量报告查询入口，用于查看检测结果快照、通过率和低敏报告摘要。",
                Map.of("GET", "VIEW")));
        defaults.add(route("/api/quality/quality-rules/executor/diagnostics", "QUALITY_EXECUTION",
                "质量执行器诊断入口，展示 worker 健康、执行积压和运维排障信息。",
                Map.of("GET", "DIAGNOSE")));
        defaults.add(route("/api/quality/quality-rules/executor/executions/*/succeed", "QUALITY_EXECUTION",
                "质量执行器成功回调入口，只应由受控 worker 或服务账号调用。",
                Map.of("POST", "CALLBACK")));
        defaults.add(route("/api/quality/quality-rules/executor/executions/*/fail", "QUALITY_EXECUTION",
                "质量执行器失败回调入口，只应由受控 worker 或服务账号调用。",
                Map.of("POST", "CALLBACK")));
        defaults.add(route("/api/quality/quality-rules/executor/executions/start", "QUALITY_EXECUTION",
                "质量执行器开始回调入口，用于把执行记录推进到运行态。",
                Map.of("POST", "CALLBACK")));
        defaults.add(route("/api/quality/quality-rules/executor/coordinator/run-once", "QUALITY_EXECUTION",
                "质量执行协调器单次调度入口，属于显式运行类动作。",
                Map.of("POST", "RUN")));
        defaults.add(route("/api/quality/quality-rules/executor/coordinator/run-batch", "QUALITY_EXECUTION",
                "质量执行协调器批量调度入口，可能触发多个规则检测，按运行类动作授权。",
                Map.of("POST", "RUN")));
        defaults.add(route("/api/quality/quality-rules/*/run-check", "QUALITY_EXECUTION",
                "单条质量规则手动检测入口，属于执行类动作而不是规则创建动作。",
                Map.of("POST", "RUN")));
        defaults.add(route("/api/quality/quality-rules/*/executions", "QUALITY_EXECUTION",
                "单条规则执行历史查询入口，用于查看执行状态、耗时和低敏结果。",
                Map.of("GET", "VIEW")));
        defaults.add(route("/api/quality/quality-rules/*/reports", "QUALITY_REPORT",
                "单条规则质量报告查询入口，用于查看该规则产生的报告列表。",
                Map.of("GET", "VIEW")));
        defaults.add(route("/api/quality/quality-rules/*/validate-target", "QUALITY_RULE",
                "规则目标校验入口，用于校验表、字段或对象是否可被质量规则引用。",
                Map.of("POST", "VALIDATE")));
        defaults.add(route("/api/quality/quality-rules/*/scan-plan", "QUALITY_RULE",
                "规则扫描计划生成入口，会影响后续检测方式，按规则配置动作授权。",
                Map.of("POST", "CONFIGURE")));
        defaults.add(route("/api/quality/quality-rules/*/relational-sql-plan", "QUALITY_RULE",
                "关系型规则 SQL 计划预览入口，只返回低敏计划摘要，但仍属于规则配置能力。",
                Map.of("POST", "CONFIGURE")));
        defaults.add(route("/api/quality/quality-rules/*/schedule-task", "QUALITY_EXECUTION",
                "规则调度任务配置入口，会影响后续自动执行节奏，按执行配置动作授权。",
                Map.of("POST", "CONFIGURE")));
        defaults.add(route("/api/quality/quality-rules/*/enable", "QUALITY_RULE",
                "启用质量规则入口，会让规则参与后续检测。",
                Map.of("POST", "ENABLE")));
        defaults.add(route("/api/quality/quality-rules/*/disable", "QUALITY_RULE",
                "禁用质量规则入口，会让规则退出后续检测。",
                Map.of("POST", "DISABLE")));
        defaults.add(route("/api/quality/quality-rules/*/archive", "QUALITY_RULE",
                "归档质量规则入口，保留审计事实但停止作为活跃规则使用。",
                Map.of("POST", "ARCHIVE")));
        defaults.add(route("/api/quality/quality-rules/*/restore", "QUALITY_RULE",
                "恢复已归档质量规则入口，重新进入可配置或可启用状态。",
                Map.of("POST", "ENABLE")));
        defaults.add(route("/api/quality/quality-rules/suggestions", "QUALITY_RULE",
                "质量规则建议生成入口，用于沉淀可编辑规则草案。",
                Map.of("POST", "CREATE")));
        defaults.add(route("/api/quality/quality-rules/**", "QUALITY_RULE",
                "数据质量规则管理兜底入口，用于规则创建、查询、更新和删除。",
                defaultMethodActions()));
        defaults.add(route("/api/quality/**", "QUALITY_RULE",
                "数据质量模块兼容兜底入口；新增端点应优先补充更具体的元数据规则。",
                defaultMethodActions()));
    }

    /**
     * 默认内部服务端点。
     *
     * <p>当前只把 AgentPlan 接入口纳入强保护。
     * 后续如果 WebSocket replay、Kafka callback fallback、模型 usage write-back 等能力也通过 HTTP 暴露，
     * 应继续在这里增加端点，而不是让它们依赖普通 `/api/agent/**` 通配策略。
     */
    private static List<InternalServiceEndpointProperties> defaultInternalServiceEndpoints() {
        InternalServiceEndpointProperties endpoint = new InternalServiceEndpointProperties();
        endpoint.setName("agent-plan-ingestion");
        endpoint.setPathPattern("/api/agent/plan-ingestions");
        endpoint.setAllowedActorRoles(List.of("SERVICE_ACCOUNT"));
        endpoint.setRateLimitEnabled(true);
        endpoint.setMaxRequestsPerMinute(120);
        endpoint.setDescription("Python AI Runtime 提交 AgentPlan 到 Java 控制面的内部入口。");
        return new ArrayList<>(List.of(endpoint));
    }

    /**
     * 创建一条默认路由元数据。
     *
     * <p>默认动作采用通用 HTTP 语义：
     * GET=VIEW，POST=CREATE，PUT/PATCH=UPDATE，DELETE=DELETE。
     * 这不是最终商业权限模型，只是当前阶段比硬编码更清晰、更容易扩展的基线。
     */
    private static RouteAuthorizationMetadata route(String pathPattern, String resourceType, String description) {
        return route(pathPattern, resourceType, description, defaultMethodActions());
    }

    /**
     * 创建一条带自定义动作映射的路由元数据。
     *
     * <p>当一个 POST 并不表示 CREATE，而是 RUN、CALLBACK、RECOVER、ACKNOWLEDGE 等业务动作时，
     * 必须使用这个重载显式指定 methodActions。这样 gateway 发给 permission-admin 的 action 才能反映真实业务意图。
     */
    private static RouteAuthorizationMetadata route(String pathPattern,
                                                    String resourceType,
                                                    String description,
                                                    Map<String, String> methodActions) {
        RouteAuthorizationMetadata metadata = new RouteAuthorizationMetadata();
        metadata.setPathPattern(pathPattern);
        metadata.setResourceType(resourceType);
        metadata.setDefaultAction("EXECUTE");
        metadata.setDescription(description);
        metadata.setMethodActions(methodActions);
        return metadata;
    }

    /**
     * 默认 HTTP 方法到平台动作的映射。
     */
    private static Map<String, String> defaultMethodActions() {
        Map<String, String> methodActions = new LinkedHashMap<>();
        methodActions.put("GET", "VIEW");
        methodActions.put("POST", "CREATE");
        methodActions.put("PUT", "UPDATE");
        methodActions.put("PATCH", "UPDATE");
        methodActions.put("DELETE", "DELETE");
        return methodActions;
    }

    /**
     * 单条路由授权元数据。
     *
     * <p>这不是 Spring Cloud Gateway 自带的 Route Metadata，而是 DataSmart Govern 的授权语义层。
     * Spring Gateway 负责“把请求转发到哪个服务”，本对象负责“把请求解释成哪类资源和哪种动作”，
     * permission-admin 再基于这个语义做角色、路由策略、数据范围和审计记录。
     */
    @Data
    public static class RouteAuthorizationMetadata {

        /**
         * 路由路径匹配规则。
         *
         * <p>当前支持完全匹配和 /** 后缀通配，例如 /api/task/**。
         * 未来如果出现更复杂的 Ant Path 或 PathPattern 需求，可以在网关过滤器中替换匹配器，而不改权限请求契约。
         */
        private String pathPattern;

        /**
         * 资源类型。
         *
         * <p>该值会传给 permission-admin，用于匹配数据范围策略和资源级权限。
         * 推荐值应与权限中心的资源类型枚举保持一致，例如 DATASOURCE、TASK、QUALITY_RULE、SYSTEM_SETTING、AUDIT_LOG。
         */
        private String resourceType;

        /**
         * 未命中 methodActions 时使用的默认动作。
         *
         * <p>EXECUTE 表示“非标准或无法简单归类的执行类动作”。
         * 比如后续的 retry、cancel、approve、export 都可以先落到 EXECUTE，再逐步细分为更具体的动作编码。
         */
        private String defaultAction = "EXECUTE";

        /**
         * HTTP 方法到平台动作的映射。
         *
         * <p>这里用 Map 而不是固定字段，是为了后续支持 HEAD、OPTIONS、批处理自定义方法或网关内部虚拟动作时更灵活。
         */
        private Map<String, String> methodActions = defaultMethodActions();

        /**
         * 元数据说明。
         *
         * <p>它不参与运行时判定，但对学习、运维和后续配置审查很有价值：
         * 管理员看到一条路由规则时，应该能理解它对应哪个业务模块、保护什么能力、为什么这样映射。
         */
        private String description;
    }

    /**
     * 网关授权缓存配置。
     *
     * <p>所有默认值都偏保守：缓存默认关闭，TTL 较短，管理端点默认关闭。
     * 这是因为权限是安全能力，不能为了性能过早牺牲策略一致性和审计完整性。
     */
    @Data
    public static class AuthorizationCacheProperties {

        /**
         * 是否启用本地授权判定缓存。
         *
         * <p>默认 false，避免在权限矩阵仍然频繁调整时出现“策略已经改了但网关还在使用旧判定”的误判。
         * 当 permission-admin 策略稳定、缓存失效机制可用后，可以逐步开启。
         */
        private boolean enabled = false;

        /**
         * 是否在影子模式下缓存判定结果。
         *
         * <p>默认 false。影子模式的核心价值是观察真实流量下权限中心会如何判定。
         * 如果过早缓存，permission-admin 就无法记录每次真实请求的判定审计，观察价值会下降。
         */
        private boolean cacheShadowModeDecisions = false;

        /**
         * 是否缓存拒绝结果。
         *
         * <p>拒绝结果缓存能减轻恶意探测和无权限用户重复访问的压力；
         * 但它也更容易受到权限变更影响，例如管理员刚给用户授权，短 TTL 内仍可能被拒绝。
         * 因此拒绝 TTL 应明显短于允许 TTL。
         */
        private boolean cacheDeniedDecisions = true;

        /**
         * 是否缓存需要审批的判定结果。
         *
         * <p>默认 false。需要审批通常意味着高风险操作，例如导出敏感数据、修改权限、强制终止任务。
         * 这类动作更强调审计和实时策略，不应过早缓存。
         */
        private boolean cacheApprovalRequiredDecisions = false;

        /**
         * 允许结果的缓存时间。
         *
         * <p>商业化目标可以逐步向“本地缓存 P95 < 20ms、远程授权 P95 < 80ms”靠近。
         * 这里默认 30 秒，是一个兼顾性能和策略新鲜度的开发期起点。
         */
        private Duration allowTtl = Duration.ofSeconds(30);

        /**
         * 拒绝结果的缓存时间。
         *
         * <p>拒绝 TTL 默认更短，减少权限刚变更后仍被旧拒绝结果影响的时间窗口。
         */
        private Duration denyTtl = Duration.ofSeconds(5);

        /**
         * 本地缓存最大条目数。
         *
         * <p>网关处于入口层，必须防止恶意构造大量不同路径或身份导致内存无限增长。
         * 达到上限时，当前实现会优先清理过期条目，仍超限则做一次保守清空。
         */
        private int maxEntries = 10000;

        /**
         * 手动缓存管理端点配置。
         *
         * <p>当前阶段先提供“本地实例手动失效”能力，用于联调和应急排障。
         * 多实例生产环境应继续接入 permission-admin 发布的 Kafka 策略变更事件，实现所有网关实例自动失效。
         */
        private CacheManagementEndpointProperties managementEndpoint = new CacheManagementEndpointProperties();
    }

    /**
     * 授权缓存管理端点配置。
     */
    @Data
    public static class CacheManagementEndpointProperties {

        /**
         * 是否启用网关本地缓存管理端点。
         *
         * <p>默认关闭。因为当前 Spring Security 还未完整接入正式认证，
         * 不能把缓存清理能力直接暴露给外部调用方。
         */
        private boolean enabled = false;

        /**
         * 管理端点要求的内部 Header 名称。
         *
         * <p>这是临时防护，不是最终生产认证。生产环境应由平台管理员权限或服务账号签名控制。
         */
        private String tokenHeaderName = "X-DataSmart-Gateway-Admin-Token";

        /**
         * 管理端点要求的内部 Token。
         *
         * <p>默认留空。只要 token 为空，即使端点被启用也会拒绝清理请求，
         * 避免开发者误开端点后形成无保护的缓存清理入口。
         */
        private String token = "";
    }
}
