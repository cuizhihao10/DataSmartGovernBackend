/**
 * @Author : Cui
 * @Date: 2026/07/05 15:58
 * @Description DataSmart Govern Backend - SyncDataXRuntimeSafetyPolicy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * DataX-style 执行拓扑的运行安全策略。
 *
 * <p>这个对象回答“真实 Runner 在拿到作业拓扑后，哪些内容可以消费、哪些内容必须继续留在受控服务端”。
 * 真实商业化数据同步系统里，最危险的不是单纯能不能跑通，而是为了跑通把 SQL 正文、连接串、账号密码、字段映射原文、
 * where 条件原文、checkpoint 原始水位或失败行样本扩散到日志、事件、Agent 上下文、Prometheus 标签或普通 API 响应里。
 * 因此我们把安全策略作为 DataX-style 拓扑的一等公民，而不是写在文档里的口头约束。</p>
 *
 * <p>注意：本策略本身也是低敏摘要，只描述“应该如何处理”，不承载任何真实凭据、SQL、字段名列表或数据样本。</p>
 *
 * @param credentialPolicy 凭据策略。通常要求 Runner 只能通过 datasourceId 在执行面受控解析凭据，不能从合同中获得连接串或密码。
 * @param sqlPolicy SQL 策略。用于约束自定义 SQL、where/filter、Reader 查询语句只能以托管引用或安全参数形式出现。
 * @param objectMappingPolicy 对象映射策略。多表、整 schema、整库场景必须在 Runner 侧受控展开，合同不直接暴露对象清单正文。
 * @param fieldMappingPolicy 字段映射策略。字段名和映射原文不能进入普通低敏合同，最小 bridge 只在内部内存里消费执行契约。
 * @param filterPolicy 过滤条件策略。where 条件必须结构化、参数化，禁止拼接 raw SQL。
 * @param checkpointPolicy checkpoint 策略。只允许 checkpointRef 或 digest 进入报告，原始水位留在受控存储。
 * @param dirtyRecordPolicy 脏数据策略。失败样本默认只允许摘要、分类或引用，不允许行原文进入普通事件。
 * @param rateLimitPolicy 限速策略。说明当前是否只有最小 batch 限制，还是必须由专用 Runner 的资源组和限速器接管。
 * @param retryPolicy 重试策略。说明重试必须依赖幂等键、checkpoint、批次边界和写入策略，不能简单重复提交。
 * @param auditPolicy 审计策略。说明配置变更、派发、回调、失败和人工干预都应该形成低敏审计事实。
 * @param forbiddenPayloads 明确禁止出现在合同、事件、指标和普通日志中的载荷类型。
 * @param payloadPolicy 当前对象自身的低敏载荷策略说明。
 */
public record SyncDataXRuntimeSafetyPolicy(
        String credentialPolicy,
        String sqlPolicy,
        String objectMappingPolicy,
        String fieldMappingPolicy,
        String filterPolicy,
        String checkpointPolicy,
        String dirtyRecordPolicy,
        String rateLimitPolicy,
        String retryPolicy,
        String auditPolicy,
        List<String> forbiddenPayloads,
        String payloadPolicy
) {
}
