/**
 * @Author : Cui
 * @Date: 2026/07/02 04:10
 * @Description DataSmart Govern Backend - WorkspaceFilePayloadDigestSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Workspace 文件 payload 的 SHA-256 摘要支持。
 *
 * <p>摘要用于低敏关联和乐观并发证据，不是加密存储。原路径和正文仍只能保存在受控 payload store。
 */
final class WorkspaceFilePayloadDigestSupport {

    private WorkspaceFilePayloadDigestSupport() {
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成 workspace 文件载荷摘要", exception);
        }
    }
}
