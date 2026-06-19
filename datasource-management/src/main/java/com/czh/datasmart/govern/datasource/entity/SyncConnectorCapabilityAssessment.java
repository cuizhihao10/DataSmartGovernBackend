package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/06/20 02:00
 * @Description DataSmart Govern Backend - SyncConnectorCapabilityAssessment.java
 * @Version:1.0.0
 *
 * 同步模板连接器能力评估结果。
 *
 * <p>能力画像描述“某类连接器能做什么”，而能力评估描述“当前这份模板的源端、目标端、同步模式和写入策略是否匹配”。
 * 两者分离后，前端可以直接查询画像，后端也可以在模板创建、更新、智能校验时复用同一套判断逻辑。</p>
 *
 * <p>该对象只包含低敏配置层信息，不包含连接串、用户名、密码、SQL、样本数据或同步数据本身。
 * 这是因为能力评估通常会出现在模板校验页、审批页、审计页和运维诊断页，必须从设计上避免泄露敏感资产。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncConnectorCapabilityAssessment {

    /**
     * 本次评估是否通过。
     * true 表示没有阻断错误；warnings 仍可能存在，需要运营或审批人员关注。
     */
    private boolean passed;

    /**
     * 源端连接器类型。
     */
    private String sourceConnectorType;

    /**
     * 目标端连接器类型。
     */
    private String targetConnectorType;

    /**
     * 模板声明的同步模式。
     */
    private String syncMode;

    /**
     * 模板声明的目标端写入策略。
     */
    private String writeStrategy;

    /**
     * 阻断错误。
     * 这些问题会导致模板不应进入执行，例如源连接器不支持该同步模式、目标端不支持该写入策略。
     */
    private List<String> errors;

    /**
     * 风险告警。
     * 这些问题不一定阻断模板，但需要审批、运维或执行器在生产落地前关注。
     */
    private List<String> warnings;

    /**
     * 性能建议。
     * 这里沉淀批量写入、分区并行、检查点、水位线、背压等方向，帮助后续从“能跑”演进到“跑得稳”。
     */
    private List<String> performanceRecommendations;

    /**
     * 下一步建设建议。
     * 用于告诉产品和研发：如果要把当前连接器组合推向更完整闭环，应优先补哪些能力。
     */
    private List<String> recommendedNextCapabilities;

    /**
     * 评估时间。
     */
    private LocalDateTime assessedAt;
}
