/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxDiagnosticsRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DataSync worker outbox 诊断查询请求。
 *
 * <p>该请求面向内部运维排障和后续管理台只读视图，用来回答几类生产问题：</p>
 * <p>1. 某个租户/项目的 data-sync 命令是否堆积在 PENDING 或 DEFERRED；</p>
 * <p>2. 某个 taskId 或 commandId 的跨服务命令是否已经投递到下游；</p>
 * <p>3. 是否存在长期 DISPATCHING、FAILED 或 DEAD_LETTER，需要人工干预；</p>
 * <p>4. 最近一批 outbox 的 receipt、下游 syncTaskId/syncExecutionId 是否已经回写。</p>
 *
 * <p>注意：请求只允许按 ID、状态和隔离维度查询，不支持按 payload 内容搜索，避免把敏感工具参数变成可被检索的管理入口。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSyncWorkerOutboxDiagnosticsRequest {

    /**
     * 可选租户过滤条件。
     */
    private Long tenantId;

    /**
     * 可选项目过滤条件。
     */
    private Long projectId;

    /**
     * 可选 task-management 任务 ID。
     */
    private Long taskId;

    /**
     * 可选 Agent command ID。
     */
    private String commandId;

    /**
     * 可选 outbox 状态过滤条件。
     *
     * <p>支持 PENDING、DISPATCHING、DEFERRED、SUCCEEDED、FAILED、DEAD_LETTER。
     * 服务层会做大小写归一和枚举校验，避免把任意字符串拼进查询条件。</p>
     */
    private String status;

    /**
     * 最近记录返回数量。
     *
     * <p>服务层会限制最大值，防止诊断接口一次返回过多记录影响 task-management 的主业务吞吐。</p>
     */
    private Integer limit;
}
