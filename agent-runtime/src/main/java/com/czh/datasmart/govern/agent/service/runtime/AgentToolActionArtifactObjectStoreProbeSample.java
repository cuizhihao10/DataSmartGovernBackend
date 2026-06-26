/**
 * @Author : Cui
 * @Date: 2026/06/26 23:13
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreProbeSample.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * 对象存储 adapter 的内部探针结果。
 *
 * <p>这个 record 是 service 层内部合同，不是 HTTP 响应 DTO。`sampleBytes` 可能包含 artifact 正文前缀，
 * 因此只能在内存中短暂存在，用于 Host 计算 SHA-256 指纹和字节统计。任何实现都不能把 `sampleBytes` 写入
 * runtime event、projection、日志、异常消息、DTO、缓存或模型上下文。</p>
 *
 * @param probeExecuted true 表示 adapter 实际尝试访问对象存储；默认禁用实现会返回 false。
 * @param objectAvailable true 表示对象存在且 sample 读取成功。
 * @param contentType adapter 识别出的低敏内容类型。
 * @param contentLengthBytes adapter 识别出的对象长度，允许为空。
 * @param sampleBytes 对象前缀 sample，只能被 Service 用于哈希与字节统计，不允许外传。
 * @param sampleTruncated true 表示 sample 没覆盖完整对象或被 adapter 主动裁剪。
 * @param objectVersionFingerprint 低敏版本指纹，不能是原始 versionId、etag 明文、bucket/key 或 URL。
 * @param evidenceCodes adapter 提供的低敏证据码。
 * @param issueCodes adapter 提供的不可用或降级原因码。
 * @param recommendedActions adapter 建议的后续动作。
 */
public record AgentToolActionArtifactObjectStoreProbeSample(
        boolean probeExecuted,
        boolean objectAvailable,
        String contentType,
        Long contentLengthBytes,
        byte[] sampleBytes,
        boolean sampleTruncated,
        String objectVersionFingerprint,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions
) {
}
