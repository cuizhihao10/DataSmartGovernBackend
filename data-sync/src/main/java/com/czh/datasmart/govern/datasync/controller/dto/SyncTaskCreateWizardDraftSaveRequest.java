/**
 * @Author : Cui
 * @Date: 2026/07/08 22:35
 * @Description DataSmart Govern Backend - SyncTaskCreateWizardDraftSaveRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * 同步任务创建向导草稿保存请求。
 *
 * <p>这个 DTO 专门服务“新建同步任务四步向导”的渐进式保存，而不是替代最终发布/执行接口。
 * 用户在第一步选择源端、目标端、传输模式、任务名称和分组后，点击“保存并进入对象映射”，
 * 后端就应该创建一条带完整定义快照的 {@code DRAFT} 任务。这样即使用户关闭页面、刷新或稍后继续编辑，
 * 任务列表里也能看到“编辑中”的草稿，而不是把所有配置都悬挂在浏览器内存里。</p>
 *
 * <p>请求同时包含任务运营字段和任务定义字段，后端在同一个任务聚合中持久化；
 * 前端只需保存 taskId，不再维护第二个任务定义标识。</p>
 *
 * <p>安全边界：</p>
 * <p>草稿保存不会连接源端、不会写目标端、不会创建 execution、不会入队、不会启用调度。
 * 它只保存低敏控制面配置；目标 schema/table 是否存在、字段是否兼容、目标表是否有主键/外键、
 * SQL 是否真正可执行等，都留给第四步自动预检查和后续发布/执行链路判断。</p>
 */
@Data
public class SyncTaskCreateWizardDraftSaveRequest {

    /**
     * 已存在草稿任务 ID。
     *
     * <p>为空表示创建新草稿；有值表示继续编辑已有草稿。服务端只允许更新 {@code DRAFT} 状态任务，
     * 防止用户把已经发布、调度、运行或下线的正式任务通过创建向导悄悄改回半成品配置。</p>
     */
    private Long taskId;

    /**
     * 当前保存来自哪个创建向导步骤。
     *
     * <p>常见值为 SOURCE_TARGET、OBJECT_MAPPING、FIELD_SQL、PRECHECK。
     * 服务端不会因为 stepCode 跳过核心安全校验，但会把它写入响应建议和审计摘要，方便排查用户在哪一步保存了草稿。</p>
     */
    private String stepCode;

    /**
     * 历史兼容租户 ID。
     *
     * <p>普通页面不应让用户填写该字段。后端会优先使用 gateway 可信 Header 中的租户，
     * 只有平台管理员、服务账号或本地迁移脚本才允许显式指定。</p>
     */
    private Long tenantId;

    /**
     * 当前项目 ID。
     *
     * <p>产品层级已经收敛为“租户 -> 项目 -> 数据源/同步任务”。项目应来自前端项目切换器或 gateway Header，
     * 而不是要求用户在表单中手填数字 ID。保留该字段只是为了兼容旧请求体。</p>
     */
    private Long projectId;

    /**
     * 历史工作空间字段。
     *
     * <p>新建同步任务不再使用工作空间层级，因此服务端会忽略该字段并写入 null。
     * 它保留在请求体里是为了兼容旧前端或脚本，不代表业务模型又恢复了 workspace。</p>
     */
    private Long workspaceId;

    /**
     * 任务名称。
     *
     * <p>创建向导第一步必填。草稿任务落库后，任务列表依赖该字段展示“编辑中”的任务条目。</p>
     */
    private String taskName;

    /**
     * 兼容旧任务名称字段。
     *
     * <p>如果 taskName 为空但 name 有值，后端会用 name 作为草稿任务名称。
     * 新前端推荐只传 taskName，避免用户误解“任务定义名称”和“任务名称”是两个必填项。</p>
     */
    private String name;

    /**
     * 任务说明。
     */
    private String taskDescription;

    /**
     * 兼容旧任务说明字段。
     */
    private String description;

    /**
     * 任务分组编码。
     *
     * <p>为空时进入 DEFAULT/默认分组；如果传入自定义分组，服务端会校验该项目下分组是否存在。</p>
     */
    private String groupCode;

    /**
     * 任务分组展示名称。
     */
    private String groupName;

    /**
     * 任务优先级，例如 LOW、MEDIUM、HIGH、URGENT。
     */
    private String priority;

    /**
     * 定期全量、定期批量的调度配置 JSON。
     *
     * <p>DRAFT 保存阶段只持久化配置，不启用 scheduleEnabled、不计算 nextFireTime。
     * 只有第四步预检查通过并发布后，调度器才会把定期任务推进到等待调度。</p>
     */
    private String scheduleConfig;

    /**
     * 负责人 ID。
     *
     * <p>为空时后端使用当前 actorId。普通用户不需要手填负责人。</p>
     */
    private Long ownerId;

    private Long sourceDatasourceId;
    private Long targetDatasourceId;
    private String sourceSchemaName;
    private String sourceObjectName;
    private String targetSchemaName;
    private String targetObjectName;
    private String sourceConnectorType;
    private String targetConnectorType;
    private String syncMode;
    private String syncScopeType;
    private String writeStrategy;
    private String fieldMappingConfig;
    private String objectMappingConfig;
    private String filterConfig;
    private String customSqlConfig;
    private String partitionConfig;
    private String retryPolicy;
    /** Server-created low-sensitive authorization snapshot for unattended recovery. */
    private String autopilotPolicy;
    private String timeoutPolicy;
}
