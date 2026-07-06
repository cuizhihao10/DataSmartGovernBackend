/**
 * @Author : Cui
 * @Date: 2026/07/07 23:20
 * @Description DataSmart Govern Backend - SyncPartitionRangeProbeInternalRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 分片范围探测 internal 请求。
 *
 * <p>该 DTO 服务于 DataX-style {@code splitPk} 自动范围切分。data-sync 控制面在不知道源表
 * {@code splitPk} 最小值和最大值时，会通过 datasource-management 的 internal 路由发起一次只读探测；
 * datasource-management 使用受控 JDBC 连接执行 {@code MIN/MAX/COUNT}，再把低敏探测结果返回给 data-sync。</p>
 *
 * <p>为什么探测接口独立于 run-once：</p>
 * <p>1. range probe 是只读元数据/统计探测，不写目标端；run-once 会真实读写业务数据，两者副作用等级不同；</p>
 * <p>2. 独立接口便于后续给 probe 设置更短超时、专门限流和单独审计；</p>
 * <p>3. data-sync 可以先生成分片账本，再按 channel/taskGroup 有界并发执行 run-once。</p>
 *
 * <p>安全边界：</p>
 * <p>请求中包含对象定位和分片字段，属于内部执行元数据，只能通过服务账号 internal 路由传输。
 * Controller 和 Service 不应打印完整请求体，也不能把该对象返回给普通管理台。</p>
 */
@Getter
@Setter
public class SyncPartitionRangeProbeInternalRequest {

    /**
     * 源端数据源 ID。
     *
     * <p>探测只打开源端只读连接，不触碰目标端数据源。真实连接串和凭据仍由 datasource-management
     * 内部连接提供者读取，data-sync 不接触凭据。</p>
     */
    @NotNull(message = "datasourceId 不能为空")
    private Long datasourceId;

    /**
     * 源端连接器类型，例如 MYSQL、POSTGRESQL。
     *
     * <p>当前探测服务用它选择最小安全引用符。后续可替换为正式方言接口，而不改变 data-sync 请求合同。</p>
     */
    @NotBlank(message = "connectorType 不能为空")
    private String connectorType;

    /**
     * 源端对象定位，通常为 schema.table 或 table。
     *
     * <p>服务端会逐段校验标识符，不允许表达式、函数、注释、空格、引号或 SQL 片段。</p>
     */
    @NotBlank(message = "objectLocator 不能为空")
    private String objectLocator;

    /**
     * DataX-style 分片字段，也就是 DataX 常说的 splitPk。
     *
     * <p>当前第一阶段只支持数值型 splitPk，因为范围均分需要做 min/max 数值计算。时间窗口和 hash bucket
     * 后续应作为独立策略扩展，不能把复杂表达式塞进 splitPk 字段。</p>
     */
    @NotBlank(message = "splitPk 不能为空")
    private String splitPk;
}
