/**
 * @Author : Cui
 * @Date: 2026/07/01 10:47
 * @Description DataSmartGovernBackend - PlatformClosureReadinessResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台级闭环 readiness 响应。
 *
 * <p>这个响应不是 Prometheus 的替代品，也不是实时服务发现结果。
 * 它解决的是“产品架构闭环是否完整”的问题：哪些模块应该是微服务、哪些模块已经纳入
 * gateway/Actuator/Prometheus/smoke 的闭环、哪些模块只是共享契约、哪些能力还需要收尾。</p>
 *
 * <p>为什么要把这个能力放在 observability：
 * 1. observability 的职责就是让平台运行状态、告警、日志和闭环可见；
 * 2. 如果每个模块自己解释自己是否完整，平台层无法给出统一验收视角；
 * 3. 该接口未来可以继续接入 Prometheus target 状态、Grafana dashboard 清单、告警规则启用状态和审计事件。</p>
 *
 * @param assessmentVersion 诊断契约版本，便于前端或脚本识别字段变化。
 * @param productStage 当前产品收敛阶段说明。
 * @param conclusion 面向人的结论摘要。
 * @param platformCommonShouldBeMicroservice 明确回答 platform-common 是否应该被做成微服务。
 * @param expectedJavaMicroserviceCount 当前规划中的 Java 微服务数量。
 * @param deployableRuntimeCount 需要独立启动和探活的运行时数量，包含 Python Runtime。
 * @param sharedLibraryCount 共享库数量。
 * @param wiredRuntimeCount 已经接入本地闭环线索的运行时数量。注意：该字段不表示商业级全功能完成，
 *                          只表示模块已经有端口、路由、探活或诊断证据，后续仍需要继续补产品能力。
 * @param missingMicroservices 本阶段判断仍缺失的微服务编码；为空表示没有“目录存在但服务漏建”的问题。
 * @param modules 模块明细。
 * @param recommendedNextActions 为项目收敛阶段准备的下一步建议。
 * @param generatedAt 响应生成时间。
 */
public record PlatformClosureReadinessResponse(
        String assessmentVersion,
        String productStage,
        String conclusion,
        boolean platformCommonShouldBeMicroservice,
        int expectedJavaMicroserviceCount,
        int deployableRuntimeCount,
        int sharedLibraryCount,
        int wiredRuntimeCount,
        List<String> missingMicroservices,
        List<PlatformClosureModuleView> modules,
        List<String> recommendedNextActions,
        LocalDateTime generatedAt) {
}
