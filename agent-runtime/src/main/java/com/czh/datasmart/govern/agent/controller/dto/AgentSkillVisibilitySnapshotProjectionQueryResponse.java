/**
 * @Author : Cui
 * @Date: 2026/06/04 19:16
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotProjectionQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * Skill 可见性快照投影查询响应。
 *
 * <p>该响应不是简单返回列表，而是附带低敏聚合摘要。原因是商业化控制台经常需要先回答“这段时间/这个
 * run/session 的 Skill 能力边界整体是否健康”，再让用户点开单条事件详情。例如：</p>
 *
 * <p>1. `availableSnapshotCount/unavailableSnapshotCount` 可以快速判断会话中是否存在能力不可用；</p>
 * <p>2. `totalVisibleSkillCount/totalHiddenSkillCount` 可以展示能力暴露规模和隐藏规模；</p>
 * <p>3. `permissionFactSourceCounts` 可以判断生产路径是否还在使用旧式请求变量；</p>
 * <p>4. `hiddenAdmissionStatusCounts` 可以解释隐藏主要来自缺权限、角色不足、租户开关还是风险策略。</p>
 *
 * <p>当前聚合只基于本次返回窗口，不等同于全量历史报表。后续持久化仓储上线后，可以把同样字段迁移为
 * SQL/ClickHouse 聚合，接口契约仍保持一致。</p>
 */
public record AgentSkillVisibilitySnapshotProjectionQueryResponse(
        /**
         * 服务端应用后的查询上限。默认 100，最大值由 `AgentRuntimeEventProjectionQuery` 约束。
         */
        Integer limit,

        /**
         * 本次返回窗口命中的快照数量。
         */
        Integer totalMatched,

        Long availableSnapshotCount,
        Long unavailableSnapshotCount,
        Integer totalVisibleSkillCount,
        Integer totalHiddenSkillCount,
        Map<String, Long> permissionFactSourceCounts,
        Map<String, Integer> hiddenAdmissionStatusCounts,
        List<AgentSkillVisibilitySnapshotProjectionView> snapshots
) {
}
