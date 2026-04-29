package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/04/28 21:18
 * @Description DataSmart Govern Backend - DataSourceReadOnlySqlExecutionAudit.java
 * @Version:1.0.0
 *
 * 数据源受控只读 SQL 执行审计实体。
 *
 * 这张表记录的不是业务结果数据，而是“访问源库这件事本身”的证据链。
 * 在商业化数据治理产品中，只要平台能够读取客户源库，就必须回答这些审计问题：
 * 1. 谁发起了访问，使用什么角色或服务账号；
 * 2. 访问的是哪个已登记数据源；
 * 3. 访问目的是什么，例如质量指标扫描、异常样本采集、字段画像；
 * 4. SQL 被平台应用了哪些行数和超时限制；
 * 5. 本次访问成功还是失败，耗时多久，返回多少行；
 * 6. 发生事故或合规审查时，能否通过 SQL 指纹定位同类访问。
 *
 * 这里刻意只保存 SQL 指纹和截断预览，不保存完整结果集。
 * 原因是审计表本身也属于敏感资产：如果把完整 SQL、完整样本值、完整结果都写入审计，
 * 它就会从“审计证据”变成新的敏感数据泄漏面。
 */
@Data
@TableName("datasource_readonly_sql_execution_audit")
public class DataSourceReadOnlySqlExecutionAudit {

    /**
     * 审计记录主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 被访问的数据源 ID。
     */
    private Long datasourceId;

    /**
     * 被访问的数据源名称快照。
     *
     * 保存快照是为了避免数据源后续改名后，历史审计记录变得难以人工识别。
     */
    private String datasourceName;

    /**
     * 被访问的数据源类型快照，例如 MYSQL、POSTGRESQL、SQLSERVER。
     */
    private String datasourceType;

    /**
     * 执行目的。
     *
     * 建议使用稳定编码，例如 QUALITY_METRIC_SCAN、QUALITY_ANOMALY_SAMPLE、DATA_ASSET_PROFILE。
     */
    private String purpose;

    /**
     * 租户 ID。
     *
     * 该字段让审计记录具备多租户隔离和租户级检索能力。
     */
    private Long actorTenantId;

    /**
     * 操作者 ID。
     */
    private Long actorId;

    /**
     * 操作者角色。
     *
     * 优先来自网关或服务间认证上下文中的可信 Header。
     */
    private String actorRole;

    /**
     * 操作者类型，例如 USER、SERVICE_ACCOUNT、AGENT。
     */
    private String actorType;

    /**
     * 调用来源服务。
     */
    private String sourceService;

    /**
     * 链路追踪 ID。
     */
    private String traceId;

    /**
     * SQL SHA-256 指纹。
     *
     * 指纹用于排查“同一类 SQL 是否反复执行”，同时避免直接暴露完整 SQL。
     */
    private String sqlFingerprint;

    /**
     * SQL 脱敏截断预览。
     *
     * 只用于人工排查，长度受限，并且在服务层写入前会对常见敏感字面量做遮蔽。
     * 它与 sqlFingerprint 的职责不同：fingerprint 用于稳定聚合同一 SQL，preview 用于让审计人员理解查询结构。
     * 因此 preview 不追求还原完整 SQL，更不应该保存手机号、邮箱、身份证号、token、password 等原始敏感值。
     */
    private String sqlPreview;

    /**
     * 调用方请求的最大返回行数。
     */
    private Integer requestedMaxRows;

    /**
     * 服务端最终应用的最大返回行数。
     */
    private Integer appliedMaxRows;

    /**
     * 调用方请求的查询超时秒数。
     */
    private Integer requestedQueryTimeoutSeconds;

    /**
     * 服务端最终应用的查询超时秒数。
     */
    private Integer appliedQueryTimeoutSeconds;

    /**
     * 实际返回行数。
     */
    private Integer returnedRowCount;

    /**
     * 结果列数量。
     */
    private Integer columnCount;

    /**
     * 执行耗时毫秒。
     */
    private Long durationMs;

    /**
     * 执行状态。
     *
     * 当前使用 SUCCESS / FAILED；后续可扩展 DENIED、TIMEOUT、CANCELLED 等更细状态。
     */
    private String executionStatus;

    /**
     * 失败原因摘要。
     *
     * 成功时为空；失败时只记录截断摘要，避免驱动异常携带过多敏感上下文。
     */
    private String failureMessage;

    /**
     * SQL 执行开始时间。
     */
    private LocalDateTime executedAt;

    /**
     * 审计记录创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
