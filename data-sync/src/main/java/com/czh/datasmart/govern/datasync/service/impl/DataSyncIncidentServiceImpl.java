/**
 * @Author : Cui
 * @Date: 2026/05/08 22:44
 * @Description DataSmart Govern Backend - DataSyncIncidentServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncIncidentService;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataScopeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataVisibility;
import com.czh.datasmart.govern.datasync.service.support.SyncOperatorPermissionSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncQuerySupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * data-sync 事故记录服务实现。
 *
 * <p>事故管理是运营闭环的第二层：人工介入任务回答“哪个同步任务需要人处理”，
 * 事故记录回答“这个问题由谁负责、当前处理到哪一步、最后如何解决”。
 *
 * <p>当前实现先提供列表、详情、确认、分派、解决和关闭。
 * 后续可以继续扩展 SLA 到期告警、值班组分派、外部工单同步、事故复盘报告和根因分析。
 */
@Service
@RequiredArgsConstructor
public class DataSyncIncidentServiceImpl implements DataSyncIncidentService {

    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 200L;

    private final SyncIncidentRecordMapper incidentRecordMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncOperatorPermissionSupport operatorPermissionSupport;
    private final SyncAuditSupport auditSupport;
    private final SyncQuerySupport querySupport;

    /**
     * 分页查询事故记录。
     *
     * <p>查询接口允许审计和普通租户用户按数据范围查看事故；只有修改状态时才要求运营权限。
     * 这符合企业后台常见设计：可见性和可操作性分开控制。
     */
    @Override
    public PlatformPageResponse<SyncIncidentRecord> pageIncidents(SyncIncidentQueryCriteria criteria,
                                                                  SyncActorContext actorContext) {
        SyncDataVisibility visibility = dataScopeSupport.resolveVisibility(
                criteria.tenantId(), criteria.projectId(), criteria.workspaceId(), actorContext);
        LambdaQueryWrapper<SyncIncidentRecord> wrapper = new LambdaQueryWrapper<SyncIncidentRecord>()
                .orderByDesc(SyncIncidentRecord::getCreateTime)
                .orderByDesc(SyncIncidentRecord::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncIncidentRecord::getTenantId, visibility.tenantId());
        }
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getProjectId, visibility.projectId());
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncIncidentRecord::getProjectId, visibility);
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getWorkspaceId, visibility.workspaceId());
        if (visibility.selfOnly()) {
            wrapper.and(scope -> scope
                    .eq(SyncIncidentRecord::getOperatorId, querySupport.actorId(actorContext))
                    .or()
                    .eq(SyncIncidentRecord::getAssignedOperatorId, querySupport.actorId(actorContext)));
        }
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getSyncTaskId, criteria.syncTaskId());
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getExecutionId, criteria.executionId());
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getIncidentType, querySupport.normalizeCode(criteria.incidentType()));
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getSeverity, querySupport.normalizeCode(criteria.severity()));
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getIncidentStatus, querySupport.normalizeCode(criteria.incidentStatus()));
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getOperatorId, criteria.operatorId());
        querySupport.eqIfPresent(wrapper, SyncIncidentRecord::getAssignedOperatorId, criteria.assignedOperatorId());
        Page<SyncIncidentRecord> page = incidentRecordMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 查询事故详情。
     */
    @Override
    public SyncIncidentRecord getIncident(Long incidentId, SyncActorContext actorContext) {
        SyncIncidentRecord incident = requireIncident(incidentId, actorContext);
        dataScopeSupport.validateTenantReadable(incident.getTenantId(), actorContext);
        return incident;
    }

    /**
     * 确认事故已被接手。
     */
    @Override
    @Transactional
    public SyncIncidentOperationResult acknowledge(Long incidentId,
                                                   SyncIncidentOperationRequest request,
                                                   SyncActorContext actorContext) {
        operatorPermissionSupport.assertOperator(actorContext, "ACKNOWLEDGE_INCIDENT");
        SyncIncidentRecord incident = requireIncident(incidentId, actorContext);
        int updated = incidentRecordMapper.acknowledgeIncident(incidentId);
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有 OPEN 状态的事故可以确认接手，incidentId=" + incidentId + ",status=" + incident.getIncidentStatus());
        }
        audit(incident, SyncAuditActionType.ACKNOWLEDGE_INCIDENT, actorContext,
                defaultText(request == null ? null : request.getNote(), "事故已确认接手"));
        return result(requireIncident(incidentId, actorContext), "ACKNOWLEDGE", "事故已确认接手");
    }

    /**
     * 分派事故负责人。
     */
    @Override
    @Transactional
    public SyncIncidentOperationResult assign(Long incidentId,
                                              SyncIncidentOperationRequest request,
                                              SyncActorContext actorContext) {
        operatorPermissionSupport.assertOperator(actorContext, "ASSIGN_INCIDENT");
        if (request == null || request.getAssignedOperatorId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "分派事故必须提供 assignedOperatorId");
        }
        SyncIncidentRecord incident = requireIncident(incidentId, actorContext);
        String assignedRole = normalizeCode(defaultText(request.getAssignedOperatorRole(), "OPERATOR"));
        int updated = incidentRecordMapper.assignIncident(incidentId, request.getAssignedOperatorId(), assignedRole);
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "已关闭的事故不能重新分派，incidentId=" + incidentId);
        }
        audit(incident, SyncAuditActionType.ASSIGN_INCIDENT, actorContext,
                "assignedOperatorId=" + request.getAssignedOperatorId() + ",assignedRole=" + assignedRole
                        + ",note=" + truncate(request.getNote(), 300));
        return result(requireIncident(incidentId, actorContext), "ASSIGN", "事故负责人已更新");
    }

    /**
     * 标记事故已解决。
     */
    @Override
    @Transactional
    public SyncIncidentOperationResult resolve(Long incidentId,
                                               SyncIncidentOperationRequest request,
                                               SyncActorContext actorContext) {
        operatorPermissionSupport.assertOperator(actorContext, "RESOLVE_INCIDENT");
        SyncIncidentRecord incident = requireIncident(incidentId, actorContext);
        String summary = defaultText(request == null ? null : request.getResolutionSummary(),
                defaultText(request == null ? null : request.getNote(), "事故已解决，暂无补充摘要"));
        int updated = incidentRecordMapper.resolveIncident(incidentId, truncate(summary, 2000));
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "已关闭的事故不能标记解决，incidentId=" + incidentId);
        }
        audit(incident, SyncAuditActionType.RESOLVE_INCIDENT, actorContext, summary);
        return result(requireIncident(incidentId, actorContext), "RESOLVE", "事故已标记解决");
    }

    /**
     * 关闭事故。
     */
    @Override
    @Transactional
    public SyncIncidentOperationResult close(Long incidentId,
                                             SyncIncidentOperationRequest request,
                                             SyncActorContext actorContext) {
        operatorPermissionSupport.assertOperator(actorContext, "CLOSE_INCIDENT");
        SyncIncidentRecord incident = requireIncident(incidentId, actorContext);
        String summary = request == null ? null : truncate(request.getResolutionSummary(), 2000);
        int updated = incidentRecordMapper.closeIncident(incidentId, summary);
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "事故已经关闭，不能重复关闭，incidentId=" + incidentId);
        }
        audit(incident, SyncAuditActionType.CLOSE_INCIDENT, actorContext,
                defaultText(request == null ? null : request.getNote(), "事故已关闭"));
        return result(requireIncident(incidentId, actorContext), "CLOSE", "事故已关闭");
    }

    private SyncIncidentRecord requireIncident(Long incidentId, SyncActorContext actorContext) {
        SyncIncidentRecord incident = incidentRecordMapper.selectById(incidentId);
        if (incident == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步事故记录不存在: " + incidentId);
        }
        dataScopeSupport.validateAnyActorReadable(incident.getTenantId(), incident.getProjectId(), actorContext, "同步事故",
                incident.getOperatorId(), incident.getAssignedOperatorId());
        return incident;
    }

    private void audit(SyncIncidentRecord incident,
                       SyncAuditActionType actionType,
                       SyncActorContext actorContext,
                       String payload) {
        auditSupport.saveAudit(incident.getTenantId(), incident.getSyncTaskId(), incident.getExecutionId(),
                actionType, actorContext, "incidentId=" + incident.getId() + ",payload=" + truncate(payload, 500));
    }

    private SyncIncidentOperationResult result(SyncIncidentRecord incident, String operation, String message) {
        return new SyncIncidentOperationResult(
                incident.getId(),
                incident.getSyncTaskId(),
                incident.getIncidentStatus(),
                operation,
                incident.getAssignedOperatorId(),
                message);
    }

    private <T> Page<T> page(Long current, Long size) {
        long safeCurrent = current == null || current <= 0 ? DEFAULT_CURRENT : current;
        long safeSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
    }

    private <T, V> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                    com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, V> column,
                                    V value) {
        if (value != null) {
            if (value instanceof String text && text.isBlank()) {
                return;
            }
            wrapper.eq(column, value);
        }
    }

    /**
     * 追加 PROJECT 数据范围的授权项目集合过滤。
     *
     * <p>事故工作台很容易暴露跨项目运营信息，例如失败原因、数据源名称、负责人和恢复动作。
     * 因此 PROJECT 范围不能只依赖前端传 projectId；当 gateway 已经给出授权项目集合时，后端必须自动追加 IN 过滤。
     */
    private <T> void applyAuthorizedProjectScope(LambdaQueryWrapper<T> wrapper,
                                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, Long> projectColumn,
                                                 SyncDataVisibility visibility) {
        if (!visibility.projectScopeEnforced() || visibility.projectId() != null) {
            return;
        }
        List<Long> projectIds = visibility.authorizedProjectIds();
        if (projectIds == null || projectIds.isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.in(projectColumn, projectIds);
    }

    private String defaultText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
