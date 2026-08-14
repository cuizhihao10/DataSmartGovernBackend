/**
 * @Author : Cui
 * @Date: 2026/08/11 19:35
 * @Description DataSmart Govern Backend - DataSyncAutopilotRecoveryController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryDecisionRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryDeadLetterRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryQuarantineRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryRepairRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryTriggerConsumerResultRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryTransitionRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryCaseService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryCaseView;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryAutonomousQuarantineService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryDecisionCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryDeadLetterService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryPrincipalContext;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryQuarantineCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryQuarantineReceiptView;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryRepairCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryRepairReceiptView;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryRepairService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTransitionCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultView;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryConsumerResultStatus;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * agent-runtime 与 data-sync 之间的 Autopilot 状态控制 API。
 *
 * <p>该控制器不暴露给浏览器。Gateway 会清理内部令牌 Header，部署环境还应使用内网路由、mTLS
 * 或服务网格 ACL。即使内部认证通过，服务层仍会重新加载任务、授权和 execution 归属，并通过
 * 乐观锁与幂等 receipt 防止越权和重复状态推进。</p>
 */
@RestController
@RequestMapping("/internal/data-sync/autopilot/recovery")
public class DataSyncAutopilotRecoveryController {

    private final SyncAutopilotRecoveryCaseService caseService;
    private final SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService;
    private final SyncAutopilotRecoveryAutonomousQuarantineService autonomousQuarantineService;
    private final SyncAutopilotRecoveryRepairService repairService;
    private final SyncAutopilotRecoveryDeadLetterService deadLetterService;

    /**
     * 部署时注入的服务凭据，用于证明请求经过受信任的内部调用链。
     *
     * <p>空值表示部署配置错误，不会被解释成本地开发免认证。凭据只保留在内存中，不能进入响应、
     * 日志、数据库记录或审计载荷。</p>
     */
    private final String internalServiceToken;

    /**
     * 使用共享服务凭据构造内部恢复控制器。
     *
     * <p>所有内部路由共用同一认证检查。把凭据和各执行服务作为显式构造参数，既能清楚展示安全边界，
     * 也方便单元测试在不修改进程环境变量的情况下验证缺失配置和路由委派。</p>
     */
    public DataSyncAutopilotRecoveryController(
            SyncAutopilotRecoveryCaseService caseService,
            SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService,
            SyncAutopilotRecoveryAutonomousQuarantineService autonomousQuarantineService,
            SyncAutopilotRecoveryRepairService repairService,
            SyncAutopilotRecoveryDeadLetterService deadLetterService,
            @Value("${DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN:}") String internalServiceToken) {
        this.caseService = caseService;
        this.consumerResultService = consumerResultService;
        this.autonomousQuarantineService = autonomousQuarantineService;
        this.repairService = repairService;
        this.deadLetterService = deadLetterService;
        this.internalServiceToken = internalServiceToken;
    }

    /**
     * 在任务首次 Autopilot 授权盒内应用与预览绑定的隔离动作。
     *
     * <p>路由固定且仅供服务内部使用，调用方不能提供其他 URL、工具名、原因、选择器或源记录值。传输认证后，
     * 服务层还会重新校验策略、范围、摘要、状态、deadline、幂等性和选择器。成功响应是持久完成回执，但不代表
     * 后续失败对象重试已经成功。</p>
     *
     * @param caseId 已持有 AUTO_APPROVED APPLY_QUARANTINE 决策的恢复 case
     * @param request 低敏预览与范围绑定
     * @param internalToken Agent Runtime 服务凭据
     * @param representedActorId 首次授权仍然生效的用户主体
     * @param actorRole 为既有审计格式保留的被代理用户角色
     * @param agentId 选择动作的自治 Agent 身份
     * @param delegationId 首次用户到 Agent 的授权标识
     * @param traceId 跨服务追踪标识
     * @return 持久且幂等的隔离回执
     */
    @PostMapping("/cases/{caseId}/quarantine/apply")
    public PlatformApiResponse<SyncAutopilotRecoveryQuarantineReceiptView> applyAutonomousQuarantine(
            @PathVariable Long caseId,
            @RequestBody SyncAutopilotRecoveryQuarantineRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false)
            String representedActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false)
            String actorRole,
            @RequestHeader(value = PlatformContextHeaders.AGENT_ID, required = false)
            String agentId,
            @RequestHeader(value = PlatformContextHeaders.AGENT_DELEGATION_ID, required = false)
            String delegationId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        if (caseId == null || caseId <= 0 || request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot quarantine request is incomplete");
        }
        SyncAutopilotRecoveryQuarantineReceiptView view = autonomousQuarantineService.apply(
                new SyncAutopilotRecoveryQuarantineCommand(
                        caseId, request.expectedVersion(), request.tenantId(), request.projectId(),
                        request.syncTaskId(), request.executionId(), request.cycle(),
                        request.authorizationDigest(), request.policyDigest(), request.previewDigest(),
                        request.selectedSampleIds(), request.actionFingerprint(), request.receiptId()),
                new SyncAutopilotRecoveryPrincipalContext(
                        representedActorId, actorRole, agentId, delegationId, traceId));
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * 在首次授权盒内申请执行一项固定、低风险的受治理修复。
     *
     * <p>控制器只负责内部服务认证、基础形状检查和枚举解析。它不会信任调用方给出的授权结论、
     * 参数或动作指纹；服务层会重新读取 case、任务、execution、持久授权和权威元数据，并复算
     * 跨语言指纹。请求不能携带 SQL、凭据、字段值、checkpoint 内容或任意工具地址。</p>
     *
     * <p>用户主体和 Agent 主体必须同时存在。这样无人值守执行产生的每项配置修复、元数据刷新或
     * 分片重放都能追溯到首次授权用户、实际执行 Agent 和委派关系。</p>
     *
     * @param caseId 已持久化且处于 AUTO_APPROVED 的恢复 case
     * @param request 乐观锁、范围、摘要、动作和白名单参数
     * @param internalToken Agent Runtime 内部服务凭据
     * @param representedActorId 首次授权用户主体
     * @param actorRole 首次授权用户角色
     * @param agentId 执行本次修复的 Agent 主体
     * @param delegationId 用户向 Agent 的委派标识
     * @param traceId 跨服务追踪标识
     * @param headers 完整可信 Header，用于恢复项目范围和项目角色快照
     * @return 幂等且低敏的控制面修复回执
     */
    @PostMapping("/cases/{caseId}/repairs/apply")
    public PlatformApiResponse<SyncAutopilotRecoveryRepairReceiptView> applyGovernedRepair(
            @PathVariable Long caseId,
            @RequestBody SyncAutopilotRecoveryRepairRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false)
            String representedActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false)
            String actorRole,
            @RequestHeader(value = PlatformContextHeaders.AGENT_ID, required = false)
            String agentId,
            @RequestHeader(value = PlatformContextHeaders.AGENT_DELEGATION_ID, required = false)
            String delegationId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        verifyInternalServiceToken(internalToken);
        if (caseId == null || caseId <= 0 || request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot repair request is incomplete");
        }
        SyncActorContext actor = SyncActorContextHeaderSupport.fromHeaders(
                request.tenantId(), parseActorId(representedActorId), actorRole, traceId, headers);
        SyncAutopilotRecoveryRepairReceiptView view = repairService.apply(
                new SyncAutopilotRecoveryRepairCommand(
                        caseId, request.expectedVersion(), request.tenantId(), request.projectId(),
                        request.syncTaskId(), request.executionId(), request.cycle(),
                        request.authorizationDigest(), request.policyDigest(),
                        enumValue(SyncAutopilotRecoveryAction.class, request.action(), "action"),
                        request.actionFingerprint(), request.receiptId(), request.repairParameters()),
                new SyncAutopilotRecoveryPrincipalContext(
                        representedActorId, actorRole, agentId, delegationId, traceId),
                actor);
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * 将被代理用户 Header 转换为 data-sync 领域审计使用的数字主体 ID。
     *
     * <p>普通 data-sync 用户主键是 Long。缺失或非数字主体不会在控制器里伪造默认用户，而是返回 {@code null}，
     * 随后由受治理修复服务按权限边界拒绝；这样内部服务令牌不能把损坏的主体 Header 降级成服务账号。</p>
     */
    private Long parseActorId(String representedActorId) {
        if (representedActorId == null || representedActorId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(representedActorId.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * data-sync 重新评估任务本地策略后，记录 Agent Runtime 提交的恢复候选。
     *
     * <p>请求只提供低敏范围事实、指纹、有界计数器和建议动作，不能直接作为执行权限。服务会重新加载任务、
     * 持久授权快照和引用的 execution，再创建或复用恢复 case。唯一持久副作用是 case 及其
     * {@code DECISION_RECORDED} 回执；这里不会启动 worker、发送 Kafka 消息或执行修复。响应只暴露状态和版本，
     * 不返回策略正文、证据、凭据或模型输出。</p>
     *
     * <p>相同 {@code receiptId} 与相同决策事实重复调用时会重放完成回执，因而具备幂等性；同一 ID 被不同事实
     * 复用时拒绝。内部令牌保护传输边界，服务还会重新校验租户/项目/执行范围和策略权限，内部调用方也不能建立
     * 跨租户控制路径。</p>
     *
     * @param request 包含 deadline、指纹、动作、风险和回执 ID 的低敏决策事实
     * @param internalToken Agent Runtime 服务令牌，已配置环境要求精确匹配
     * @param traceId 写入平台响应信封的跨服务关联 ID
     * @return 已持久化或重放的恢复 case 状态与乐观锁版本
     * @throws PlatformBusinessException 认证、结构、枚举、范围、策略或回执校验失败时抛出
     */
    @PostMapping("/decisions")
    public PlatformApiResponse<SyncAutopilotRecoveryCaseView> recordDecision(
            @RequestBody SyncAutopilotRecoveryDecisionRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        requireDecisionRequest(request);
        SyncAutopilotRecoveryCaseView view = caseService.recordDecision(
                new SyncAutopilotRecoveryDecisionCommand(
                        request.tenantId(),
                        request.projectId(),
                        request.syncTaskId(),
                        request.rootExecutionId(),
                        request.currentExecutionId(),
                        request.cycle(),
                        request.deadlineAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                        request.errorFingerprint(),
                        request.repeatedErrorCount(),
                        enumValue(SyncAutopilotRecoveryAction.class, request.action(), "action"),
                        enumValue(SyncAutopilotRiskLevel.class, request.riskLevel(), "riskLevel"),
                        request.repairFingerprint(),
                        request.receiptId(),
                        request.confidenceScore(),
                        request.evidenceAvailable(),
                        request.autopilotRecoveryFacts()
                ));
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * 为既有恢复 case 记录一次合法生命周期回执。
     *
     * <p>{@code caseId} 标识持久记录，{@code expectedVersion} 证明调用方观察到的版本，{@code receiptId}
     * 标识至少一次投递回调。可选 execution、轮次、错误和关注字段表示新观察事实，空值则保留已有事实。目标状态
     * 由服务端状态机决定，不由 HTTP 客户端指定，并通过条件 SQL 仲裁并发写入。</p>
     *
     * <p>相同完成回执会幂等重放；过期版本、处理中回执或同一回执被不同事实复用时产生冲突，不会二次推进状态。
     * 控制器先校验令牌，服务再校验持久范围和迁移合法性，因此调用方不能任意选择状态或跨越租户/项目边界。</p>
     *
     * @param caseId 控制面 URL 中的正数恢复 case 标识
     * @param request 乐观锁、回执和可选新增生命周期事实
     * @param internalToken 已配置环境要求的 Agent Runtime 服务令牌
     * @param traceId 写入响应信封的跨服务关联 ID
     * @return 已持久化或重放的 case 状态与乐观锁版本
     * @throws PlatformBusinessException 请求、令牌、回执、状态或乐观锁版本无效时抛出
     */
    @PostMapping("/cases/{caseId}/transitions")
    public PlatformApiResponse<SyncAutopilotRecoveryCaseView> recordTransition(
            @PathVariable Long caseId,
            @RequestBody SyncAutopilotRecoveryTransitionRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        if (caseId == null || caseId <= 0 || request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery transition request is incomplete");
        }
        SyncAutopilotRecoveryCaseView view = caseService.recordTransition(
                new SyncAutopilotRecoveryTransitionCommand(
                        caseId,
                        request.expectedVersion(),
                        request.receiptId(),
                        enumValue(SyncAutopilotRecoveryReceiptType.class,
                                request.receiptType(), "receiptType"),
                        request.currentExecutionId(),
                        request.cycle(),
                        request.errorFingerprint(),
                        request.repeatedErrorCount(),
                        request.attentionReason()
                ));
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * 持久化 Autopilot 触发消费者返回的最终低敏结果。
     *
     * <p>路径 event ID 和请求 execution ID 都是必填项，因为接受回调前必须定位原始 outbox 行。调用方不能
     * 提供摘要、载荷、模型响应、主题或新事件数据；data-sync 校验有限状态枚举、规范短原因码、自行计算摘要，
     * 并只持久化这些小型事实。这样既保留有效审计链路，也避免把 outbox 变成模型文本或原始错误存储。</p>
     *
     * <p>同一事实重复回调时返回首次持久化的低敏视图，并保留原消费时间；event ID 被不同状态、原因、case 或
     * execution 复用时失败关闭。内部令牌认证服务边界，event/execution 查询再证明回调属于本 data-sync 实例
     * 真正发出的触发器。</p>
     *
     * @param eventId Kafka 事件中的不可变触发标识
     * @param request 仅含状态、原因码、可选 case 和 execution ID 的消费结果
     * @param internalToken 已配置环境要求的 Agent Runtime 服务令牌
     * @param traceId 写入标准平台信封的跨服务关联 ID
     * @return 持久低敏消费结果视图，绝不返回原始 outbox 载荷或模型响应
     * @throws PlatformBusinessException 令牌、请求、状态、原因格式或 outbox 事实无效时抛出
     */
    @PostMapping("/triggers/{eventId}/results")
    public PlatformApiResponse<SyncAutopilotRecoveryTriggerConsumerResultView> recordTriggerConsumerResult(
            @PathVariable String eventId,
            @RequestBody SyncAutopilotRecoveryTriggerConsumerResultRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        requireConsumerResultRequest(eventId, request);
        SyncAutopilotRecoveryTriggerConsumerResultView view = consumerResultService.recordConsumerResult(
                eventId.trim(),
                new SyncAutopilotRecoveryTriggerConsumerResultCommand(
                        enumValue(SyncAutopilotRecoveryConsumerResultStatus.class, request.status(), "status"),
                        shortEnumText(request.reasonCode(), "reasonCode"),
                        request.caseId(),
                        request.currentExecutionId(),
                        optionalShortEnumText(request.retrievalDecision(), "retrievalDecision"),
                        optionalShortEnumText(request.retrievalStrategy(), "retrievalStrategy"),
                        request.retrievalEvidenceCount(),
                        normalizedEvidenceDigest(request.retrievalEvidenceDigest())
                ));
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * 仅使用 data-sync 自有持久事实，收敛一次已耗尽 Agent Runtime Kafka 投递的事件。
     *
     * <p>调用方只提供 event ID 和原始 execution ID，不能选择 case、目标状态、回执、原因或错误描述。服务令牌
     * 认证后，死信服务解析原始 outbox 和精确决策回执，通过正常状态机推进可执行 case，并记录或重放低敏触发
     * 结果。成功返回告诉 DLT 处理器：该记录已经由持久控制面状态表达，可以安全提交。</p>
     *
     * @param eventId 从原始 Kafka 触发器复制的不可变标识
     * @param request 只包含原始 current execution ID 的请求体
     * @param internalToken 已配置的 Agent Runtime 服务凭据
     * @param traceId 可选跨服务追踪标识
     * @return DLT 收敛后的持久触发结果视图
     */
    @PostMapping("/triggers/{eventId}/dead-letter")
    public PlatformApiResponse<SyncAutopilotRecoveryTriggerConsumerResultView> recordTriggerDeadLetter(
            @PathVariable String eventId,
            @RequestBody SyncAutopilotRecoveryDeadLetterRequest request,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false)
            String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        verifyInternalServiceToken(internalToken);
        if (eventId == null || eventId.isBlank() || eventId.trim().length() > 96
                || request == null || request.currentExecutionId() == null
                || request.currentExecutionId() <= 0) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "Autopilot dead-letter trigger request is incomplete");
        }
        SyncAutopilotRecoveryTriggerConsumerResultView view = deadLetterService.recordDeadLettered(
                eventId.trim(), request.currentExecutionId());
        return PlatformApiResponse.success(view, traceId);
    }

    /**
     * 在内部服务间边界校验凭据，同时不暴露凭据数据。
     *
     * <p>使用 {@link MessageDigest#isEqual(byte[], byte[])} 比较请求头与
     * {@code DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN}，避免提前退出比较泄露时序信息。配置令牌缺失或
     * 为空同样视为认证失败，防止配置不完整的部署静默把内部写接口变成未认证端点。校验只读配置、不写状态，
     * 对相同输入幂等，也不会记录或返回任何一侧令牌。</p>
     *
     * @param suppliedToken 内部请求头携带的令牌；已配置环境中空值按空字符串比较
     * @throws PlatformBusinessException 已配置认证失败时以 {@code FORBIDDEN} 抛出
     */
    private void verifyInternalServiceToken(String suppliedToken) {
        String expectedToken = internalServiceToken == null ? "" : internalServiceToken.trim();
        if (expectedToken.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot recovery internal service authentication is not configured");
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Autopilot recovery internal service authentication failed");
        }
    }

    /**
     * 拒绝缺少受治理持久命令所需事实的决策 DTO。
     *
     * <p>该浅层边界检查只确认身份、deadline 和幂等回执是否存在，不授予权限，也不重复后续层负责的枚举、
     * 策略和范围校验。方法纯函数且可重复，使格式错误输入在任何持久化开始前得到 {@code BAD_REQUEST}，
     * 而不是稍后出现含义不明的空指针错误。</p>
     *
     * @param request 待检查的已反序列化内部决策 DTO
     * @throws PlatformBusinessException 必填身份、deadline 或回执字段缺失时抛出
     */
    private void requireDecisionRequest(SyncAutopilotRecoveryDecisionRequest request) {
        if (request == null || request.deadlineAt() == null || request.tenantId() == null
                || request.syncTaskId() == null || request.rootExecutionId() == null
                || request.currentExecutionId() == null || request.receiptId() == null
                || request.receiptId().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery decision request is incomplete");
        }
    }

    /**
     * 在消费结果回调到达持久 outbox 服务前拒绝不完整请求。
     *
     * <p>这里只检查传输结构和数字身份，不判断事件是否存在、重复事实是否相同或某结果能否替换另一结果；这些
     * 判断需要数据库行，仍由服务层负责。把结构校验放在 HTTP 边界，可让格式错误 JSON 稳定返回 BAD_REQUEST，
     * 而不是稍后暴露 mapper 错误。</p>
     *
     * @param eventId 预期标识一条有界 outbox 记录的路径值
     * @param request 已反序列化的低敏回调请求体
     * @throws PlatformBusinessException 必填字段空白、缺失、过长或非正数时抛出
     */
    private void requireConsumerResultRequest(
            String eventId,
            SyncAutopilotRecoveryTriggerConsumerResultRequest request) {
        if (eventId == null || eventId.isBlank() || eventId.trim().length() > 96
                || request == null || request.status() == null || request.status().isBlank()
                || request.reasonCode() == null || request.reasonCode().isBlank()
                || request.currentExecutionId() == null || request.currentExecutionId() <= 0
                || (request.caseId() != null && request.caseId() <= 0)
                || (request.retrievalEvidenceCount() != null && request.retrievalEvidenceCount() < 0)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot trigger consumer result request is incomplete");
        }
    }

    /**
     * 把协议枚举字符串转换为服务端已知枚举，拒绝任意值。
     *
     * <p>只为方便内部调用方使用文档名称而规范连字符和大小写；最终
     * {@link Enum#valueOf(Class, String)} 查询仍是严格白名单。该辅助方法纯函数且幂等，不改变生命周期；
     * 格式错误文本映射为 {@code BAD_REQUEST}，不会暴露 Java 异常或接收无类型状态/动作值。</p>
     *
     * @param enumType 控制器选择的可信枚举类
     * @param value 内部调用方提供的传输格式枚举值
     * @param fieldName 安全校验错误中包含的字段名
     * @param <T> 服务端控制的具体枚举类型
     * @return 已验证枚举常量
     * @throws PlatformBusinessException 值缺失或不受支持时抛出
     */
    private <T extends Enum<T>> T enumValue(Class<T> enumType, String value, String fieldName) {
        try {
            return Enum.valueOf(enumType, value == null
                    ? ""
                    : value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (RuntimeException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Unsupported Autopilot recovery " + fieldName);
        }
    }

    /**
     * 规范并校验紧凑原因码，不允许任意自然语言正文。
     *
     * <p>消费者集成可以增加稳定原因码，无需 data-sync 持久化模型解释。允许语法有意窄于自由文本：只接受
     * 大写字母、数字和下划线，最多 96 字符。连字符和大小写按状态规则规范，但空白、JSON 片段、SQL、提示词
     * 和异常正文仍会被拒绝。</p>
     *
     * @param value 内部消费者提供的传输格式原因码
     * @param fieldName 校验错误中使用的安全字段标签
     * @return 适合数据库约束和摘要的规范短枚举式代码
     * @throws PlatformBusinessException 值缺失或不是紧凑枚举式代码时抛出
     */
    private String shortEnumText(String value, String fieldName) {
        String normalized = value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,95}")) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Unsupported Autopilot recovery " + fieldName);
        }
        return normalized;
    }

    /**
     * 规范可选检索短代码，同时避免在规划完成前伪造决策。
     *
     * <p>格式错误 JSON 或授权拒绝可能发生在模型 turn 之前，因此 null 具有明确含义。非空值使用与其他回调码
     * 相同的有界语法；服务层随后结合证据数量和摘要校验 SEARCH/SKIP 关系。</p>
     */
    private String optionalShortEnumText(String value, String fieldName) {
        return value == null ? null : shortEnumText(value, fieldName);
    }

    /**
     * 只接受公开 {@code sha256:} 证据摘要格式，并统一十六进制大小写。
     *
     * <p>摘要是完整性指针，不是证据正文。SKIP 或规划前拒绝继续保留 null；任何非空但格式错误的值都会在
     * 持久化前于 HTTP 边界失败。</p>
     */
    private String normalizedEvidenceDigest(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Unsupported Autopilot recovery retrievalEvidenceDigest");
        }
        return normalized;
    }
}
