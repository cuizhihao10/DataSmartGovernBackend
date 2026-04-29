/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - QualityTargetValidationStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

/**
 * 质量规则目标校验状态常量。
 *
 * <p>规则能否执行，不只取决于规则状态是 ACTIVE，还取决于它描述的目标是否可被平台理解和扫描。
 * 例如字段规则必须知道数据源、表名、字段名；Kafka 规则必须知道 topic；文件规则必须知道路径。
 *
 * <p>把目标校验状态单独保存到规则表，可以让管理后台清楚展示：
 * 1. 规则是否还没校验；
 * 2. 规则目标是否已经满足当前扫描策略要求；
 * 3. 规则目标是否配置错误；
 * 4. 当前平台是否还不支持这种目标扫描。
 */
public final class QualityTargetValidationStatus {

    /**
     * 尚未校验。
     *
     * <p>新建或修改规则目标后可先进入该状态，等待人工或系统触发校验。
     */
    public static final String UNVALIDATED = "UNVALIDATED";

    /**
     * 校验通过。
     */
    public static final String VALIDATED = "VALIDATED";

    /**
     * 校验失败。
     *
     * <p>通常代表字段缺失、目标格式错误、必需的数据源 ID 为空等可修复问题。
     */
    public static final String INVALID = "INVALID";

    /**
     * 暂不支持。
     *
     * <p>目标类型合法，但当前平台还没有对应扫描策略或连接器能力。
     */
    public static final String UNSUPPORTED = "UNSUPPORTED";

    private QualityTargetValidationStatus() {
    }
}
