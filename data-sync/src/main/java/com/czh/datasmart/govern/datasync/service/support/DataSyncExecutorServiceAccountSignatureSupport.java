/**
 * @Author : Cui
 * @Date: 2026/05/09 20:20
 * @Description DataSmart Govern Backend - DataSyncExecutorServiceAccountSignatureSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncExecutorSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 执行器服务账号签名校验组件。
 *
 * <p>这个组件位于 data-sync 内部，而不是放在 gateway，原因是执行器协议属于 data-sync 的领域边界：
 * claim、heartbeat、checkpoint、complete、fail 等动作会直接改变同步执行记录、任务状态、错误样本和审计事实。
 * 即使请求已经通过 gateway，也仍然需要 data-sync 自己确认调用方是否真的是受信任 worker。
 *
 * <p>签名协议采用“共享密钥 + HMAC-SHA256 + 时间戳 + nonce”的工程化起步方案：
 * 1. 服务端通过 {@code serviceAccountId} 找到共享密钥；
 * 2. 双方对同一段 canonical string 计算 HMAC；
 * 3. 服务端使用常量时间比较，避免普通字符串比较在理论上泄露匹配长度；
 * 4. 服务端检查时间窗口，拒绝过旧或明显来自未来的请求；
 * 5. 服务端记录 nonce，在窗口内拒绝同一服务账号重复使用同一个 nonce。
 *
 * <p>当前 canonical string 不包含请求体。这样做是有意的阶段性取舍：
 * 如果要签 body，Spring MVC 在进入 Controller 前需要通过 Filter 缓存输入流，否则 Controller 消费 body 后无法重复读取；
 * 当前优先补齐机器调用身份认证和防重放，后续可以追加 {@code X-DataSmart-Body-Digest}，
 * 由执行器签入 body 摘要，服务端通过缓存请求体进行更强完整性校验。
 */
@Component
@RequiredArgsConstructor
public class DataSyncExecutorServiceAccountSignatureSupport {

    private static final String CANONICAL_SEPARATOR = "\n";

    private final DataSyncExecutorSecurityProperties properties;

    /**
     * 本实例内 nonce 使用记录。
     *
     * <p>key 使用“服务账号 ID + nonce”，value 是该 nonce 在本实例中的过期时间。
     * 生产多实例部署时，这里应升级为 Redis set / sorted set 或带 TTL 的分布式缓存，
     * 否则同一请求如果被重放到另一台 data-sync 实例，另一台实例无法知道该 nonce 已经被使用。
     */
    private final ConcurrentMap<String, Instant> usedNonces = new ConcurrentHashMap<>();

    /**
     * 校验当前执行器协议请求。
     *
     * @param servletRequest Spring MVC 暴露的原始 HTTP 请求，用于读取 method、path、query 和签名 Header。
     * @param traceId 平台链路追踪 ID，参与签名可以让重放者不能随意替换 traceId 伪造排障上下文。
     * @param operation 当前业务动作名称，例如 CLAIM、HEARTBEAT、COMPLETE，用于让审计和异常信息更可读。
     */
    public void verify(HttpServletRequest servletRequest, String traceId, String operation) {
        if (!properties.isEnabled()) {
            return;
        }

        String serviceAccountId = requiredHeader(servletRequest, properties.getServiceAccountIdHeader(), "服务账号 ID");
        String timestampText = requiredHeader(servletRequest, properties.getTimestampHeader(), "签名时间戳");
        String nonce = requiredHeader(servletRequest, properties.getNonceHeader(), "签名 nonce");
        String providedSignature = requiredHeader(servletRequest, properties.getSignatureHeader(), "签名值");
        String secret = lookupSecret(serviceAccountId);
        Instant requestTime = parseAndValidateTimestamp(timestampText);

        String canonical = buildCanonicalString(servletRequest, traceId, serviceAccountId, timestampText, nonce);
        byte[] digest = hmac(secret, canonical);
        String expectedBase64 = Base64.getEncoder().encodeToString(digest);
        String expectedHex = toHex(digest);

        if (!constantTimeEquals(providedSignature, expectedBase64)
                && !constantTimeEquals(providedSignature.toLowerCase(Locale.ROOT), expectedHex)) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "执行器服务账号签名不匹配，拒绝执行 " + operation + " 动作");
        }

        rememberNonce(serviceAccountId, nonce, requestTime);
    }

    /**
     * 读取必填 Header。
     *
     * <p>这里不把缺失 Header 归类为普通参数校验错误，而是归类为未认证：
     * 因为签名 Header 是机器调用身份的一部分，缺失它意味着请求没有完成执行器身份声明。
     */
    private String requiredHeader(HttpServletRequest request, String headerName, String readableName) {
        String value = request.getHeader(headerName);
        if (!StringUtils.hasText(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "执行器服务账号" + readableName + "缺失，请在 Header " + headerName + " 中传入");
        }
        return value.trim();
    }

    /**
     * 根据服务账号 ID 查找共享密钥。
     */
    private String lookupSecret(String serviceAccountId) {
        Map<String, String> secrets = properties.getSecrets();
        String secret = secrets == null ? null : secrets.get(serviceAccountId);
        if (!StringUtils.hasText(secret)) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "执行器服务账号未注册或未配置密钥，serviceAccountId=" + serviceAccountId);
        }
        return secret;
    }

    /**
     * 解析并校验请求时间戳。
     */
    private Instant parseAndValidateTimestamp(String timestampText) {
        long timestampMillis;
        try {
            timestampMillis = Long.parseLong(timestampText);
        } catch (NumberFormatException ex) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "执行器服务账号签名时间戳格式不合法，应使用 Unix epoch 毫秒");
        }

        Instant requestTime = Instant.ofEpochMilli(timestampMillis);
        Instant now = Instant.now();
        long skewSeconds = properties.effectiveAllowedClockSkewSeconds();
        if (requestTime.isBefore(now.minusSeconds(skewSeconds)) || requestTime.isAfter(now.plusSeconds(skewSeconds))) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "执行器服务账号签名时间戳已过期或来自过远未来，请检查 worker 与 data-sync 节点时间同步");
        }
        return requestTime;
    }

    /**
     * 构造签名原文。
     *
     * <p>字段顺序必须稳定，否则不同语言 worker 很容易出现“签名算法正确但原文不一致”的问题。
     * 当前顺序是：HTTP 方法、请求路径与查询串、traceId、服务账号 ID、时间戳、nonce。
     */
    private String buildCanonicalString(HttpServletRequest request,
                                        String traceId,
                                        String serviceAccountId,
                                        String timestampText,
                                        String nonce) {
        return request.getMethod().toUpperCase(Locale.ROOT)
                + CANONICAL_SEPARATOR + requestPathWithQuery(request)
                + CANONICAL_SEPARATOR + nullToEmpty(traceId)
                + CANONICAL_SEPARATOR + serviceAccountId
                + CANONICAL_SEPARATOR + timestampText
                + CANONICAL_SEPARATOR + nonce;
    }

    /**
     * 返回路径与查询串。
     *
     * <p>同一路径不同查询条件可能代表不同请求语义，所以 queryString 也要参与签名。
     */
    private String requestPathWithQuery(HttpServletRequest request) {
        String path = request.getRequestURI();
        String queryString = request.getQueryString();
        if (!StringUtils.hasText(queryString)) {
            return path;
        }
        return path + "?" + queryString;
    }

    /**
     * 计算 HMAC 摘要。
     */
    private byte[] hmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(properties.getSignatureAlgorithm());
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), properties.getSignatureAlgorithm()));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "执行器服务账号签名算法初始化失败，请检查 signature-algorithm 配置");
        }
    }

    /**
     * 记录 nonce，并拒绝短时间内重复出现的 nonce。
     */
    private void rememberNonce(String serviceAccountId, String nonce, Instant requestTime) {
        pruneExpiredNonces();
        String nonceKey = serviceAccountId + ":" + nonce;
        Instant expireAt = requestTime.plusSeconds(properties.effectiveAllowedClockSkewSeconds());
        Instant existing = usedNonces.putIfAbsent(nonceKey, expireAt);
        if (existing != null && existing.isAfter(Instant.now())) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "执行器服务账号 nonce 已被使用，疑似重复请求或重放攻击");
        }
        if (usedNonces.size() > properties.effectiveMaxNonceCacheSize()) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "执行器 nonce 缓存达到保护上限，请缩短时间窗口、扩容实例或接入分布式 nonce 存储");
        }
    }

    /**
     * 清理已经超过允许时间窗口的 nonce。
     */
    private void pruneExpiredNonces() {
        Instant now = Instant.now();
        for (Map.Entry<String, Instant> entry : usedNonces.entrySet()) {
            if (entry.getValue().isBefore(now)) {
                usedNonces.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
