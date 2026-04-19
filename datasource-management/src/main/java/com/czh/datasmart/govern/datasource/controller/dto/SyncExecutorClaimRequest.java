package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/19 19:18
 * @Description DataSmart Govern Backend - SyncExecutorClaimRequest.java
 * @Version:1.0.0
 *
 * 执行器认领任务请求。
 * 这个请求的定位不是“用户手工操作”，而是未来真实执行器节点向控制面发起的工作拉取动作。
 *
 * 当前先保留这些核心信息：
 * - 谁在认领：actorId + actorRole；
 * - 哪个执行器实例在认领：executorId；
 * - 可选的租户范围；
 * - 可选的运行模式和同步模式能力过滤。
 *
 * 这些过滤条件不是为了把逻辑做复杂，而是为了给未来多种执行器池预留能力：
 * - 有些执行器只处理回放或补数；
 * - 有些执行器只处理批处理，不处理流式任务；
 * - 有些执行器只服务某个租户或专属资源池。
 */
@Data
public class SyncExecutorClaimRequest {

    /**
     * 操作者 ID。
     * 对 SERVICE_ACCOUNT 场景，通常可传系统账户或执行器绑定的服务账号 ID。
     */
    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    /**
     * 操作者角色。
     */
    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 执行器实例标识。
     * 例如容器实例名、节点 ID、工作进程名等。
     */
    @NotBlank(message = "executorId 不能为空")
    private String executorId;

    /**
     * 可选租户范围。
     * 为空时表示在当前可见范围内认领任务。
     */
    private Long tenantId;

    /**
     * 执行器支持的运行模式列表。
     * 例如只接 MANUAL、SCHEDULED，或专门接 BACKFILL。
     */
    private List<String> supportedRunModes;

    /**
     * 执行器支持的同步模式列表。
     * 例如只接 FULL、INCREMENTAL_TIME，不接 CDC。
     */
    private List<String> supportedSyncModes;
}
