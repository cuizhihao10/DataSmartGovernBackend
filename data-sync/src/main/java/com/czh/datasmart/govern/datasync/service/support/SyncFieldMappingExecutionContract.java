/**
 * @Author : Cui
 * @Date: 2026/06/29 23:42
 * @Description DataSmart Govern Backend - SyncFieldMappingExecutionContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import lombok.Getter;

import java.util.List;

/**
 * 字段映射执行契约。
 *
 * <p>这个对象是 data-sync 控制面和后续 batch runner bridge 之间的“内部执行契约”，不是公开 API DTO。
 * 它会保存源字段、目标字段和主键字段等执行器真正需要使用的字段名；这些字段名虽然不是密码，
 * 但在企业项目里仍可能暴露业务数据模型，因此不能放进 worker claim 响应、runtime event、普通审计摘要或日志正文。</p>
 *
 * <p>为什么不直接让 worker 读取 {@code fieldMappingConfig} 原始 JSON：</p>
 * <p>1. 原始 JSON 未来可能包含转换表达式、默认值、脱敏规则、字段级异常处理和用户注释，敏感面比字段名更大；</p>
 * <p>2. 执行器真正需要的是“读哪些源字段、写哪些目标字段、冲突键是什么”，先解析为结构化契约可以避免每个 runner 重复解析；</p>
 * <p>3. 当前最小 JDBC bridge 尚不支持源字段改名到目标字段的中间转换，所以必须显式标记 {@code requiresFieldRenameTransform}，
 * 防止控制面误以为“字段映射已声明”就一定可以直接跑通真实读写。</p>
 *
 * <p>本类刻意不使用 Java record，也不生成 Lombok {@code @ToString}：默认 {@code Object#toString()} 不会输出字段内容，
 * 可以降低被误打日志时泄露字段模型的概率。真正需要调试时，应只打印 issueCodes、mappingCount 等低敏摘要。</p>
 */
@Getter
public class SyncFieldMappingExecutionContract {

    /**
     * 载荷策略说明。
     *
     * <p>该值用于提醒调用方：当前对象含有执行器内部字段名，只能在受控服务内存中流转，不能直接暴露给用户或 Agent 事件。</p>
     */
    public static final String PAYLOAD_POLICY = "INTERNAL_FIELD_MAPPING_EXECUTION_CONTRACT_DO_NOT_EXPOSE";

    /**
     * 字段映射 JSON 是否成功解析。
     *
     * <p>false 通常表示 JSON 语法错误或结构不是数组/{mappings:[...]}。解析失败时，后续执行器不应该猜测字段。</p>
     */
    private final boolean parseable;

    /**
     * 是否至少声明了一组可识别的字段映射。
     *
     * <p>真实写入器需要知道目标端写入列；如果映射为空，即使源/目标数据源可连接，也不应该进入最小执行闭环。</p>
     */
    private final boolean hasMappings;

    /**
     * 字段映射条数。
     *
     * <p>这是低敏摘要，可用于诊断或测试；公开接口如需展示，也应优先展示数量而非字段名正文。</p>
     */
    private final int mappingCount;

    /**
     * 源端读取字段列表。
     *
     * <p>该字段用于受控连接器运行时构建读取计划。它可能暴露业务表结构，例如 customer_id、phone_hash，
     * 因此不要进入公开响应或普通日志。</p>
     */
    private final List<String> selectedColumns;

    /**
     * 目标端写入字段列表。
     *
     * <p>JDBC 批量写入器会根据该列表生成参数顺序。当前最小 bridge 要求源字段名和目标字段名一致，
     * 否则读取出的 row key 与写入参数无法直接匹配，需要后续增加字段重命名/转换层。</p>
     */
    private final List<String> writeColumns;

    /**
     * 目标端主键或冲突键字段列表。
     *
     * <p>UPSERT、INSERT_IGNORE、REPLACE 等策略需要该列表生成冲突处理语义。当前模板只有单字段主键，
     * 这里仍使用列表，是为了后续支持复合唯一键时不再改桥接契约。</p>
     */
    private final List<String> primaryKeyColumns;

    /**
     * 是否需要字段改名或转换层。
     *
     * <p>当 sourceField 与 targetField 不一致时，该值为 true。当前最小 JDBC bridge 不支持中间 row key 改写，
     * 因此桥接计划会 fail-closed，并提示后续应实现 transform hook 后再放行。</p>
     */
    private final boolean requiresFieldRenameTransform;

    /**
     * 阻断类问题码。
     *
     * <p>这些编码只描述配置结构问题，不包含原始 JSON、SQL、样本数据、过滤表达式或连接信息。</p>
     */
    private final List<String> issueCodes;

    /**
     * 非阻断提示。
     *
     * <p>例如检测到字段改名时，解析器先作为 warning 记录；真正是否阻断由 bridge 根据当前 runner 能力决定。</p>
     */
    private final List<String> warnings;

    /**
     * 当前对象的敏感边界说明。
     */
    private final String payloadPolicy;

    public SyncFieldMappingExecutionContract(boolean parseable,
                                             boolean hasMappings,
                                             int mappingCount,
                                             List<String> selectedColumns,
                                             List<String> writeColumns,
                                             List<String> primaryKeyColumns,
                                             boolean requiresFieldRenameTransform,
                                             List<String> issueCodes,
                                             List<String> warnings) {
        this.parseable = parseable;
        this.hasMappings = hasMappings;
        this.mappingCount = mappingCount;
        this.selectedColumns = List.copyOf(selectedColumns);
        this.writeColumns = List.copyOf(writeColumns);
        this.primaryKeyColumns = List.copyOf(primaryKeyColumns);
        this.requiresFieldRenameTransform = requiresFieldRenameTransform;
        this.issueCodes = List.copyOf(issueCodes);
        this.warnings = List.copyOf(warnings);
        this.payloadPolicy = PAYLOAD_POLICY;
    }

    /**
     * 判断当前字段契约是否满足最小 JDBC bridge 的直接执行条件。
     *
     * <p>这里的“可执行”只表示字段契约层面通过，不代表数据源连接、权限审批、worker 容量、checkpoint 或真实写入一定成功。
     * 上层 bridge 仍会继续检查连接器类型、同步模式、写入策略、任务状态和 workerPlan。</p>
     */
    public boolean directlyRunnableByMinimalBridge() {
        return parseable
                && hasMappings
                && !writeColumns.isEmpty()
                && !requiresFieldRenameTransform
                && issueCodes.isEmpty();
    }
}
