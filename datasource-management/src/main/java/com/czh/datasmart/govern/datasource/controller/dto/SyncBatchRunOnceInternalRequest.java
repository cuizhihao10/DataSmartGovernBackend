/**
 * @Author : Cui
 * @Date: 2026/06/29 12:02
 * @Description DataSmart Govern Backend - SyncBatchRunOnceInternalRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 内部单批同步执行请求。
 *
 * <p>该 DTO 是 data-sync 后续调用 datasource-management 受控 connector runtime 的服务间请求体。
 * 它不是浏览器、普通管理台或 Agent 事件可以直接构造的公开接口。请求中包含字段清单、对象定位、checkpoint 起点等
 * 执行器内部事实，因此必须通过 internal 路由、服务账号 Header、网关签名或服务网格鉴权保护。</p>
 *
 * <p>为什么不直接复用旧 {@code SyncBatchExecutionRunner} 的控制面请求：</p>
 * <p>旧 Runner 会调用 datasource-management 自己的 {@code SyncTaskService} 更新进度、完成和失败状态。
 * data-sync 作为新的同步控制面，必须自己管理 execution、checkpoint、lease 和 complete/fail。
 * 因此本请求只要求 datasource-management 执行“单批 read/write”，不让它替上游改状态表。</p>
 *
 * <p>安全边界：</p>
 * <p>本类刻意不使用 Lombok {@code @Data}，避免默认 {@code toString()} 把 checkpointValue、字段名或对象名打印到日志。
 * Controller 和 Service 也不应该主动打印完整请求体。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchRunOnceInternalRequest {

    /**
     * 控制面生成的低敏执行计划。
     *
     * <p>计划中包含 datasourceId、connectorType、objectLocator、读写策略、checkpoint 类型和运行控制建议，
     * 但不包含 JDBC URL、账号、密码、SQL、where 条件正文或样本数据。</p>
     */
    @Valid
    @NotNull(message = "executionPlan 不能为空")
    private SyncBatchExecutionPlan executionPlan;

    /**
     * 源端读取字段清单。
     *
     * <p>字段名属于业务模型元数据，只能通过 internal 请求传入执行器，不应进入公开 workerPlan 或 runtime event。</p>
     */
    private List<String> selectedColumns;

    /**
     * 目标端写入字段清单。
     *
     * <p>写入 SQL 必须显式声明字段顺序，所以该字段不能为空。字段映射解析失败时，上游不应调用 run-once。</p>
     */
    @NotEmpty(message = "writeColumns 不能为空")
    private List<String> writeColumns;

    /**
     * 目标端主键或唯一键字段。
     *
     * <p>UPSERT、INSERT_IGNORE、REPLACE 等冲突感知写入需要它生成幂等写入语义。当前支持单字段和未来复合键列表。</p>
     */
    private List<String> primaryKeyColumns;

    /**
     * 服务账号或执行器操作者 ID。
     *
     * <p>当前 run-once 不写状态表，但仍保留 actor 字段，是为了后续接入审计、限流、配额和 worker receipt 时不用改变协议。</p>
     */
    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    /**
     * 服务账号或执行器角色。
     *
     * <p>通常为 SERVICE_ACCOUNT 或 WORKER。Controller 还会通过 Header 再校验 internal 调用方身份。</p>
     */
    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 调用方所属租户。
     *
     * <p>数据同步执行属于强租户隔离链路；即使当前只执行一批数据，也必须把租户事实带入内部契约。</p>
     */
    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;

    /**
     * 分片或分区标识。
     *
     * <p>单分片任务可为空。后续并发分片、日期分区补数、Kafka partition 和对象存储 manifest 都会复用这个字段。</p>
     */
    private String shardOrPartition;

    /**
     * 当前批次读取起点 checkpoint。
     *
     * <p>这是内部敏感执行值，可能透露业务时间、水位范围或数据增长节奏。它只允许进入 JDBC PreparedStatement 参数绑定，
     * 不允许写入公开响应、普通日志、runtime event、投影或文档。</p>
     */
    private Object checkpointValue;

    /**
     * 调用 run-once 前 data-sync execution 已累计读取记录数。
     *
     * <p>connector runtime 只知道本批读写数量；累计值由上游控制面传入后用于返回新的累计摘要。</p>
     */
    private Long previousRecordsRead;

    /**
     * 调用 run-once 前 data-sync execution 已累计写入记录数。
     */
    private Long previousRecordsWritten;

    /**
     * 调用 run-once 前 data-sync execution 已累计失败记录数。
     */
    private Long previousFailedRecordCount;
}
