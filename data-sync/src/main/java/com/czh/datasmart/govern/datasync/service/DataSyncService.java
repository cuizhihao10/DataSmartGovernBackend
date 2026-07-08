/**
 * @Author : Cui
 * @Date: 2026/05/07 21:31
 * @Description DataSmart Govern Backend - DataSyncService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAuditQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncCheckpointQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionLogQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionStartRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCloneRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardDraftSaveRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardDraftSaveResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskExportFile;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskFieldMappingSuggestionRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskFieldMappingSuggestionResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupCreateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupTreeNode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportOptions;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskPublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplatePlanningPreviewResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionLog;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskGroup;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;

import java.util.List;

/**
 * 数据同步服务契约。
 *
 * <p>第一版服务只覆盖“模板定义”和“任务定义/入队”。
 * 真实执行引擎、checkpoint、失败样本和数据搬运 worker 会在后续独立扩展，避免第一步就把执行细节塞进服务实现。
 */
public interface DataSyncService {

    SyncTemplate createTemplate(CreateSyncTemplateRequest request, SyncActorContext actorContext);

    PlatformPageResponse<SyncTemplate> pageTemplates(SyncTemplateQueryCriteria criteria, SyncActorContext actorContext);

    SyncTemplate getTemplate(Long id, SyncActorContext actorContext);

    SyncTaskOperationResult validateTemplate(Long id, SyncActorContext actorContext);

    /**
     * 生成同步模板规划预览。
     *
     * <p>预览只返回低敏配置健康结果，不执行同步、不读取源端数据、不返回字段映射原文、过滤条件、SQL 或样本。
     * 它用于帮助用户、Agent 或运营人员理解“这个模板是否适合进入任务草稿/执行前预检”。</p>
     */
    SyncTemplatePlanningPreviewResponse previewTemplate(Long id, SyncActorContext actorContext);

    /**
     * 生成同步模板执行前预检查。
     *
     * <p>预检查比 preview 更接近真实运行入口：它会判断当前模板是否能被现有 runner 入队执行，
     * 并把“配置非法”“需要审批”“当前 runner 暂不支持”区分开。该方法仍然不读取源端数据、不写目标端、
     * 不执行 SQL，只返回低敏状态、问题码和建议。</p>
     */
    SyncTemplateExecutionPrecheckResponse precheckTemplate(Long id, SyncActorContext actorContext);

    /**
     * 生成 DataX 风格离线作业计划。
     *
     * <p>离线作业计划用于帮助 UI、Agent、审批流和运维人员理解“这份模板如果交给专用离线 runner，
     * 应该使用哪类 Reader/Writer、需要什么分片/调度/checkpoint/审批能力”。它不是执行入口：
     * 不创建任务、不入队、不连接源端、不执行 SQL、不返回 objectMapping/fieldMapping/filter/SQL 原文。</p>
     */
    SyncOfflineJobPlanResponse buildOfflineJobPlan(Long id, SyncActorContext actorContext);

    SyncTask createTask(CreateSyncTaskRequest request, SyncActorContext actorContext);

    /**
     * 保存同步任务创建向导草稿。
     *
     * <p>该方法是新建任务四步向导的“渐进式持久化”入口：第一步校验通过并进入第二步时创建 DRAFT 任务，
     * 第二步和第三步继续更新同一条任务与模板配置。它不会执行预检查、不会发布任务、不会创建 execution，
     * 只保证用户关闭页面后还能在任务列表中看到“编辑中”并继续编辑。</p>
     */
    SyncTaskCreateWizardDraftSaveResponse saveCreateWizardDraft(SyncTaskCreateWizardDraftSaveRequest request,
                                                                SyncActorContext actorContext);

    PlatformPageResponse<SyncTask> pageTasks(SyncTaskQueryCriteria criteria, SyncActorContext actorContext);

    SyncTask getTask(Long id, SyncActorContext actorContext);

    /**
     * 查询回收站内的同步任务。
     *
     * <p>普通任务列表默认隐藏 RECYCLED/DELETED，避免用户在日常运营页面看到已删除对象。
     * 回收站列表是显式入口：它只展示 RECYCLED，支持继续查看详情、克隆或彻底删除，不能直接运行。</p>
     */
    PlatformPageResponse<SyncTask> pageRecycledTasks(SyncTaskQueryCriteria criteria, SyncActorContext actorContext);

    /**
     * 编辑同步任务定义。
     *
     * <p>编辑不会触发真实同步，不会创建 execution。若修改 scheduleConfig，任务会退回 DRAFT 并关闭自动调度，
     * 后续必须通过 publishTask 重新进入 CONFIGURED / SCHEDULED / PENDING_APPROVAL。</p>
     */
    SyncTask updateTask(Long id, SyncTaskUpdateRequest request, SyncActorContext actorContext);

    /**
     * 发布同步任务定义。
     *
     * <p>发布会重新执行预检查和审批判断，并根据调度配置决定任务进入 CONFIGURED、SCHEDULED 或 PENDING_APPROVAL。</p>
     */
    SyncTaskOperationResult publishTask(Long id, SyncTaskPublishRequest request, SyncActorContext actorContext);

    /**
     * 导出同步任务定义文件。
     *
     * <p>导出只返回低敏任务定义字段和模板引用，不返回连接串、密码、完整 SQL、样本数据或执行器内部计划。</p>
     */
    SyncTaskExportFile exportTasks(SyncTaskQueryCriteria criteria, String format, SyncActorContext actorContext);

    /**
     * 按选中的任务 ID 批量导出同步任务定义文件。
     *
     * <p>该方法面向“前端复选框/Agent 精确选择”的场景，不依赖分页筛选条件。
     * 服务层仍会逐条校验任务是否存在、是否已彻底删除、当前操作者是否可见，避免批量文件越权或静默漏导。</p>
     */
    SyncTaskExportFile exportTasksByIds(List<Long> taskIds, String format, SyncActorContext actorContext);

    /**
     * 导入同步任务定义文件。
     *
     * <p>导入采用“先全量校验、再统一写入”的策略。dryRun=true 时只做校验；runImmediately=true 时会发布并手工执行一次。</p>
     */
    SyncTaskImportResult importTasks(byte[] content, SyncTaskImportOptions options, SyncActorContext actorContext);

    /**
     * 批量手工调度同步任务。
     *
     * <p>每个任务仍会创建独立 MANUAL execution，并复用现有手工调度状态机。
     * 批量结果只负责汇总每条 taskId 的成功、失败或跳过情况。</p>
     */
    SyncTaskBatchOperationResult batchManualDispatchTasks(SyncTaskBatchOperationRequest request,
                                                          SyncActorContext actorContext);

    /**
     * 查询同步任务分组汇总。
     *
     * <p>分组汇总不是独立资源表，而是从 data_sync_task 聚合出来的只读视图。
     * 服务层必须先按租户、项目、工作空间和 SELF 范围收口，再返回 groupCode/groupName 以及状态计数，
     * 避免普通用户通过分组列表推断其它项目或其它用户的任务存在。</p>
     */
    List<SyncTaskGroupSummary> listTaskGroups(SyncTaskQueryCriteria criteria, SyncActorContext actorContext);

    /**
     * 查询同步任务分组树。
     *
     * <p>该接口服务前端左侧导航栏和内容页中间分组菜单栏，返回完整父子结构、默认分组标记和任务计数。
     * 它不会创建、删除或移动任务，只是把当前操作者可见的正式分组与历史任务分组聚合成可渲染树。</p>
     */
    List<SyncTaskGroupTreeNode> listTaskGroupTree(SyncTaskQueryCriteria criteria, SyncActorContext actorContext);

    /**
     * 创建同步任务分组。
     *
     * <p>创建后的分组会立刻出现在创建同步任务时的分组下拉/树选择控件中。服务端会自动确保同一作用域下存在默认分组。</p>
     */
    SyncTaskGroup createTaskGroup(SyncTaskGroupCreateRequest request, SyncActorContext actorContext);

    /**
     * 删除同步任务分组。
     *
     * <p>删除普通分组不会删除任务，而是把该分组及子分组下的任务迁回 DEFAULT/默认分组；默认分组本身不可删除。</p>
     */
    SyncTaskOperationResult deleteTaskGroup(String groupCode,
                                            Long tenantId,
                                            Long projectId,
                                            Long workspaceId,
                                            String reason,
                                            SyncActorContext actorContext);

    /**
     * 调整单个同步任务所属分组。
     *
     * <p>该动作属于任务定义管理，不会触发执行、不修改模板、不影响已有 execution 历史。
     * 它会写审计记录，用于后续排查“为什么这个任务出现在某个业务分组下”。</p>
     */
    SyncTaskOperationResult updateTaskGroup(Long id, SyncTaskGroupUpdateRequest request, SyncActorContext actorContext);

    /**
     * 发现创建任务配置阶段可选择的 schema/table/field 元数据。
     *
     * <p>data-sync 通过 datasource-management 读取低敏结构信息，并在本服务内处理 MySQL 与 PostgreSQL 等连接器的筛选差异。</p>
     */
    SyncTaskMetadataDiscoveryResponse discoverTaskMetadata(SyncTaskMetadataDiscoveryRequest request,
                                                           SyncActorContext actorContext);

    /**
     * 根据已选源表和目标表生成字段映射建议。
     *
     * <p>该接口不会创建模板或任务，只返回字段级低敏结构信息和默认 syncEnabled 建议，供用户最终确认。</p>
     */
    SyncTaskFieldMappingSuggestionResponse suggestFieldMappings(SyncTaskFieldMappingSuggestionRequest request,
                                                                SyncActorContext actorContext);

    SyncTaskOperationResult runTask(Long id, SyncActorContext actorContext);

    /**
     * 手工调度同步任务立即执行一次。
     *
     * <p>该动作和 runTask 的底层效果都是创建 MANUAL execution，但产品语义更清晰：
     * runTask 是通用运行入口，manualDispatchTask 对应用户在调度台点击“立即调度一次”。
     * 定时任务在手工调度完成后仍应回到 SCHEDULED 等待下一次计划触发。</p>
     */
    SyncTaskOperationResult manualDispatchTask(Long id, SyncActorContext actorContext);

    /**
     * 批量下线同步任务。
     *
     * <p>下线会关闭自动调度并清空 nextFireTime，是批量删除进回收站之前的强制前置动作。</p>
     */
    SyncTaskBatchOperationResult batchOfflineTasks(SyncTaskBatchOperationRequest request,
                                                   SyncActorContext actorContext);

    /**
     * 暂停同步任务。
     *
     * <p>暂停属于可恢复的生命周期控制动作，通常用于维护窗口、下游限流、字段映射待确认等场景。
     * 当前契约只暴露普通用户/项目负责人入口，不包含管理员强制暂停或批量暂停能力。
     */
    SyncTaskOperationResult pauseTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 恢复已暂停同步任务，并重新创建待执行 execution。
     */
    SyncTaskOperationResult resumeTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 从失败或部分成功状态发起普通重试。
     *
     * <p>人工介入任务的重跑走 attention 专用服务，避免绕过运营确认。
     */
    SyncTaskOperationResult retryTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 取消同步任务。
     *
     * <p>取消会终止任务继续执行的业务意图；已经完成的执行历史不会被篡改。
     */
    SyncTaskOperationResult cancelTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 手工结束正在排队、运行、重试或暂停中的同步任务。
     *
     * <p>手工结束会写入 execution 控制信号，后续 worker 心跳或写入型回调都会停止，适合人工止血和客户要求立即结束的场景。</p>
     */
    SyncTaskOperationResult manualTerminateTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 下线同步任务。
     *
     * <p>下线会关闭自动调度并清空 nextFireTime，是删除进回收站之前的强制前置动作。</p>
     */
    SyncTaskOperationResult offlineTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 批量删除同步任务到回收站。
     *
     * <p>该方法不会自动下线任务；每条任务仍必须已经处于 OFFLINE，避免后台 scheduler 继续触发已删除任务。</p>
     */
    SyncTaskBatchOperationResult batchRecycleTasks(SyncTaskBatchOperationRequest request,
                                                   SyncActorContext actorContext);

    /**
     * 删除任务到回收站。
     *
     * <p>只有已下线任务可以进入回收站；回收站内任务仍可查看详情和克隆，但不能运行或调度。</p>
     */
    SyncTaskOperationResult recycleTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 批量彻底删除回收站同步任务。
     *
     * <p>当前彻底删除仍是逻辑 DELETED，保留执行历史、checkpoint、错误样本和审计证据。</p>
     */
    SyncTaskBatchOperationResult batchHardDeleteTasks(SyncTaskBatchOperationRequest request,
                                                      SyncActorContext actorContext);

    /**
     * 从回收站彻底删除任务。
     *
     * <p>当前采用逻辑 DELETED，保留 execution、checkpoint、错误样本和审计证据的历史归属。</p>
     */
    SyncTaskOperationResult hardDeleteTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 克隆同步任务。
     *
     * <p>克隆只复制任务定义字段，不复制执行历史；新任务默认进入 DRAFT，可选择立即手工执行一次。</p>
     */
    SyncTaskOperationResult cloneTask(Long id, SyncTaskCloneRequest request, SyncActorContext actorContext);

    /**
     * 从历史 execution 或 checkpoint 发起同步回放。
     *
     * <p>回放不是普通 retry：
     * retry 表达“同一个失败任务再跑一次”，replay 表达“基于某次历史执行或断点重新派生一次恢复性执行”。
     * 因此请求需要携带 sourceExecutionId/sourceCheckpointId，服务层会创建新的 QUEUED execution 和恢复计划，
     * 后续 worker 再根据 executionId 读取恢复计划执行真实数据搬运。
     */
    SyncTaskOperationResult replayTask(Long id, SyncTaskRecoveryOperationRequest request, SyncActorContext actorContext);

    /**
     * 按时间窗口、分区窗口或业务分片发起历史补数。
     *
     * <p>补数用于处理历史缺口、晚到数据、客户指定时间段重刷或分区级修复。
     * 当前服务只保存低敏恢复计划，不在 API 层接收 SQL、样本数据、连接串或完整工具参数。
     */
    SyncTaskOperationResult backfillTask(Long id, SyncTaskRecoveryOperationRequest request, SyncActorContext actorContext);

    SyncExecution startExecution(Long taskId, Long executionId, SyncExecutionStartRequest request, SyncActorContext actorContext);

    SyncCheckpoint writeCheckpoint(Long taskId, Long executionId, SyncExecutionCheckpointRequest request, SyncActorContext actorContext);

    SyncExecution completeExecution(Long taskId, Long executionId, SyncExecutionCompleteRequest request, SyncActorContext actorContext);

    SyncErrorSample failExecution(Long taskId, Long executionId, SyncExecutionFailRequest request, SyncActorContext actorContext);

    PlatformPageResponse<SyncExecution> pageExecutions(SyncExecutionQueryCriteria criteria, SyncActorContext actorContext);

    /**
     * 查询某次 execution 的运行日志。
     *
     * <p>运行日志是“阶段时间线”，用于回答“这次同步执行过程中每一步发生了什么、读写了多少、速度如何、哪里失败”。
     * 它和 execution 列表不同：execution 列表只展示最终或当前状态，运行日志展示过程。</p>
     */
    PlatformPageResponse<SyncExecutionLog> pageExecutionLogs(SyncExecutionLogQueryCriteria criteria,
                                                            SyncActorContext actorContext);

    /**
     * 查询某次 execution 内部的对象级执行账本。
     *
     * <p>该接口是 OBJECT_LIST 部分成功闭环的运维入口：用户或运营人员能看到哪些对象成功、哪些失败、
     * 尝试次数、低敏错误码和计数信息，然后再决定是否走对象级选择性重试。</p>
     */
    PlatformPageResponse<SyncObjectExecutionView> pageObjectExecutions(SyncObjectExecutionQueryCriteria criteria,
                                                                       SyncActorContext actorContext);

    /**
     * 对某次 execution 下的 FAILED 对象发起选择性重试。
     *
     * <p>该动作不会直接搬运数据，而是重置失败对象账本并重新排队父 execution。真实执行仍由 worker 认领后完成，
     * 因此它和现有租约、回调、审计、可观测链路保持一致。</p>
     */
    SyncObjectRetryResult retryObjectExecutions(Long taskId,
                                                Long executionId,
                                                SyncObjectRetryRequest request,
                                                SyncActorContext actorContext);

    PlatformPageResponse<SyncCheckpoint> pageCheckpoints(SyncCheckpointQueryCriteria criteria, SyncActorContext actorContext);

    PlatformPageResponse<SyncErrorSample> pageErrorSamples(SyncErrorSampleQueryCriteria criteria, SyncActorContext actorContext);

    /**
     * 基于结构化错误样本创建脏数据修复重放计划。
     *
     * <p>该方法不是直接在 HTTP 线程里改写目标表，而是创建新的 REPLAY execution 和恢复计划。
     * 真正的数据重放仍由 worker 按租约、checkpoint、模板配置、连接器能力和幂等策略执行。</p>
     */
    SyncDirtyRecordReplayResult replayDirtyRecords(Long taskId,
                                                   SyncDirtyRecordReplayRequest request,
                                                   SyncActorContext actorContext);

    PlatformPageResponse<SyncAuditRecord> pageAuditRecords(SyncAuditQueryCriteria criteria, SyncActorContext actorContext);
}
