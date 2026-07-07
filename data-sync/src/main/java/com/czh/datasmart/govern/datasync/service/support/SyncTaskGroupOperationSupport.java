/**
 * @Author : Cui
 * @Date: 2026/07/07 18:46
 * @Description DataSmart Govern Backend - SyncTaskGroupOperationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 同步任务分组操作支撑组件。
 *
 * <p>任务分组是 data-sync 走向产品化运营台的关键能力之一。真实客户不会只看单个任务：
 * 他们通常会按业务域、迁移批次、目标系统、责任团队或 Agent 生成计划来管理一批同步任务。
 * 当前版本采用“任务表内 groupCode/groupName”的轻量模型，原因是：</p>
 * <p>1. 能快速支撑列表过滤、分组汇总、导入导出和 Agent 工具调用；</p>
 * <p>2. 不会提前引入复杂的 group 成员表、组级权限表和组级配额表，避免闭环阶段再次发散；</p>
 * <p>3. groupCode 是稳定引用，未来如果升级为独立 sync_task_group 表，可以用 groupCode 作为自然迁移键。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskGroupOperationSupport {

    /**
     * 分组编码格式。
     *
     * <p>允许字母、数字、下划线、短横线、点号和冒号，是为了兼容常见业务域编码：
     * ORDER_SYNC、customer-batch-202607、ods.order、tenantA:order 等。禁止空格和中文作为编码，
     * 是为了让编码适合导入导出、URL 参数、Agent 工具调用和后续唯一键。</p>
     */
    private static final Pattern GROUP_CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_.:-]{0,63}$");

    /**
     * 分组操作原因的低敏关键字。
     *
     * <p>原因会进入审计摘要，不能成为 SQL、凭据、样本数据或 prompt 的二次泄露点。</p>
     */
    private static final Set<String> SENSITIVE_REASON_KEYWORDS = Set.of(
            "password", "token", "secret", "credential", "access_key", "private_key",
            "jdbc:", "sql", "prompt", "payload", "sample", "密码", "密钥", "令牌", "凭据", "样本"
    );

    private final SyncTaskMapper taskMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncQuerySupport querySupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 查询任务分组汇总。
     *
     * <p>该方法先把请求条件翻译成当前操作者可见的数据范围，再委托 Mapper 做聚合。
     * 如果当前用户是 SELF 范围，只统计 ownerId=actorId 的任务，避免普通用户通过分组列表推断别人是否创建了任务。</p>
     */
    public List<SyncTaskGroupSummary> listTaskGroups(SyncTaskQueryCriteria criteria, SyncActorContext actorContext) {
        SyncTaskQueryCriteria safeCriteria = criteria == null
                ? new SyncTaskQueryCriteria(null, null, null, null, null, null, null, null, null, null, null)
                : criteria;
        SyncDataVisibility visibility = dataScopeSupport.resolveVisibility(
                safeCriteria.tenantId(), safeCriteria.projectId(), safeCriteria.workspaceId(), actorContext);
        if (visibility.projectScopeEnforced()
                && visibility.projectId() == null
                && (visibility.authorizedProjectIds() == null || visibility.authorizedProjectIds().isEmpty())) {
            return List.of();
        }
        Long ownerId = resolveOwnerFilter(safeCriteria.ownerId(), visibility, actorContext);
        if (ownerId == null && visibility.selfOnly()) {
            return List.of();
        }
        int limit = normalizeLimit(safeCriteria.size());
        return taskMapper.selectTaskGroupSummaries(
                visibility.tenantId(),
                visibility.projectId(),
                visibility.workspaceId(),
                visibility.projectScopeEnforced(),
                visibility.authorizedProjectIds(),
                ownerId,
                normalizeOptionalGroupCode(safeCriteria.groupCode()),
                limit);
    }

    /**
     * 调整单个任务的分组。
     *
     * <p>该方法只修改任务定义字段，不触发执行、不改模板、不影响历史 execution。
     * 对运行中的任务，移组仍然允许，因为它只是运营视图变化；执行器读取的是模板和 execution，不依赖 groupCode。</p>
     */
    public SyncTaskOperationResult updateTaskGroup(SyncTask task,
                                                   SyncTaskGroupUpdateRequest request,
                                                   SyncActorContext actorContext) {
        TaskGroupAssignment assignment = resolveAssignment(
                request == null ? null : request.getGroupCode(),
                request == null ? null : request.getGroupName());
        String oldGroupCode = task.getGroupCode();
        String oldGroupName = task.getGroupName();
        int updated = taskMapper.updateTaskGroup(
                task.getId(),
                assignment.groupCode(),
                assignment.groupName());
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步任务分组更新失败，taskId=" + task.getId());
        }
        String reason = sanitizeReason(request == null ? null : request.getReason(), "用户调整同步任务分组");
        auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                SyncAuditActionType.UPDATE_TASK_GROUP, actorContext,
                "oldGroupCode=" + oldGroupCode
                        + ",oldGroupName=" + oldGroupName
                        + ",newGroupCode=" + assignment.groupCode()
                        + ",newGroupName=" + assignment.groupName()
                        + ",reason=" + reason);
        return new SyncTaskOperationResult(task.getId(), task.getCurrentState(),
                assignment.groupCode() == null
                        ? "同步任务已移出任务分组"
                        : "同步任务已移动到分组 " + assignment.groupCode());
    }

    /**
     * 将外部输入解析为稳定的任务分组赋值。
     *
     * <p>创建任务、克隆任务、移组任务都会复用该方法。这样整个 data-sync 模块对 groupCode 大小写、长度、
     * 展示名兜底和清空分组的语义保持一致。</p>
     */
    public TaskGroupAssignment resolveAssignment(String rawGroupCode, String rawGroupName) {
        String groupCode = normalizeOptionalGroupCode(rawGroupCode);
        String groupName = trimToNull(rawGroupName);
        if (groupCode == null) {
            if (groupName != null) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "设置任务分组名称时必须同时提供 groupCode");
            }
            return new TaskGroupAssignment(null, null);
        }
        return new TaskGroupAssignment(groupCode, truncate(groupName == null ? groupCode : groupName, 128));
    }

    private Long resolveOwnerFilter(Long requestedOwnerId, SyncDataVisibility visibility, SyncActorContext actorContext) {
        if (!visibility.selfOnly()) {
            return requestedOwnerId;
        }
        Long actorId = querySupport.actorId(actorContext);
        if (requestedOwnerId != null && !requestedOwnerId.equals(actorId)) {
            return null;
        }
        return actorId;
    }

    private int normalizeLimit(Long requestedSize) {
        if (requestedSize == null || requestedSize <= 0) {
            return 100;
        }
        return Math.toIntExact(Math.min(requestedSize, 200));
    }

    private String normalizeOptionalGroupCode(String rawGroupCode) {
        String groupCode = trimToNull(rawGroupCode);
        if (groupCode == null) {
            return null;
        }
        String normalized = groupCode.toUpperCase(Locale.ROOT);
        if (!GROUP_CODE_PATTERN.matcher(normalized).matches()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "任务分组编码只能包含大写字母、数字、下划线、短横线、点号或冒号，且长度不能超过 64: " + rawGroupCode);
        }
        return normalized;
    }

    private String sanitizeReason(String reason, String defaultReason) {
        if (reason == null || reason.isBlank()) {
            return defaultReason;
        }
        String compact = reason.trim().replaceAll("\\s+", " ");
        String lower = compact.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_REASON_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "操作原因包含敏感关键字，已按审计低敏策略脱敏";
            }
        }
        return truncate(compact, 500);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 规范化后的分组赋值。
     *
     * @param groupCode 稳定分组编码；为空表示未分组
     * @param groupName 展示名称；groupCode 为空时必须为空
     */
    public record TaskGroupAssignment(String groupCode, String groupName) {
    }
}
