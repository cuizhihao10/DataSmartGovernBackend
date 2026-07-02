/**
 * @Author : Cui
 * @Date: 2026/07/02 04:10
 * @Description DataSmart Govern Backend - WorkspaceFileContentValidationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * Workspace 写入正文校验的低敏内部结果。
 *
 * <p>只保存摘要、字节数和提供方式，不保存正文或外部正文引用原值。
 */
record WorkspaceFileContentValidationResult(
        String contentSha256,
        Integer contentSizeBytes,
        boolean contentReferenceProvided,
        boolean inlineContentProvided
) {
}
