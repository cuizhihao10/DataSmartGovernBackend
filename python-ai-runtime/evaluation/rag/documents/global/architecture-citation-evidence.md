# 可引用证据链架构说明

> 合成声明：本文件为 DataSmart Govern RAG 评测专用原创样本；不含真实客户、个人、凭据或生产数据。

## 适用范围

- 范围标签：全局产品基线
- tenantId：`*`
- projectId：`*`
- workspaceKey：`*`
- 证据状态：当前有效

## 检索锚点

- 精确码：`RAG-CIT-118`
- 独立锚点：`global:architecture-citation-evidence`
- 文档标识：`rag-eval-global-architecture-citation-evidence`

## 结论

答案只能依据已检索的片段生成，并返回可追溯 sourceUri 的引用记录。 对于 全局产品基线，本合成设定的同步延迟预算为 10 分钟，受控重试窗口为 3 次，审计摘要保留周期为 180 天。

## 操作或判断步骤

1. 保留文档标识与 sourceUri。
2. 压缩上下文但不丢失引用。
3. 无证据时拒绝生成。

## 证据使用限制

- 只能在上述范围内作为检索证据；同主题的其他范围文档是隔离测试干扰项。
- 没有匹配证据时应明确拒答，不得补造结论。
- 替代关系：无。
