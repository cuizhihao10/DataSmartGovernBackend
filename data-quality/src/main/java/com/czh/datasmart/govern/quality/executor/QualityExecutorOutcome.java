/**
 * @Author : Cui
 * @Date: 2026/05/05 23:18
 * @Description DataSmart Govern Backend - QualityExecutorOutcome.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor;

/**
 * 质量执行器运行结果编码。
 *
 * <p>把 outcome 常量从 coordinator 中独立出来，是为了避免多个协作组件各自硬编码字符串。
 * 在真实商业产品里，任务执行结果不仅用于接口返回，还会进入日志、指标、告警规则、运维看板和审计检索。
 * 如果字符串散落在不同类里，后续改名或新增状态时很容易出现“指标里叫 A、接口里叫 B、告警里叫 C”的割裂。
 *
 * <p>当前没有直接做成 enum，是因为 DTO 仍然以 String 暴露 outcome，方便前端、脚本和外部运维系统先按稳定文本消费。
 * 等质量执行器状态完全稳定后，可以再评估是否升级为 enum，并补充 OpenAPI 文档。
 */
public final class QualityExecutorOutcome {

    /**
     * 执行器或 task-management 集成开关未开启。
     */
    public static final String DISABLED = "DISABLED";

    /**
     * 本轮没有从 task-management 认领到可执行任务。
     */
    public static final String NO_TASK = "NO_TASK";

    /**
     * 关系型数据质量扫描成功完成，并已生成质量报告。
     */
    public static final String RELATIONAL_SCAN_SUCCEEDED = "RELATIONAL_SCAN_SUCCEEDED";

    /**
     * payload 合法，但当前执行器尚不支持对应规则、目标类型或扫描策略。
     */
    public static final String UNSUPPORTED_SCAN = "UNSUPPORTED_SCAN";

    /**
     * 执行过程中出现真实异常，例如远程调用失败、SQL 返回格式异常、序列化失败等。
     */
    public static final String FAILED_TO_PROCESS = "FAILED_TO_PROCESS";

    /**
     * 本实例并发护栏触发，任务已延期回队列，等待稍后重新认领。
     */
    public static final String THROTTLED_DEFERRED = "THROTTLED_DEFERRED";

    /**
     * 并发护栏反复触发，task-management 已把任务移入死信状态，需要运维人工处理。
     */
    public static final String THROTTLED_DEAD_LETTER = "THROTTLED_DEAD_LETTER";

    private QualityExecutorOutcome() {
        /*
         * 工具常量类不应该被实例化。
         * 私有构造方法可以让调用方明确知道这里只承载语义常量，不承载业务状态。
         */
    }
}
