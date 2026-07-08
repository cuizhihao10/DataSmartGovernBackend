/**
 * @Author : Cui
 * @Date: 2026/07/08 15:23
 * @Description DataSmart Govern Backend - SyncTaskCreateWizardContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardContractResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskWizardStepValidationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskWizardStepValidationResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTransferModeOption;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 同步任务创建向导合同支撑组件。
 *
 * <p>这个组件专门负责“创建同步任务页面应该遵守什么后端规则”。它不创建模板、不创建任务、不触碰真实源端或目标端数据，
 * 只返回低敏合同和单步校验结果。这样前端可以同步修复 UI，同时后端也不会把旧的执行器字段、审批字段、恢复动作继续暴露成表单项。</p>
 *
 * <p>当前合同重点解决用户指出的几个产品问题：</p>
 * <p>1. 租户/项目/工作空间由上下文自动推导，普通表单不填写数字 ID；</p>
 * <p>2. 源端和目标端数据源按 usagePurpose 分流；</p>
 * <p>3. 传输模式只保留全量、定期批量、定期全量、SQL 自定义、实时五类；</p>
 * <p>4. 对象映射和字段映射由元数据发现、选择、搜索、排除、改名、字段勾选生成，不让用户直接编辑大段 JSON；</p>
 * <p>5. 写入策略只保留 INSERT 和 UPDATE，主键/外键/字段数量/对象存在性在预检查阶段自动判断；</p>
 * <p>6. 审批确认、补数、回放、脏数据重放属于运行期运营能力，不属于新建任务向导。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskCreateWizardContractSupport {

    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncTransferModeCatalogSupport transferModeCatalogSupport;
    private final SyncTaskScheduleConfigSupport scheduleConfigSupport;

    /**
     * 构建创建向导合同。
     *
     * @param actorContext 当前操作者上下文，通常来自 gateway 注入 Header
     * @return 前端和 Agent 可消费的创建向导规则
     */
    public SyncTaskCreateWizardContractResponse buildContract(SyncActorContext actorContext) {
        Long tenantId = dataScopeSupport.resolveTenantForCreate(null, actorContext);
        Long projectId = dataScopeSupport.resolveProjectForCreate(null, actorContext);
        Long workspaceId = dataScopeSupport.resolveWorkspaceForCreate(null, actorContext);
        return new SyncTaskCreateWizardContractResponse(
                "datasmart.sync-task.create-wizard.v4",
                new SyncTaskCreateWizardContractResponse.ScopeBinding(
                        tenantId,
                        projectId,
                        workspaceId,
                        tenantId == null ? null : "租户 " + tenantId,
                        projectId == null ? null : "FlashSync 默认项目",
                        workspaceId == null ? null : "默认工作空间",
                        actorContext != null && (actorContext.projectId() != null || actorContext.workspaceId() != null),
                        true),
                wizardSteps(),
                transferModeCatalogSupport.listUserSelectableModes(),
                writeStrategies(),
                datasourceUsageContract(),
                metadataDiscoveryContract(),
                hiddenLowLevelFields(),
                backendValidationPrinciples(),
                false,
                false,
                false);
    }

    /**
     * 校验创建向导某一步是否满足进入下一步的基本条件。
     *
     * <p>注意：这里做的是“步骤级低敏校验”，不是最终执行预检查。真正的预检查还需要读取数据源元数据、目标表约束、字段类型、
     * SQL 语法、对象存在性、连接器能力和权限策略。因此本方法会把明显缺失的必填项作为 blocking，把需要真实元数据的检查作为下一步动作提示。</p>
     */
    public SyncTaskWizardStepValidationResponse validateStep(SyncTaskWizardStepValidationRequest request) {
        String stepCode = normalizeCode(request == null ? null : request.getStepCode(), "SOURCE_TARGET");
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> nextActions = new ArrayList<>();

        SyncMode mode = resolveMode(request == null ? null : request.getSyncMode(), blocking);
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(request == null ? null : request.getWriteStrategy(), blocking);
        String derivedRunMode = mode != null && mode.requiresTaskScheduleConfig() ? "SCHEDULED" : "MANUAL";

        switch (stepCode) {
            case "SOURCE_TARGET" -> validateSourceTargetStep(request, mode, blocking, warnings, nextActions);
            case "OBJECT_MAPPING" -> validateObjectMappingStep(request, mode, blocking, warnings, nextActions);
            case "FIELD_SQL" -> validateFieldSqlStep(request, mode, writeStrategy, blocking, warnings, nextActions);
            case "PRECHECK" -> validatePrecheckStep(request, mode, blocking, warnings, nextActions);
            default -> blocking.add("不支持的创建向导步骤: " + stepCode);
        }

        return new SyncTaskWizardStepValidationResponse(
                blocking.isEmpty(),
                stepCode,
                derivedRunMode,
                writeStrategy == null ? SyncWriteStrategy.INSERT.name() : normalizeUserFacingStrategy(writeStrategy),
                List.copyOf(blocking),
                List.copyOf(warnings),
                List.copyOf(nextActions));
    }

    private List<SyncTaskCreateWizardContractResponse.WizardStep> wizardSteps() {
        return List.of(
                new SyncTaskCreateWizardContractResponse.WizardStep(
                        "SOURCE_TARGET",
                        "源端/目标端",
                        1,
                        List.of("syncMode", "sourceDatasourceId", "targetDatasourceId"),
                        "校验通过后进入第二步时保存模板草稿，后续任务归属于当前项目/工作空间上下文",
                        List.of("源端和目标端不能相同", "数据源用途必须匹配源端/目标端角色", "定时模式不在本步骤手填 runMode"),
                        List.of("tenantId", "projectId", "workspaceId", "runMode")),
                new SyncTaskCreateWizardContractResponse.WizardStep(
                        "OBJECT_MAPPING",
                        "对象映射",
                        2,
                        List.of("syncScopeType", "objectMappingConfig 或 source/target object"),
                        "保存并进入下一步",
                        List.of("自动发现源端/目标端 schema、表、字段", "按 schema/表/schema+表筛选", "支持选择整个 schema 后排除部分表", "SQL 自定义模式只选择目标表"),
                        List.of("rawJsonEditorForObjectMapping")),
                new SyncTaskCreateWizardContractResponse.WizardStep(
                        "FIELD_SQL",
                        "字段/SQL",
                        3,
                        List.of("writeStrategy", "fieldMappingConfig 或 customSqlConfig"),
                        "保存并进入下一步",
                        List.of("普通模式校验字段映射数量和类型兼容", "SQL 模式校验只读 SQL、语法、库表存在性和别名字段映射"),
                        List.of("primaryKeyField", "incrementalField", "partitionConfig", "retryPolicy", "timeoutPolicy")),
                new SyncTaskCreateWizardContractResponse.WizardStep(
                        "PRECHECK",
                        "预检与创建",
                        4,
                        List.of("taskName", "groupCode"),
                        "运行预检查并创建任务",
                        List.of("连接器兼容性", "对象存在性", "目标主键/外键/约束", "字段数量和类型兼容", "SQL 安全", "调度配置合法性"),
                        List.of("approvalConfirmed", "approvalFactId", "manualRunModeSelector")));
    }

    private List<SyncTaskCreateWizardContractResponse.WriteStrategyOption> writeStrategies() {
        return List.of(
                new SyncTaskCreateWizardContractResponse.WriteStrategyOption(
                        SyncWriteStrategy.INSERT.name(),
                        "插入",
                        "向目标端插入新记录；目标约束冲突会在预检查或执行错误样本中体现",
                        SyncWriteStrategy.INSERT.toRunnerStrategy(),
                        false),
                new SyncTaskCreateWizardContractResponse.WriteStrategyOption(
                        SyncWriteStrategy.UPDATE.name(),
                        "更新",
                        "按目标端主键或唯一约束执行更新/合并；主键和冲突字段由预检查自动识别",
                        SyncWriteStrategy.UPDATE.toRunnerStrategy(),
                        false));
    }

    private SyncTaskCreateWizardContractResponse.DatasourceUsageContract datasourceUsageContract() {
        return new SyncTaskCreateWizardContractResponse.DatasourceUsageContract(
                "SOURCE",
                "TARGET",
                List.of("SOURCE", "TARGET"),
                "GET /datasources?usagePurpose=SOURCE 用于源端选择；GET /datasources?usagePurpose=TARGET 用于目标端选择",
                List.of(
                        "SOURCE 数据源只能作为源端读取",
                        "TARGET 数据源只能作为目标端写入",
                        "后端不再返回或接受 BOTH；如果同一个物理库既要读又要写，应分别登记源端连接和目标端连接",
                        "创建任务时源端和目标端数据源不能是同一个登记实例"));
    }

    /**
     * 构建创建向导的元数据发现合同。
     *
     * <p>这段合同解决的是“前端不应该手写 JSON、也不应该手动点击裸元数据请求按钮”的问题。
     * 前端进入对象映射步骤后，可以直接按 {@code objectDiscoveryApi} 对源端和目标端分别发起低敏元数据发现；
     * 用户选择表或目标表后，再按 {@code fieldMappingSuggestionApi} 生成字段映射建议。这样后端把
     * MySQL 无 PostgreSQL 风格 schema、SQL 自定义模式只选目标表、普通模式源表/目标表都要选择等规则集中声明，
     * 避免每个页面、Agent 工具或测试脚本各自猜一套流程。</p>
     */
    private SyncTaskCreateWizardContractResponse.MetadataDiscoveryContract metadataDiscoveryContract() {
        return new SyncTaskCreateWizardContractResponse.MetadataDiscoveryContract(
                "POST /sync-tasks/create-wizard/metadata/objects/discover",
                "POST /sync-tasks/create-wizard/metadata/field-mappings/suggest",
                "POST /sync-tasks/create-wizard/sql/check",
                List.of("TABLE", "SCHEMA", "SCHEMA_AND_TABLE", "CATALOG", "ALL"),
                List.of("SOURCE", "TARGET"),
                List.of(
                        "进入对象映射步骤后自动按 SOURCE/TARGET 两侧数据源拉取低敏 schema/table/field 摘要，不要求用户手动请求元数据",
                        "MySQL/MariaDB 使用 database/catalog/table 语义，不具备 PostgreSQL 风格 schema；选择 SCHEMA 或 SCHEMA_AND_TABLE 时应提示用户改用 TABLE 或 CATALOG",
                        "普通 FULL/SCHEDULED_FULL/SCHEDULED_BATCH 模式需要选择源端对象和目标端对象，支持按表、按 schema、按 schema+表搜索、勾选、排除和改名",
                        "选择整个 schema 或全库范围时，前端应允许用户排除部分表，后端后续预检查再判断对象是否存在、字段是否兼容和目标约束是否满足"),
                List.of(
                        "普通表到表模式下，字段映射建议接口基于同名字段和类型家族兼容性生成默认 syncEnabled 建议",
                        "字段是否同步、目标字段名、目标类型兼容性应以表格方式呈现并允许用户编辑，不应让用户直接编辑 fieldMappingConfig JSON",
                        "主键、冲突字段、外键、字段数量匹配不要求用户手填，应由预检查读取目标表约束后自动判断"),
                List.of(
                        "CUSTOM_SQL_QUERY 模式在对象映射步骤只要求选择目标表，源表和输出字段由只读 SQL 的 SELECT 列与别名决定",
                        "SQL 输入区应调用 POST /sync-tasks/create-wizard/sql/check 完成只读性、语法、源端库表字段存在性和输出列别名检查",
                        "SQL 模式字段映射应基于 SQL 输出列名或别名与目标表字段建立映射，不要求用户选择源端表"));
    }

    private List<String> hiddenLowLevelFields() {
        return List.of(
                "tenantId",
                "projectId",
                "workspaceId",
                "runMode",
                "approvalConfirmed",
                "approvalFactId",
                "primaryKeyField",
                "incrementalField",
                "partitionConfig",
                "retryPolicy",
                "timeoutPolicy",
                "rawObjectMappingJsonEditor",
                "rawFieldMappingJsonEditor");
    }

    private List<String> backendValidationPrinciples() {
        return List.of(
                "项目、工作空间和租户由可信上下文推导，request body 中的同名字段仅作为旧接口兼容兜底",
                "对象映射和字段映射应由元数据发现、搜索、勾选、排除、改名等交互生成，后端继续以结构化配置保存",
                "CUSTOM_SQL_QUERY 只要求选择目标对象，源端字段由 SQL select 列和别名决定",
                "定期全量和定期批量必须携带 scheduleConfig，其他模式不能通过 runMode 或 scheduleConfig 伪装成定时任务",
                "主键、外键、目标约束、字段数量、字段类型、对象存在性和 SQL 安全在预检查阶段自动判断",
                "失败回放、历史补数、脏数据修复重放属于任务运行期恢复能力，不作为新建任务模式展示");
    }

    private void validateSourceTargetStep(SyncTaskWizardStepValidationRequest request,
                                          SyncMode mode,
                                          List<String> blocking,
                                          List<String> warnings,
                                          List<String> nextActions) {
        if (request == null) {
            blocking.add("请求体不能为空");
            return;
        }
        if (request.getSourceDatasourceId() == null) {
            blocking.add("必须选择源端数据源");
        }
        if (request.getTargetDatasourceId() == null) {
            blocking.add("必须选择目标端数据源");
        }
        if (request.getSourceDatasourceId() != null
                && request.getSourceDatasourceId().equals(request.getTargetDatasourceId())) {
            blocking.add("源端和目标端数据源不能相同");
        }
        if (mode == SyncMode.SCHEDULED_FULL || mode == SyncMode.SCHEDULED_BATCH) {
            nextActions.add("进入后续步骤前请准备 scheduleConfig；定时模式的运行方式由 syncMode 自动推导为 SCHEDULED");
        }
        warnings.add("源端候选数据源应调用 usagePurpose=SOURCE，目标端候选数据源应调用 usagePurpose=TARGET");
        nextActions.add("进入对象映射步骤后自动调用元数据发现接口加载源端/目标端 schema、表和字段");
    }

    private void validateObjectMappingStep(SyncTaskWizardStepValidationRequest request,
                                           SyncMode mode,
                                           List<String> blocking,
                                           List<String> warnings,
                                           List<String> nextActions) {
        if (mode == SyncMode.CUSTOM_SQL_QUERY) {
            if (!hasText(request == null ? null : request.getTargetObjectName())
                    && !hasText(request == null ? null : request.getObjectMappingConfig())) {
                blocking.add("SQL 自定义传输在对象映射步骤至少需要选择目标表/目标对象");
            }
            warnings.add("SQL 自定义模式不需要选择源表，源字段由 SQL select 列和别名决定");
            nextActions.add("进入字段/SQL 步骤后填写只读 SQL，并基于 SQL 输出列生成字段映射");
            return;
        }
        if (!hasText(request == null ? null : request.getObjectMappingConfig())
                && (!hasText(request == null ? null : request.getSourceObjectName())
                || !hasText(request == null ? null : request.getTargetObjectName()))) {
            blocking.add("必须完成源端对象到目标端对象的映射；可以选择单表，也可以通过对象映射配置表达多表、schema 或全库范围");
        }
        nextActions.add("对象选择完成后，调用字段映射建议接口生成同名字段和类型兼容建议");
    }

    private void validateFieldSqlStep(SyncTaskWizardStepValidationRequest request,
                                      SyncMode mode,
                                      SyncWriteStrategy writeStrategy,
                                      List<String> blocking,
                                      List<String> warnings,
                                      List<String> nextActions) {
        if (writeStrategy != null && !writeStrategy.isUserFacingStrategy()) {
            blocking.add("创建向导写入策略只允许 INSERT 或 UPDATE");
        }
        if (mode == SyncMode.CUSTOM_SQL_QUERY) {
            if (!hasText(request == null ? null : request.getCustomSqlConfig())) {
                blocking.add("SQL 自定义传输必须提供 customSqlConfig");
            }
            nextActions.add("运行 SQL 语法检查、只读检查、库表存在性检查和输出列别名映射检查");
            return;
        }
        if (!hasText(request == null ? null : request.getFieldMappingConfig())) {
            warnings.add("尚未提交字段映射配置；如果前端已选择源/目标对象，应调用字段映射建议接口自动生成初始映射");
        }
        nextActions.add("预检查阶段将校验字段数量、字段类型、目标约束和 INSERT/UPDATE 写入策略可行性");
    }

    private void validatePrecheckStep(SyncTaskWizardStepValidationRequest request,
                                      SyncMode mode,
                                      List<String> blocking,
                                      List<String> warnings,
                                      List<String> nextActions) {
        if (mode != null && mode.requiresTaskScheduleConfig()) {
            if (!hasText(request == null ? null : request.getScheduleConfig())) {
                blocking.add("定期全量或定期批量必须提供 scheduleConfig");
            } else {
                scheduleConfigSupport.parseRequired(request.getScheduleConfig());
            }
        }
        if (mode != null && !mode.allowsTaskScheduleConfig()
                && hasText(request == null ? null : request.getScheduleConfig())) {
            blocking.add("非定时传输模式不能携带 scheduleConfig；请改用 SCHEDULED_FULL 或 SCHEDULED_BATCH");
        }
        warnings.add("预检查不再要求用户填写审批确认；高风险执行审批应进入后续发布/执行/恢复的专用流程");
        nextActions.add("调用模板预检查，确认连接器兼容性、对象存在性、字段映射、SQL 安全和目标表约束");
    }

    private SyncMode resolveMode(String syncMode, List<String> blocking) {
        if (!hasText(syncMode)) {
            blocking.add("必须选择同步模式");
            return null;
        }
        try {
            SyncMode mode = SyncMode.valueOf(syncMode.trim().toUpperCase(Locale.ROOT));
            if (!mode.isUserSelectableTransferMode()) {
                blocking.add("同步模式只能选择 FULL、SCHEDULED_BATCH、SCHEDULED_FULL、CUSTOM_SQL_QUERY、CDC_STREAMING");
                return null;
            }
            return mode;
        } catch (IllegalArgumentException exception) {
            blocking.add("不支持的同步模式: " + syncMode);
            return null;
        }
    }

    private SyncWriteStrategy resolveWriteStrategy(String writeStrategy, List<String> blocking) {
        try {
            SyncWriteStrategy strategy = SyncWriteStrategy.fromValue(writeStrategy);
            if (strategy == SyncWriteStrategy.APPEND) {
                return SyncWriteStrategy.INSERT;
            }
            if (strategy == SyncWriteStrategy.UPSERT) {
                return SyncWriteStrategy.UPDATE;
            }
            return strategy;
        } catch (IllegalArgumentException exception) {
            blocking.add(exception.getMessage());
            return null;
        }
    }

    private String normalizeUserFacingStrategy(SyncWriteStrategy strategy) {
        if (strategy == SyncWriteStrategy.APPEND) {
            return SyncWriteStrategy.INSERT.name();
        }
        if (strategy == SyncWriteStrategy.UPSERT) {
            return SyncWriteStrategy.UPDATE.name();
        }
        return strategy.name();
    }

    private String normalizeCode(String value, String defaultValue) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : defaultValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
