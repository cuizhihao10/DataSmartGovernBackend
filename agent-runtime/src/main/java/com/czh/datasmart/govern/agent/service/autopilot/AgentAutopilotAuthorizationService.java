/**
 * @Author : Cui
 * @Date: 2026/08/10 16:10
 * @Description DataSmart Govern Backend - AgentAutopilotAuthorizationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.controller.dto.AgentAutopilotPolicyRequest;
import com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 创建首次确认的持久、可验证且不能被模型扩大的 Autopilot 授权。 */
@Service
public class AgentAutopilotAuthorizationService {

    public static final String POLICY_VERSION = "datasmart.autopilot.authorization.v1";
    /**
     * 首次创建 Autopilot 授权快照时，在用户没有主动选择恢复动作时使用的保守默认集合。
     *
     * <p>默认集合故意小于平台动作目录：缺失 {@code allowedRecoveryActions} 或传入空数组只能得到
     * {@code RETRY_EXECUTION} 与 {@code APPLY_QUARANTINE}，不会因为客户端遗漏字段而自动获得后续新增的
     * 高级动作。这条规则把“平台认识某个动作”和“本次授权已经同意该动作”分开，避免配置升级或请求反序列化
     * 差异造成静默扩权。</p>
     *
     * <p>需要 checkpoint、分片、元数据或字段映射等高级恢复能力时，调用方必须在请求中显式列出对应业务码；
     * 随后仍由完整动作目录和其他策略校验共同决定是否可写入授权快照。</p>
     */
    public static final List<String> DEFAULT_AUTOMATIC_ACTIONS = List.of(
            "RETRY_EXECUTION",
            "APPLY_QUARANTINE");

    /**
     * 平台当前可被治理层识别、校验并在用户显式请求后写入授权快照的完整自动恢复动作目录。
     *
     * <p>该集合不是默认授权清单。它列出八个当前业务码，供 {@link #validatedActions(List, Set, List, String)}
     * 拒绝未知动作，并允许用户明确申请完整目录或其中的任意受支持子集。默认逻辑始终读取
     * {@link #DEFAULT_AUTOMATIC_ACTIONS}，因此目录扩充不会反向扩大已有调用的默认权限。</p>
     */
    public static final Set<String> AUTOMATIC_ACTION_ALLOWLIST = Set.of(
            "RETRY_EXECUTION",
            "APPLY_QUARANTINE",
            "ROLLBACK_EXECUTION_POLICY",
            "TUNE_EXECUTION_POLICY",
            "REFRESH_METADATA",
            "RESUME_FROM_CHECKPOINT",
            "REPLAY_FAILED_SHARDS",
            "REPAIR_FIELD_MAPPING");
    public static final Set<String> APPROVAL_ACTION_ALLOWLIST = Set.of(
            "CHANGE_SCHEMA",
            "CHANGE_CREDENTIAL",
            "DELETE_DATA",
            "OVERWRITE_TARGET",
            "EXPAND_DATA_SCOPE"
    );

    /**
     * 根据首次确认过的 Agent 会话和可选策略请求，创建一份不能由模型扩大权限的 Autopilot 授权快照。
     *
     * <p>输入是已经由上层认证的 {@code session}、本次根运行标识和用户提交的策略；{@code request} 可以为
     * {@code null}，此时使用受平台白名单约束的保守默认值。输出是不可变的低敏授权事实，调用方随后会把它
     * 持久化到 run 和同步任务定义中；本方法本身不写数据库、不调用下游服务，也不启动恢复动作。</p>
     *
     * <p>权限边界在这里收紧而不是放宽：自动动作和需审批动作只能取自各自的平台白名单，最大自动风险固定为
     * {@code LOW}。{@code policyDigest} 对归一化后的关键字段提供可复算的完整性证据，但它不是新的审批事实
     * 或签名。每次首次确认都会生成新的 {@code policyId}，因此本方法不以相同输入复用旧授权；恢复链路的
     * 幂等性由后来持久化的授权和 receipt 处理。</p>
     *
     * @param session 已认证且包含有效 delegation 的 Agent 会话
     * @param rootRunId 要绑定该授权的根运行标识，不能为空
     * @param request 用户确认的策略；为空时采用受限默认策略
     * @return 可持久化并供后续 Kafka 恢复重新校验的授权快照
     * @throws IllegalArgumentException 当会话、运行标识、动作、风险等级、数值边界或过期时间不符合治理规则时
     */
    public AgentAutopilotAuthorizationSnapshot authorize(AgentSessionRecord session,
                                                         String rootRunId,
                                                         AgentAutopilotPolicyRequest request) {
        if (session == null || session.getDelegation() == null) {
            throw new IllegalArgumentException("Autopilot 授权必须绑定可信 Agent 会话和委派");
        }
        if (session.getApplicationId() == null || session.getApplicationId() <= 0) {
            throw new IllegalArgumentException("Autopilot 授权缺少 applicationId");
        }
        String mode = code(request == null ? null : request.executionMode(), "AUTOPILOT");
        if (!"AUTOPILOT".equals(mode)) {
            throw new IllegalArgumentException("executionMode 仅支持 AUTOPILOT");
        }
        int maxCycles = bounded(request == null ? null : request.maxRecoveryCycles(), 5, 1, 10,
                "maxRecoveryCycles");
        int durationMinutes = bounded(request == null ? null : request.maxTotalDurationMinutes(), 120, 5, 1440,
                "maxTotalDurationMinutes");
        String maxRisk = code(request == null ? null : request.maxAutomaticRiskLevel(), "LOW");
        if (!"LOW".equals(maxRisk)) {
            throw new IllegalArgumentException("自动执行最大风险等级只能是 LOW");
        }
        List<String> automaticActions = validatedActions(
                request == null ? null : request.allowedRecoveryActions(),
                AUTOMATIC_ACTION_ALLOWLIST,
                DEFAULT_AUTOMATIC_ACTIONS,
                "allowedRecoveryActions");
        List<String> approvalActions = validatedActions(
                request == null ? null : request.requireApprovalFor(),
                APPROVAL_ACTION_ALLOWLIST,
                List.copyOf(APPROVAL_ACTION_ALLOWLIST),
                "requireApprovalFor");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = request == null || request.expiresAt() == null
                ? now.plusDays(30)
                : request.expiresAt().withOffsetSameInstant(ZoneOffset.UTC);
        if (!expiresAt.isAfter(now.plusMinutes(5)) || expiresAt.isAfter(now.plusDays(366))) {
            throw new IllegalArgumentException("Autopilot 授权到期时间必须在 5 分钟后且不超过 366 天");
        }
        AgentDelegationRecord delegation = session.getDelegation();
        String policyId = "aap_" + UUID.randomUUID().toString().replace("-", "");
        String digestMaterial = String.join("|",
                POLICY_VERSION, policyId, session.getSessionId(), rootRunId,
                String.valueOf(session.getTenantId()), String.valueOf(session.getApplicationId()),
                String.valueOf(session.getProjectId()), session.getActorId(), session.getAgentId(),
                delegation.getDelegationId(), String.valueOf(maxCycles), String.valueOf(durationMinutes),
                String.join(",", automaticActions), String.join(",", approvalActions), expiresAt.toString());
        return new AgentAutopilotAuthorizationSnapshot(
                policyId, POLICY_VERSION, "ACTIVE", session.getSessionId(), required(rootRunId, "rootRunId"),
                session.getTenantId(), session.getApplicationId(), session.getProjectId(),
                delegation.getUserActorId(), session.getActorId(), session.getAgentId(), delegation.getDelegationId(),
                maxCycles, durationMinutes, maxRisk, automaticActions, approvalActions,
                now, expiresAt, "sha256:" + sha256(digestMaterial));
    }

    /**
     * 归一化一个动作列表，并确认每项都位于调用场景允许的平台白名单中。
     *
     * <p>当输入列表为 {@code null} 或为空时，只使用调用方传入的 {@code defaults}，绝不以
     * {@code platformAllowlist} 作为兜底值。前者表示本次请求默认可授予的最小权限，后者只是平台能够识别
     * 的完整目录；混用两者会让一个缺失字段的请求意外获得全部高级动作。输出会保持首次出现的顺序、去除
     * 重复项并冻结，便于将同一授权事实稳定地写入快照。</p>
     *
     * <p>该方法没有副作用，却是权限收口点：它不会接受模型、客户端或配置传来的未知动作。列表内容会进入
     * {@code policyDigest}，所以归一化也为后续完整性校验提供证据。</p>
     *
     * @param requested 用户请求的动作列表，可为空
     * @param platformAllowlist 当前策略种类可接受的全部动作
     * @param defaults 没有显式请求动作时使用的安全默认列表
     * @param fieldName 用于异常信息的请求字段名
     * @return 无重复、已规范化且不可修改的动作列表
     * @throws IllegalArgumentException 当任一动作为空、格式非法或不在白名单内时
     */
    private List<String> validatedActions(List<String> requested,
                                          Set<String> platformAllowlist,
                                          List<String> defaults,
                                          String fieldName) {
        List<String> source = requested == null || requested.isEmpty() ? defaults : requested;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String action = code(value, null);
            if (action == null || !platformAllowlist.contains(action)) {
                throw new IllegalArgumentException(fieldName + " 包含未授权动作: " + action);
            }
            normalized.add(action);
        }
        return List.copyOf(normalized);
    }

    /**
     * 读取一个可选整数并限制在治理规定的闭区间内。
     *
     * <p>输入缺失时返回 {@code fallback}，不会修改任何状态。该限制避免调用方把循环次数或总时长扩大为
     * 无界恢复窗口，是授权权限的一部分，而不是业务重试计数。数值本身会进入授权摘要，因而是可审计证据。</p>
     *
     * @param value 用户提供的值，可为空
     * @param fallback 缺失值的安全默认值
     * @param min 允许的最小值
     * @param max 允许的最大值
     * @param fieldName 用于异常信息的字段名
     * @return 通过边界校验的最终整数
     * @throws IllegalArgumentException 当最终值落在允许范围之外时
     */
    private int bounded(Integer value, int fallback, int min, int max, String fieldName) {
        int resolved = value == null ? fallback : value;
        if (resolved < min || resolved > max) {
            throw new IllegalArgumentException(fieldName + " 必须位于 " + min + " 到 " + max);
        }
        return resolved;
    }

    /**
     * 将动作、模式或风险等级等枚举式输入转换为统一的大写下划线编码。
     *
     * <p>空白输入返回调用方给定的 {@code fallback}；非空输入会去除首尾空白、替换连字符并校验格式。
     * 该纯函数不授予权限，但保证白名单比较和摘要材料不会因为大小写差异产生两种授权证据。</p>
     *
     * @param value 待规范化的外部文本，可为空
     * @param fallback 空白输入的替代值，可为空
     * @return 合法的规范化编码，或 {@code fallback}
     * @throws IllegalArgumentException 当非空输入不符合受限编码格式时
     */
    private String code(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException("策略编码格式非法");
        }
        return normalized;
    }

    /**
     * 校验并返回一个不能缺失的标识文本。
     *
     * <p>该方法只做本地校验和去空白，不产生副作用。根运行标识等字段参与授权范围和摘要计算，若允许空值，
     * 后续验证将无法证明授权属于哪一条运行记录，因此立即拒绝。</p>
     *
     * @param value 待检查的文本
     * @param name 用于异常信息的字段名
     * @return 去除首尾空白后的非空文本
     * @throws IllegalArgumentException 当文本为空或只包含空白时
     */
    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 为稳定的授权材料计算小写十六进制 SHA-256 摘要。
     *
     * <p>输出用于检测后续恢复事件中的授权字段是否与首次确认内容一致，不承担加密、签名或权限授予职责。
     * 计算没有 I/O 或持久化副作用，对相同 UTF-8 输入始终返回相同结果，因此可被不同服务幂等地复算。</p>
     *
     * @param value 已按固定顺序拼接的授权材料
     * @return 64 个十六进制字符组成的 SHA-256 摘要
     * @throws IllegalStateException 当运行 JDK 缺少必需的 SHA-256 算法时
     */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }
}
