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
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupCreateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupTreeNode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskGroup;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskGroupMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 同步任务分组操作支撑组件。
 *
 * <p>本组件是 data-sync 任务运营模型里“分组资源”的核心业务层。它同时处理四类语义：</p>
 * <p>1. 分组资源管理：创建分组、构建多级分组树、删除分组；</p>
 * <p>2. 任务归属管理：创建任务、编辑任务、移组、克隆和导入时解析任务应归属哪个分组；</p>
 * <p>3. 默认分组兜底：任务没有显式选择分组时必须进入 DEFAULT/默认分组，避免出现前端菜单不可见的“游离任务”；</p>
 * <p>4. 历史兼容：旧版本只在 data_sync_task 上保存 groupCode/groupName，本组件会把这类历史分组合并进树视图，方便平滑迁移。</p>
 *
 * <p>为什么不把分组逻辑写在 Controller 或 DataSyncServiceImpl 中：</p>
 * <p>分组看似只是 UI 菜单，但它会影响任务创建、导入导出、批量运营、告警聚合、权限范围和 Agent 工具调用。
 * 如果每个入口各自处理 groupCode 大小写、默认分组、父子关系、删除回退和审计，很快会出现“创建时一个规则、编辑时另一个规则”的漂移。
 * 因此这里把规则集中收口，主 Service 只负责入口级数据范围校验和事务边界。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskGroupOperationSupport {

    /**
     * 系统默认分组编码。
     *
     * <p>默认分组是每个 tenant/project/workspace 作用域内的保底容器。它不是“未分组”，而是一个真实可选择、可展示、
     * 不可删除的系统分组。这样前端创建任务时总能给出确定值，删除普通分组时也有安全回退目标。</p>
     */
    public static final String DEFAULT_GROUP_CODE = "DEFAULT";

    /**
     * 系统默认分组展示名。
     */
    public static final String DEFAULT_GROUP_NAME = "默认分组";

    /**
     * 分组编码格式。
     *
     * <p>编码只允许 ASCII 范围内的稳定字符，原因是 groupCode 会出现在 URL、导入导出文件、Agent 工具参数、
     * 未来组级批量操作和审计 payload 中。展示名称可以用中文，但稳定编码不应包含空格或中文。</p>
     */
    private static final Pattern GROUP_CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_.:-]{0,63}$");

    /**
     * 分组操作原因的低敏关键字。
     *
     * <p>原因会进入审计摘要，不能成为 SQL、连接串、密钥、样本数据或 prompt 的二次泄露渠道。</p>
     */
    private static final Set<String> SENSITIVE_REASON_KEYWORDS = Set.of(
            "password", "token", "secret", "credential", "access_key", "private_key",
            "jdbc:", "sql", "prompt", "payload", "sample", "密码", "密钥", "令牌", "凭据", "样本"
    );

    private final SyncTaskMapper taskMapper;
    private final SyncTaskGroupMapper groupMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncQuerySupport querySupport;
    private final SyncAuditSupport auditSupport;
    private final SyncTaskGroupDisplayContractSupport displayContractSupport;

    /**
     * 创建任务分组。
     *
     * <p>业务流程：</p>
     * <p>1. 解析可信租户，并校验 PROJECT 范围下不能创建无项目归属分组；</p>
     * <p>2. 规范化 groupCode/parentGroupCode，确保编码可被 URL、导入导出和 Agent 稳定引用；</p>
     * <p>3. 确保同一作用域内存在默认分组；</p>
     * <p>4. 校验父分组存在，避免前端树出现悬挂节点；</p>
     * <p>5. 插入新分组或恢复曾经归档的同编码分组，并写入低敏审计。</p>
     */
    public SyncTaskGroup createTaskGroup(SyncTaskGroupCreateRequest request, SyncActorContext actorContext) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "创建同步任务分组请求不能为空");
        }
        Long tenantId = dataScopeSupport.resolveTenantForCreate(request.getTenantId(), actorContext);
        dataScopeSupport.validateProjectWritable(tenantId, request.getProjectId(), request.getWorkspaceId(),
                actorContext, "同步任务分组");

        String groupCode = normalizeRequiredGroupCode(request.getGroupCode());
        if (DEFAULT_GROUP_CODE.equals(groupCode)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "默认分组由系统维护，不能通过普通创建入口重复创建");
        }
        String parentGroupCode = normalizeOptionalGroupCode(request.getParentGroupCode());
        if (groupCode.equals(parentGroupCode)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "分组不能把自己设置为父分组，groupCode=" + groupCode);
        }
        ensureDefaultGroup(tenantId, request.getProjectId(), request.getWorkspaceId(), actorContext);
        if (parentGroupCode != null) {
            assertActiveGroupExists(tenantId, request.getProjectId(), request.getWorkspaceId(), parentGroupCode,
                    "父分组不存在或已归档，parentGroupCode=" + parentGroupCode);
        }

        SyncTaskGroup existing = groupMapper.selectByScopeAndCode(
                tenantId, request.getProjectId(), request.getWorkspaceId(), groupCode, true);
        SyncTaskGroup saved;
        if (existing == null) {
            saved = newGroup(tenantId, request, groupCode, parentGroupCode, actorContext);
            groupMapper.insert(saved);
        } else if (Boolean.TRUE.equals(existing.getArchived())) {
            saved = restoreArchivedGroup(existing, request, parentGroupCode, actorContext);
            groupMapper.updateById(saved);
        } else {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同一作用域下分组编码已存在，groupCode=" + groupCode);
        }

        auditSupport.saveAudit(tenantId, null, null, SyncAuditActionType.CREATE_TASK_GROUP,
                actorContext, "groupCode=" + saved.getGroupCode()
                        + ",groupName=" + saved.getGroupName()
                        + ",projectId=" + saved.getProjectId()
                        + ",workspaceId=" + saved.getWorkspaceId());
        return saved;
    }

    /**
     * 查询任务分组汇总。
     *
     * <p>该方法保留旧接口 {@code GET /sync-tasks/groups} 的返回形态。它从任务表聚合计数，
     * 并把历史 null/空 groupCode 统一视为 DEFAULT，确保旧数据也能出现在默认分组里。</p>
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
        List<SyncTaskGroupSummary> summaries = taskMapper.selectTaskGroupSummaries(
                visibility.tenantId(),
                visibility.projectId(),
                visibility.workspaceId(),
                visibility.projectScopeEnforced(),
                visibility.authorizedProjectIds(),
                ownerId,
                normalizeGroupCodeForFilter(safeCriteria.groupCode()),
                limit);
        displayContractSupport.enrichSummaries(summaries);
        return summaries;
    }

    /**
     * 查询任务分组树。
     *
     * <p>树形接口面向前端左侧导航栏和中间分组菜单栏。它会合并两类节点：</p>
     * <p>1. data_sync_task_group 中正式创建的分组资源，用于新增、删除、父子关系、排序和默认分组；</p>
     * <p>2. data_sync_task 中历史存在但分组表尚未创建的 groupCode，用 legacyOnly=true 标记，避免旧任务突然从菜单消失。</p>
     *
     * <p>对于明确 tenant/project/workspace 的请求，方法会自动确保默认分组存在；对于跨项目可见范围查询，
     * 为避免给每个授权项目隐式插入默认分组，只返回已存在资源和任务聚合结果。</p>
     */
    public List<SyncTaskGroupTreeNode> listTaskGroupTree(SyncTaskQueryCriteria criteria, SyncActorContext actorContext) {
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

        if (visibility.tenantId() != null && (!visibility.projectScopeEnforced() || visibility.projectId() != null)) {
            ensureDefaultGroup(visibility.tenantId(), visibility.projectId(), visibility.workspaceId(), actorContext);
        }

        String groupCode = normalizeGroupCodeForFilter(safeCriteria.groupCode());
        int limit = normalizeLimit(safeCriteria.size());
        List<SyncTaskGroup> groups = groupMapper.selectVisibleGroups(
                visibility.tenantId(),
                visibility.projectId(),
                visibility.workspaceId(),
                visibility.projectScopeEnforced(),
                visibility.authorizedProjectIds(),
                groupCode,
                limit);
        Map<GroupKey, SyncTaskGroupTreeNode> nodes = new LinkedHashMap<>();
        for (SyncTaskGroup group : groups) {
            SyncTaskGroupTreeNode node = toNode(group);
            nodes.put(GroupKey.of(group), node);
        }
        for (SyncTaskGroupSummary summary : listTaskGroups(safeCriteria, actorContext)) {
            GroupKey key = GroupKey.of(summary);
            SyncTaskGroupTreeNode node = nodes.computeIfAbsent(key, ignored -> legacyNode(summary));
            mergeSummary(node, summary);
        }
        return buildTree(nodes);
    }

    /**
     * 删除分组并把任务迁回默认分组。
     *
     * <p>删除动作的安全语义非常重要：</p>
     * <p>1. 默认分组不能删除，因为它是新任务和被删除分组任务的回退目标；</p>
     * <p>2. 删除普通分组时，会同时归档其子孙分组，避免树上留下悬挂子节点；</p>
     * <p>3. 属于这些分组的任务不会被删除，只会改写为 DEFAULT/默认分组；</p>
     * <p>4. 操作写入审计，payload 只记录低敏分组编码、影响任务数和原因摘要。</p>
     */
    public SyncTaskOperationResult deleteTaskGroup(String rawGroupCode,
                                                   Long requestedTenantId,
                                                   Long requestedProjectId,
                                                   Long requestedWorkspaceId,
                                                   String reason,
                                                   SyncActorContext actorContext) {
        Long tenantId = dataScopeSupport.resolveTenantForCreate(requestedTenantId, actorContext);
        dataScopeSupport.validateProjectWritable(tenantId, requestedProjectId, requestedWorkspaceId,
                actorContext, "同步任务分组");
        String groupCode = normalizeRequiredGroupCode(rawGroupCode);
        if (DEFAULT_GROUP_CODE.equals(groupCode)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "默认分组不能删除；它用于新任务默认归属和删除普通分组后的任务回退");
        }
        SyncTaskGroup target = groupMapper.selectByScopeAndCode(
                tenantId, requestedProjectId, requestedWorkspaceId, groupCode, false);
        if (target == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务分组不存在或已归档，groupCode=" + groupCode);
        }
        SyncTaskGroup defaultGroup = ensureDefaultGroup(tenantId, requestedProjectId, requestedWorkspaceId, actorContext);
        List<String> affectedGroupCodes = descendantCodes(
                tenantId, requestedProjectId, requestedWorkspaceId, groupCode);
        int movedTasks = taskMapper.reassignGroupsToDefault(
                tenantId,
                requestedProjectId,
                requestedWorkspaceId,
                affectedGroupCodes,
                defaultGroup.getGroupCode(),
                defaultGroup.getGroupName());
        int archivedGroups = groupMapper.archiveGroupsInExactScope(
                tenantId,
                requestedProjectId,
                requestedWorkspaceId,
                affectedGroupCodes,
                querySupport.actorId(actorContext));
        auditSupport.saveAudit(tenantId, null, null, SyncAuditActionType.DELETE_TASK_GROUP,
                actorContext, "groupCode=" + groupCode
                        + ",affectedGroupCodes=" + affectedGroupCodes
                        + ",movedTasks=" + movedTasks
                        + ",archivedGroups=" + archivedGroups
                        + ",reason=" + sanitizeReason(reason, "用户删除同步任务分组"));
        return new SyncTaskOperationResult(null, "GROUP_DELETED",
                "同步任务分组已删除，归档分组数=" + archivedGroups + "，迁回默认分组任务数=" + movedTasks);
    }

    /**
     * 调整单个任务的分组。
     *
     * <p>该方法只修改任务定义字段，不触发执行、不改模板、不影响历史 execution。
     * 如果请求为空或 groupCode 为空，任务会进入默认分组，而不是变成未分组。</p>
     */
    public SyncTaskOperationResult updateTaskGroup(SyncTask task,
                                                   SyncTaskGroupUpdateRequest request,
                                                   SyncActorContext actorContext) {
        TaskGroupAssignment assignment = resolveAssignmentForTask(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkspaceId(),
                request == null ? null : request.getGroupCode(),
                request == null ? null : request.getGroupName(),
                actorContext);
        String oldGroupCode = task.getGroupCode();
        String oldGroupName = task.getGroupName();
        int updated = taskMapper.updateTaskGroup(task.getId(), assignment.groupCode(), assignment.groupName());
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
                "同步任务已移动到分组 " + assignment.groupCode());
    }

    /**
     * 解析创建/编辑/克隆/导入任务时的分组归属。
     *
     * <p>与旧版 {@code resolveAssignment(groupCode, groupName)} 不同，这个方法需要任务作用域，因为同一个 groupCode
     * 在不同 tenant/project/workspace 下不是同一个分组。方法会确保默认分组存在，并要求非默认分组已经被显式创建，
     * 从而保证“创建任务时下拉框可选项”和“服务端最终写入值”一致。</p>
     */
    public TaskGroupAssignment resolveAssignmentForTask(Long tenantId,
                                                        Long projectId,
                                                        Long workspaceId,
                                                        String rawGroupCode,
                                                        String rawGroupName,
                                                        SyncActorContext actorContext) {
        String groupCode = normalizeOptionalGroupCode(rawGroupCode);
        if (groupCode == null) {
            SyncTaskGroup defaultGroup = ensureDefaultGroup(tenantId, projectId, workspaceId, actorContext);
            return new TaskGroupAssignment(defaultGroup.getGroupCode(), defaultGroup.getGroupName());
        }
        SyncTaskGroup group = assertActiveGroupExists(tenantId, projectId, workspaceId, groupCode,
                "任务分组不存在或已归档，请先在分组菜单栏创建后再选择，groupCode=" + groupCode);
        if (rawGroupName != null && !rawGroupName.isBlank()) {
            return new TaskGroupAssignment(group.getGroupCode(), truncate(rawGroupName.trim(), 128));
        }
        return new TaskGroupAssignment(group.getGroupCode(), group.getGroupName());
    }

    /**
     * 将列表筛选中的 groupCode 规范化。
     *
     * <p>该方法和任务赋值不同：筛选条件为空就应该表示“不按分组过滤”，不能自动转成 DEFAULT，
     * 否则任务列表、导出和分组汇总会意外只返回默认分组。</p>
     */
    public String normalizeGroupCodeForFilter(String rawGroupCode) {
        return normalizeOptionalGroupCode(rawGroupCode);
    }

    /**
     * 确保某个精确作用域内存在默认分组。
     */
    private SyncTaskGroup ensureDefaultGroup(Long tenantId,
                                             Long projectId,
                                             Long workspaceId,
                                             SyncActorContext actorContext) {
        Long safeTenantId = tenantId == null ? 0L : tenantId;
        SyncTaskGroup existing = groupMapper.selectByScopeAndCode(
                safeTenantId, projectId, workspaceId, DEFAULT_GROUP_CODE, true);
        if (existing != null && !Boolean.TRUE.equals(existing.getArchived())) {
            return existing;
        }
        if (existing != null) {
            existing.setArchived(false);
            existing.setDefaultGroup(true);
            existing.setGroupName(DEFAULT_GROUP_NAME);
            existing.setDisplayOrder(0);
            existing.setUpdatedBy(querySupport.actorId(actorContext));
            existing.setUpdateTime(LocalDateTime.now());
            groupMapper.updateById(existing);
            return existing;
        }
        SyncTaskGroup group = new SyncTaskGroup();
        group.setTenantId(safeTenantId);
        group.setProjectId(projectId);
        group.setWorkspaceId(workspaceId);
        group.setParentGroupCode(null);
        group.setGroupCode(DEFAULT_GROUP_CODE);
        group.setGroupName(DEFAULT_GROUP_NAME);
        group.setDescription("系统默认分组：未显式选择分组的新任务，以及被删除分组中的任务都会回落到这里");
        group.setDisplayOrder(0);
        group.setDefaultGroup(true);
        group.setArchived(false);
        group.setCreatedBy(querySupport.actorId(actorContext));
        group.setUpdatedBy(querySupport.actorId(actorContext));
        group.setCreateTime(LocalDateTime.now());
        group.setUpdateTime(LocalDateTime.now());
        try {
            groupMapper.insert(group);
        } catch (RuntimeException duplicateOrConcurrentCreate) {
            SyncTaskGroup reloaded = groupMapper.selectByScopeAndCode(
                    safeTenantId, projectId, workspaceId, DEFAULT_GROUP_CODE, false);
            if (reloaded != null) {
                return reloaded;
            }
            throw duplicateOrConcurrentCreate;
        }
        return group;
    }

    private SyncTaskGroup assertActiveGroupExists(Long tenantId,
                                                  Long projectId,
                                                  Long workspaceId,
                                                  String groupCode,
                                                  String message) {
        SyncTaskGroup group = groupMapper.selectByScopeAndCode(
                tenantId == null ? 0L : tenantId, projectId, workspaceId, groupCode, false);
        if (group == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, message);
        }
        return group;
    }

    private SyncTaskGroup newGroup(Long tenantId,
                                   SyncTaskGroupCreateRequest request,
                                   String groupCode,
                                   String parentGroupCode,
                                   SyncActorContext actorContext) {
        SyncTaskGroup group = new SyncTaskGroup();
        group.setTenantId(tenantId);
        group.setProjectId(request.getProjectId());
        group.setWorkspaceId(request.getWorkspaceId());
        group.setParentGroupCode(parentGroupCode);
        group.setGroupCode(groupCode);
        group.setGroupName(resolveGroupName(request.getGroupName(), groupCode));
        group.setDescription(truncate(trimToNull(request.getDescription()), 500));
        group.setDisplayOrder(resolveDisplayOrder(request.getDisplayOrder()));
        group.setDefaultGroup(false);
        group.setArchived(false);
        group.setCreatedBy(querySupport.actorId(actorContext));
        group.setUpdatedBy(querySupport.actorId(actorContext));
        group.setCreateTime(LocalDateTime.now());
        group.setUpdateTime(LocalDateTime.now());
        return group;
    }

    private SyncTaskGroup restoreArchivedGroup(SyncTaskGroup group,
                                               SyncTaskGroupCreateRequest request,
                                               String parentGroupCode,
                                               SyncActorContext actorContext) {
        group.setParentGroupCode(parentGroupCode);
        group.setGroupName(resolveGroupName(request.getGroupName(), group.getGroupCode()));
        group.setDescription(truncate(trimToNull(request.getDescription()), 500));
        group.setDisplayOrder(resolveDisplayOrder(request.getDisplayOrder()));
        group.setDefaultGroup(false);
        group.setArchived(false);
        group.setUpdatedBy(querySupport.actorId(actorContext));
        group.setUpdateTime(LocalDateTime.now());
        return group;
    }

    private List<String> descendantCodes(Long tenantId, Long projectId, Long workspaceId, String rootGroupCode) {
        List<SyncTaskGroup> groups = groupMapper.selectActiveGroupsInExactScope(tenantId, projectId, workspaceId);
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (SyncTaskGroup group : groups) {
            String parent = normalizeOptionalGroupCode(group.getParentGroupCode());
            if (parent != null) {
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(group.getGroupCode());
            }
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(rootGroupCode);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!codes.add(current)) {
                continue;
            }
            for (String child : children.getOrDefault(current, List.of())) {
                queue.addLast(child);
            }
        }
        return new ArrayList<>(codes);
    }

    private List<SyncTaskGroupTreeNode> buildTree(Map<GroupKey, SyncTaskGroupTreeNode> nodes) {
        Map<ScopeKey, Map<String, SyncTaskGroupTreeNode>> byScope = new LinkedHashMap<>();
        for (Map.Entry<GroupKey, SyncTaskGroupTreeNode> entry : nodes.entrySet()) {
            byScope.computeIfAbsent(entry.getKey().scopeKey(), ignored -> new LinkedHashMap<>())
                    .put(entry.getKey().groupCode(), entry.getValue());
        }
        List<SyncTaskGroupTreeNode> roots = new ArrayList<>();
        for (Map<String, SyncTaskGroupTreeNode> scopedNodes : byScope.values()) {
            for (SyncTaskGroupTreeNode node : scopedNodes.values()) {
                String parentGroupCode = normalizeOptionalGroupCode(node.getParentGroupCode());
                if (parentGroupCode == null || !scopedNodes.containsKey(parentGroupCode)) {
                    roots.add(node);
                    continue;
                }
                scopedNodes.get(parentGroupCode).getChildren().add(node);
            }
        }
        sortTree(roots);
        displayContractSupport.enrichTreeNodes(roots);
        return roots;
    }

    private void sortTree(List<SyncTaskGroupTreeNode> nodes) {
        nodes.sort(Comparator
                .comparing((SyncTaskGroupTreeNode node) -> node.getDisplayOrder() == null ? 100 : node.getDisplayOrder())
                .thenComparing(node -> node.getGroupName() == null ? "" : node.getGroupName())
                .thenComparing(node -> node.getGroupCode() == null ? "" : node.getGroupCode()));
        for (SyncTaskGroupTreeNode node : nodes) {
            sortTree(node.getChildren());
        }
    }

    private SyncTaskGroupTreeNode toNode(SyncTaskGroup group) {
        SyncTaskGroupTreeNode node = new SyncTaskGroupTreeNode();
        node.setId(group.getId());
        node.setTenantId(group.getTenantId());
        node.setProjectId(group.getProjectId());
        node.setWorkspaceId(group.getWorkspaceId());
        node.setParentGroupCode(group.getParentGroupCode());
        node.setGroupCode(group.getGroupCode());
        node.setGroupName(group.getGroupName());
        node.setDescription(group.getDescription());
        node.setDisplayOrder(group.getDisplayOrder());
        node.setDefaultGroup(Boolean.TRUE.equals(group.getDefaultGroup()));
        node.setLegacyOnly(false);
        zeroSummary(node);
        return node;
    }

    private SyncTaskGroupTreeNode legacyNode(SyncTaskGroupSummary summary) {
        SyncTaskGroupTreeNode node = new SyncTaskGroupTreeNode();
        node.setTenantId(summary.getTenantId());
        node.setProjectId(summary.getProjectId());
        node.setWorkspaceId(summary.getWorkspaceId());
        node.setParentGroupCode(null);
        node.setGroupCode(summary.getGroupCode());
        node.setGroupName(resolveGroupName(summary.getGroupName(), summary.getGroupCode()));
        node.setDescription("历史任务中存在的分组编码，尚未创建正式分组资源");
        node.setDisplayOrder(DEFAULT_GROUP_CODE.equals(summary.getGroupCode()) ? 0 : 100);
        node.setDefaultGroup(DEFAULT_GROUP_CODE.equals(summary.getGroupCode()));
        node.setLegacyOnly(true);
        zeroSummary(node);
        return node;
    }

    private void mergeSummary(SyncTaskGroupTreeNode node, SyncTaskGroupSummary summary) {
        node.setTaskCount(nullToZero(summary.getTaskCount()));
        node.setActiveTaskCount(nullToZero(summary.getActiveTaskCount()));
        node.setScheduledTaskCount(nullToZero(summary.getScheduledTaskCount()));
        node.setRunningTaskCount(nullToZero(summary.getRunningTaskCount()));
        node.setFailedTaskCount(nullToZero(summary.getFailedTaskCount()));
        node.setRecycledTaskCount(nullToZero(summary.getRecycledTaskCount()));
        node.setLastUpdateTime(summary.getLastUpdateTime());
    }

    private void zeroSummary(SyncTaskGroupTreeNode node) {
        node.setTaskCount(0L);
        node.setActiveTaskCount(0L);
        node.setScheduledTaskCount(0L);
        node.setRunningTaskCount(0L);
        node.setFailedTaskCount(0L);
        node.setRecycledTaskCount(0L);
        node.setSubtreeTaskCount(0L);
        node.setSubtreeActiveTaskCount(0L);
        node.setSubtreeScheduledTaskCount(0L);
        node.setSubtreeRunningTaskCount(0L);
        node.setSubtreeFailedTaskCount(0L);
        node.setSubtreeRecycledTaskCount(0L);
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
            return 200;
        }
        return Math.toIntExact(Math.min(requestedSize, 500));
    }

    private String normalizeRequiredGroupCode(String rawGroupCode) {
        String normalized = normalizeOptionalGroupCode(rawGroupCode);
        if (normalized == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "任务分组编码不能为空");
        }
        return normalized;
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

    private String resolveGroupName(String rawGroupName, String groupCode) {
        String groupName = trimToNull(rawGroupName);
        return truncate(groupName == null ? groupCode : groupName, 128);
    }

    private int resolveDisplayOrder(Integer displayOrder) {
        if (displayOrder == null) {
            return 100;
        }
        return Math.max(0, Math.min(displayOrder, 9999));
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

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 规范化后的分组赋值。
     *
     * @param groupCode 稳定分组编码，当前版本永远不为空；未显式选择时为 DEFAULT
     * @param groupName 展示名称
     */
    public record TaskGroupAssignment(String groupCode, String groupName) {
    }

    private record ScopeKey(Long tenantId, Long projectId, Long workspaceId) {
    }

    private record GroupKey(Long tenantId, Long projectId, Long workspaceId, String groupCode) {

        private static GroupKey of(SyncTaskGroup group) {
            return new GroupKey(group.getTenantId(), group.getProjectId(), group.getWorkspaceId(), group.getGroupCode());
        }

        private static GroupKey of(SyncTaskGroupSummary summary) {
            return new GroupKey(summary.getTenantId(), summary.getProjectId(), summary.getWorkspaceId(), summary.getGroupCode());
        }

        private ScopeKey scopeKey() {
            return new ScopeKey(tenantId, projectId, workspaceId);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof GroupKey other)) {
                return false;
            }
            return Objects.equals(tenantId, other.tenantId)
                    && Objects.equals(projectId, other.projectId)
                    && Objects.equals(workspaceId, other.workspaceId)
                    && Objects.equals(groupCode, other.groupCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, projectId, workspaceId, groupCode);
        }
    }
}
