/**
 * @Author : Cui
 * @Date: 2026/07/08 15:20
 * @Description DataSmart Govern Backend - SyncTaskCreateWizardContractResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 同步任务创建向导后端展示合同。
 *
 * <p>这个 DTO 的目标不是替前端画 UI，而是把“哪些字段应该展示、哪些字段由系统自动推导、哪些能力属于运行期恢复而不是创建模式”
 * 这些产品规则从代码和口头约定中显式暴露出来。前端可以用它驱动表单渲染，Agent 工具也可以用它生成更可靠的任务创建计划。</p>
 *
 * <p>关键设计原则：</p>
 * <p>1. 租户、项目、工作空间来自登录/网关上下文，不要求用户填写数字 ID；</p>
 * <p>2. 新建同步任务只展示 FULL、SCHEDULED_BATCH、SCHEDULED_FULL、CUSTOM_SQL_QUERY、CDC_STREAMING 五种传输模式；</p>
 * <p>3. 写入策略只展示 INSERT 和 UPDATE，主键、外键、字段兼容性、对象存在性放到预检查阶段自动判断；</p>
 * <p>4. 失败回放、历史补数、脏数据修复重放、离线导入导出属于任务详情/执行历史/恢复运营台能力，不污染新建任务模式。</p>
 *
 * @param contractVersion 合同版本；前端可以用它做灰度兼容或调试展示
 * @param scopeBinding 当前请求可感知的租户/项目/工作空间绑定
 * @param wizardSteps 创建向导步骤定义和每一步的保存语义
 * @param transferModes 用户可选择的传输模式
 * @param writeStrategies 用户可选择的写入策略
 * @param datasourceUsage 数据源用途筛选规则，指导前端源端/目标端下拉框如何调用 datasource-management
 * @param hiddenLowLevelFields 不应出现在普通创建页面的历史/底层字段
 * @param backendValidationPrinciples 后端预检查、校验和元数据发现的原则说明
 * @param approvalInCreateWizard 新建向导是否承载审批确认；当前固定为 false
 * @param runModeUserSelectable 运行模式是否允许用户选择；当前固定为 false
 * @param recoveryModesVisibleInCreateWizard 恢复类能力是否作为创建模式展示；当前固定为 false
 */
public record SyncTaskCreateWizardContractResponse(
        String contractVersion,
        ScopeBinding scopeBinding,
        List<WizardStep> wizardSteps,
        List<SyncTransferModeOption> transferModes,
        List<WriteStrategyOption> writeStrategies,
        DatasourceUsageContract datasourceUsage,
        MetadataDiscoveryContract metadataDiscovery,
        List<String> hiddenLowLevelFields,
        List<String> backendValidationPrinciples,
        boolean approvalInCreateWizard,
        boolean runModeUserSelectable,
        boolean recoveryModesVisibleInCreateWizard
) {

    /**
     * 当前请求在多租户、多项目、多工作空间下的归属。
     *
     * <p>名称字段当前先返回通用展示名，后续可以由 permission-admin 提供“当前可用应用/项目/工作空间”接口后再补成真实名称。
     * 即使名称暂时不完整，前端也不应该再让用户手填 ID，而是通过项目切换器和工作空间切换器改变上下文。</p>
     */
    public record ScopeBinding(
            Long tenantId,
            Long projectId,
            Long workspaceId,
            String tenantDisplayName,
            String projectDisplayName,
            String workspaceDisplayName,
            boolean derivedFromTrustedHeader,
            boolean requestBodyScopeFieldsDeprecated
    ) {
    }

    /**
     * 创建向导步骤。
     *
     * <p>{@code savePolicy} 用于表达用户体验：第一步校验通过后进入第二步时才视为“已保存草稿/模板事实”，
     * 第二步及以后“下一步”按钮应理解为“保存并进入下一步”。后端不会要求前端用 JSON 大文本一次性提交所有细节。</p>
     */
    public record WizardStep(
            String stepCode,
            String displayName,
            int stepOrder,
            List<String> requiredFields,
            String savePolicy,
            List<String> backendChecks,
            List<String> hiddenFields
    ) {
    }

    /**
     * 普通用户可见写入策略。
     */
    public record WriteStrategyOption(
            String strategy,
            String displayName,
            String description,
            String runnerCompatibilityStrategy,
            boolean requiresManualConflictField
    ) {
    }

    /**
     * 数据源用途筛选合同。
     *
     * <p>datasource-management 已支持 usagePurpose。前端在源端选择框只请求 SOURCE，在目标端选择框只请求 TARGET。
     * 后端不再支持 BOTH，这样可以避免用户把“只读源端库”误选为目标端写入库，或把“只写目标端库”误选为源端读取库。</p>
     */
    public record DatasourceUsageContract(
            String sourceSelectorUsage,
            String targetSelectorUsage,
            List<String> allowedUsageValues,
            String datasourceListApiHint,
            List<String> usageRules
    ) {
    }

    /**
     * 创建向导使用的元数据发现与字段映射合同。
     *
     * <p>这里刻意把“接口路径、筛选模式、步骤依赖、SQL 模式差异”放进后端合同，而不是让前端硬编码：
     * 创建任务页面需要先选源端/目标端数据源，再进入对象映射和字段映射步骤；如果前端只知道某个裸接口存在，
     * 很容易在 MySQL、PostgreSQL、SQL 自定义传输等场景下把参数传错。该合同相当于后端把产品规则显式公布出来，
     * 前端和 Agent 工具都能按同一套规则编排调用。</p>
     *
     * <p>安全边界：这些接口只返回 schema、表、字段、类型、主键等低敏结构信息，不返回样本数据、连接串、账号、
     * 密码、完整 SQL、where 条件正文或执行器内部计划。真正的读取数据与写入目标端仍由预检查和 worker 执行链路保护。</p>
     */
    public record MetadataDiscoveryContract(
            String objectDiscoveryApi,
            String fieldMappingSuggestionApi,
            List<String> filterModes,
            List<String> supportedSides,
            List<String> objectMappingRules,
            List<String> fieldMappingRules,
            List<String> customSqlRules
    ) {
    }
}
