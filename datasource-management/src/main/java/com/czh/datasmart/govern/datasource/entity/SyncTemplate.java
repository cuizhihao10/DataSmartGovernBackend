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
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncTemplate.java
 * @Version:1.0.0
 *
 * 同步模板实体。
 * 模板是“可重复复用的数据同步配置”，它介于数据源注册和任务实例之间：
 * - 数据源解决“连接到谁”；
 * - 模板解决“按什么规则搬数据”；
 * - 任务解决“何时、由谁、以什么优先级执行一次具体同步”。
 *
 * 采用模板而不是把所有配置直接塞进任务，有几个商业化场景收益：
 * 1. 同一类同步任务可以重复创建，避免每次手工重新配置字段映射和过滤条件；
 * 2. 模板可以绑定审批、启停、版本和校验能力，便于企业级治理；
 * 3. 模板与任务分离后，任务历史不会因为模板调整而完全失真。
 */
@Data
@TableName("sync_template")
public class SyncTemplate {

    /**
     * 模板主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户标识。
     * 当前仓库还没有完成真正的多租户隔离，但领域模型必须提前保留这个能力位。
     */
    private Long tenantId;

    /**
     * 模板名称。
     * 实际产品中通常会显示在模板列表、任务创建弹窗、审批单据和运行看板中。
     */
    private String name;

    /**
     * 模板说明。
     * 用于帮助运维、数据开发和审核人员快速理解这个模板的业务目标。
     */
    private String description;

    /**
     * 源端数据源 ID。
     */
    private Long sourceDatasourceId;

    /**
     * 源端 schema 名称。
     * 不同数据库的逻辑命名层级不同，但对关系型数据库来说，schema 能明显降低同名表歧义。
     */
    private String sourceSchemaName;

    /**
     * 源端对象名称。
     * 当前阶段主要表示表名或视图名，后续可以继续扩展到 topic、文件对象、API 资源名等。
     */
    private String sourceObjectName;

    /**
     * 目标端数据源 ID。
     */
    private Long targetDatasourceId;

    /**
     * 目标端 schema 名称。
     */
    private String targetSchemaName;

    /**
     * 目标端对象名称。
     */
    private String targetObjectName;

    /**
     * 同步模式。
     */
    private String syncMode;

    /**
     * 主键字段。
     * 对很多同步任务来说，这个字段会影响去重、幂等写入、回放和冲突处理策略。
     */
    private String primaryKeyField;

    /**
     * 增量字段。
     * 当同步模式是时间增量或 ID 增量时，这个字段通常是必填项。
     */
    private String incrementalField;

    /**
     * 字段映射配置。
     * 当前阶段先保存为 JSON 字符串，后续如果字段映射复杂度上升，可以拆出独立表版本化管理。
     */
    private String fieldMappingConfig;

    /**
     * 过滤条件配置。
     * 常见用途包括增量条件、源表过滤、灰度同步、业务分区限制等。
     */
    private String filterConfig;

    /**
     * 分片或分区配置。
     * 这是未来并行执行、分区回放和分批补数的重要基础。
     */
    private String partitionConfig;

    /**
     * 重试策略配置。
     * 预留为 JSON，是为了允许未来表达分模式、分错误类型的差异化重试策略。
     */
    private String retryPolicy;

    /**
     * 超时策略配置。
     */
    private String timeoutPolicy;

    /**
     * 是否启用模板。
     * 禁用模板不会删除历史任务，只是阻止新任务继续引用它。
     */
    private Boolean enabled;

    /**
     * 创建人。
     */
    private Long createdBy;

    /**
     * 更新人。
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
