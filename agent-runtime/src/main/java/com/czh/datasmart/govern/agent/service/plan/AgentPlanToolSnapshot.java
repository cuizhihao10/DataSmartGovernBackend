/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - AgentPlanToolSnapshot.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.plan;

import java.util.List;
import java.util.Map;

/**
 * Python AgentPlan 中单个工具计划的 Java 控制面快照。
 *
 * <p>该对象不是 HTTP DTO，而是 service 层内部使用的“干净业务结构”。
 * Controller DTO 负责接收外部 JSON，Ingestion Service 负责完成工具目录校验和字段归一化，
 * 最后把可信、可审计的工具计划转成该快照交给工具审计服务。
 *
 * <p>为什么不让工具审计服务直接依赖 HTTP DTO：
 * 1. 审计服务属于控制面核心能力，不应该知道某个入口路由的请求体长什么样；
 * 2. 后续同一个能力可能来自 Kafka、gRPC、批处理回放或测试夹具，而不只是 REST；
 * 3. DTO 字段会随外部协议变化，内部快照可以保持更稳定的业务语义。
 *
 * @param sequence 工具在 AgentPlan 中的顺序，用于还原规划链路和生成稳定审计 bindingId。
 * @param toolCode 工具编码，必须已经在 Java 工具目录中注册并启用。
 * @param toolType 工具类型，来自 Java 工具目录，不能由 Python 计划随意伪造。
 * @param targetService 下游服务名，来自 Java 工具目录。
 * @param targetEndpoint 下游端点模板，来自 Java 工具目录。
 * @param targetResourceId 本次计划想作用的业务资源 ID，例如 datasourceId、taskId、ruleId。
 * @param readOnly 是否只读，来自 Java 工具目录。
 * @param riskLevel 风险等级，优先取 Python 计划和 Java 目录两者中更高的风险语义。
 * @param executionMode 执行模式，优先继承 Java 工具目录，必要时可被计划标记为 APPROVAL_REQUIRED。
 * @param requiresApproval 是否必须人工确认，取 Java 目录策略与 Python 计划策略的并集。
 * @param idempotent 工具是否幂等，来自 Java 工具目录。
 * @param allowedActions 允许动作列表，来自 Java 工具目录。
 * @param reason Python 规划器给出的调用理由。
 * @param arguments Python 规划器推导出的工具参数快照。
 * @param governanceHints Python Runtime 透传的治理提示，例如项目范围、敏感字段、缓存范围。
 * @param parameterValidation Python Runtime 透传的参数校验/上下文补齐结果。
 */
public record AgentPlanToolSnapshot(Integer sequence,
                                    String toolCode,
                                    String toolType,
                                    String targetService,
                                    String targetEndpoint,
                                    Long targetResourceId,
                                    Boolean readOnly,
                                    String riskLevel,
                                    String executionMode,
                                    Boolean requiresApproval,
                                    Boolean idempotent,
                                    List<String> allowedActions,
                                    String reason,
                                    Map<String, Object> arguments,
                                    Map<String, Object> governanceHints,
                                    Map<String, Object> parameterValidation) {
}
