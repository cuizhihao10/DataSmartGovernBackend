/**
 * @Author : Cui
 * @Date: 2026/07/02 04:15
 * @Description DataSmart Govern Backend - WorkspaceFilePayloadValueSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * Workspace 文件 payload 可选文本规范化支持。
 *
 * <p>仅去除边界空白并把空串转成 null，不记录或输出路径、正文和引用值。
 */
final class WorkspaceFilePayloadValueSupport {

    private WorkspaceFilePayloadValueSupport() {
    }

    static String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static boolean hasText(String value) {
        return safeText(value) != null;
    }
}
