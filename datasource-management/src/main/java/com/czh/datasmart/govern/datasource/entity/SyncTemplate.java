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
 * 模板是“可重复复用的数据同步定义”，它处在数据源注册和同步任务之间：
 * 1. 数据源负责说明“连到哪里”。
 * 2. 模板负责说明“按照什么规则搬数据”。
 * 3. 任务负责说明“谁来执行、什么时候执行、当前执行到什么状态”。
 *
 * 之所以单独抽象模板层，而不是把所有配置都直接写进任务里，
 * 是因为企业级产品里通常会反复复用同一类同步方案，例如：
 * - 每天夜间把业务库订单表同步到治理库；
 * - 每小时把增量用户数据写入分析库；
 * - 对相同源表和目标表反复创建回放、补数、重试任务。
 *
 * 模板一旦独立存在，就可以承载更多治理能力：
 * - 模板启停；
 * - 模板审批；
 * - 模板智能校验；
 * - 模板版本化；
 * - 模板和执行任务解耦后的历史追踪。
 */
@Data
@TableName("sync_template")
public class SyncTemplate {

    /**
     * 模板主键。
     * 这是模板在数据库中的唯一标识，用于任务引用、审计记录关联和后续版本演进。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户标识。
     * 当前仓库还没有把真正的多租户隔离做完整，但同步域模型必须从一开始就保留租户维度，
     * 否则未来补权限、配额、审计和资源隔离时会出现大面积返工。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     * 同步模板不只是技术配置，它通常服务于某个业务项目的数据接入或数据治理目标。
     * 把项目归属固化在模板上，可以让 PROJECT 数据范围、项目级模板列表、项目级同步成本统计和项目级审计报表直接落地。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     * 工作空间用于在项目内部继续区分研发、测试、生产、临时分析等协作边界。
     * 当前字段允许为空，表示模板只归属到项目层；后续如果引入空间级权限，可在该字段上继续收敛。
     */
    private Long workspaceId;

    /**
     * 模板名称。
     * 一般会直接出现在模板列表、任务创建向导、审批界面和审计日志中，因此应具备业务可读性。
     */
    private String name;

    /**
     * 模板说明。
     * 用来向运维、开发、审计和平台管理员解释这份模板的业务目的与适用范围。
     */
    private String description;

    /**
     * 源端数据源 ID。
     * 说明数据从哪个注册过的数据源读取。
     */
    private Long sourceDatasourceId;

    /**
     * 源端 schema 名称。
     * 对关系型数据库而言，schema 可以帮助平台区分同名表，降低元数据发现与模板配置歧义。
     */
    private String sourceSchemaName;

    /**
     * 源端对象名称。
     * 当前阶段主要表示表名或视图名，后续也可以扩展为 Topic、文件对象、API 资源名等。
     */
    private String sourceObjectName;

    /**
     * 目标端数据源 ID。
     * 说明数据最终要写到哪个已注册的目标系统。
     */
    private Long targetDatasourceId;

    /**
     * 目标端 schema 名称。
     * 便于多 schema 目标库下的对象精确定位。
     */
    private String targetSchemaName;

    /**
     * 目标端对象名称。
     * 当前阶段主要表示目标表或目标视图名。
     */
    private String targetObjectName;

    /**
     * 同步模式。
     * 例如 FULL、INCREMENTAL_TIME、INCREMENTAL_ID、CDC、BACKFILL。
     * 同步模式不仅影响界面展示，更直接决定：
     * - 需要哪些关键字段；
     * - 检查点如何保存；
     * - 任务完成如何定义；
     * - 失败后如何重试与恢复。
     */
    private String syncMode;

    /**
     * 写入策略。
     * 这是本轮新增的重要字段，用来明确“目标端收到数据后如何落库”。
     * 常见策略包括：
     * - APPEND：只追加，不主动处理冲突；
     * - UPSERT：存在则更新，不存在则插入；
     * - INSERT_IGNORE：冲突时忽略；
     * - REPLACE：冲突时替换；
     * - OVERWRITE：先清空或覆盖后再写入。
     *
     * 把写入策略建模成一级字段的原因是：
     * 1. 它会直接影响目标端是否必须存在主键或唯一索引；
     * 2. 它会影响补数、回放、重试时是否容易产生重复或脏写；
     * 3. 它决定了模板校验时该重点检查哪些高风险问题。
     */
    private String writeStrategy;

    /**
     * 主键字段。
     * 这个字段在产品语义上更接近“冲突判定字段”或“幂等关键字段”，
     * 会影响去重、重试、回放、upsert 和冲突处理策略。
     */
    private String primaryKeyField;

    /**
     * 增量字段。
     * 当同步模式是时间增量或 ID 增量时，这个字段通常是必填项，
     * 它决定平台如何识别“上次跑到哪里”以及下一次应从哪里继续。
     */
    private String incrementalField;

    /**
     * 字段映射配置。
     * 当前先使用 JSON 字符串保存，以便快速支撑灵活的映射结构。
     * 后续如果需要做字段级版本管理、审批和更强审计能力，可以再拆成独立表。
     */
    private String fieldMappingConfig;

    /**
     * 过滤条件配置。
     * 常见场景包括：
     * - 只同步最近 7 天数据；
     * - 只同步某个租户或业务分区；
     * - 按灰度规则限制同步范围。
     */
    private String filterConfig;

    /**
     * 分片或分区配置。
     * 这是后续支持并行拉取、按分区回放、分批补数的重要基础。
     */
    private String partitionConfig;

    /**
     * 重试策略配置。
     * 预留为 JSON 的原因是企业级产品里往往需要表达：
     * - 最大重试次数；
     * - 指数退避；
     * - 可重试错误类型；
     * - 不同异常的差异化策略。
     */
    private String retryPolicy;

    /**
     * 超时策略配置。
     * 用于描述模板级别的超时控制，例如单次执行超时、批次超时、连接超时覆盖策略。
     */
    private String timeoutPolicy;

    /**
     * 是否启用模板。
     * 禁用并不代表删除历史任务，而是禁止新任务继续复用这份模板。
     */
    private Boolean enabled;

    /**
     * 创建人。
     * 便于后续配合权限系统实现“谁创建、谁负责、谁可修改”的治理规则。
     */
    private Long createdBy;

    /**
     * 更新人。
     * 用于追踪最后一次修改模板的人。
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
