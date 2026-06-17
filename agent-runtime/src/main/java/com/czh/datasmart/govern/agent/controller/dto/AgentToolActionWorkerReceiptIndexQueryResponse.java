/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * worker receipt 低敏索引查询响应。
 *
 * <p>响应分成三层：第一层是本次查询如何被收口，例如 limit、storeMode、scope；第二层是生产化缺口和证据码，
 * 方便管理台展示“现在能看什么、还缺什么”；第三层才是 receipt 列表。这样设计是为了让接口既能服务排障，
 * 也能持续暴露商业化成熟度，不会让调用方误以为当前已经具备完整真实 resume 能力。</p>
 */
public record AgentToolActionWorkerReceiptIndexQueryResponse(
        /** 单次查询实际采用的上限，避免管理台或脚本误触发无界扫描。 */
        Integer appliedLimit,

        /** 当前窗口命中的 receipt 数量，不代表全量历史报表。 */
        Integer totalMatched,

        /** 当前索引仓储模式，例如 memory 或 mysql，用于判断是否具备跨 JVM 持久化能力。 */
        String storeMode,

        /** 查询模式说明，固定表达“按 commandId 的低敏索引查询”，避免被误用为通用事件搜索。 */
        String queryMode,

        /** 底层 receipt record 的 payload 策略，说明本接口不会返回敏感上下文正文。 */
        String payloadPolicy,

        /** 当前索引中总记录数，用于低频排障和本地学习，不建议生产高频轮询。 */
        Integer currentIndexSize,

        /** 本次查询的 commandId。它是强制入口，因此响应显式回显低敏定位符便于排障。 */
        String commandId,

        /** 可选工具编码过滤条件。 */
        String toolCode,

        /** 服务层收口后的租户条件。 */
        String scopedTenantId,

        /** 服务层收口后的项目条件。 */
        String scopedProjectId,

        /** 服务层收口后的 actor 条件。 */
        String scopedActorId,

        /** 服务层收口后的 run 条件。 */
        String scopedRunId,

        /** 服务层收口后的 session 条件。 */
        String scopedSessionId,

        /** PROJECT 数据范围下的授权项目集合；null 表示无项目集合限制，空集合表示无项目可见。 */
        List<String> authorizedProjectIds,

        /** 本次查询链路产生的低敏证据码，便于管理台解释过滤、脱敏和命中状态。 */
        List<String> evidenceCodes,

        /** 仍未完成的生产化能力，持续提醒后续路线不要只停留在 demo 查询。 */
        List<String> missingCapabilities,

        /** 低敏 receipt 记录列表。 */
        List<AgentToolActionWorkerReceiptIndexView> receipts
) {
}
