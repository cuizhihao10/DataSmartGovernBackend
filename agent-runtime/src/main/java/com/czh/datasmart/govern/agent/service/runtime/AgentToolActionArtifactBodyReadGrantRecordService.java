/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantRecordService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * artifact 正文读取授权事实登记服务。
 *
 * <p>该服务把 {@link AgentToolActionArtifactBodyReadGrantService} 生成的“低敏授权决策响应”
 * 转换成服务端可回查的 grant fact。拆出独立服务的原因是：签发决策、存储事实、后续验证属于三个不同职责。
 * 如果全部塞进 grant service，文件会越来越大，也会让 future MySQL store、撤销 API、指标统计和审计导出
 * 与授权规则本身高度耦合。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionArtifactBodyReadGrantRecordService {

    private final AgentToolActionArtifactBodyReadGrantStore grantStore;

    /**
     * 在 grant 成功签发后保存服务端事实。
     *
     * <p>仅 granted=true 且 grantDecisionReference 非空时写入。拒绝类响应不保存为可复用事实，
     * 因为拒绝原因应通过响应、审计事件和日志解释，不能被后续 final-check 当作“曾经授权过”。</p>
     */
    public void recordGrantedDecision(
            AgentToolActionArtifactBodyReadGrantResponse decision,
            long issuedAtEpochMs) {
        if (decision == null || !decision.granted() || !hasText(decision.grantDecisionReference())) {
            return;
        }
        grantStore.save(AgentToolActionArtifactBodyReadGrantRecord.fromDecision(decision, issuedAtEpochMs));
    }

    /**
     * 按低敏引用回查 grant fact。
     *
     * <p>方法保留在服务层而不是让 controller/final-check 直接依赖 store，
     * 是为了后续可以在这里追加查询审计、Micrometer 指标、缓存穿透保护或 MySQL 读写降级策略。</p>
     */
    public Optional<AgentToolActionArtifactBodyReadGrantRecord> findByReference(String grantDecisionReference) {
        return grantStore.findByReference(grantDecisionReference);
    }

    /**
     * 撤销 grant fact 的服务层入口。
     *
     * <p>当前批次暂不新增管理员 HTTP 路由，但先保留服务方法，后续接 permission-admin 审批、
     * 风险扫描隔离或人工撤销时，可以复用同一条低敏状态流转。</p>
     */
    public Optional<AgentToolActionArtifactBodyReadGrantRecord> revoke(
            String grantDecisionReference,
            String operatorId,
            String reasonCode) {
        return grantStore.revoke(grantDecisionReference, operatorId, reasonCode, Instant.now().toEpochMilli());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
