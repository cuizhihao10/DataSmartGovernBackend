package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:42
 * @Description DataSmart Govern Backend - MetadataDiscoveryRequest.java
 * @Version:1.0.0
 *
 * 元数据发现请求。
 * 当前先做成轻量探查参数，而不是一次性支持非常复杂的采集策略，
 * 目的是让这个接口先用于：
 * - 连接器元数据预览；
 * - 模板配置时的源表选择；
 * - 字段映射前的结构查看。
 *
 * 随着项目往真实产品推进，请求参数也开始承担“性能保护”的职责，
 * 例如限制一次最多返回多少张表、每张表最多展开多少个字段、是否需要样本预览等。
 */
@Data
public class MetadataDiscoveryRequest {

    /**
     * 发起探查的操作者 ID。
     * 当前阶段主要用于审计和角色边界控制的入参承载。
     */
    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    /**
     * 发起探查的操作者角色。
     */
    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 发起探查的操作者所属租户。
     * 元数据探查虽然当前还没有完全落成数据源可见性策略，但租户上下文必须先被建模出来，
     * 否则后续接数据源授权、租户隔离和审计中心时会缺最基本的语义载体。
     */
    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;

    /**
     * catalog 过滤条件。
     * 某些数据库会使用 catalog 概念，某些数据库更偏向 schema，二者都预留能提升兼容性。
     */
    private String catalog;

    /**
     * schema 模式过滤条件。
     */
    private String schemaPattern;

    /**
     * 表名过滤条件。
     * 例如传入 `user%` 可以快速缩小扫描范围。
     */
    private String tableNamePattern;

    /**
     * 最多返回多少张表。
     * 默认做上限控制，是为了避免一次把海量元数据全部拉回来，导致接口过慢或前端难以处理。
     */
    @Min(value = 1, message = "maxTables 不能小于 1")
    @Max(value = 200, message = "maxTables 不能大于 200")
    private Integer maxTables;

    /**
     * 每张表最多返回多少个字段。
     * 对超宽表而言，这个限制可以显著降低响应大小与探查压力。
     */
    @Min(value = 1, message = "maxColumnsPerTable 不能小于 1")
    @Max(value = 500, message = "maxColumnsPerTable 不能大于 500")
    private Integer maxColumnsPerTable;

    /**
     * 是否返回字段明细。
     * 某些场景只需要看表清单，不需要立刻取全部字段，可以用这个开关降低响应开销。
     */
    private Boolean includeColumns;

    /**
     * 是否包含视图。
     */
    private Boolean includeViews;

    /**
     * 是否返回主键信息。
     */
    private Boolean includePrimaryKeys;

    /**
     * 是否返回索引信息。
     */
    private Boolean includeIndexes;

    /**
     * 是否返回样本数据。
     * 这是一个很有价值但也更容易带来性能和权限风险的能力，因此默认应谨慎使用。
     */
    private Boolean includeSampleRows;

    /**
     * 每张表最多返回多少行样本。
     */
    @Min(value = 1, message = "sampleRowLimit 不能小于 1")
    @Max(value = 50, message = "sampleRowLimit 不能大于 50")
    private Integer sampleRowLimit;
}
