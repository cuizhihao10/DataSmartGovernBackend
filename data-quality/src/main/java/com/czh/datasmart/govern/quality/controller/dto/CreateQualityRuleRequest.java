/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - CreateQualityRuleRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建质量规则请求体。
 * DTO 的职责是描述接口允许外部提交哪些字段，以及这些字段在入口层的基本合法性。
 * 它不直接表达数据库结构，也不承载复杂业务逻辑。
 */
@Data
public class CreateQualityRuleRequest {

    /**
     * 租户 ID。
     *
     * <p>创建规则时显式要求传入 tenantId，是为了让质量规则从诞生开始就带有租户边界。
     * 后续如果接入 gateway 的租户 Header，可以在 Controller 层继续校验“请求体 tenantId 与 Header tenantId 是否一致”。</p>
     */
    @NotNull(message = "租户 ID 不能为空")
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>项目是当前阶段最关键的二级隔离维度。质量规则、报告、异常样本都会继承该字段，
     * 从而支持项目级规则列表、项目级质量大盘、项目级异常工作台和项目负责人权限边界。</p>
     */
    @NotNull(message = "项目 ID 不能为空")
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>工作空间用于在项目内继续拆分研发、测试、生产或团队协作空间。
     * 现阶段允许为空，表示只隔离到项目级；后续如果引入空间级权限，可直接复用该字段。</p>
     */
    private Long workspaceId;

    /**
     * 规则名称。
     * 用于列表展示、搜索和管理识别，因此要求非空。
     */
    @NotBlank(message = "规则名称不能为空")
    private String name;

    /**
     * 规则类型。
     * 例如完整性、唯一性、有效性等，用于说明规则属于哪一类质量维度。
     */
    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    /**
     * 检测目标。
     * 当前先用字符串表达，后续可以扩展成更结构化的字段定位模型。
     */
    @NotBlank(message = "检测目标不能为空")
    private String targetObject;

    /**
     * 检测目标类型。
     *
     * <p>允许为空，服务层会默认使用 GENERIC 兼容旧接口。
     * 如果要让规则未来具备真实扫描能力，建议填写 RELATIONAL_TABLE、RELATIONAL_FIELD、KAFKA_TOPIC、FILE_OBJECT 或 API_ENDPOINT。
     */
    private String targetType;

    /**
     * 数据源 ID。
     *
     * <p>关系型表/字段规则建议填写 datasource-management 中的数据源 ID。
     */
    private Long dataSourceId;

    /**
     * 数据库名称。
     */
    private String databaseName;

    /**
     * Schema 名称。
     */
    private String schemaName;

    /**
     * 表名。
     */
    private String tableName;

    /**
     * 字段名。
     *
     * <p>RELATIONAL_FIELD 类型规则建议填写，用于定位具体质量检测字段。
     */
    private String fieldName;

    /**
     * 比较运算符。
     * 例如 GT、GTE、EQ 等，用于定义“如何判断是否合格”。
     */
    @NotBlank(message = "比较运算符不能为空")
    private String comparisonOperator;

    /**
     * 期望值或阈值。
     * 当前要求不能小于 0，主要面向数值类检测场景。
     */
    @NotNull(message = "期望值不能为空")
    @DecimalMin(value = "0", inclusive = true, message = "期望值必须大于等于 0")
    private BigDecimal expectedValue;

    /**
     * 严重级别。
     * 允许为空，服务层会补默认值。
     */
    private String severity;

    /**
     * 规则描述。
     */
    private String description;
}
