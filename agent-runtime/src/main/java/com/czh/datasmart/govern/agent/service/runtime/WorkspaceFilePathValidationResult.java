/**
 * @Author : Cui
 * @Date: 2026/07/02 04:10
 * @Description DataSmart Govern Backend - WorkspaceFilePathValidationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * Workspace 相对路径校验的内部结果。
 *
 * <p>normalizedPath 只能进入服务端 payload store，pathDigest 才允许进入低敏响应和审计。
 */
record WorkspaceFilePathValidationResult(String normalizedPath, String pathDigest) {
}
