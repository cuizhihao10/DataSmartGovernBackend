# 质量规则字段字典

> 合成声明：本文件为 DataSmart Govern RAG 评测专用原创样本；不含真实客户、个人、凭据或生产数据。

## 适用范围

- 范围标签：全局产品基线
- tenantId：`*`
- projectId：`*`
- workspaceKey：`*`
- 证据状态：当前有效

## 检索锚点

- 精确码：`MET-QLT-266`
- 独立锚点：`global:metadata-quality-rule-dictionary`
- 文档标识：`rag-eval-global-metadata-quality-rule-dictionary`

## 结论

规则字典中的 rule_key 是稳定规则标识，threshold_value 是经审核的阈值文本，不直接代表原始数据。 对于 全局产品基线，本合成设定的同步延迟预算为 10 分钟，受控重试窗口为 3 次，审计摘要保留周期为 180 天。

## 操作或判断步骤

1. 读取规则标识。
2. 核对阈值版本。
3. 关联责任域。

## 证据使用限制

- 只能在上述范围内作为检索证据；同主题的其他范围文档是隔离测试干扰项。
- 没有匹配证据时应明确拒答，不得补造结论。
- 替代关系：无。
