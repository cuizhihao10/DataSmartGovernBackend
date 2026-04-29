package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/04/29 00:18
 * @Description DataSmart Govern Backend - ReadOnlySqlAuditMaskingProperties.java
 * @Version:1.0.0
 *
 * 受控只读 SQL 审计预览脱敏配置。
 *
 * datasource-management 的只读 SQL 执行入口会被 data-quality、data-asset、字段画像、
 * 异常样本诊断等多个平台模块复用。它的审计表需要回答“谁在什么时候访问了什么数据源”，
 * 但审计表本身不能变成新的敏感数据沉淀点。
 *
 * 因此本配置类专门控制“写入审计表之前，SQL 预览如何被脱敏”：
 * - SQL 指纹仍基于原始 SQL 计算，保证同一条 SQL 可以稳定聚合和检索；
 * - SQL 预览使用脱敏后的文本，方便人工排查时看到大致查询结构；
 * - 常见手机号、邮箱、身份证号、口令、token 等字面量会被遮蔽；
 * - 过长字符串字面量会被摘要替代，避免把业务备注、地址、证件、token 等长文本原样写入审计表。
 *
 * 需要特别注意：这里是“审计预览层”的基础保护，不是完整的合规脱敏中心。
 * 后续如果建设独立 compliance-masking 微服务，应把字段级识别、策略审批、可逆/不可逆脱敏、
 * 数据分类分级、样本预览授权等能力下沉到统一合规模块，本类只保留 datasource-management
 * 在写审计时必须具备的最低安全兜底。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.read-only-sql.audit-masking")
public class ReadOnlySqlAuditMaskingProperties {

    /**
     * 是否启用 SQL 审计预览脱敏。
     *
     * 商业化部署建议默认开启。只有在本地排障、测试脱敏规则或客户明确要求保留完整预览时，
     * 才应短时间关闭，并且关闭行为本身后续也应进入配置变更审计。
     */
    private Boolean enabled = true;

    /**
     * 是否遮蔽邮箱地址。
     *
     * 邮箱经常出现在用户表、客户表、通知日志、账号绑定表中，属于可直接定位个人的标识信息。
     */
    private Boolean maskEmail = true;

    /**
     * 是否遮蔽中国大陆手机号。
     *
     * 当前采用常见 11 位手机号模式作为基础识别，后续可以由 compliance-masking 模块扩展为
     * 多国家号码、座机、企业联系人等更丰富的识别策略。
     */
    private Boolean maskPhone = true;

    /**
     * 是否遮蔽中国大陆居民身份证号。
     *
     * 身份证号属于强敏感个人信息，哪怕只出现在 SQL where 条件里，也不应该原样进入审计表。
     */
    private Boolean maskIdentityNumber = true;

    /**
     * 是否遮蔽常见凭据字段赋值。
     *
     * 例如 password='xxx'、token='xxx'、api_key='xxx'。
     * 这些字段往往不是业务查询条件，而是误用或排障时被带入 SQL 的高风险敏感值。
     */
    private Boolean maskCredentialAssignments = true;

    /**
     * 是否遮蔽 Bearer Token。
     *
     * 有些系统会把 Authorization 头、访问令牌或三方 API token 误写入日志表，
     * 质量扫描或异常样本诊断查询这些日志时，SQL 条件里也可能携带 token 片段。
     */
    private Boolean maskBearerToken = true;

    /**
     * 是否遮蔽过长的单引号字符串字面量。
     *
     * 这是一条兜底策略：并不是所有敏感内容都能通过手机号、邮箱、身份证等规则识别。
     * 长文本可能包含地址、备注、证件、cookie、加密串或客户隐私描述，进入审计表前应尽量压缩成摘要。
     */
    private Boolean maskQuotedLongText = true;

    /**
     * 单引号字符串字面量超过多少字符后被替换为长度摘要。
     *
     * 例如阈值为 24 时，'abcdefghijklmnopqrstuvwxyz' 会变为 '[MASKED_LITERAL:26]'。
     * 保留长度信息可以帮助排查“是否传入了异常长条件”，但不会暴露原始内容。
     */
    private Integer maxQuotedLiteralPreviewLength = 24;
}
