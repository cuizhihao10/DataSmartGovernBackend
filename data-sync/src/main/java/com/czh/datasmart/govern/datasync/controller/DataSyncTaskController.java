/**
 * @Author : Cui
 * @Date: 2026/05/07 21:33
 * @Description DataSmart Govern Backend - DataSyncTaskController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCloneRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 同步任务 API。
 *
 * <p>任务 API 面向“运营对象”：创建后可以进入调度、运行、暂停、恢复、重试、取消、归档等生命周期。
 * 当前实现已经具备基础控制面：创建、查询、入队运行、暂停、恢复、重试和取消。
 * 真正的数据搬运仍由 execution 租约、执行器回调和后续 connector worker 完成，Controller 不直接读写源端或目标端数据。
 */
@RestController
@RequestMapping("/sync-tasks")
@RequiredArgsConstructor
public class DataSyncTaskController {

    private final DataSyncService dataSyncService;

    /**
     * 创建同步任务。
     */
    @PostMapping
    public PlatformApiResponse<SyncTask> createTask(
            @Valid @RequestBody CreateSyncTaskRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTask task = dataSyncService.createTask(request, actorContext(tenantId, actorId, actorRole, traceId, headers));
        return PlatformApiResponse.success("同步任务创建成功", task, traceId);
    }

    /**
     * 分页查询同步任务。
     */
    @GetMapping
    public PlatformApiResponse<PlatformPageResponse<SyncTask>> pageTasks(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) Long templateId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String currentState,
            @RequestParam(required = false) String approvalState,
            @RequestParam(required = false) String triggerType,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTaskQueryCriteria criteria = new SyncTaskQueryCriteria(
                tenantId, projectId, workspaceId, templateId, ownerId, groupCode,
                currentState, approvalState, triggerType, current, size);
        return PlatformApiResponse.success(dataSyncService.pageTasks(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询同步任务分组汇总。
     *
     * <p>路由语义：
     * - GET /sync-tasks/groups 是只读分组列表，不创建任务、不执行任务；
     * - tenant/project/workspace 仍只是请求过滤条件，最终可见范围由服务层结合权限上下文二次收口；
     * - groupCode 可用于只查某个稳定分组，适合 Agent 在拿到用户口令后先做分组摘要确认。</p>
     *
     * <p>返回内容：
     * 返回每个 groupCode 下的任务数量、活跃任务数量、等待调度数量、运行中数量、失败数量和回收站数量。
     * 它用于运营台分组卡片和 Agent 总结，不返回 SQL、映射配置、连接串或样本数据。</p>
     */
    @GetMapping("/groups")
    public PlatformApiResponse<List<SyncTaskGroupSummary>> listTaskGroups(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String groupCode,
            @RequestParam(defaultValue = "100") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTaskQueryCriteria criteria = new SyncTaskQueryCriteria(
                tenantId, projectId, workspaceId, null, ownerId, groupCode,
                null, null, null, 1L, size);
        return PlatformApiResponse.success(dataSyncService.listTaskGroups(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询同步任务详情。
     */
    @GetMapping("/{id}")
    public PlatformApiResponse<SyncTask> getTask(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.getTask(
                id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 调整同步任务分组。
     *
     * <p>该接口只改变任务定义的 groupCode/groupName，不会触发执行、不会修改模板、不会改写历史 execution。
     * 如果 request.groupCode 为空，表示把任务移出分组；如果非空，服务端会规范化编码并写入审计。</p>
     *
     * <p>为什么这是 POST 而不是 PATCH：
     * 当前项目接口习惯把“有业务审计语义的管理动作”建模为 POST 子资源，例如 pause、offline、clone。
     * 这里延续同一风格，便于 gateway 和 permission-admin 把它识别为 UPDATE_GROUP 动作。</p>
     */
    @PostMapping("/{id}/group")
    public PlatformApiResponse<SyncTaskOperationResult> updateTaskGroup(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskGroupUpdateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务分组已更新",
                dataSyncService.updateTaskGroup(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)),
                traceId);
    }

    /**
     * 手动运行同步任务。
     *
     * <p>当前实现只把任务推进到 QUEUED。
     * 后续执行器上线后，该动作会进一步触发 task-management 或 data-sync worker 认领流程。
     */
    @PostMapping("/{id}/run")
    public PlatformApiResponse<SyncTaskOperationResult> runTask(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交运行",
                dataSyncService.runTask(id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 手工调度同步任务。
     *
     * <p>业务语义：
     * 用户在任务详情或调度台点击“立即执行一次”时调用该接口。它会创建一条 triggerType=MANUAL 的 execution，
     * 并把任务主状态推进到 QUEUED，随后由 worker loop 按租约协议认领执行。</p>
     *
     * <p>和 {@code /run} 的关系：
     * {@code /run} 是历史兼容运行入口；{@code /manual-dispatch} 是更贴近产品调度台语义的显式入口。
     * 对定期任务而言，单次手工调度成功或失败会写入 execution 历史，任务本身在执行终态回调后仍会回到 SCHEDULED。</p>
     */
    @PostMapping("/{id}/manual-dispatch")
    public PlatformApiResponse<SyncTaskOperationResult> manualDispatchTask(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已手工调度",
                dataSyncService.manualDispatchTask(id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 暂停同步任务。
     *
     * <p>路由语义：
     * - path 中的 id 表示被控制的同步任务；
     * - request.reason 是可选低敏操作说明，会进入审计摘要；
     * - 返回值只包含任务 ID、目标状态和低敏说明，不返回源端连接、目标端连接、SQL、样本数据或 worker 内部信息。
     *
     * <p>执行边界：
     * 对 QUEUED execution，暂停会阻止后续 worker 认领；
     * 对 RUNNING/RETRYING execution，暂停会先写入控制面状态，后续 worker 需要在心跳或 checkpoint 阶段协作停止。
     */
    @PostMapping("/{id}/pause")
    public PlatformApiResponse<SyncTaskOperationResult> pauseTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交暂停",
                dataSyncService.pauseTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 恢复同步任务。
     *
     * <p>恢复只允许从 PAUSED 状态出发，并会创建新的 QUEUED execution。
     * 这样旧 execution 可以保留暂停历史，新 execution 继续复用现有租约认领、心跳、checkpoint 和完成/失败回调协议。
     */
    @PostMapping("/{id}/resume")
    public PlatformApiResponse<SyncTaskOperationResult> resumeTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交恢复",
                dataSyncService.resumeTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 重试同步任务。
     *
     * <p>普通重试面向 FAILED 和 PARTIALLY_SUCCEEDED。
     * 如果任务已经进入 AWAITING_OPERATOR_ACTION，说明自动恢复已经不再安全，必须改走人工介入接口，
     * 由运营人员先确认问题处理结果，再决定是否重跑。
     */
    @PostMapping("/{id}/retry")
    public PlatformApiResponse<SyncTaskOperationResult> retryTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交重试",
                dataSyncService.retryTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 取消同步任务。
     *
     * <p>取消是终态控制动作，用于明确表达“这个任务不再继续执行”。
     * 如果最近 execution 仍在 QUEUED/RUNNING/RETRYING/PAUSED 窗口，服务端会同步写入 CANCELLED 控制信号；
     * 如果最近 execution 已经成功或失败，则保留 execution 历史事实，只把任务主状态关闭到 CANCELLED。
     */
    @PostMapping("/{id}/cancel")
    public PlatformApiResponse<SyncTaskOperationResult> cancelTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交取消",
                dataSyncService.cancelTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 手工结束同步任务。
     *
     * <p>路由语义：
     * - path 中的 id 表示被结束的任务；
     * - request.reason 是低敏原因，会进入审计摘要；
     * - 服务端会把任务主状态置为 MANUALLY_TERMINATED，并尽量把最近活跃 execution 置为 MANUALLY_TERMINATED。</p>
     *
     * <p>执行器影响：
     * 如果 worker 已经启动，下一次 heartbeat 会收到 STOP_FOR_MANUAL_TERMINATE；
     * 如果 worker 延迟提交 checkpoint/complete/fail，回调保护会拒绝继续写入，避免“前端显示已结束但目标端继续变化”。</p>
     */
    @PostMapping("/{id}/terminate")
    public PlatformApiResponse<SyncTaskOperationResult> manualTerminateTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已手工结束",
                dataSyncService.manualTerminateTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 下线同步任务。
     *
     * <p>下线会关闭自动调度并清空下一次触发时间，是删除进回收站之前的强制前置动作。
     * 活跃任务不能直接下线，调用方需要先暂停、取消或手工结束，避免后台仍有 execution 继续写入。</p>
     */
    @PostMapping("/{id}/offline")
    public PlatformApiResponse<SyncTaskOperationResult> offlineTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已下线",
                dataSyncService.offlineTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 删除任务到回收站。
     *
     * <p>该接口要求任务已处于 OFFLINE。回收站中的任务不能运行或调度，但仍能查看详情与克隆，
     * 便于误删除后的配置参考和快速派生新任务。</p>
     */
    @PostMapping("/{id}/recycle")
    public PlatformApiResponse<SyncTaskOperationResult> recycleTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已移入回收站",
                dataSyncService.recycleTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 彻底删除回收站任务。
     *
     * <p>当前实现是逻辑彻底删除：任务进入 DELETED 后不再出现在普通列表和详情中，
     * 但 execution、checkpoint、错误样本和审计证据仍保留，后续由数据保留策略统一清理。</p>
     */
    @PostMapping("/{id}/hard-delete")
    public PlatformApiResponse<SyncTaskOperationResult> hardDeleteTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已彻底删除",
                dataSyncService.hardDeleteTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 克隆同步任务。
     *
     * <p>克隆只复制任务定义字段，不复制 execution、checkpoint、错误样本、对象账本或审批事实。
     * 默认克隆结果进入 DRAFT，适合用户或 Agent 再次确认配置；如果 request.runImmediately=true，
     * 服务端会在预检通过后立即创建 MANUAL execution。</p>
     */
    @PostMapping("/{id}/clone")
    public PlatformApiResponse<SyncTaskOperationResult> cloneTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskCloneRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已克隆",
                dataSyncService.cloneTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 从历史 execution 或 checkpoint 发起同步回放。
     *
     * <p>路由语义：
     * - path 中的 id 表示被回放的同步任务；
     * - request.sourceExecutionId 可指定来源执行记录，不传时默认使用任务最近 execution；
     * - request.sourceCheckpointId 可指定来源 checkpoint，不传时服务端尝试选择来源 execution 最新 checkpoint；
     * - 返回值只给出任务 ID、目标状态、新 executionId 与恢复计划摘要，不返回 SQL、样本数据、连接串或 worker 内部参数。
     *
     * <p>设计意图：
     * replay 是“恢复性派生执行”，不是普通 retry。服务端会创建新的 QUEUED execution，
     * 并把来源 execution/checkpoint 写入恢复计划表。未来 data-sync worker 认领该 execution 后，
     * 再按恢复计划决定从哪个断点重新读取。
     */
    @PostMapping("/{id}/replay")
    public PlatformApiResponse<SyncTaskOperationResult> replayTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskRecoveryOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务回放计划已提交",
                dataSyncService.replayTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 按时间窗口、分区窗口或业务分片发起同步补数。
     *
     * <p>路由语义：
     * - windowStart/windowEnd 用于描述补数边界，当前保持字符串以兼容 MySQL 时间戳、Kafka offset 时间、文件目录日期等不同连接器；
     * - shardOrPartition 用于表达分区、分片或业务桶；
     * - 三者至少提供一个，否则补数动作无法解释“补什么范围”，服务层会返回 BAD_REQUEST；
     * - reason 进入审计摘要，服务层会做基础低敏兜底，禁止把 SQL、prompt、凭据、样本数据或完整工具参数写入控制面。
     *
     * <p>执行边界：
     * 当前接口只创建恢复计划和待执行 execution，不直接触达源端/目标端。
     * 这样可以先把 API、权限、审计和 worker 契约闭合起来，再在后续批次接真实连接器执行。
     */
    @PostMapping("/{id}/backfill")
    public PlatformApiResponse<SyncTaskOperationResult> backfillTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskRecoveryOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务补数计划已提交",
                dataSyncService.backfillTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId, HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }
}
