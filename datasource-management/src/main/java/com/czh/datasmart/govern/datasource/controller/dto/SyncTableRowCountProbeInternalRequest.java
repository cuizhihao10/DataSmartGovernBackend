/**
 * @Author : Cui
 * @Date: 2026/07/09 22:35
 * @Description DataSmart Govern Backend - SyncTableRowCountProbeInternalRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 同步目标表行数探测 internal 请求。
 *
 * <p>这个 DTO 服务于 data-sync 创建任务第四步“预检查”中的目标表空表判断。它和普通元数据发现不同：
 * 元数据发现只读取 schema/table/column 结构，而本请求会在 datasource-management 内部执行受控
 * {@code COUNT(*)}，用于回答一个商用同步产品必须提前说明的问题：全量 INSERT 写入前，目标表是否已经有数据。</p>
 *
 * <p>安全边界：</p>
 * <p>1. data-sync 只传 datasourceId、connectorType 和低敏对象定位，不传 JDBC URL、账号、密码；</p>
 * <p>2. datasource-management 会自行根据 datasourceId 打开只读连接，并对 objectLocator 逐段做安全标识符校验；</p>
 * <p>3. 响应只返回行数、是否为空、耗时和低敏 warning，不返回 SQL、样本行、业务字段值或异常堆栈；</p>
 * <p>4. 该请求只能通过 internal 服务账号路由调用，不能暴露给普通前端作为任意表扫描工具。</p>
 */
@Getter
@Setter
public class SyncTableRowCountProbeInternalRequest {

    /**
     * 目标端数据源 ID。
     *
     * <p>这里通常是同步任务的 targetDatasourceId。真实连接配置仍由 datasource-management 持有，
     * data-sync 只引用登记事实，不接触凭据。</p>
     */
    @NotNull(message = "datasourceId 不能为空")
    private Long datasourceId;

    /**
     * 目标连接器类型，例如 MYSQL、POSTGRESQL、SQLSERVER。
     *
     * <p>服务端使用该字段选择安全引用符：MySQL 使用反引号，PostgreSQL 使用双引号，SQL Server 使用方括号。</p>
     */
    @NotBlank(message = "connectorType 不能为空")
    private String connectorType;

    /**
     * 目标对象定位。
     *
     * <p>允许 table、schema.table 或 database.schema.table 三种形式；每一段必须是普通标识符，
     * 不允许空格、引号、函数、注释或 SQL 片段。PostgreSQL/SQL Server 建议使用 schema.table，
     * MySQL 通常使用 table，必要时也可以使用 database.table。</p>
     */
    @NotBlank(message = "objectLocator 不能为空")
    private String objectLocator;

    /**
     * 调用目的。
     *
     * <p>当前默认由 data-sync 传入 {@code PRECHECK_INSERT_TARGET_EMPTY}，便于 datasource-management
     * 后续按目的做审计、限流或差异化超时策略。</p>
     */
    private String purpose;
}
