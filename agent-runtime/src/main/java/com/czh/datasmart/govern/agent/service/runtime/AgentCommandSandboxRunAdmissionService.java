/**
 * @Author : Cui
 * @Date: 2026/06/24 23:59
 * @Description DataSmart Govern Backend - AgentCommandSandboxRunAdmissionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandSandboxRunAdmissionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandSandboxRunAdmissionResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * command sandbox run 准入服务。
 *
 * <p>该服务是 command durable action 从“控制面回执合同”走向“真实 sandbox runner”的收敛点。
 * 它当前仍不执行命令、不启动进程、不读取 payloadReference，也不访问对象存储；它只在 worker 真正进入执行区前，
 * 统一校验 Java Host 必须掌握的低敏事实：</p>
 *
 * <p>1. 当前 worker 是否仍然持有有效 command lease；</p>
 * <p>2. 命令安全决策是否允许受控执行；</p>
 * <p>3. workspace 引用是否是低敏受控引用，而不是本机路径或 URL；</p>
 * <p>4. 超时、输出、CPU、内存预算是否被裁剪到平台上限；</p>
 * <p>5. 响应是否能作为 worker 后续 receipt/output-sanitization/artifact gate 的低敏执行合同。</p>
 *
 * <p>这样设计的原因是：真实 Agent 工具执行最危险的地方不是“能不能启动进程”，而是启动前缺少统一治理。
 * 如果 Python runner、容器 runner 或 task-management worker 各自判断安全边界，后续很容易出现绕过审批、旧 lease
 * 写回、输出泄露、workspace 越权等事故。sandbox admission 把这些准入条件前置到 Java 控制面，后续执行器只负责
 * 按合同运行和回写低敏事实。</p>
 */
@Service
public class AgentCommandSandboxRunAdmissionService {

    private static final String PAYLOAD_POLICY =
            "ADMISSION_ONLY_NO_COMMAND_LINE_NO_STDIO_NO_TOOL_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY";
    private static final String ALLOW_CONTROLLED_EXECUTION = "ALLOW_CONTROLLED_EXECUTION";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_OUTPUT_LIMIT_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_LIMIT_BYTES = 256 * 1024;
    private static final int DEFAULT_CPU_MILLICORES = 500;
    private static final int MAX_CPU_MILLICORES = 4_000;
    private static final int DEFAULT_MEMORY_MB = 512;
    private static final int MAX_MEMORY_MB = 2_048;
    private static final Pattern SAFE_REFERENCE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.:/=@+-]{1,260}$");
    private static final Pattern SAFE_MACHINE_CODE_PATTERN = Pattern.compile("^[A-Z0-9_.:-]{1,120}$");
    private static final Set<String> ALLOWED_ISOLATION_MODES = Set.of(
            "NO_NETWORK_PROCESS_SANDBOX",
            "PROCESS_SANDBOX",
            "CONTAINER_SANDBOX"
    );

    private final AgentCommandWorkerLeaseService leaseService;
    private final Clock clock;

    /**
     * Spring 运行时构造器。
     *
     * <p>command sandbox 准入服务在单元测试中需要注入固定 {@link Clock} 来稳定 sandboxRunId
     * 与时间相关断言，因此类内保留了一个包级测试构造器。真实应用启动时必须使用该 public 构造器，
     * 从容器拿到 worker lease 服务后再进入准入校验流程；显式标注 {@link Autowired} 可以避免
     * Spring 在多构造器场景下误判为需要无参构造器。</p>
     */
    @Autowired
    public AgentCommandSandboxRunAdmissionService(AgentCommandWorkerLeaseService leaseService) {
        this(leaseService, Clock.systemUTC());
    }

    AgentCommandSandboxRunAdmissionService(AgentCommandWorkerLeaseService leaseService, Clock clock) {
        this.leaseService = leaseService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 评估 command 是否可以进入 sandbox run。
     *
     * <p>该方法的返回值有两类：输入非法或 lease 证据不匹配时抛出业务异常；安全决策、workspace 等业务前置条件不满足时，
     * 返回 accepted=false 的低敏响应。这样区分是为了让调用方能判断“请求本身不可信”与“请求可信但当前不能执行”。</p>
     */
    public AgentCommandSandboxRunAdmissionResponse admit(String sessionId,
                                                         String runId,
                                                         AgentCommandSandboxRunAdmissionRequest request) {
        if (request == null) {
            throw badRequest("command sandbox run 准入请求不能为空");
        }
        AgentCommandWorkerLeaseRecord lease = leaseService.requireCurrentLeaseEvidence(
                sessionId,
                runId,
                request.commandId(),
                request.executorId(),
                request.fencingToken(),
                request.workerLeaseVersion(),
                request.workerLeaseExpiresAtMs(),
                "sandbox-admission"
        );
        assertScopeAligned(request, lease);

        String isolationMode = normalizeIsolationMode(request.requestedIsolationMode());
        int timeoutSeconds = normalizePositiveBudget(
                request.requestedTimeoutSeconds(),
                DEFAULT_TIMEOUT_SECONDS,
                MAX_TIMEOUT_SECONDS
        );
        int outputLimitBytes = normalizePositiveBudget(
                request.requestedOutputByteLimitBytes(),
                DEFAULT_OUTPUT_LIMIT_BYTES,
                MAX_OUTPUT_LIMIT_BYTES
        );
        int cpuMillicores = normalizePositiveBudget(
                request.requestedCpuMillicores(),
                DEFAULT_CPU_MILLICORES,
                MAX_CPU_MILLICORES
        );
        int memoryMb = normalizePositiveBudget(request.requestedMemoryMb(), DEFAULT_MEMORY_MB, MAX_MEMORY_MB);
        List<String> issueCodes = new ArrayList<>();
        List<String> evidenceCodes = new ArrayList<>();
        evidenceCodes.add("CURRENT_WORKER_LEASE_VERIFIED");
        evidenceCodes.add("RAW_COMMAND_NOT_ACCEPTED");
        evidenceCodes.add("PROCESS_NOT_STARTED_BY_HOST");
        addBudgetEvidence(evidenceCodes, request, timeoutSeconds, outputLimitBytes, cpuMillicores, memoryMb);

        String workspaceReference = normalizeWorkspaceReference(request.workspaceReference(), issueCodes, evidenceCodes);
        String safetyDecision = normalizeMachineCode(request.commandSafetyDecision(), "commandSafetyDecision");
        List<String> safetyIssueCodes = normalizeIssueCodes(request.commandSafetyIssueCodes());
        if (!ALLOW_CONTROLLED_EXECUTION.equals(safetyDecision)) {
            issueCodes.add("COMMAND_SAFETY_DECISION_NOT_ALLOW");
        }
        issueCodes.addAll(safetyIssueCodes);
        validateLowSensitiveOptional(request.commandSafetyPolicyVersion(), "commandSafetyPolicyVersion");
        validateLowSensitiveOptional(request.toolCode(), "toolCode");
        validateLowSensitiveOptional(request.requesterComponent(), "requesterComponent");
        validateLowSensitiveOptional(request.idempotencyKey(), "idempotencyKey");

        boolean accepted = issueCodes.isEmpty();
        String decision = accepted ? "ADMITTED_FOR_SANDBOX_EXECUTION" : denialDecision(issueCodes);
        String sandboxRunId = accepted ? sandboxRunId(sessionId, runId, request, lease, clock.instant()) : null;
        if (accepted) {
            evidenceCodes.add("SANDBOX_RUN_ID_LOW_SENSITIVE");
            evidenceCodes.add("WORKSPACE_REFERENCE_VERIFIED");
        }
        return new AgentCommandSandboxRunAdmissionResponse(
                accepted,
                decision,
                sandboxRunId,
                lease.commandId(),
                lease.executorId(),
                lease.leaseVersion(),
                lease.leaseExpiresAt().toEpochMilli(),
                longValue(lease.tenantId(), request.tenantId()),
                longValue(lease.projectId(), request.projectId()),
                longValue(lease.actorId(), request.actorId()),
                isolationMode,
                timeoutSeconds,
                outputLimitBytes,
                cpuMillicores,
                memoryMb,
                workspaceReference,
                PAYLOAD_POLICY,
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                recommendedActions(accepted),
                false,
                false,
                false
        );
    }

    private void assertScopeAligned(AgentCommandSandboxRunAdmissionRequest request,
                                    AgentCommandWorkerLeaseRecord lease) {
        assertLongAligned(request.tenantId(), lease.tenantId(), "tenantId");
        assertLongAligned(request.projectId(), lease.projectId(), "projectId");
        assertLongAligned(request.actorId(), lease.actorId(), "actorId");
    }

    private void assertLongAligned(Long requestValue, String leaseValue, String fieldName) {
        if (requestValue == null || leaseValue == null || leaseValue.isBlank()) {
            return;
        }
        if (!String.valueOf(requestValue).equals(leaseValue)) {
            throw badRequest(fieldName + " 与当前 command worker lease fact 不一致，拒绝 sandbox run 准入");
        }
    }

    private String normalizeIsolationMode(String value) {
        String mode = trimToNull(value);
        if (mode == null) {
            return "NO_NETWORK_PROCESS_SANDBOX";
        }
        String normalized = mode.toUpperCase(Locale.ROOT).replace('-', '_');
        if (!ALLOWED_ISOLATION_MODES.contains(normalized)) {
            throw badRequest("requestedIsolationMode 只能使用低敏受控隔离模式，不能携带命令、路径、URL 或参数正文");
        }
        return normalized;
    }

    private String normalizeWorkspaceReference(String value,
                                               List<String> issueCodes,
                                               List<String> evidenceCodes) {
        String reference = trimToNull(value);
        if (reference == null) {
            issueCodes.add("MISSING_WORKSPACE_REFERENCE");
            return null;
        }
        if (looksSensitive(reference) || !SAFE_REFERENCE_PATTERN.matcher(reference).matches()) {
            throw badRequest("workspaceReference 必须是低敏受控引用，不能是 URL、本机路径、对象正文或凭据");
        }
        String lower = reference.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("agent-workspace:")
                || lower.startsWith("workspace:")
                || lower.startsWith("sandbox-workspace:"))) {
            issueCodes.add("UNSUPPORTED_WORKSPACE_REFERENCE");
            return reference;
        }
        evidenceCodes.add("WORKSPACE_REFERENCE_LOW_SENSITIVE");
        return reference;
    }

    private List<String> normalizeIssueCodes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String code = normalizeMachineCode(value, "commandSafetyIssueCodes");
            if (code != null) {
                result.add(code);
            }
        }
        return List.copyOf(result);
    }

    private String normalizeMachineCode(String value, String fieldName) {
        String code = trimToNull(value);
        if (code == null) {
            return null;
        }
        String normalized = code.toUpperCase(Locale.ROOT).replace('-', '_');
        if (looksSensitive(normalized) || !SAFE_MACHINE_CODE_PATTERN.matcher(normalized).matches()) {
            throw badRequest(fieldName + " 只能使用低敏机器码，不能携带正文、路径、URL、SQL、prompt 或凭据");
        }
        return normalized;
    }

    private void validateLowSensitiveOptional(String value, String fieldName) {
        String text = trimToNull(value);
        if (text != null && (looksSensitive(text) || !SAFE_REFERENCE_PATTERN.matcher(text).matches())) {
            throw badRequest(fieldName + " 只能使用低敏引用或机器标识，不能携带正文、路径、URL、SQL、prompt 或凭据");
        }
    }

    private int normalizePositiveBudget(Integer value, int defaultValue, int maxValue) {
        int parsed = value == null || value <= 0 ? defaultValue : value;
        return Math.min(parsed, maxValue);
    }

    private void addBudgetEvidence(List<String> evidenceCodes,
                                   AgentCommandSandboxRunAdmissionRequest request,
                                   int timeoutSeconds,
                                   int outputLimitBytes,
                                   int cpuMillicores,
                                   int memoryMb) {
        evidenceCodes.add(request.requestedTimeoutSeconds() != null
                && request.requestedTimeoutSeconds() > timeoutSeconds
                ? "TIMEOUT_BUDGET_CAPPED" : "TIMEOUT_BUDGET_NORMALIZED");
        evidenceCodes.add(request.requestedOutputByteLimitBytes() != null
                && request.requestedOutputByteLimitBytes() > outputLimitBytes
                ? "OUTPUT_BUDGET_CAPPED" : "OUTPUT_BUDGET_NORMALIZED");
        evidenceCodes.add(request.requestedCpuMillicores() != null
                && request.requestedCpuMillicores() > cpuMillicores
                ? "CPU_BUDGET_CAPPED" : "CPU_BUDGET_NORMALIZED");
        evidenceCodes.add(request.requestedMemoryMb() != null
                && request.requestedMemoryMb() > memoryMb
                ? "MEMORY_BUDGET_CAPPED" : "MEMORY_BUDGET_NORMALIZED");
    }

    private String denialDecision(List<String> issueCodes) {
        if (issueCodes.contains("MISSING_WORKSPACE_REFERENCE")
                || issueCodes.contains("UNSUPPORTED_WORKSPACE_REFERENCE")) {
            return "DENIED_BY_WORKSPACE_POLICY";
        }
        return "DENIED_BY_COMMAND_SAFETY";
    }

    private List<String> recommendedActions(boolean accepted) {
        if (accepted) {
            return List.of(
                    "按准入合同启动受控 sandbox，不要把命令正文写入日志或事件",
                    "输出写入 artifact 前必须调用 command-worker-output-sanitizations",
                    "执行结束后携带同一 lease 证据写回 command-worker-receipts"
            );
        }
        return List.of(
                "不要启动 sandbox 进程",
                "先补齐 workspace 或安全审批事实，再重新请求 sandbox admission",
                "如命令无法恢复，应通过 outbox dead-letter 或 ignore 补偿"
        );
    }

    private String sandboxRunId(String sessionId,
                                String runId,
                                AgentCommandSandboxRunAdmissionRequest request,
                                AgentCommandWorkerLeaseRecord lease,
                                Instant now) {
        String seed = sessionId + ":" + runId + ":" + lease.commandId() + ":" + lease.executorId()
                + ":" + lease.leaseVersion() + ":" + nullToEmpty(request.idempotencyKey())
                + ":" + now.toEpochMilli();
        return "sandbox-run:sha256:" + sha256Hex(seed).substring(0, 24);
    }

    private Long longValue(String leaseValue, Long requestValue) {
        String text = trimToNull(leaseValue);
        if (text == null) {
            return requestValue;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return requestValue;
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean looksSensitive(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("update ")
                || lower.contains("delete ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("secret")
                || lower.contains("credential")
                || lower.contains("api_key")
                || lower.contains("prompt:")
                || lower.contains("stdout")
                || lower.contains("stderr")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }

    private PlatformBusinessException badRequest(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
