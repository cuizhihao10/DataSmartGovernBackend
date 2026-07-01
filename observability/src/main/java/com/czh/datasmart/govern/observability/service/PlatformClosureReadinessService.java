/**
 * @Author : Cui
 * @Date: 2026/07/01 10:48
 * @Description DataSmartGovernBackend - PlatformClosureReadinessService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service;

import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureModuleView;
import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureReadinessResponse;
import com.czh.datasmart.govern.observability.support.PlatformModuleKind;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台闭环 readiness 服务。
 *
 * <p>职责说明：
 * 该服务从“产品架构与本地 E2E 闭环”的角度整理 DataSmart Govern 当前应该存在的运行单元。
 * 它不会去实时调用每个微服务，因为实时探活已经由 Actuator、Prometheus 和 smoke 脚本负责；
 * 这里更像一个“平台服务目录 + 闭环验收口径”的只读控制面。</p>
 *
 * <p>为什么暂时使用静态清单：
 * 1. 当前项目处于收敛阶段，最重要的是先把模块边界、端口、网关前缀和验收路径统一记录下来；
 * 2. 如果此接口启动时就强依赖 gateway、Nacos、Prometheus 或所有业务服务在线，会让 observability
 *    自身变得脆弱，反而不利于排障；
 * 3. 后续商业化增强可以在这个清单基础上追加 Prometheus target 状态、Grafana dashboard 状态、
 *    告警规则启用状态和服务发现实例数，而不破坏当前 API 结构。</p>
 *
 * <p>敏感信息边界：
 * 该服务只返回端口、路径、职责、状态和下一步建议，不返回数据库密码、JWT、内部 token、SQL、样本数据、
 * prompt、模型输出、工具参数或客户业务数据。可观测性接口必须始终保持低敏，避免“诊断接口变泄密接口”。</p>
 */
@Service
public class PlatformClosureReadinessService {

    private static final String ASSESSMENT_VERSION = "platform-closure-readiness.v1";
    private static final String PRODUCT_STAGE = "LOCAL_E2E_CLOSURE_AND_COMMERCIAL_CONVERGENCE";

    /**
     * 生成平台闭环 readiness 视图。
     *
     * <p>输出中的 counts 不是运行时在线实例数，而是产品结构视角的验收数量：
     * - expectedJavaMicroserviceCount：当前规划中应该独立部署的 Java 微服务；
     * - deployableRuntimeCount：所有需要独立启动的运行时，包含 Python AI Runtime；
     * - sharedLibraryCount：只应该作为依赖包存在的公共库数量；
     * - wiredRuntimeCount：已经有阶段性闭环线索的运行时数量。它不等于“全功能完成”，
     *   只表示该运行时已经进入本地验收、探活、路由或诊断视图。</p>
     *
     * @return 平台闭环诊断响应。
     */
    public PlatformClosureReadinessResponse buildClosureReadiness() {
        List<PlatformClosureModuleView> modules = buildModuleInventory();
        int javaMicroservices = countByKind(modules, PlatformModuleKind.JAVA_MICROSERVICE);
        int deployableRuntimeCount = (int) modules.stream()
                .filter(PlatformClosureModuleView::deployableRuntime)
                .count();
        int sharedLibraryCount = countByKind(modules, PlatformModuleKind.SHARED_LIBRARY);
        int wiredRuntimeCount = (int) modules.stream()
                .filter(PlatformClosureModuleView::deployableRuntime)
                .filter(module -> !"MISSING".equals(module.closureStatus()))
                .filter(module -> !"PLANNED_ONLY".equals(module.closureStatus()))
                .count();

        /*
         * 当前没有发现“目录存在但微服务漏建”的问题：
         * - data-quality 已经是 Spring Boot 微服务；
         * - observability 已经是 Spring Boot 微服务，本批新增产品化闭环诊断入口；
         * - platform-common 正确形态是共享库，不应出现在缺失微服务列表里。
         */
        List<String> missingMicroservices = List.of();

        return new PlatformClosureReadinessResponse(
                ASSESSMENT_VERSION,
                PRODUCT_STAGE,
                "当前核心 Java 微服务、Python Runtime 与共享契约库边界已经明确；data-quality 和 observability 是微服务，platform-common 是共享库。本接口只说明阶段性闭环接入情况，不代表所有商业功能已经完成。",
                false,
                javaMicroservices,
                deployableRuntimeCount,
                sharedLibraryCount,
                wiredRuntimeCount,
                missingMicroservices,
                modules,
                List.of(
                        "把本接口纳入本地 smoke，作为平台是否缺失微服务的统一验收入口。",
                        "observability 优先补实时服务健康聚合、Prometheus target 状态、告警规则状态和 Grafana dashboard 清单。",
                        "data-quality 优先补低敏报告导出、质量执行告警、异常治理对账和任务闭环验收。",
                        "platform-common 继续保持共享契约层，只允许放统一响应、Header、错误码、上下文和低敏事件契约。"),
                LocalDateTime.now());
    }

    /**
     * 构建当前项目的平台模块清单。
     *
     * <p>这里显式列出模块，而不是从文件夹自动扫描。原因是“存在目录”不等于“应该部署”：
     * platform-common 虽然是 Maven module，但它是所有服务依赖的共享 jar；
     * 如果自动扫描后强行要求它也占一个端口，反而会把基础契约层误设计成运行时依赖。</p>
     */
    private List<PlatformClosureModuleView> buildModuleInventory() {
        return List.of(
                javaService(
                        "gateway",
                        "统一网关与认证入口",
                        "datasmart-govern-gateway",
                        8080,
                        "/api/**",
                        "统一外部入口、OIDC/JWT 验证、路由授权、Agent/Python Runtime 转发、服务账号和低敏控制面 Header 注入。",
                        "LOCAL_E2E_WIRED",
                        List.of("Spring Cloud Gateway 路由已接入", "Actuator/Prometheus 已暴露", "本地 smoke 已验证 gateway health 与 auth capabilities"),
                        List.of("生产还需要完善限流、熔断、灰度、OpenTelemetry trace 和企业 IdP 多环境配置。")),
                javaService(
                        "permission-admin",
                        "权限与策略中心",
                        "permission-admin",
                        8085,
                        "/api/permission/**",
                        "负责 RBAC、路由策略、项目成员、数据范围、服务账号和 Agent 工具预算策略。",
                        "LOCAL_E2E_WIRED",
                        List.of("Keycloak/OIDC 样例链路已贯通", "gateway 可回源权限中心", "服务账号权限与工具预算策略已进入闭环"),
                        List.of("生产还需要更完整的审计持久化、策略变更事件和多租户套餐治理。")),
                javaService(
                        "task-management",
                        "任务中心",
                        "task-management",
                        8081,
                        "/api/task/**",
                        "承载治理任务创建、调度、认领、重试、回执、草案和 Agent/DataSync/DataQuality 任务衔接。",
                        "LOCAL_E2E_WIRED",
                        List.of("DataSync worker command outbox/receipt 已接入", "质量治理任务幂等键已接入", "Actuator/Prometheus 已暴露"),
                        List.of("生产还需要更完整的工作流恢复、任务归档、SLA 配额和跨服务补偿视图。")),
                javaService(
                        "datasource-management",
                        "数据源管理",
                        "datasource-management",
                        8082,
                        "/api/datasource/**",
                        "负责数据源登记、连接测试、元数据发现、只读 SQL 执行审计和 Agent datasource 命令回执。",
                        "LOCAL_E2E_WIRED",
                        List.of("多数据源连接器和只读 SQL 审计已有阶段性闭环", "Actuator/Prometheus 已暴露", "本地 smoke 已验证 health"),
                        List.of("生产还需要连接池隔离、源库容量画像、慢查询保护和更多 connector 真连接验收。")),
                javaService(
                        "data-sync",
                        "数据同步",
                        "data-sync",
                        8086,
                        "/api/sync/**",
                        "负责同步模板、同步任务生命周期、worker route policy、replay/backfill/recovery plan 和任务中心回执。",
                        "LOCAL_E2E_WIRED",
                        List.of("connector capabilities 已进入 smoke", "恢复计划和模板执行契约已落表", "Actuator/Prometheus 已暴露"),
                        List.of("生产还需要真实 worker dry-run/执行压测、CDC 连接器验收和大规模 backfill 容量模型。")),
                javaService(
                        "data-quality",
                        "数据质量",
                        "data-quality",
                        8083,
                        "/api/quality/**",
                        "负责质量规则、质量扫描计划、执行诊断、报告、异常聚合和异常治理任务创建。",
                        "STAGE_FUNCTIONAL_NOT_FINAL",
                        List.of("Spring Boot 微服务已存在", "规则/报告/异常/治理任务/执行诊断已实现", "Prometheus 抓取配置已存在"),
                        List.of("仍需补低敏报告导出、质量执行告警、真实扫描压测、报告归档和图谱/清洗联动。")),
                javaService(
                        "agent-runtime",
                        "Agent Java 控制面",
                        "agent-runtime",
                        8091,
                        "/api/agent/**",
                        "负责 Agent 会话、工具目录、审批、outbox、runtime event 投影、长期记忆候选和 Java 侧控制面治理。",
                        "LOCAL_E2E_WIRED",
                        List.of("runtime event、工具执行、长期记忆、artifact grant 和 outbox 能力已有闭环", "Actuator/Prometheus 已暴露"),
                        List.of("生产还需要进一步收敛真实沙箱、worker 补偿和跨服务最终态对账。")),
                javaService(
                        "observability",
                        "可观测性服务",
                        "observability",
                        8084,
                        "/api/observability/**",
                        "负责平台健康、指标、告警、监控面板和闭环 readiness 视图，是运维和商业交付验收入口。",
                        "CONTROL_PLANE_STARTED",
                        List.of("Spring Boot 微服务已存在", "本批新增平台闭环 readiness API", "Prometheus 抓取配置已存在"),
                        List.of("仍需补服务健康聚合、Prometheus target 状态、告警规则管理、Grafana dashboard 清单、日志/事件审计聚合。")),
                module(
                        "python-ai-runtime",
                        "Python AI Runtime",
                        PlatformModuleKind.PYTHON_RUNTIME,
                        true,
                        "python-ai-runtime",
                        8090,
                        "/api/agent/**",
                        "/agent/capabilities/closure-readiness",
                        "/agent/metrics",
                        "负责 OpenClaw 风格 Agent Host、tools、skills、memory、query engine、context、permission、sessions、LLM provider 抽象和低敏诊断。",
                        "LOCAL_E2E_WIRED",
                        List.of("FastAPI 诊断接口已接入 gateway", "本地 smoke 已验证 closure readiness/Skill diagnostics/inference diagnostics", "Prometheus 通过 /agent/metrics 抓取"),
                        List.of("生产还需要真实模型服务配置、向量存储 HA、长期记忆容量治理和 WebSocket/Kafka 事件投递压测。"),
                        List.of("不是 Java 微服务，但仍然是必须独立启动、独立探活、独立扩缩容的运行时。")),
                module(
                        "platform-common",
                        "平台公共契约库",
                        PlatformModuleKind.SHARED_LIBRARY,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "提供统一响应、分页载荷、平台 Header、请求上下文、错误码、审计事件和跨模块低敏契约。",
                        "SHARED_CONTRACT_ONLY",
                        List.of("作为 Maven module 被其他服务依赖", "不暴露端口", "不注册 Nacos", "不需要 Actuator"),
                        List.of("后续只应补公共契约，不应放具体业务逻辑或运行时依赖。"),
                        List.of("platform-common 不应该被做成微服务；否则所有服务会反向依赖一个基础契约运行时，增加启动耦合和故障面。")));
    }

    private int countByKind(List<PlatformClosureModuleView> modules, PlatformModuleKind kind) {
        return (int) modules.stream()
                .filter(module -> module.moduleKind() == kind)
                .count();
    }

    /**
     * Java 微服务的便捷构造方法。
     *
     * <p>Java 微服务在本项目中有一致的基础探活约定：/actuator/health 用于存活判断，
     * /actuator/prometheus 用于 Prometheus 抓取。这里统一封装，避免每个模块重复写相同路径。</p>
     */
    private PlatformClosureModuleView javaService(
            String moduleCode,
            String displayName,
            String serviceName,
            Integer port,
            String gatewayPrefix,
            String responsibility,
            String closureStatus,
            List<String> evidence,
            List<String> remainingGaps) {
        return module(
                moduleCode,
                displayName,
                PlatformModuleKind.JAVA_MICROSERVICE,
                true,
                serviceName,
                port,
                gatewayPrefix,
                "/actuator/health",
                "/actuator/prometheus",
                responsibility,
                closureStatus,
                evidence,
                remainingGaps,
                List.of("需要独立进程、独立端口、服务发现注册、健康检查、指标抓取和网关路由。"));
    }

    private PlatformClosureModuleView module(
            String moduleCode,
            String displayName,
            PlatformModuleKind moduleKind,
            boolean deployableRuntime,
            String serviceName,
            Integer port,
            String gatewayPrefix,
            String healthProbePath,
            String metricsProbePath,
            String responsibility,
            String closureStatus,
            List<String> evidence,
            List<String> remainingGaps,
            List<String> operationalNotes) {
        return new PlatformClosureModuleView(
                moduleCode,
                displayName,
                moduleKind,
                deployableRuntime,
                serviceName,
                port,
                gatewayPrefix,
                healthProbePath,
                metricsProbePath,
                responsibility,
                closureStatus,
                evidence,
                remainingGaps,
                operationalNotes);
    }
}
