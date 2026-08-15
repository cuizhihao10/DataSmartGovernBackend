# 订单事实表数据字典

> 合成声明：本文件为 DataSmart Govern RAG 评测专用原创样本；不含真实客户、个人、凭据或生产数据。

## 适用范围

- 范围标签：全局产品基线
- tenantId：`*`
- projectId：`*`
- workspaceKey：`*`
- 证据状态：当前有效

## 检索锚点

- 精确码：`MET-ORD-155`
- 独立锚点：`global:metadata-order-fact-dictionary`
- 文档标识：`rag-eval-global-metadata-order-fact-dictionary`

## 结论

合成订单事实表以 order_event_id 为事件标识，以 occurred_at 为业务发生时间，金额仅用于规则示例。 对于 全局产品基线，本合成设定的同步延迟预算为 10 分钟，受控重试窗口为 3 次，审计摘要保留周期为 180 天。

## 操作或判断步骤

1. 核对字段口径。
2. 确认主键语义。
3. 标记时间字段的业务含义。

## 证据使用限制

- 只能在上述范围内作为检索证据；同主题的其他范围文档是隔离测试干扰项。
- 没有匹配证据时应明确拒答，不得补造结论。
- 替代关系：无。
