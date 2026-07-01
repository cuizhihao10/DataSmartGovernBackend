/**
 * @Author : Cui
 * @Date: 2026/07/01 10:46
 * @Description DataSmartGovernBackend - PlatformClosureModuleView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.controller.dto;

import com.czh.datasmart.govern.observability.support.PlatformModuleKind;

import java.util.List;

/**
 * 单个模块在平台闭环诊断中的展示模型。
 *
 * <p>这个 DTO 是 observability 对外 API 契约的一部分，字段刻意保持低敏：
 * 只描述模块职责、默认端口、网关前缀、健康检查路径、指标路径和阶段性闭环状态，
 * 不返回数据库连接串、内部管理端点、服务账号密钥、JWT、SQL、样本数据、prompt、模型输出或任何业务正文。</p>
 *
 * @param moduleCode 模块编码，通常等于 Maven module 或 Python runtime 目录名，便于和仓库结构对应。
 * @param displayName 面向运维人员和产品视角的中文名称。
 * @param moduleKind 模块运行形态，用于区分 Java 微服务、Python Runtime 和共享库。
 * @param deployableRuntime 是否是需要独立启动、独立探活、独立部署的运行时单元。
 * @param expectedServiceName 服务发现名称；共享库没有运行时服务名，因此为空。
 * @param defaultPort 本地开发默认端口；共享库没有端口，因此为空。
 * @param gatewayPrefix 外部统一网关前缀；没有外部 HTTP 入口的模块为空。
 * @param healthProbePath 健康检查路径；Java 微服务通常是 /actuator/health，Python Runtime 使用自身诊断端点。
 * @param metricsProbePath 指标抓取路径；Java 微服务通常是 /actuator/prometheus，Python Runtime 使用 /agent/metrics。
 * @param responsibility 当前模块在平台中的核心职责。
 * @param closureStatus 阶段性闭环状态。该字段不是实例存活状态，而是“能力是否已纳入产品闭环”的判断。
 * @param closureEvidence 证明该状态的低敏证据，例如网关路由、Actuator、Prometheus、smoke 探针。
 * @param remainingGaps 尚未完成的商业化增强项，用于把项目从“能跑通”继续推进到“可运营”。
 * @param operationalNotes 运维说明，强调部署、扩缩容、故障隔离或不要误做成微服务的原因。
 */
public record PlatformClosureModuleView(
        String moduleCode,
        String displayName,
        PlatformModuleKind moduleKind,
        boolean deployableRuntime,
        String expectedServiceName,
        Integer defaultPort,
        String gatewayPrefix,
        String healthProbePath,
        String metricsProbePath,
        String responsibility,
        String closureStatus,
        List<String> closureEvidence,
        List<String> remainingGaps,
        List<String> operationalNotes) {
}
