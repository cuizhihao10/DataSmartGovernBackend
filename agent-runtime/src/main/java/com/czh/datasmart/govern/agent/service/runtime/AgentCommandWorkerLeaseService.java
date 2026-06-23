/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * command worker lease 控制面服务。
 *
 * <p>它是 command durable action 的并发护栏：worker 在真正进入副作用区之前必须领取 lease，
 * receipt 写回时必须携带同一份 fencing token。这样可以阻止旧 worker、重复 worker 或旁路调用把过期结果写入
 * runtime timeline。</p>
 *
 * <p>当前服务只保存低敏控制面事实，不接触命令行、stdout/stderr、payload、SQL、prompt、模型输出、真实路径、
 * 凭据或内部 endpoint。fencingToken 只允许存在于内部 lease store 和 worker POST 请求中，不进入 projection 明文。</p>
 */
@Service
public class AgentCommandWorkerLeaseService {

    public static final int DEFAULT_LEASE_TTL_SECONDS = 60;
    public static final int MAX_LEASE_TTL_SECONDS = 600;

    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_.:@/+-]{1,260}$");
    private static final Pattern FENCING_TOKEN_PATTERN = Pattern.compile("^cmd-lease:([1-9][0-9]*):[a-fA-F0-9]{8,64}$");

    private final AgentCommandWorkerLeaseStore store;
    private final Clock clock;

    public AgentCommandWorkerLeaseService(AgentCommandWorkerLeaseStore store) {
        this(store, Clock.systemUTC());
    }

    AgentCommandWorkerLeaseService(AgentCommandWorkerLeaseStore store, Clock clock) {
        this.store = store;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 领取 command worker lease。
     *
     * <p>当前方法供内部 controller、未来 task-management worker 或 Python Runtime 使用。领取成功后，调用方必须把返回的
     * fencingToken 透传到 command worker receipt；如果被其他 worker 持有，调用方必须停止处理并等待队列重试。</p>
     */
    public AgentCommandWorkerLeaseClaimResult claim(String sessionId,
                                                    String runId,
                                                    AgentCommandWorkerLeaseClaimRequest request) {
        if (request == null) {
            throw badRequest("command worker lease 领取请求体不能为空");
        }
        Instant now = clock.instant();
        String safeSessionId = requiredSafeText(sessionId, "sessionId");
        String safeRunId = requiredSafeText(runId, "runId");
        String commandId = requiredSafeText(request.commandId(), "commandId");
        String executorId = requiredSafeText(request.executorId(), "executorId");
        int ttlSeconds = normalizedTtl(request.leaseTtlSeconds());
        AgentCommandWorkerLeaseRecord candidate = new AgentCommandWorkerLeaseRecord(
                leaseIdentityKey(safeSessionId, safeRunId, commandId),
                safeSessionId,
                safeRunId,
                commandId,
                executorId,
                stringValue(request.tenantId()),
                stringValue(request.projectId()),
                stringValue(request.actorId()),
                null,
                1L,
                now.plusSeconds(ttlSeconds),
                now,
                now
        );
        return store.claim(candidate, now);
    }

    /**
     * 校验 receipt 中携带的 lease/fencing 证据是否仍然有效。
     *
     * <p>这一步发生在 runtime event 写入之前。只要进入过副作用区，receipt 就必须能对上当前 Java 控制面 lease fact；
     * 否则 agent-runtime 会拒绝写入，避免过期 worker 或绕过 claim 的 worker 污染 timeline。</p>
     */
    public void validateReceiptLease(String sessionId,
                                     String runId,
                                     AgentToolActionCommandWorkerReceiptRequest request,
                                     boolean sideEffectTouched) {
        boolean required = Boolean.TRUE.equals(request.workerLeaseRequired())
                || Boolean.TRUE.equals(request.sideEffectStarted())
                || Boolean.TRUE.equals(request.sideEffectExecuted());
        if (sideEffectTouched && !required) {
            throw badRequest("受控命令 worker 回执进入副作用执行区时，必须声明 workerLeaseRequired=true");
        }
        if (!required && trimToNull(request.fencingToken()) == null
                && request.workerLeaseVersion() == null
                && request.workerLeaseExpiresAtMs() == null) {
            return;
        }
        String token = requiredFencingToken(request.fencingToken());
        long tokenVersion = tokenVersion(token);
        if (request.workerLeaseVersion() == null || request.workerLeaseVersion() < 1) {
            throw badRequest("workerLeaseRequired=true 时必须提供大于 0 的 workerLeaseVersion");
        }
        if (tokenVersion != request.workerLeaseVersion()) {
            throw badRequest("fencingToken 中的版本号必须与 workerLeaseVersion 一致");
        }
        if (request.workerLeaseExpiresAtMs() == null || request.workerLeaseExpiresAtMs() <= 0) {
            throw badRequest("workerLeaseRequired=true 时必须提供大于 0 的 workerLeaseExpiresAtMs");
        }

        AgentCommandWorkerLeaseRecord record = store.findByIdentity(
                requiredSafeText(sessionId, "sessionId"),
                requiredSafeText(runId, "runId"),
                requiredSafeText(request.commandId(), "commandId")
        ).orElseThrow(() -> badRequest("未找到 command worker lease fact，拒绝写入副作用回执"));
        Instant now = clock.instant();
        if (!record.activeAt(now)) {
            throw badRequest("command worker lease 已过期，旧 worker 不能写回 receipt");
        }
        if (!record.heldBy(requiredSafeText(request.executorId(), "executorId"))
                || !token.equals(record.fencingToken())
                || request.workerLeaseVersion().longValue() != record.leaseVersion()) {
            throw badRequest("command worker lease 与 receipt 中的 executor/token/version 不匹配");
        }
        if (request.workerLeaseExpiresAtMs().longValue() != record.leaseExpiresAt().toEpochMilli()) {
            throw badRequest("receipt 中的 workerLeaseExpiresAtMs 与当前 lease fact 不一致");
        }
    }

    /**
     * 生成 token digest，用于 runtime event 低敏投影。
     */
    public String tokenDigest(String fencingToken) {
        String token = trimToNull(fencingToken);
        if (token == null) {
            return null;
        }
        return "sha256:" + sha256Hex(token).substring(0, 20);
    }

    static String leaseIdentityKey(String sessionId, String runId, String commandId) {
        return sessionId + ":" + runId + ":" + commandId;
    }

    static String newFencingToken(String leaseIdentityKey, String executorId, long leaseVersion, Instant now) {
        String digest = sha256Hex(leaseIdentityKey + ":" + executorId + ":" + leaseVersion + ":" + now.toEpochMilli());
        return "cmd-lease:" + leaseVersion + ":" + digest.substring(0, 20);
    }

    private long tokenVersion(String token) {
        Matcher matcher = FENCING_TOKEN_PATTERN.matcher(token);
        if (!matcher.matches()) {
            throw badRequest("fencingToken 必须使用 cmd-lease:{version}:{digest} 低敏格式，不能携带 URL、路径、输出或凭据");
        }
        return Long.parseLong(matcher.group(1));
    }

    private String requiredFencingToken(String value) {
        String token = trimToNull(value);
        if (token == null) {
            throw badRequest("workerLeaseRequired=true 时必须提供 fencingToken");
        }
        tokenVersion(token);
        return token;
    }

    private String requiredSafeText(String value, String fieldName) {
        String text = trimToNull(value);
        if (text == null || !SAFE_ID_PATTERN.matcher(text).matches() || looksSensitive(text)) {
            throw badRequest(fieldName + " 只能使用低敏标识符，不能携带命令、路径、URL、输出、SQL、prompt 或凭据");
        }
        return text;
    }

    private int normalizedTtl(Integer value) {
        int parsed = value == null ? DEFAULT_LEASE_TTL_SECONDS : value;
        return Math.max(1, Math.min(parsed, MAX_LEASE_TTL_SECONDS));
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String stringValue(Long value) {
        return value == null ? null : String.valueOf(value);
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
