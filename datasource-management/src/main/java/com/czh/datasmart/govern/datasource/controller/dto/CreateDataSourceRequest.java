package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - CreateDataSourceRequest.java
 * @Version:1.0.0
 *
 * 创建数据源请求体。
 *
 * <p>新建数据源页面只应该让用户填写“业务可理解”的连接信息，例如名称、类型、用途、JDBC 地址和账号密码。
 * tenantId、projectId、workspaceId 属于系统治理上下文，不应该暴露成普通表单字段让用户手填。服务端仍保留这三个
 * 兼容字段，是为了旧脚本、本地 E2E 或暂未接入网关注入上下文的调用方可以继续运行；正式页面应优先通过
 * {@code X-DataSmart-Tenant-Id}、{@code X-DataSmart-Project-Id}、{@code X-DataSmart-Workspace-Id}
 * 这类 Header 表达“当前在哪个租户/项目/工作空间下创建”。</p>
 */
@Data
public class CreateDataSourceRequest {

    /**
     * 兼容字段：租户 ID。
     *
     * <p>普通页面不需要填写。服务端优先读取 gateway 注入的租户 Header；如果 Header 和请求体同时存在且不一致，
     * 后端会拒绝请求，避免用户通过篡改请求体把数据源写入其他租户。</p>
     */
    private Long tenantId;

    /**
     * 兼容字段：项目 ID。
     *
     * <p>项目来自当前页面的项目切换上下文，而不是用户手动输入。保留该字段只是为了旧脚本可继续运行，
     * 新前端应通过 Header 或统一上下文接口传递当前项目。</p>
     */
    private Long projectId;

    /**
     * 兼容字段：工作空间 ID。
     *
     * <p>工作空间用于区分开发、测试、生产或团队协作空间。它同样应该由系统上下文自动填充，而不是让业务用户
     * 理解并输入数据库主键。</p>
     */
    private Long workspaceId;

    /**
     * 数据源名称。
     *
     * <p>名称用于页面展示、任务配置选择和人工排障，建议使用能表达业务含义的名字，例如“订单库只读源端”
     * 或“FlashSync DWD 目标库”。</p>
     */
    @NotBlank(message = "数据源名称不能为空")
    private String name;

    /**
     * 数据源类型，例如 MYSQL、POSTGRESQL、SQLSERVER。
     *
     * <p>类型决定后端使用哪类 JDBC Driver、元数据读取策略和连接器能力画像。类型变化通常意味着它已经不是
     * 同一条数据源定义，因此更新接口不允许直接修改类型。</p>
     */
    @NotBlank(message = "数据源类型不能为空")
    private String type;

    /**
     * 数据源业务用途：SOURCE 或 TARGET。
     *
     * <p>SOURCE 表示该连接只能作为源端读取，TARGET 表示该连接只能作为目标端写入。后端不再接受 BOTH，
     * 也不再把空值默认为“两端都可用”，因为读写用途混在一起会让新建同步任务页面很难避免误选生产源库作为
     * 目标端。</p>
     */
    @NotBlank(message = "数据源用途不能为空，请选择源端或目标端")
    private String usagePurpose;

    /**
     * JDBC 连接地址。
     *
     * <p>当前阶段为了快速闭环仍由页面提交 JDBC URL；生产化后应继续演进为凭据托管、Secret Manager、
     * 连接模板和敏感信息脱敏展示。</p>
     */
    @NotBlank(message = "JDBC 连接地址不能为空")
    private String jdbcUrl;

    /**
     * 连接用户名。
     *
     * <p>建议源端使用只读账号，目标端使用写入范围受控的账号。账号权限越清晰，后续预检查、审计和事故定位越可靠。</p>
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 连接密码。
     *
     * <p>当前开发阶段仍通过请求体提交；生产环境应使用密钥托管、加密存储、轮换策略和审计记录，避免明文长期暴露。</p>
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 数据源描述。
     *
     * <p>用于补充说明数据源业务归属、权限边界、维护人、用途限制等信息，方便后续任务创建和运维排障。</p>
     */
    private String description;
}
