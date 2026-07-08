/**
 * @Author : Cui
 * @Date: 2026/07/08 23:16
 * @Description DataSmart Govern Backend - PermissionProjectCreateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建项目请求。
 *
 * <p>前端页面只应让用户填写“项目名称、项目编码、项目描述”这类业务可理解信息。
 * tenantId、applicationId、ownerActorId 都属于系统上下文或管理字段：正式链路优先由 gateway 登录态、
 * 当前租户上下文和当前操作者推导；保留这些字段只是为了平台管理员跨租户开通、自动化脚本和本地 E2E。</p>
 *
 * <p>注意：这里故意没有 workspaceId。当前产品已经决定去掉用户可见工作空间层级，新项目创建后直接作为
 * datasource/data-sync/Agent 会话的归属范围；旧 workspace 字段仅保留在数据库和 Agent 内部兼容链路里。</p>
 */
public record PermissionProjectCreateRequest(
        /**
         * 目标租户 ID。
         *
         * <p>普通角色为空即可，服务层会使用操作者所在租户。只有平台管理员才允许显式指定其他租户。</p>
         */
        Long tenantId,

        /**
         * 所属应用 ID。
         *
         * <p>普通页面不传。服务层会自动选择当前租户的默认可用应用，例如 FlashSync。
         * 保留该字段主要是为了平台管理员或交付脚本在多应用租户下创建指定应用项目。</p>
         */
        Long applicationId,

        /**
         * 项目稳定编码。
         *
         * <p>可为空；为空时后端会在生成项目 ID 后自动生成 PROJECT_{projectId}。
         * 编码建议使用大写字母、数字、下划线或短横线，例如 CUSTOMER_SYNC、RISK_TAG_SYNC。</p>
         */
        @Size(max = 64, message = "projectCode 长度不能超过 64")
        String projectCode,

        /**
         * 项目展示名称。
         *
         * <p>这是项目切换器、数据源归属、同步任务归属和审计页面最常用的用户可读字段，因此必须填写。</p>
         */
        @NotBlank(message = "projectName 不能为空")
        @Size(max = 128, message = "projectName 长度不能超过 128")
        String projectName,

        /**
         * 项目类型。
         *
         * <p>可为空，默认 DATA_GOVERNANCE。后续可扩展为 SANDBOX、PRODUCTION、CUSTOMER_DELIVERY 等。</p>
         */
        @Size(max = 64, message = "projectType 长度不能超过 64")
        String projectType,

        /**
         * 项目负责人 actorId。
         *
         * <p>为空时默认使用当前操作者 actorId。创建成功后，服务层会自动给负责人写入 OWNER 项目成员授权。</p>
         */
        Long ownerActorId,

        /**
         * 项目描述。
         *
         * <p>建议填写业务域、数据同步目标、维护人或敏感等级说明。描述会进入审计，但禁止填写密码、Token、连接串等敏感信息。</p>
         */
        @Size(max = 1000, message = "description 长度不能超过 1000")
        String description,

        /**
         * 创建原因。
         *
         * <p>用于审计摘要。管理后台可把它做成可选输入，交付脚本可填写批次号或工单号。</p>
         */
        @Size(max = 500, message = "reason 长度不能超过 500")
        String reason) {
}
