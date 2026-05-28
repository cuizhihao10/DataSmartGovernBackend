/**
 * @Author : Cui
 * @Date: 2026/05/07 21:27
 * @Description DataSmart Govern Backend - SyncTemplate.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步模板实体。
 *
 * <p>同步模板保存的是“可复用的同步配置”，不是某一次运行记录。
 * 真实产品中，同一个模板可能被手动运行、定时运行、失败重试、历史回放或补数多次使用。
 * 因此模板要和任务、执行记录、checkpoint 分开建模，避免后续为了保存运行状态不断修改配置定义。
 */
@Data
@TableName("data_sync_template")
public class SyncTemplate {

    /**
     * 模板主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     * 多租户平台必须从最早的数据模型就保留租户边界，否则后续权限、配额、审计和账单都会返工。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>租户只能表达“属于哪个公司/组织”，项目用于表达租户内部的业务域、团队或数据产品边界。
     * permission-admin 的 PROJECT 数据范围最终会落到该字段，避免项目负责人看到同租户其他项目的同步模板。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>workspaceId 比 projectId 更偏产品协作空间，可用于多环境、多团队看板、空间级配额和空间级审计。
     * 当前先作为可选字段保留，后续如果平台把项目和空间统一建模，也可以在服务层映射为同一个治理域。
     */
    private Long workspaceId;

    /**
     * 模板名称。
     * 主要面向用户识别，例如“CRM 客户表每日同步到数仓”。
     */
    private String name;

    /**
     * 模板描述。
     */
    private String description;

    /**
     * 源数据源 ID。
     * 只保存 datasource-management 中登记的数据源 ID，不在本表重复保存 JDBC URL、密码等敏感连接信息。
     */
    private Long sourceDatasourceId;

    /**
     * 目标数据源 ID。
     */
    private Long targetDatasourceId;

    /**
     * 同步模式，例如 FULL、INCREMENTAL_TIME、CDC_STREAMING。
     */
    private String syncMode;

    /**
     * 字段映射配置 JSON。
     * 真实项目中这里会包含源字段、目标字段、类型转换、默认值、脱敏规则和字段级异常处理策略。
     */
    private String fieldMappingConfig;

    /**
     * 过滤条件配置 JSON。
     * 例如按业务日期、租户字段、状态字段过滤源端数据。
     */
    private String filterConfig;

    /**
     * 分区与并发配置 JSON。
     * 例如按 id range、日期分区、hash 分片进行并行同步。
     */
    private String partitionConfig;

    /**
     * 重试策略 JSON。
     * 例如最大重试次数、退避间隔、可重试错误类型。
     */
    private String retryPolicy;

    /**
     * 超时策略 JSON。
     * 例如单批读取超时、整体执行超时、目标写入超时。
     */
    private String timeoutPolicy;

    /**
     * 是否启用模板。
     * 禁用模板后不应继续创建新的同步任务，但历史任务和执行记录仍然保留。
     */
    private Boolean enabled;

    /**
     * 创建人 ID。
     */
    private Long createdBy;

    /**
     * 更新人 ID。
     */
    private Long updatedBy;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
