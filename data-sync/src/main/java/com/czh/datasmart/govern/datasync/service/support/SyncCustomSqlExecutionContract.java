/**
 * @Author : Cui
 * @Date: 2026/07/07 23:58
 * @Description DataSmart Govern Backend - SyncCustomSqlExecutionContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 自定义 SQL 内部执行合同。
 *
 * <p>该 record 只在 data-sync 服务端内部使用。它把 customSqlConfig 的解析结果拆成“是否可执行”、
 * “只读 SQL 正文”、“低敏 SQL 指纹”和“问题码”。之所以不用普通字符串返回，是为了让调用方在代码结构上
 * 明确区分 SQL 正文和低敏诊断信息，避免后续不小心把 SQL 正文写到日志或普通响应。</p>
 *
 * @param executable 当前自定义 SQL 是否通过 v1 执行门禁。
 * @param sql 只读 SQL 正文，只能进入 internal run-once 请求。
 * @param sqlFingerprint SQL 指纹，可用于审计聚合，不暴露 SQL 正文。
 * @param issueCodes 阻断原因码，不包含 SQL 正文。
 * @param warnings 非阻断提示，不包含 SQL 正文。
 */
public record SyncCustomSqlExecutionContract(
        boolean executable,
        String sql,
        String sqlFingerprint,
        List<String> issueCodes,
        List<String> warnings
) {
    public SyncCustomSqlExecutionContract {
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
