/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - GrantDataSourceAuthorizationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 授予数据源访问权请求。
 *
 * <p>该请求用于数据源管理页面的“授权给其他用户/角色”。前端不应该直接修改 datasource_config，
 * 而应该通过这个专用合同写入授权账本。这样后端可以统一完成主体类型校验、动作校验、授权过期时间校验和审计字段填充。</p>
 */
@Data
public class GrantDataSourceAuthorizationRequest {

    /**
     * 授权主体类型：USER、ROLE、SERVICE_ACCOUNT。
     */
    @NotBlank(message = "授权主体类型不能为空")
    private String subjectType;

    /**
     * 授权主体 ID。USER 场景通常是 actorId，ROLE 场景可以是角色编码。
     */
    @NotBlank(message = "授权主体 ID 不能为空")
    private String subjectId;

    /**
     * 授权主体展示名称，例如“治理运营 operator”。
     */
    private String subjectName;

    /**
     * 授权主体角色快照。按角色授权时建议填写角色编码，按用户授权时可填写该用户当前角色。
     */
    private String subjectRole;

    /**
     * 授权动作集合，支持 VIEW、USE、MANAGE。
     */
    @NotEmpty(message = "授权动作列表不能为空")
    private List<String> authorizedActions;

    /**
     * 授权来源。为空时服务端默认写 UI_MANUAL。
     */
    private String grantSource;

    /**
     * 授权原因，用于审计追踪。
     */
    private String grantReason;

    /**
     * 授权过期时间。临时排障、短期协作建议设置该字段。
     */
    private LocalDateTime expireTime;
}
