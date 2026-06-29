/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFileWorkerReceiptRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Workspace 文件工具 worker 回执请求。
 *
 * <p>该 DTO 是 workspace 文件工具的专用适配层请求，不替代通用
 * {@link AgentToolActionCommandWorkerReceiptRequest}。它额外要求 worker 提交 `payloadReference` 和
 * READ/WRITE 操作语义，目的是在写入 runtime event 之前确认：本次回执确实对应已经由 Java Host 物化过的
 * workspace 文件工具参数，而不是某个 worker 自行拼出来的命令事实。</p>
 *
 * <p>安全边界：这里不接收 relativePath、content、contentReference 原值、workspace root、SQL、prompt、
 * 工具参数正文、stdout/stderr、模型输出、凭据或内部 endpoint。真实参数只能由 worker 在服务端内部通过
 * `payloadReference` 回查 payload store；回执进入 Java timeline 的内容仍由通用 command worker receipt
 * 合同做二次低敏过滤。</p>
 *
 * @param payloadReference 已由 payload 物化 API 创建的 `agent-payload:` 引用。
 * @param operation workspace 文件操作，支持 READ/WRITE；必须与 payload body 和 toolCode 匹配。
 * @param commandWorkerReceipt 通用 command worker 低敏回执合同，负责表达 lease、outcome、artifact 和审计事实。
 */
public record AgentWorkspaceFileWorkerReceiptRequest(
        String payloadReference,
        String operation,
        AgentToolActionCommandWorkerReceiptRequest commandWorkerReceipt
) {
}
