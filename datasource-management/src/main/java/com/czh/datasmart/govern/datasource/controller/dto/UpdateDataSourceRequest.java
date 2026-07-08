package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - UpdateDataSourceRequest.java
 * @Version:1.0.0
 *
 * 更新数据源请求体。
 *
 * <p>更新接口允许调整名称、用途、连接地址、账号、密码和描述，但不允许直接修改 tenant/project/workspace 归属，
 * 也不允许修改 type。归属迁移属于治理动作，通常需要审计；类型变化则意味着它已经不是同一条数据源定义，
 * 更合理的做法是新建一条数据源。</p>
 */
@Data
public class UpdateDataSourceRequest {

    /**
     * 数据源名称。
     *
     * <p>名称会出现在数据源列表、源端/目标端选择器、任务详情和审计摘要中，因此不应该使用含糊的“测试库1”
     * 这类名字，最好能体现业务用途和端角色。</p>
     */
    @NotBlank(message = "数据源名称不能为空")
    private String name;

    /**
     * 数据源业务用途：SOURCE 或 TARGET。
     *
     * <p>编辑时允许管理员把一条数据源从源端用途改为目标端用途，或反向调整。但后端不再接受 BOTH，因为
     * “两端都可用”会削弱源端/目标端的权限边界，也会让新建同步任务页面难以给用户稳定筛选候选项。</p>
     */
    private String usagePurpose;

    /**
     * JDBC 连接地址。
     *
     * <p>更新连接地址后，建议立即调用连接测试或等待健康检查刷新，避免任务继续使用已经不可达的连接。</p>
     */
    @NotBlank(message = "JDBC 连接地址不能为空")
    private String jdbcUrl;

    /**
     * 连接用户名。
     *
     * <p>源端账号建议只保留 SELECT/元数据读取权限；目标端账号建议仅授予目标 schema/table 的写入权限。</p>
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 连接密码。
     *
     * <p>编辑时该字段允许为空：为空表示“不修改已有凭据”，避免前端为了改名称、用途或描述而要求用户重新输入密码。
     * 如果填写，则表示执行一次显式凭据轮换。生产化后应进一步升级为密钥托管引用和凭据轮换流程。</p>
     */
    private String password;

    /**
     * 描述信息。
     *
     * <p>用于记录用途限制、维护人、业务域、权限说明或接入注意事项。</p>
     */
    private String description;
}
