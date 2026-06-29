/**
 * @Author : Cui
 * @Date: 2026/06/29 13:18
 * @Description DataSmart Govern Backend - TaskManagementExecutionReceiptEnvelope.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.task.receipt;

import lombok.Data;

/**
 * task-management 统一响应 envelope 的最小镜像。
 *
 * <p>data-sync 投递 receipt 时只需要确认对方是否按业务码成功接收，不需要解析完整 data 正文。
 * 因此这里不定义 data 字段，避免把 task-management 的内部视图结构耦合进 data-sync。</p>
 */
@Data
public class TaskManagementExecutionReceiptEnvelope {

    /** 业务码，0 表示成功。 */
    private Integer code;

    /** 低敏结果消息；客户端不应把它原样上抛给普通用户。 */
    private String message;
}
