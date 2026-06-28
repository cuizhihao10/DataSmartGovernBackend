/**
 * @Author : Cui
 * @Date: 2026/06/28 19:20
 * @Description DataSmart Govern Backend - AgentToolActionPayloadMaterializationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具动作 payload body 物化服务。
 *
 * <p>该服务专门处理“Host 控制面已经生成低敏结果，但不希望把结果正文反复复制到事件、日志、审批单或 outbox”
 * 的场景。典型例子是 `quality.remediation.task.draft`：data-quality dry-run 会返回治理任务草案，
 * 这些草案可用于审批确认，但不应该作为普通工具输出在每条 runtime event 和查询响应里重复展开。</p>
 *
 * <p>职责拆分说明：</p>
 * <p>1. {@link AgentToolActionPayloadStoreService} 负责 envelope 登记、引用校验和 writer/verifier 复核；</p>
 * <p>2. 本服务负责把“已经由 Java Host 收口过的低敏正文”写入 {@link AgentToolActionPayloadStore}；</p>
 * <p>3. 业务 adapter 只拿到 {@code payloadReference}、大小和策略字段，并把它们放入低敏工具输出。</p>
 *
 * <p>这种拆分可以避免一个 service 同时承担 writer、verifier、adapter materializer 三类职责，也能让单文件行数
 * 保持在可阅读范围内。后续如果将 payload body 改为 MySQL/Redis/MinIO/KMS 存储，只需要替换 store 实现或本服务，
 * 不需要修改质量工具 adapter 和 outbox writer 的主流程。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionPayloadMaterializationService {

    private static final String AGENT_PAYLOAD_PREFIX = "agent-payload:";
    private static final Duration DEFAULT_PAYLOAD_TTL = Duration.ofHours(2);

    private final AgentToolActionPayloadStore payloadStore;

    /**
     * 构造 `agent-payload:` 受控引用。
     *
     * <p>引用只允许由 runId 和 payloadKey 两段安全字符串组成，严禁把 prompt、SQL、样本值、完整参数、凭据、
     * 内部 endpoint 或模型输出写进引用本身。这样引用可以安全进入审批页、runtime event、outbox command、
     * 查询投影和日志，而真正正文仍保留在服务端 store。</p>
     *
     * @param runId 当前 Agent run ID，用于把载荷限制在一次运行内。
     * @param payloadKey run 内部的载荷用途键，例如 `quality-remediation-task-draft:audit-001`。
     * @return 合法时返回 `agent-payload:{runId}/{payloadKey}`，非法时返回空。
     */
    public Optional<String> buildPayloadReference(String runId, String payloadKey) {
        String safeRunId = safeText(runId);
        String safePayloadKey = safeText(payloadKey);
        if (safeRunId == null || safePayloadKey == null || !safeSegment(safeRunId) || !safeSegment(safePayloadKey)) {
            return Optional.empty();
        }
        return Optional.of(AGENT_PAYLOAD_PREFIX + safeRunId + "/" + safePayloadKey);
    }

    /**
     * 物化已低敏处理的 payload body。
     *
     * <p>调用方必须先完成业务语义层面的低敏收口。本服务只做通用控制面保护：</p>
     * <p>1. 校验 `agent-payload:` 引用格式和 runId 绑定，防止跨 run 复用正文；</p>
     * <p>2. 复制非空字段，避免调用方后续修改 Map 影响已经登记的服务端事实；</p>
     * <p>3. 写入 tenant/project/actor/tool/graph/contract/policy 元数据，供 verifier 和 executor 再次复核；</p>
     * <p>4. 设置短 TTL，避免审批页或 Agent loop 在很久之后误执行旧草案。</p>
     *
     * <p>注意：本服务不会尝试理解业务 payload，也不会扫描其中是否有 SQL 或样本值。低敏责任必须在更靠近业务
     * 的 adapter/mapper 中完成，因为只有业务层知道哪些字段可以给审批页看，哪些字段必须留在内部或彻底丢弃。</p>
     *
     * @param request payload body 物化请求。
     * @return 服务端登记记录；引用非法、runId 不匹配或 body 为空时返回空。
     */
    public Optional<AgentToolActionPayloadRecord> materializePayloadBody(
            AgentToolActionPayloadMaterializationRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String reference = safeText(request.payloadReference());
        if (reference == null || !reference.startsWith(AGENT_PAYLOAD_PREFIX)) {
            return Optional.empty();
        }
        ParsedAgentPayloadReference parsed = parse(reference);
        if (!parsed.valid() || !safeEquals(parsed.runId(), request.runId())) {
            return Optional.empty();
        }
        Map<String, Object> body = lowSensitiveBody(request.payloadBody());
        if (body.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Duration ttl = request.ttl() == null ? DEFAULT_PAYLOAD_TTL : request.ttl();
        AgentToolActionPayloadRecord record = new AgentToolActionPayloadRecord(
                reference,
                parsed.runId(),
                parsed.payloadKey(),
                safeText(request.tenantId()),
                safeText(request.projectId()),
                safeText(request.actorId()),
                safeText(request.toolName()),
                safeText(request.graphId()),
                safeText(request.contractId()),
                safeText(request.payloadPolicy()),
                safeList(request.argumentNames()),
                safeList(request.sensitiveArgumentNames()),
                true,
                payloadSizeBytes(body),
                materializedMetadataDigest(request, parsed, body),
                now,
                now.plus(ttl),
                body
        );
        payloadStore.append(record);
        return payloadStore.findByReference(reference);
    }

    private ParsedAgentPayloadReference parse(String reference) {
        String body = reference == null ? "" : reference.substring(AGENT_PAYLOAD_PREFIX.length());
        String[] parts = body.split("/", -1);
        List<String> issues = new ArrayList<>();
        if (parts.length < 2) {
            issues.add("AGENT_PAYLOAD_REFERENCE_REQUIRES_RUN_AND_KEY");
        }
        for (String part : parts) {
            String text = safeText(part);
            if (text == null) {
                issues.add("AGENT_PAYLOAD_REFERENCE_EMPTY_SEGMENT");
                continue;
            }
            if (text.length() > 96) {
                issues.add("AGENT_PAYLOAD_REFERENCE_SEGMENT_TOO_LONG");
            }
            if (!safeSegment(text)) {
                issues.add("AGENT_PAYLOAD_REFERENCE_SEGMENT_UNSAFE_CHARACTERS");
            }
        }
        String runId = parts.length > 0 ? safeText(parts[0]) : null;
        String payloadKey = parts.length > 1 ? safeText(parts[1]) : null;
        return new ParsedAgentPayloadReference(runId, payloadKey, List.copyOf(issues));
    }

    private String materializedMetadataDigest(AgentToolActionPayloadMaterializationRequest request,
                                              ParsedAgentPayloadReference parsed,
                                              Map<String, Object> body) {
        return sha256(String.join("\n",
                defaultText(request.payloadReference()),
                defaultText(parsed.runId()),
                defaultText(parsed.payloadKey()),
                defaultText(request.tenantId()),
                defaultText(request.projectId()),
                defaultText(request.actorId()),
                defaultText(request.toolName()),
                defaultText(request.graphId()),
                defaultText(request.contractId()),
                defaultText(request.payloadPolicy()),
                String.join(",", safeList(request.argumentNames())),
                String.join(",", safeList(request.sensitiveArgumentNames())),
                String.valueOf(payloadSizeBytes(body)),
                String.join(",", body.keySet().stream().sorted().toList())
        ));
    }

    private Map<String, Object> lowSensitiveBody(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::safeText)
                .filter(value -> value != null)
                .distinct()
                .sorted()
                .toList();
    }

    private int payloadSizeBytes(Map<String, Object> body) {
        return Math.max(0, String.valueOf(body).getBytes(StandardCharsets.UTF_8).length);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成 agent-payload 物化摘要", exception);
        }
    }

    private boolean safeSegment(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean allowed = Character.isLetterOrDigit(current)
                    || current == '-'
                    || current == '_'
                    || current == '.'
                    || current == ':';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private boolean safeEquals(String left, String right) {
        String normalizedLeft = safeText(left);
        String normalizedRight = safeText(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultText(String value) {
        String text = safeText(value);
        return text == null ? "" : text;
    }

    private record ParsedAgentPayloadReference(
            String runId,
            String payloadKey,
            List<String> issueCodes
    ) {
        private boolean valid() {
            return issueCodes == null || issueCodes.isEmpty();
        }
    }

    /**
     * 服务端 payload body 物化请求。
     *
     * @param payloadReference `agent-payload:{runId}/{payloadKey}` 引用。
     * @param runId 当前 Agent run ID，必须与引用中的 runId 一致。
     * @param tenantId 租户 ID 字符串，只用于服务端绑定校验和后续 verifier。
     * @param projectId 项目 ID 字符串，只用于服务端绑定校验和后续 verifier。
     * @param actorId 操作者或服务账号 ID，只用于审计和 SELF 范围校验。
     * @param toolName 工具编码，例如 `quality.remediation.task.draft`。
     * @param graphId 来源执行图或工具链路 ID，可为空。
     * @param contractId 来源契约 ID，可为空。
     * @param payloadPolicy 载荷策略，例如 `LOW_SENSITIVE_DRAFT_BODY`。
     * @param argumentNames 低敏参数名列表，不包含参数值。
     * @param sensitiveArgumentNames 敏感参数名列表，不包含参数值。
     * @param payloadBody 已经由业务 adapter 收口过的低敏 body。
     * @param ttl 记录有效期；为空时使用默认 2 小时。
     */
    public record AgentToolActionPayloadMaterializationRequest(
            String payloadReference,
            String runId,
            String tenantId,
            String projectId,
            String actorId,
            String toolName,
            String graphId,
            String contractId,
            String payloadPolicy,
            List<String> argumentNames,
            List<String> sensitiveArgumentNames,
            Map<String, Object> payloadBody,
            Duration ttl
    ) {
    }
}
