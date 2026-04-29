/**
 * @Author : Cui
 * @Date: 2026/04/28 19:34
 * @Description DataSmart Govern Backend - QualityTaskPayload.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import lombok.Data;

import java.math.BigDecimal;

/**
 * DATA_QUALITY_SCAN 任务载荷。
 *
 * <p>这个类不是直接给前端调用的请求体，而是 data-quality 写入 task-management `params` 字段的
 * 版本化 JSON 合同。未来质量执行器从 task-management 认领任务后，会先解析这个 payload，
 * 再决定如何调用 data-quality 的 start/succeed/fail 回调。
 *
 * <p>为什么需要专门建模 payload，而不是继续使用 Map：
 * 1. 任务可能在队列中等待很久，规则和代码都可能升级，版本字段能帮助执行器做兼容判断；
 * 2. 执行器通常是独立进程，甚至可能是 Python/Go/Java 多语言实现，稳定字段比临时 Map 更适合跨服务协作；
 * 3. payload 是审计材料的一部分，必须能解释“当时按什么规则、什么计划、什么阈值执行”；
 * 4. 任务失败重试时，执行器应该基于同一份快照重试，而不是每次都重新读取可能已经变化的规则。
 */
@Data
public class QualityTaskPayload {

    /**
     * 当前 payload schema 版本。
     *
     * <p>建议格式使用稳定编码，例如 QUALITY_SCAN_TASK_V1。
     * 当后续新增分片扫描、CDC 窗口、动态采样、敏感字段脱敏策略等重大结构变化时，
     * 可以升级到 V2，并在解析器中保留 V1 兼容逻辑。
     */
    private String schemaVersion;

    /**
     * 产生任务载荷的源模块。
     *
     * <p>当前固定为 data-quality。执行器可用它判断任务来源，避免误消费其他模块误填的同名任务类型。
     */
    private String sourceModule;

    /**
     * 任务业务种类。
     *
     * <p>当前固定为 QUALITY_SCAN，用于和 task-management 的 type=DATA_QUALITY_SCAN 形成双重语义校验。
     */
    private String taskKind;

    /**
     * 租户 ID 快照。
     *
     * <p>质量执行器会用该字段参与租户级并发护栏，避免某个租户的质量扫描长期占满执行器。
     * 当前项目还处于 gateway/permission-admin 可信租户上下文逐步接入阶段，因此该字段先作为可选快照保留；
     * 后续生产化时，应由网关或服务账号上下文写入，而不是完全信任前端请求体。
     */
    private Long tenantId;

    /**
     * 质量规则 ID。
     *
     * <p>执行器回调 start 时会把该字段作为 ruleId 传回 data-quality。
     */
    private Long ruleId;

    /**
     * 规则名称快照。
     *
     * <p>用于任务列表、日志和执行器错误信息展示，即使规则后续改名，任务仍能解释提交时的业务含义。
     */
    private String ruleName;

    /**
     * 规则版本快照。
     */
    private Integer ruleVersion;

    /**
     * 规则类型快照。
     */
    private String ruleType;

    /**
     * 规则严重级别快照。
     */
    private String severity;

    /**
     * 比较运算符快照。
     */
    private String comparisonOperator;

    /**
     * 期望值快照。
     */
    private BigDecimal expectedValue;

    /**
     * 提交任务的业务原因。
     *
     * <p>例如人工复核、定时巡检、上线前校验、告警补偿等。
     */
    private String reason;

    /**
     * 本次任务实际使用的扫描计划。
     *
     * <p>执行器应优先信任这里的计划快照，而不是重新生成计划。
     */
    private QualityScanPlan scanPlan;
}
