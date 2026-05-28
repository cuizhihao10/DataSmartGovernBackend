/**
 * @Author : Cui
 * @Date: 2026/05/08 22:24
 * @Description DataSmart Govern Backend - SyncAttentionOperationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人工介入任务处理请求。
 *
 * <p>多个运营动作共用一个请求体，是为了让前端和 API 文档保持简单：
 * acknowledge、resolve、rerun、cancel、archive 通常只需要 note；
 * create-incident 额外使用 incidentType、severity、title、description。
 */
@Data
public class SyncAttentionOperationRequest {

    /**
     * 操作备注。
     *
     * <p>用于说明运营人员为什么确认、解决、重跑、取消或归档。
     * 商业化产品中，这类备注会进入审计和事故复盘，不应保存密码、密钥、完整 SQL 或敏感样本。
     */
    @Size(max = 1000, message = "操作备注不能超过 1000 个字符")
    private String note;

    /**
     * 事故类型。
     *
     * <p>建议值：EXECUTOR_UNSTABLE、CONNECTOR_FAILURE、TARGET_THROTTLED、CONFIGURATION_ERROR、DATA_CONTRACT_BROKEN。
     * 当前不强制枚举，是为了给后续不同客户的运维分类预留扩展空间。
     */
    @Size(max = 64, message = "事故类型不能超过 64 个字符")
    private String incidentType;

    /** 事故严重级别，例如 P1、P2、P3、P4。 */
    @Size(max = 32, message = "事故严重级别不能超过 32 个字符")
    private String severity;

    /** 事故标题。 */
    @Size(max = 256, message = "事故标题不能超过 256 个字符")
    private String title;

    /** 事故描述。 */
    @Size(max = 2000, message = "事故描述不能超过 2000 个字符")
    private String description;
}
