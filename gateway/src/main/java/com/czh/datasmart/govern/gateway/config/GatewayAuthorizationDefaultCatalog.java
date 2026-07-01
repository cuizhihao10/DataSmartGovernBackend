/**
 * @Author : Cui
 * @Date: 2026/07/02 02:20
 * @Description DataSmart Govern Backend - GatewayAuthorizationDefaultCatalog.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网关授权配置的模块化默认目录。
 *
 * <p>{@link GatewayAuthorizationProperties} 的核心职责是声明 Spring 可绑定的配置结构；随着产品端点增加，
 * 如果所有默认路由都继续堆在配置类里，配置字段、业务目录和构造辅助方法会形成一个难以审查的大文件。
 * 本类先承接 data-quality 细粒度目录和内部服务端点目录，保持默认值、匹配顺序与公开配置类型不变。
 *
 * <p>这里是“没有显式 YAML 覆盖时的安全基线”，不是权限事实源。最终允许/拒绝仍由
 * permission-admin 根据角色、策略、数据范围和审批事实判定；新增高风险端点时仍应同步审查网关目录、
 * permission-admin 策略和业务服务内部鉴权。
 */
final class GatewayAuthorizationDefaultCatalog {

    private GatewayAuthorizationDefaultCatalog() {
    }

    /**
     * 补齐 data-quality 模块的细粒度授权语义。
     *
     * <p>质量模块同时包含治理总览、规则配置、报告、异常工作台、调度和 worker 回调。这些端点的风险
     * 不同，不能全部退化为 {@code QUALITY_RULE + CREATE/VIEW}。规则按从具体到宽泛的顺序追加，
     * 保证 PathPattern 首次命中时得到最准确的资源和动作。
     *
     * @param defaults 调用方维护的有序默认路由集合
     */
    static void addDataQualityRouteMetadata(
            List<GatewayAuthorizationProperties.RouteAuthorizationMetadata> defaults) {
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/governance/overview", "QUALITY_GOVERNANCE",
                "数据质量治理总览低敏聚合入口，用于查看规则、报告、执行和异常的项目级态势。",
                Map.of("GET", "VIEW")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/remediation-tasks", "QUALITY_ANOMALY",
                "质量异常治理任务创建入口，用于把低敏异常聚合转成 task-management 治理/复核任务；它是异常处置动作，不是质量规则创建。",
                Map.of("POST", "CREATE_REMEDIATION_TASK")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/reports/*/anomalies", "QUALITY_ANOMALY",
                "按报告查看质量异常明细或聚合结果，风险高于普通报告摘要，因此单独映射到异常资源。",
                Map.of("GET", "VIEW")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/anomalies/**", "QUALITY_ANOMALY",
                "质量异常工作台入口，用于查看异常聚合、异常列表和后续清洗任务线索。",
                Map.of("GET", "VIEW")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/reports/**", "QUALITY_REPORT",
                "质量报告查询入口，用于查看检测结果快照、通过率和低敏报告摘要。",
                Map.of("GET", "VIEW")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/executor/diagnostics", "QUALITY_EXECUTION",
                "质量执行器诊断入口，展示 worker 健康、执行积压和运维排障信息。",
                Map.of("GET", "DIAGNOSE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/executor/executions/*/succeed", "QUALITY_EXECUTION",
                "质量执行器成功回调入口，只应由受控 worker 或服务账号调用。",
                Map.of("POST", "CALLBACK")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/executor/executions/*/fail", "QUALITY_EXECUTION",
                "质量执行器失败回调入口，只应由受控 worker 或服务账号调用。",
                Map.of("POST", "CALLBACK")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/executor/executions/start", "QUALITY_EXECUTION",
                "质量执行器开始回调入口，用于把执行记录推进到运行态。",
                Map.of("POST", "CALLBACK")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/executor/coordinator/run-once", "QUALITY_EXECUTION",
                "质量执行协调器单次调度入口，属于显式运行类动作。",
                Map.of("POST", "RUN")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/executor/coordinator/run-batch", "QUALITY_EXECUTION",
                "质量执行协调器批量调度入口，可能触发多个规则检测，按运行类动作授权。",
                Map.of("POST", "RUN")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/run-check", "QUALITY_EXECUTION",
                "单条质量规则手动检测入口，属于执行类动作而不是规则创建动作。",
                Map.of("POST", "RUN")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/executions", "QUALITY_EXECUTION",
                "单条规则执行历史查询入口，用于查看执行状态、耗时和低敏结果。",
                Map.of("GET", "VIEW")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/reports", "QUALITY_REPORT",
                "单条规则质量报告查询入口，用于查看该规则产生的报告列表。",
                Map.of("GET", "VIEW")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/validate-target", "QUALITY_RULE",
                "规则目标校验入口，用于校验表、字段或对象是否可被质量规则引用。",
                Map.of("POST", "VALIDATE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/scan-plan", "QUALITY_RULE",
                "规则扫描计划生成入口，会影响后续检测方式，按规则配置动作授权。",
                Map.of("POST", "CONFIGURE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/relational-sql-plan", "QUALITY_RULE",
                "关系型规则 SQL 计划预览入口，只返回低敏计划摘要，但仍属于规则配置能力。",
                Map.of("POST", "CONFIGURE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/schedule-task", "QUALITY_EXECUTION",
                "规则调度任务配置入口，会影响后续自动执行节奏，按执行配置动作授权。",
                Map.of("POST", "CONFIGURE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/enable", "QUALITY_RULE",
                "启用质量规则入口，会让规则参与后续检测。",
                Map.of("POST", "ENABLE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/disable", "QUALITY_RULE",
                "禁用质量规则入口，会让规则退出后续检测。",
                Map.of("POST", "DISABLE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/archive", "QUALITY_RULE",
                "归档质量规则入口，保留审计事实但停止作为活跃规则使用。",
                Map.of("POST", "ARCHIVE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/*/restore", "QUALITY_RULE",
                "恢复已归档质量规则入口，重新进入可配置或可启用状态。",
                Map.of("POST", "ENABLE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/suggestions", "QUALITY_RULE",
                "质量规则建议生成入口，用于沉淀可编辑规则草案。",
                Map.of("POST", "CREATE")));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/quality-rules/**", "QUALITY_RULE",
                "数据质量规则管理兜底入口，用于规则创建、查询、更新和删除。",
                GatewayAuthorizationProperties.defaultMethodActions()));
        defaults.add(GatewayAuthorizationProperties.route(
                "/api/quality/**", "QUALITY_RULE",
                "数据质量模块兼容兜底入口；新增端点应优先补充更具体的元数据规则。",
                GatewayAuthorizationProperties.defaultMethodActions()));
    }

    /**
     * 构造只允许可信服务账号访问的内部端点默认目录。
     *
     * <p>这些入口会提交计划、续租、回写执行事实或物化真实工具参数。默认目录同时约束主体类型和本地
     * 固定窗口限流，但它不能替代 OIDC 服务身份、签名校验、permission-admin 策略和业务幂等。
     */
    static List<InternalServiceEndpointProperties> defaultInternalServiceEndpoints() {
        List<InternalServiceEndpointProperties> endpoints = new ArrayList<>();
        endpoints.add(internalEndpoint("agent-plan-ingestion", "/api/agent/plan-ingestions", 120,
                "Python AI Runtime 提交 AgentPlan 到 Java 控制面的内部入口。"));
        endpoints.add(internalEndpoint("agent-runtime-command-worker-receipts",
                "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-receipts",
                240, "命令 worker 写回执行结果的内部入口。"));
        endpoints.add(internalEndpoint("agent-runtime-command-worker-leases",
                "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-leases/**",
                600, "命令 worker 领取、续租和释放租约的内部入口。"));
        endpoints.add(internalEndpoint("agent-runtime-controlled-dry-run-receipts",
                "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/controlled-dry-run-receipts",
                240, "受控 dry-run worker 写回低敏预演结果的内部入口。"));
        endpoints.add(internalEndpoint("agent-runtime-command-sandbox-run-admissions",
                "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-sandbox-run-admissions",
                240, "命令沙箱准入结果写回入口，用于执行前补齐隔离与资源配额证据。"));
        endpoints.add(internalEndpoint("agent-runtime-command-worker-output-sanitizations",
                "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-output-sanitizations",
                240, "worker 输出低敏清洗结果写回入口。"));
        endpoints.add(internalEndpoint("agent-runtime-workspace-file-payload-materializations",
                "/api/internal/agent-runtime/tool-action-workspace-files/payload-materializations",
                120, "workspace 文件工具真实参数物化入口，只允许可信服务账号调用。"));
        endpoints.add(internalEndpoint("agent-runtime-workspace-file-worker-receipts",
                "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/workspace-file-worker-receipts",
                240, "workspace 文件 worker 执行结果写回入口。"));
        return endpoints;
    }

    private static InternalServiceEndpointProperties internalEndpoint(
            String name,
            String pathPattern,
            int maxRequestsPerMinute,
            String description) {
        InternalServiceEndpointProperties endpoint = new InternalServiceEndpointProperties();
        endpoint.setName(name);
        endpoint.setPathPattern(pathPattern);
        endpoint.setAllowedActorRoles(List.of("SERVICE_ACCOUNT"));
        endpoint.setAllowedActorTypes(List.of("SERVICE_ACCOUNT"));
        endpoint.setRateLimitEnabled(true);
        endpoint.setMaxRequestsPerMinute(maxRequestsPerMinute);
        endpoint.setDescription(description);
        return endpoint;
    }
}
