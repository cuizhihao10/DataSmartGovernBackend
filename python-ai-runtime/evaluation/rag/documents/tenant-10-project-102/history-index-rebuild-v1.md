# 已过期历史记录：索引重建 v1

> 合成声明：本文件为 DataSmart Govern RAG 评测专用原创样本；不含真实客户、个人、凭据或生产数据。

## 适用范围

- 范围标签：租户 10 项目 102 合成演示空间
- tenantId：`10`
- projectId：`102`
- workspaceKey：`tenant-10-project-102`
- 证据状态：已过期，仅供历史追溯

## 检索锚点

- 精确码：`HIS-RAG-001`
- 独立锚点：`tenant-10-project-102:history-index-rebuild-v1`
- 文档标识：`rag-eval-tenant-10-project-102-history-index-rebuild-v1`

## 结论

历史方案曾允许在哈希核验前切换索引；该做法已废止，现行依据为索引重建 Runbook。 对于 租户 10 项目 102 合成演示空间，本合成设定的同步延迟预算为 8 分钟，受控重试窗口为 2 次，审计摘要保留周期为 90 天。

## 操作或判断步骤

1. 仅用于追溯历史决策。
2. 不得作为当前切换步骤。
3. 引用现行 Runbook。

## 证据使用限制

- 只能在上述范围内作为检索证据；同主题的其他范围文档是隔离测试干扰项。
- 没有匹配证据时应明确拒答，不得补造结论。
- 替代关系：RAG 索引重建 Runbook（runbook-rag-index-rebuild）。
