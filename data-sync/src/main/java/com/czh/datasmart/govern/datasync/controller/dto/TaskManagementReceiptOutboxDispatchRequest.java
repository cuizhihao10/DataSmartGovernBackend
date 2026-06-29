/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - TaskManagementReceiptOutboxDispatchRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * task-management receipt outbox 手动派发请求。
 *
 * <p>该 DTO 只用于 internal/运维触发 due receipt 补偿，不允许调用方指定 receipt payload、目标 URL 或任意 HTTP 参数。
 * 这样可以避免把“手动重放”变成任意内部请求工具。</p>
 */
@Data
public class TaskManagementReceiptOutboxDispatchRequest {

    /**
     * 本轮最多处理多少条 due receipt。
     *
     * <p>为空时使用配置中的默认 batchSize。服务层会再次做上限收敛，避免误传一个极大值打爆 task-management。</p>
     */
    private Integer limit;
}
