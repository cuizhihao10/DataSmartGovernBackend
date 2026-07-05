/**
 * @Author : Cui
 * @Date: 2026/07/05 15:30
 * @Description DataSmart Govern Backend - SyncFilterExecutionContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import lombok.Getter;

import java.util.List;

/**
 * 过滤条件执行契约。
 *
 * <p>同步模板中的 {@code filterConfig} 是用户配置面对象，可能来自前端表单、Agent 生成或历史配置导入。
 * 真实执行器不能直接读取原始 JSON 并拼 SQL，因此 data-sync 先把它解析成这个“可执行契约”：
 * 只保留安全字段名、标准化操作符和值，后续由 datasource-management JDBC 方言层生成 PreparedStatement。</p>
 *
 * <p>该契约的设计目的不是覆盖全部 SQL where 能力，而是为商业化数据同步产品提供第一条可审计、安全、可测试的过滤路径。
 * 如果未来要支持复杂表达式，应新增专门的表达式 AST、审批和 explain 预检查，而不是放宽当前最小契约。</p>
 */
@Getter
public class SyncFilterExecutionContract {

    public static final String PAYLOAD_POLICY = "INTERNAL_FILTER_EXECUTION_CONTRACT_DO_NOT_EXPOSE";

    /**
     * filterConfig 是否存在。
     */
    private final boolean declared;

    /**
     * filterConfig 是否能被解析。
     */
    private final boolean parseable;

    /**
     * 可执行过滤条件列表。
     *
     * <p>条件值可能是敏感业务范围信息，只能用于 internal run-once 请求和 PreparedStatement 参数绑定。</p>
     */
    private final List<SyncFilterExecutionCondition> conditions;

    /**
     * 阻断问题码。
     */
    private final List<String> issueCodes;

    /**
     * 非阻断提示。
     */
    private final List<String> warnings;

    private final String payloadPolicy;

    public SyncFilterExecutionContract(boolean declared,
                                       boolean parseable,
                                       List<SyncFilterExecutionCondition> conditions,
                                       List<String> issueCodes,
                                       List<String> warnings) {
        this.declared = declared;
        this.parseable = parseable;
        this.conditions = List.copyOf(conditions);
        this.issueCodes = List.copyOf(issueCodes);
        this.warnings = List.copyOf(warnings);
        this.payloadPolicy = PAYLOAD_POLICY;
    }

    /**
     * 判断当前过滤契约能否被最小 JDBC run-once 执行。
     *
     * <p>未声明 filterConfig 是合法情况，表示不追加 where 条件。
     * 已声明但解析失败、字段不安全、操作符不支持或值缺失时，必须阻断真实读写。</p>
     */
    public boolean directlyRunnableByMinimalBridge() {
        return parseable && issueCodes.isEmpty();
    }
}
