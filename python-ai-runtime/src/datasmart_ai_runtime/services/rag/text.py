"""RAG 文本处理工具。

RAG 的质量很大程度取决于“文本如何被切块、如何被分词、如何被压缩”。这些步骤如果完全交给框架黑盒，
面试时很容易被追问到答不上来。本文件保留项目自己的轻量实现：

- 中文/英文混合 token 抽取；
- 面向文档的滑窗切块；
- 面向上下文预算的证据压缩；
- 片段摘要和相似度计算。

这些实现不是要替代生产级 tokenizer 或 reranker，而是把核心原理清楚落地，并为后续接入更强模型保留
稳定接口。
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections import Counter
from dataclasses import dataclass
from typing import Iterable, Mapping, Sequence

from datasmart_ai_runtime.services.rag.models import RagChunk, RagDocument


_SPLIT_PATTERN = re.compile(r"[\s,，、。；;:：/\\|()（）\[\]【】{}<>《》\"'`!?！？]+")
_SENTENCE_PATTERN = re.compile(r"(?<=[。！？!?；;])")
_AUTHORIZED_SCOPE_PHRASE_PATTERNS = (
    re.compile(r"全局产品基线"),
    re.compile(r"租户\s*[A-Za-z0-9_*:-]+\s*项目\s*[A-Za-z0-9_*:-]+(?:\s*合成演示空间)?"),
    re.compile(
        r"tenant\s*[A-Za-z0-9_*:-]+\s*project\s*[A-Za-z0-9_*:-]+(?:\s*workspace\s*[A-Za-z0-9_*:-]+)?",
        re.IGNORECASE,
    ),
)

# 精确检索不是把所有字符串都当成“错误码”。这里仅识别带有稳定分隔符的 ASCII 标识符，覆盖
# 资料码（RAG-ISO-401）、字段名（order_event_id）和独立检索锚点（global:architecture-...）。
# 普通中文词不会进入这个通道，因此不会因为用户写了“规则”和“任务”就误触发精确优先级。
_EXACT_HYPHEN_OR_UNDERSCORE_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+(?![A-Za-z0-9_])"
)
_EXACT_ANCHOR_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])[A-Za-z0-9_*.-]+:[A-Za-z0-9_.:-]+(?![A-Za-z0-9_])"
)

# 只有用户明确表达“我要按资料码/锚点精确读取”时，精确命中才会冻结多证据选择。
# 普通问题里出现 ``region_code``、``config_version`` 这类字段名只是业务内容的一部分，不能
# 因为它满足 ASCII 标识符格式，就阻止系统继续寻找手册、案例和执行日志等互补证据。
_EXPLICIT_EXACT_INTENT_PATTERN = re.compile(
    r"(?:精确(?:码|标识符|检索|查询|查找)|准确(?:码|标识符)|"
    r"(?:只|仅|必须)\s*依据(?:资料|文档|锚点|资料码|文档码)?|"
    r"(?:只|仅|按|依据)\s*(?:资料码|文档码|检索锚点|锚点|指定资料|指定文档)|"
    r"原始(?:资料|文档)|指定(?:资料|文档)|"
    r"(?:exact[_ -]?(?:search|lookup|match)|artifactCode|retrievalAnchor))",
    re.IGNORECASE,
)

# 这些词在治理资料中出现频率很高。它们可以参与正常排序，但不能单独证明“无答案”问题有可靠
# 依据；拒答门禁会优先检查查询中除这些泛词以外的独特词项。
_GENERIC_RAG_QUERY_TERMS = frozenset(
    {
        "全局",
        "产品",
        "基线",
        "租户",
        "项目",
        "空间",
        "当前",
        "现在",
        "阈值",
        "字典",
        "事实",
        "边界",
        "检索",
        "隔离",
        "共同",
        "以及",
        "并且",
        "分别",
        "结合",
        "需要",
        "哪些",
        "什么",
        "如何",
        "怎样",
        "是否",
        "可以",
        "应该",
        "说明",
        "处理",
        "原则",
        "规则",
        "任务",
        "数据",
        "同步",
        "字段",
        "操作",
        "查看",
        "方法",
        "步骤",
        "要求",
        "信息",
        "内容",
        "记录",
        "案例",
        "系统",
        "服务",
        "接口",
        "用户",
        "权限",
        "审批",
        "恢复",
        "执行",
        "问题",
        "依据",
        "确定",
        "包含",
        "避免",
        "发生",
        "出现",
        "检查",
        "核对",
        "验证",
        "要求",
        "相关",
        "详细",
        "精确",
        "处理方式",
        "调度",
        "调度规则",
        "满足",
    }
)

# 中文泛短语经过 n-gram 后会产生跨词片段，例如“调度规则”会变成“度规”。这些片段本身没有实体
# 含义，也必须从拒答门禁锚点中移除；只做完整短语比较会留下它们。
_GENERIC_RAG_QUERY_NGRAMS = frozenset(
    phrase[index : index + size]
    for phrase in _GENERIC_RAG_QUERY_TERMS
    if phrase and all("\u4e00" <= char <= "\u9fff" for char in phrase)
    for size in (2, 3, 4)
    for index in range(max(len(phrase) - size + 1, 0))
)

# 离线词法基线无法像 Embedding 一样自动理解同义表达，因此只维护少量稳定、可审计的治理术语扩展。
# 这些不是把答案写死在检索器里，而是把用户常说的业务表达映射到资料中已经明确使用的术语；真实
# BGE-M3 仍会通过向量通道提供更广的语义召回。扩展词会进入同一 lexical/rerank 评分，不改变范围或
# 证据状态规则。
_RAG_QUERY_EXPANSIONS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("检索隔离", ("范围过滤", "范围隔离", "范围三元组")),
    ("授权事实", ("授权决策", "权限边界", "最小权限")),
    ("哈希核验", ("文档哈希", "内容哈希", "内容指纹")),
    ("引用可追溯", ("引用链", "证据链", "sourceUri")),
    # 中文业务问法与接口、日志、CSV/JSON/XLSX 中稳定字段名之间的映射。这里只维护跨版本可审计的
    # 术语，不写入某一条黄金用例的文档 ID、标题或答案；BGE 向量通道仍负责开放表达的语义召回。
    ("运维流程", ("运维手册", "Runbook", "标准操作步骤")),
    ("配置版本", ("config_version", "configVersion")),
    ("批量", ("batch", "batch_size", "batchSize")),
    ("并发", ("channel", "channelCount", "concurrency")),
    ("超时", ("timeout", "timeout_s", "timeoutSeconds")),
    ("最终运行结果", ("成功运行记录", "successful-runs", "completed_at", "SUCCEEDED")),
    ("日志验证", ("Worker 执行日志", "POST_RECOVERY_VERIFIED", "errorCode")),
    ("消费者日志", ("consumer lag", "consumer_lag", "groupLag")),
    ("评测指标", ("Recall", "MRR", "nDCG", "citationPrecision")),
    ("字段画像", ("field profile", "field_profile", "null_ratio", "distinct_count")),
    ("字段案例", ("field mapping case", "field_mapping_case")),
    ("修复决策", ("recovery decision", "decisionReason", "actionCode")),
    ("安全位点", ("safe checkpoint", "lastSafeCheckpoint", "checkpoint")),
    ("定时任务", ("schedule", "scheduled", "cron")),
    ("有界自治恢复", ("autonomous recovery", "recoveryCase", "recovery loop")),
    ("任务案例", ("task case", "task_case", "case library")),
    ("执行接口", ("data sync API", "executionId", "execute")),
    ("失败对象", ("failed object", "objectId", "OBJECT_FAILED")),
    ("恢复台账", ("recovery ledger", "recoveryCaseId", "ledger")),
    ("历史事故", ("postmortem", "incident", "incidentId")),
    ("字段演进案例", ("schema evolution", "schema drift")),
    ("配置差异", ("previousVersion", "config diff")),
    ("限流", ("rate limit", "429", "throttle")),
    # JSONL 事件常用稳定事件码而不是自然语言“replay”。把两种写法放在同一受控扩展里，
    # 让离线词法基线和线上 Embedding/Reranker 看到一致的业务锚点。
    ("分片 replay", ("failed shard", "replay", "FAILED_OBJECT_REPLAYED", "失败对象", "重放")),
)

# 资料的职责不是由正文里的一个高频词决定的。比如“限流”会同时出现在事故复盘、连接器快照和任务
# 参数表中；如果只按正文词频排序，系统很容易把“看起来相关”的通用手册排在真正应该引用的资料前面。
# 这里维护的是一组很小、可审计的“查询意图 -> 资料职责”先验：
#
# - 第 1 项是能激活意图的用户表达；
# - 第 2 项是 Manifest 中的 category 前缀或完整值；
# - 第 3 项是来源类型；
# - 第 4 项是物理格式；
# - 最后一项是意图权重。
#
# 它不是黄金集答案，也不包含任何 documentId。category/sourceType/format 只作为排序辅助，最终仍必须
# 通过词法、向量、Reranker 和证据门禁。真实企业资料没有 category 时，标题/标签只提供较弱的补充信号。
@dataclass(frozen=True)
class _RagDocumentIntentHint:
    """声明一个资料职责及其可泛化的查询概念组。

    每个概念组表示一个独立语义维度，组内词是同义词或稳定领域别名。运行时只有至少两个组命中时，
    才会把类别职责提升到多证据门槛；单组命中只能作为同分候选之间的弱平局信号。这让 ``Kafka``、
    ``日志``、``配置`` 这类单词不会单独把资料路由到某个固定答案。
    """

    intent_key: str
    concept_groups: tuple[tuple[str, ...], ...]
    category_patterns: tuple[str, ...]
    source_types: tuple[str, ...]
    formats: tuple[str, ...]
    weight: float


# 资料职责由短术语和概念组合表达，而不是由评测题干表达。每个条目中的两个或更多概念组分别描述
# 主题、动作、状态或证据形态；这类组合可迁移到同义改写和真实运维问法，不依赖某条黄金问题、资料 ID
# 或“问题 -> category”的专门表。
_RAG_QUERY_DOCUMENT_INTENT_HINTS: tuple[_RagDocumentIntentHint, ...] = (
    _RagDocumentIntentHint(
        "successful_task_configuration",
        (("成功", "已完成", "近期", "最近", "上一回", "上次"), ("任务", "作业", "同步"), ("配置", "参数", "版本", "批量", "并发", "超时")),
        ("successful_task_case",), ("task_case",), ("xlsx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "successful_runs",
        (("成功", "已完成", "完成"), ("运行", "执行", "作业"), ("结果", "记录", "统计", "数量", "完成时间", "写入")),
        ("successful_runs",), ("task_case",), ("csv",), 0.95,
    ),
    _RagDocumentIntentHint(
        "configuration_versions",
        (("配置", "参数", "版本"), ("差异", "对比", "上一版", "前一版", "历史")),
        ("task_config_versions", "successful_task_case"), ("task_case",), ("json",), 0.85,
    ),
    _RagDocumentIntentHint(
        "operations_flow",
        (("运维", "运行", "值守"), ("流程", "手册", "排查", "处置", "命令")),
        ("operations_manual", "operations_command_reference", "observability_operations_manual", "kafka_operations_manual"),
        ("runbook",), ("docx", "txt"), 0.90,
    ),
    _RagDocumentIntentHint(
        "operations_history",
        (("历史", "经过", "时间线", "记录"), ("事故", "运维", "故障", "处置")),
        ("operations_record", "incident_"), ("incident",), ("docx", "md", "csv", "log"), 0.90,
    ),
    _RagDocumentIntentHint(
        "alert_history",
        (("告警", "异常", "报警"), ("恢复", "处置", "自动处理", "通知", "提醒", "值班")),
        ("alert_history",), ("incident",), ("csv",), 1.15,
    ),
    _RagDocumentIntentHint(
        "error_code_catalog",
        (("错误", "故障", "异常", "报错", "错误码"), ("处理", "自动处理", "人工", "接手", "权限", "越权")),
        ("error_code_catalog",), ("runbook",), ("txt", "md", "docx"), 1.20,
    ),
    _RagDocumentIntentHint(
        "administrator_manual",
        (("平台", "管理", "设置", "配置"), ("成员", "角色", "职责", "审批", "可见", "资料", "权限")),
        ("administrator_manual",), ("document",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "agent_lifecycle_state",
        (
            ("自动处理", "自动完成", "自动执行", "处理完成", "事情完成"),
            ("下一步", "收尾", "最终环节", "最终验证", "结束"),
        ),
        ("agent_state_snapshot", "recovery_events", "database_recovery_ledger"),
        ("memory_export", "incident", "dataset"),
        ("json", "jsonl", "sql"),
        1.20,
    ),
    _RagDocumentIntentHint(
        "connector_capacity",
        (("连接器", "connector"), ("容量", "版本", "批量", "并发", "清单", "上限")),
        ("connector_capabilities", "connector_inventory"), ("metadata",), ("json", "csv"), 1.00,
    ),
    _RagDocumentIntentHint(
        "schema_mapping",
        (("字段", "schema", "列"), ("映射", "默认值", "非空", "约束", "漂移", "演进", "不满足要求", "系统代劳", "人工")),
        ("field_mapping_case", "schema_evolution_cases", "recovery_manual", "incident_schema_drift"),
        ("dataset", "runbook", "incident"), ("xlsx", "docx"), 0.95,
    ),
    _RagDocumentIntentHint(
        "field_mapping_case_specific",
        (("字段", "映射"), ("案例", "样例", "案例库", "表格")),
        ("field_mapping_case",), ("dataset", "task_case"), ("xlsx",), 1.15,
    ),
    _RagDocumentIntentHint(
        "governed_mapping_repair",
        (("字段", "映射"), ("修复", "恢复", "治理", "允许", "预检")),
        ("recovery_manual", "field_mapping_case"), ("runbook", "dataset"), ("docx", "xlsx"), 1.15,
    ),
    _RagDocumentIntentHint(
        "worker_and_consumer_logs",
        (("worker", "消费者", "消费组", "consumer"), ("日志", "错误", "验证", "lag", "积压", "堆积", "分区")),
        ("worker_execution", "kafka_lag_log", "observability_operations_manual"),
        ("incident", "runbook"), ("log", "docx"), 0.95,
    ),
    _RagDocumentIntentHint(
        "observability_operations",
        (("日志", "指标", "trace", "追踪", "运行痕迹", "运行轨迹", "运行线索"), ("定位", "排查", "故障", "异常", "作业异常", "问题位置", "环节", "卡住", "跨团队")),
        ("observability_operations_manual",), ("runbook",), ("docx",), 1.15,
    ),
    _RagDocumentIntentHint(
        "backup_disaster_recovery",
        (("灾难", "灾备", "演练", "严重故障", "故障后", "rpo", "rto"), ("恢复", "数据库", "对象", "业务资料", "服务", "位点", "顺序", "恢复顺序")),
        ("backup_disaster_recovery_manual",), ("runbook",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "platform_deployment",
        (("部署", "发布", "安装", "上线", "新环境"), ("java", "jdk", "kafka", "pgvector", "ai runtime", "服务", "健康检查", "基础服务")),
        ("deployment_manual",), ("runbook",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "retrieval_scope_isolation",
        (
            ("团队", "租户", "项目", "范围", "资料", "内容"),
            ("混进", "混入", "串入", "隔离", "过滤", "当前答复"),
        ),
        ("architecture", "rag_scope_filter"), ("document",), ("md", "docx"), 1.20,
    ),
    _RagDocumentIntentHint(
        "event_bridge_correlation",
        (
            ("事件", "回执", "异步", "桥接", "处理结果", "工作结束", "完成结果"),
            ("关联", "correlationid", "标识", "版本", "原任务", "回到", "发起", "对应", "那件事"),
        ),
        ("architecture", "architecture_event_bridge"), ("wiki", "document"), ("md", "docx", "json"), 1.20,
    ),
    _RagDocumentIntentHint(
        "authentication_reference",
        (("认证", "会话", "身份", "令牌"), ("接口", "服务", "登录", "调用")),
        ("api_authentication_reference",), ("document",), ("docx",), 1.10,
    ),
    _RagDocumentIntentHint(
        "security_approval",
        (("审批", "授权", "权限", "越权", "同意", "自动处理"), ("双主体", "边界", "安全", "校验", "人工", "范围限制", "做的范围")),
        ("security_manual",), ("rule", "document"), ("docx",), 1.15,
    ),
    _RagDocumentIntentHint(
        "audit_events",
        (("审计", "留痕"), ("修复", "调用", "事件", "记录")),
        ("audit_event_stream",), ("memory_export", "incident"), ("jsonl",), 1.10,
    ),
    _RagDocumentIntentHint(
        "api_contract",
        (("接口", "api", "rest", "websocket"), ("标识", "合同", "字段", "说明", "响应")),
        ("api_contract_snapshot", "api_reference", "api_agent_reference"), ("metadata", "document"), ("json", "docx"), 1.00,
    ),
    _RagDocumentIntentHint(
        "websocket_events",
        (("websocket", "ws"), ("事件", "状态", "实时", "字典")),
        ("websocket_event_reference",), ("document",), ("docx",), 0.90,
    ),
    _RagDocumentIntentHint(
        "task_and_data_sync_api",
        (("任务", "同步", "执行"), ("接口", "api", "触发", "请求", "响应")),
        ("api_data_sync_reference", "api_task_reference"), ("document",), ("docx",), 0.95,
    ),
    _RagDocumentIntentHint(
        "task_parameters",
        (("任务", "作业", "同步"), ("参数", "配置", "基线", "批量", "并发", "超时")),
        ("successful_task_case", "api_task_cases", "kafka_task_cases", "schedule_case", "task_case_library", "full_load_task_cases"),
        ("task_case",), ("xlsx", "jsonl"), 0.90,
    ),
    _RagDocumentIntentHint(
        "schedule_cases",
        (("定时", "调度", "夜间", "非工作时间", "例行"), ("重试", "失败", "间隔", "次数", "执行")),
        ("schedule_case",), ("task_case", "incident"), ("xlsx", "jsonl"), 1.20,
    ),
    _RagDocumentIntentHint(
        "task_case_library",
        (("任务", "同步", "作业"), ("案例", "案例库", "流水", "保留", "样例")),
        ("task_case_library", "full_load_task_cases", "api_task_cases", "kafka_task_cases", "recovery_replay_cases", "successful_task_case"),
        ("task_case", "incident"), ("xlsx", "jsonl"), 0.95,
    ),
    _RagDocumentIntentHint(
        "full_load_task_cases",
        (("全量", "批量导入"), ("任务", "同步", "作业", "案例")),
        ("full_load_task_cases",), ("task_case",), ("xlsx",), 1.00,
    ),
    _RagDocumentIntentHint(
        "kafka_operations",
        (("kafka", "消息", "消费者", "消费组"), ("积压", "堆积", "堆着不动", "分区", "lag", "dlt", "死信", "失败队列", "回放", "重复消费", "重复处理", "处理变慢")),
        ("kafka_operations_manual", "kafka_task_cases", "kafka_lag_log", "incident_kafka_backlog"),
        ("runbook", "task_case", "incident"), ("docx", "xlsx", "log"), 1.00,
    ),
    _RagDocumentIntentHint(
        "recovery_and_audit",
        (("恢复", "修复", "补救", "补救过程", "recovery"), ("事件", "台账", "验证", "最后确认", "审计", "快照", "状态", "依据", "动作")),
        ("recovery_events", "database_recovery_ledger", "recovery_decision_trace", "agent_state_snapshot"),
        ("incident", "dataset", "memory_export"), ("json", "jsonl", "sql"), 0.90,
    ),
    _RagDocumentIntentHint(
        "recovery_decision",
        (("恢复", "修复", "recovery"), ("决策", "决议", "原因", "动作")),
        ("recovery_decision_trace", "recovery_events"), ("incident",), ("jsonl",), 1.00,
    ),
    _RagDocumentIntentHint(
        "autonomous_recovery",
        (("自治", "自动", "自行"), ("恢复", "修复", "纠正", "recovery")),
        ("api_recovery_reference", "recovery_manual", "api_task_reference"), ("document", "runbook"), ("docx",), 0.95,
    ),
    _RagDocumentIntentHint(
        "recovery_replay",
        (("回放", "重放", "重新处理", "replay"), ("失败对象", "失败分片", "分片", "位点", "检查点", "安全起点")),
        ("recovery_replay_cases", "recovery_events"), ("incident",), ("xlsx", "jsonl"), 1.10,
    ),
    _RagDocumentIntentHint(
        "checkpoint_incident",
        (("checkpoint", "检查点", "位点", "进度位置"), ("事故", "漂移", "异常", "根因", "提前确认")),
        ("incident_checkpoint",), ("incident",), ("docx",), 1.10,
    ),
    _RagDocumentIntentHint(
        "rag_evaluation",
        (("rag", "检索"), ("评测", "评估", "指标", "recall", "citationprecision", "拒答")),
        ("rag_agent_evaluation_report", "test_report", "performance_test_report"), ("document",), ("docx",), 1.00,
    ),
    _RagDocumentIntentHint(
        "pgvector_and_model_provider",
        (("pgvector", "向量", "embedding", "reranker", "资料匹配", "语义检索", "检索结果", "智能服务", "外部服务"), ("模型", "provider", "维度", "检索", "索引", "存放设置", "存储设置", "结果对不上", "不稳定", "被限制", "答复不全", "响应缺项", "降级", "处理变慢")),
        ("postgresql_pgvector_manual", "model_provider_manual"), ("runbook",), ("docx", "md"), 0.90,
    ),
    _RagDocumentIntentHint(
        "vector_degraded",
        (("向量", "pgvector", "provider"), ("降级", "变慢", "不可用", "故障", "degraded")),
        ("runbook_pgvector_degraded",), ("runbook",), ("md",), 0.90,
    ),
    _RagDocumentIntentHint(
        "field_profile",
        (("字段", "列", "field"), ("画像", "统计", "null_ratio", "distinct_count", "分布")),
        ("field_profile_statistics",), ("dataset",), ("csv",), 1.20,
    ),
    _RagDocumentIntentHint(
        "rate_limit",
        (("限流", "429", "throttle"), ("目标端", "api", "压力", "并发", "批量", "连接器")),
        ("incident_rate_limit", "api_task_cases", "connector_inventory"), ("incident", "task_case", "metadata"),
        ("docx", "xlsx", "csv"), 1.00,
    ),
    _RagDocumentIntentHint(
        "rate_limit_incident",
        (("限流", "429", "throttle"), ("事故", "历史", "复盘", "影响")),
        ("incident_rate_limit",), ("incident",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "schema_drift",
        (("schema", "字段", "列"), ("漂移", "演进", "变更", "兼容")),
        ("incident_schema_drift", "schema_evolution_cases", "task_config_versions"),
        ("incident", "dataset", "task_case"), ("docx", "xlsx", "json"), 1.00,
    ),
    # 下面是跨格式资料的细粒度职责。概念组只描述可审计的领域语义，不绑定资料 ID，也不替代
    # Embedding/Reranker；它们的作用只是让候选窗口和最终引用裁剪能分清不同资料的稳定职责。
    _RagDocumentIntentHint(
        "agent_planning_api",
        (("agent", "智能体", "专业职责"), ("规划", "toolplan", "工具", "证据", "接口")),
        ("api_agent_reference",), ("document",), ("docx",), 1.25,
    ),
    _RagDocumentIntentHint(
        "data_sync_api_contract",
        (("数据同步", "同步", "连接器"), ("接口", "预检", "分片", "checkpoint", "台账")),
        ("api_data_sync_reference",), ("document",), ("docx",), 1.25,
    ),
    _RagDocumentIntentHint(
        "recovery_api_contract",
        (("恢复", "修复", "纠正", "自助纠错", "自动纠错", "自治恢复", "自治修复", "recovery"), ("预览", "方案", "权限", "人工", "人审", "接管", "越界", "停下", "退出")),
        ("api_recovery_reference",), ("document",), ("docx",), 1.25,
    ),
    _RagDocumentIntentHint(
        "lifecycle_api_contract",
        (("agent", "任务", "同步"), ("标识", "关联", "correlationid", "rest", "websocket")),
        ("api_reference",), ("document",), ("docx",), 1.25,
    ),
    _RagDocumentIntentHint(
        "task_management_api",
        (("任务", "作业"), ("接口", "创建", "版本", "触发", "历史", "调度")),
        ("api_task_reference",), ("document",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "websocket_event_dictionary",
        (("websocket", "ws"), ("事件", "字典", "全链路", "实时", "状态")),
        ("websocket_event_reference",), ("document",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "user_operation_manual",
        (("用户", "需求", "首次"), ("授权", "同步", "创建", "操作", "手册")),
        ("user_manual",), ("document",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "product_feature_specification",
        (("agent", "智能体", "多角色"), ("协作", "闭环", "产品", "同步")),
        ("product_features",), ("document",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "e2e_test_report",
        (("e2e", "端到端", "验收", "专业职责"), ("自治", "恢复", "成功", "失败", "受阻")),
        ("e2e_test_report",), ("document",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "rag_agent_evaluation_report",
        (("rag", "检索", "材料", "证据"), ("agent", "智能体", "决策", "自动"), ("评测", "评判", "可靠", "品质", "引用", "拒答", "治理")),
        ("rag_agent_evaluation_report",), ("document",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "quick_reference",
        (("快速", "参考", "速查"), ("失败", "排查", "检查", "命令")),
        ("quick_reference",), ("document",), ("txt",), 1.15,
    ),
    _RagDocumentIntentHint(
        "operator_faq",
        (("运维", "操作员", "值班"), ("权限", "ddl", "错误", "循环", "常见问题")),
        ("operator_faq",), ("runbook",), ("txt",), 1.20,
    ),
    _RagDocumentIntentHint(
        "operations_record",
        (("时间线", "经过", "过程", "发现", "值守"), ("修复", "处置", "复核", "验证", "验收", "恢复")),
        ("operations_record",), ("incident",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "operations_command_reference",
        (("只读", "命令", "查询"), ("定位", "排查", "同步", "异常", "故障")),
        ("operations_command_reference",), ("runbook",), ("txt",), 1.15,
    ),
    _RagDocumentIntentHint(
        "foreign_key_incident",
        (("外键", "父子表"), ("事故", "约束", "依赖", "顺序", "删除")),
        ("incident_foreign_key",), ("incident",), ("docx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "worker_execution_log",
        (("worker", "执行"), ("日志", "非空", "错误", "字段", "失败")),
        ("worker_execution",), ("incident",), ("log",), 1.20,
    ),
    _RagDocumentIntentHint(
        "persistence_snapshot",
        (("持久化", "保存", "快照", "记录"), ("任务", "执行", "恢复", "修复", "证据", "依据")),
        ("persistence_snapshot",), ("dataset",), ("sql",), 1.20,
    ),
    _RagDocumentIntentHint(
        "recovery_decision_trace",
        (("恢复", "修复", "recovery"), ("循环", "轮次", "轨迹", "有界", "新证据")),
        ("recovery_decision_trace",), ("incident",), ("jsonl",), 1.20,
    ),
    _RagDocumentIntentHint(
        "repair_ledger",
        (("事故", "证据", "台账", "字段"), ("修复", "动作", "验证", "非空")),
        ("repair_ledger",), ("incident",), ("xlsx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "recovery_ledger_lifecycle",
        (
            ("台账", "账本", "记录", "保存"),
            ("补救", "修复", "动作", "依据", "证据"),
            ("确认", "验证", "完成", "收尾"),
        ),
        ("database_recovery_ledger", "recovery_events", "repair_ledger"),
        ("dataset", "incident"),
        ("sql", "json", "jsonl", "xlsx"),
        1.25,
    ),
    _RagDocumentIntentHint(
        "cdc_task_case_workbook",
        (("cdc", "变更数据捕获"), ("任务", "同步", "作业"), ("心跳", "schema", "位点", "配置", "策略")),
        ("cdc_task_cases",), ("task_case",), ("xlsx",), 1.15,
    ),
    _RagDocumentIntentHint(
        "data_quality_task_cases",
        (("质量", "质量规则", "校验"), ("脏数据", "隔离", "放行", "停止", "门禁")),
        ("data_quality_cases",), ("task_case",), ("xlsx",), 1.20,
    ),
    _RagDocumentIntentHint(
        "cdc_sync_case",
        (
            ("cdc", "变更", "订单变更"),
            ("提交", "接收系统", "写到", "检查点", "位点", "序列", "目标端", "处理进度"),
        ),
        ("sync",), ("task_case",), ("md",), 1.20,
    ),
    _RagDocumentIntentHint(
        "rag_index_rebuild_runbook",
        (
            ("索引", "index", "新资料", "资料"),
            ("重建", "构建", "哈希", "核验", "启用", "把关", "带进来"),
        ),
        ("runbook",), ("runbook",), ("md",), 1.10,
    ),
    _RagDocumentIntentHint(
        "citation_evidence_architecture",
        (
            ("引用", "证据", "结论", "答复", "回答"),
            ("追溯", "追查", "链路", "依据", "材料", "找不到", "停下来", "无依据", "可验证"),
        ),
        ("architecture",), ("document",), ("md",), 1.10,
    ),
    _RagDocumentIntentHint(
        "least_privilege_governance",
        (
            ("权限", "许可", "授权", "能操作", "操作数据"),
            ("核实", "校验", "放行", "实际许可", "最小"),
        ),
        ("governance", "least_privilege"), ("rule",), ("md", "docx"), 1.20,
    ),
)

_RAG_QUERY_SCAFFOLD_TERMS = (
    "请",
    "针对",
    "给出",
    "本次",
    "需要",
    "哪些",
    "满足",
    "边界",
    "排查顺序",
    "是什么",
    "如何",
    "怎样",
    "当前",
    "的",
)


def tokenize_for_rag(text: str) -> tuple[str, ...]:
    """抽取适合 RAG 召回的粗粒度 token。

    这里没有直接用英文空格分词，因为 DataSmart 的主要场景包含大量中文治理术语，例如“质量规则”、
    “字段口径”、“权限边界”。实现策略：
    - 先按中英文标点和空白切分；
    - 对较长中文片段额外生成 2-4 字符 n-gram，提高“质量规则/规则生成”这类局部命中的概率；
    - 过滤 1 字符噪音，保留数字、英文和中文混合 token。
    """

    raw_parts = [part.strip().lower() for part in _SPLIT_PATTERN.split(text or "") if part.strip()]
    tokens: list[str] = []
    for part in raw_parts:
        if len(part) >= 2:
            tokens.append(part)
        if _contains_cjk(part) and len(part) >= 4:
            for size in (2, 3, 4):
                tokens.extend(part[index : index + size] for index in range(0, max(len(part) - size + 1, 0)))
    return tuple(token for token in tokens if len(token) >= 2)


def normalize_rag_retrieval_question(text: str) -> str:
    """删除已经由权限上下文表达的范围套话，保留真正用于相关性判断的问题。

    tenant、project 和 workspace 是检索的硬过滤条件，不应该同时作为每个候选正文的相关性词项。当前
    评测语料会在每份资料中重复“全局产品基线”或“租户 10 项目 101 合成演示空间”；如果把这些词送入
    lexical、Embedding 和 Reranker，模型很容易因为范围标签相同而提高无关文档的分数。范围冲突仍由
    管线在检索前单独判断；本函数只删除已经确认属于授权上下文的描述，不会放宽任何权限。

    如果输入只包含范围描述，函数返回原始文本，避免把合法但很短的查询变成空字符串。
    """

    normalized = str(text or "").strip()
    if not normalized:
        return ""
    for pattern in _AUTHORIZED_SCOPE_PHRASE_PATTERNS:
        normalized = pattern.sub(" ", normalized)
    normalized = re.sub(r"(?:我当前在|当前在|请直接|直接给出|请读取|直接读取)\s*", " ", normalized)
    # 范围短语被移除后，中文问题常留下“在 ，”这样的连接词；只在开头清理它，避免改写正文中的
    # “在执行阶段/在目标端”等真实业务语义。
    normalized = re.sub(r"^\s*(?:在|于)\s*[，,：:；;]?\s*", "", normalized)
    normalized = re.sub(r"\s+", " ", normalized).strip(" ，,：:；;")
    return normalized or str(text or "").strip()


def extract_rag_exact_identifiers(text: str) -> tuple[str, ...]:
    """提取适合走精确通道的稳定标识符。

    精确码和检索锚点与普通语义词有完全不同的检索意图：用户写出 ``OPS-KAF-208`` 或
    ``global:runbook-kafka-backlog`` 时，期望的是某一份确定资料，而不是一组“看起来相似”的
    手册。该函数只提取 ASCII 中带连字符、下划线或冒号的标识符，并按出现顺序去重。它不执行权限
    判断，也不因为提取到标识符就允许跨租户访问；范围过滤仍由知识库在排序前完成。
    """

    normalized = str(text or "")
    matches: list[tuple[int, str]] = []
    for pattern in (_EXACT_ANCHOR_PATTERN, _EXACT_HYPHEN_OR_UNDERSCORE_PATTERN):
        matches.extend((match.start(), match.group(0)) for match in pattern.finditer(normalized))
    matches.sort(key=lambda item: item[0])
    identifiers: list[str] = []
    seen: set[str] = set()
    for _, value in matches:
        candidate = value.strip().casefold()
        # URL scheme 不是知识库资料锚点；过滤它可以避免用户把一个链接误当成 exact_search。
        if not candidate or "://" in candidate or candidate in seen:
            continue
        seen.add(candidate)
        identifiers.append(candidate)
    return tuple(identifiers)


def rag_query_requests_explicit_exact(text: str) -> bool:
    """判断用户是否明确要求按稳定资料标识符进行精确读取。

    ``extract_rag_exact_identifiers`` 为了保证错误码、字段名和资料锚点都能进入召回窗口，会识别
    多种带连字符或下划线的 ASCII 字符串。但“能识别”不等于“必须只返回这一份资料”：普通排障
    问题经常会同时提到 ``region_code`` 和日志、案例、恢复手册。只有用户写出精确码、指定锚点、
    “只依据某资料”等明确意图时，多证据裁剪才会把 exact 命中作为受保护种子。
    """

    return bool(_EXPLICIT_EXACT_INTENT_PATTERN.search(str(text or "")))


@dataclass(frozen=True)
class ExactIdentifierMatch:
    """一个 chunk 与查询稳定标识符的精确匹配结果。"""

    score: float
    identifiers: tuple[str, ...]


def exact_identifier_match(
    query_identifiers: tuple[str, ...],
    chunk: RagChunk,
) -> ExactIdentifierMatch:
    """计算稳定标识符与 chunk 的匹配强度。

    元数据中的 ``artifactCode``、``retrievalAnchor`` 和 ``documentId`` 是最强证据；标题、标签和正文
    中的边界匹配作为兼容补充。分数按命中标识符数量归一化，最多为 1。函数只读取当前候选 chunk，
    不会跨范围查询或把未授权文档加入结果。
    """

    if not query_identifiers:
        return ExactIdentifierMatch(score=0.0, identifiers=())
    metadata = chunk.metadata or {}
    strong_values = tuple(
        str(metadata.get(key) or "").strip()
        for key in ("artifactCode", "retrievalAnchor", "logicalDocumentKey")
        if str(metadata.get(key) or "").strip()
    ) + (str(chunk.document_id or "").strip(),)
    title = str(chunk.title or "")
    tags = " ".join(str(tag) for tag in chunk.tags)
    body = str(chunk.text or "")
    matched: list[str] = []
    total_score = 0.0
    for identifier in query_identifiers:
        normalized = str(identifier or "").strip().casefold()
        if not normalized:
            continue
        if any(_identifier_equals_or_occurs(normalized, value) for value in strong_values):
            total_score += 1.0
            matched.append(normalized)
        elif _identifier_equals_or_occurs(normalized, title) or _identifier_equals_or_occurs(normalized, tags):
            total_score += 0.8
            matched.append(normalized)
        elif _identifier_equals_or_occurs(normalized, body):
            total_score += 0.65
            matched.append(normalized)
    divisor = max(1, len(query_identifiers))
    return ExactIdentifierMatch(
        score=min(1.0, total_score / divisor),
        identifiers=tuple(dict.fromkeys(matched)),
    )


def _identifier_equals_or_occurs(identifier: str, value: str) -> bool:
    """按 ASCII 标识符边界匹配，避免 ``OPS-RAG-503`` 命中更长的相似码。"""

    candidate = str(value or "").casefold()
    start = candidate.find(identifier)
    while start >= 0:
        end = start + len(identifier)
        before = candidate[start - 1] if start else ""
        after = candidate[end] if end < len(candidate) else ""
        if not (before.isalnum() or before == "_") and not (after.isalnum() or after == "_"):
            return True
        start = candidate.find(identifier, start + 1)
    return False


def _decompose_rag_query_transition(part: str) -> tuple[str, ...]:
    """把一个明确的流程迁移句拆成少量原子证据面。

    标点拆分无法识别“定时任务失败后进入自治恢复”或“全量任务从执行接口关联到失败对象”。这两类
    句式在治理问答里分别表达前置事实、转换合同和后置事实，适合拆给不同资料回答。实现只接受两种
    可审计语法，不扫描整套职责词典，也不猜测用户没有写出的主题，因此不会让普通查询产生大规模
    fan-out。
    """

    normalized = str(part or "").strip()
    if len(normalized) < 2:
        return ()
    after_transition = re.match(
        r"^(?P<before>.+?)(?:后|时)(?:应|应该)?(?:如何|怎样)?(?:进入|转入)(?P<after>.+)$",
        normalized,
        re.IGNORECASE,
    )
    if after_transition:
        return tuple(
            value
            for name in ("before", "after")
            if len(value := after_transition.group(name).strip()) >= 2
        )
    trace_transition = re.match(
        r"^(?:(?P<prefix>.+?)(?:如何|怎样))?从(?P<source>.+?)(?:关联到|追踪到|映射到)(?P<target>.+)$",
        normalized,
        re.IGNORECASE,
    )
    if trace_transition:
        return tuple(
            value
            for name in ("prefix", "source", "target")
            if (raw_value := trace_transition.group(name)) is not None
            if len(value := raw_value.strip()) >= 2
        )
    return (normalized,)


def split_rag_query_variants(text: str) -> tuple[str, ...]:
    """为多证据问题生成有限的子问题变体。

    例如“怎样推进 CDC 检查点并避免重现历史位点间隙”同时包含执行条件和事故预防两个证据面。
    只用整句做词法召回时，某一份长手册可能凭通用词占满排名；把整句和有限子句一起评分，可以让每个
    证据面都有机会进入 Reranker。这里不调用模型、不改变原问题，只生成最多几个有界的本地检索变体。
    """

    normalized = normalize_rag_retrieval_question(text)
    if not normalized:
        return ("",)
    parts = [
        cleaned
        for part in re.split(
            r"\s*(?:同时|共同|结合|以及|并且|分别|并(?!发)|和|与|、|，|,|；|;)\s*",
            normalized,
        )
        if len(cleaned := part.strip(" ，,、：:；;")) >= 2
    ]
    explicit_parts = parts if len(parts) >= 2 else []
    source_parts = explicit_parts or [normalized]
    supporting_variants = tuple(dict.fromkeys(
        decomposed
        for part in source_parts
        for decomposed in _decompose_rag_query_transition(part)
    ))
    if not explicit_parts and supporting_variants == (normalized,):
        return (normalized,)
    if len(supporting_variants) < 2:
        return (normalized,)
    # “请结合 A、B 和 C”会在“结合”前产生一个单字“请”。它只是提问骨架，不能因为长度不足就让
    # A/B/C 全部退回整句检索；上面的过滤会删除这种短片段，同时保留后续真实业务主题。
    # 保留整句作为全局语义，子句最多取八个。八个仍是有界 fan-out，又足以覆盖配置项列表末尾常见的
    # “最终运行结果/恢复验证”等证据面，避免只截取列表前四项。
    return tuple(dict.fromkeys((normalized, *supporting_variants[:8])))


def rag_query_requests_multiple_evidence(text: str) -> bool:
    """判断问题是否明确要求多份互补证据。"""

    return len(split_rag_query_variants(text)) > 1


def chunk_document(document: RagDocument, *, max_chars: int = 700, overlap_chars: int = 120) -> tuple[RagChunk, ...]:
    """把文档切成带重叠的 chunk。

    切块原则：
    - 先按段落拆分，尽量保持语义完整；
    - 单个段落过长时再滑窗切分；
    - 相邻 chunk 保留少量 overlap，避免答案所需信息刚好被切在边界两侧；
    - chunkId 使用“完整治理范围 + documentId”的稳定摘要和序号，既支持幂等更新，也避免不同租户的
      同名文档碰撞。
    """

    max_chars = max(200, min(max_chars, 4000))
    overlap_chars = max(0, min(overlap_chars, max_chars // 2))
    paragraphs = [part.strip() for part in re.split(r"\n\s*\n", document.content or "") if part.strip()]
    if not paragraphs:
        return ()

    chunks: list[str] = []
    current = ""
    for paragraph in paragraphs:
        if len(paragraph) > max_chars:
            if current:
                chunks.append(current.strip())
                current = ""
            chunks.extend(_sliding_windows(paragraph, max_chars=max_chars, overlap_chars=overlap_chars))
            continue
        candidate = f"{current}\n\n{paragraph}".strip() if current else paragraph
        if len(candidate) <= max_chars:
            current = candidate
        else:
            chunks.append(current.strip())
            current = _tail_overlap(current, overlap_chars)
            current = f"{current}\n\n{paragraph}".strip() if current else paragraph
    if current:
        chunks.append(current.strip())

    return tuple(
        RagChunk(
            chunk_id=_scoped_chunk_id(document, index),
            document_id=document.document_id,
            chunk_index=index,
            title=document.title,
            text=chunk,
            source_uri=document.source_uri,
            tenant_id=document.tenant_id,
            application_id=document.application_id,
            project_id=document.project_id,
            workspace_key=document.workspace_key,
            source_type=document.source_type,
            tags=document.tags,
            sensitivity_level=document.sensitivity_level,
            metadata=document.metadata,
        )
        for index, chunk in enumerate(chunks)
    )


def _scoped_chunk_id(document: RagDocument, chunk_index: int) -> str:
    """为一个文档分块生成跨租户安全且长度固定的持久身份。

    ``document_id`` 通常只在一个项目内部唯一，不能直接充当整张共享表的主键。这里把 tenant、project、
    workspace 和 documentId 按 JSON 数组编码后计算 SHA-256，再附加从 1 开始的分块序号。JSON 编码
    避免简单分隔符产生歧义，固定长度摘要也不会因很长的业务 ID 超过数据库 ``VARCHAR(256)``。

    这不是权限校验本身；查询与删除仍必须携带完整范围谓词。摘要只负责让数据库主键在多租户场景下
    保持稳定且不互相覆盖。
    """

    scoped_identity = json.dumps(
        [
            str(document.tenant_id),
            str(document.project_id),
            str(document.workspace_key),
            str(document.document_id),
        ],
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    digest = hashlib.sha256(scoped_identity).hexdigest()
    return f"rag-{digest}#chunk-{chunk_index + 1}"


def compress_chunk_text(text: str, query_terms: Iterable[str], *, max_chars: int) -> str:
    """按问题相关性压缩单个 chunk。

    压缩不是简单从头截断。更好的做法是优先保留包含查询词的句子，再用原文前部补足上下文。这样既能
    控制 prompt 长度，也能提高证据与问题的贴合度。
    """

    max_chars = max(80, max_chars)
    normalized_terms = {term.lower() for term in query_terms if len(term) >= 2}
    sentences = [item.strip() for item in _SENTENCE_PATTERN.split(text or "") if item.strip()]
    selected: list[str] = []
    for sentence in sentences:
        lowered = sentence.lower()
        if any(term in lowered for term in normalized_terms):
            selected.append(sentence)
    if not selected:
        selected = sentences[:2] if sentences else [text.strip()]
    compressed = " ".join(selected).strip()
    if len(compressed) < max_chars // 3 and text:
        compressed = f"{compressed} {text[: max_chars // 2]}".strip()
    return _clip(compressed, max_chars)


def cosine_similarity(left: tuple[float, ...], right: tuple[float, ...]) -> float:
    """计算向量余弦相似度。"""

    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm <= 0 or right_norm <= 0:
        return 0.0
    # 浮点累加可能得到 1.0000000000000002 或 -1.0000000000000002。余弦相似度的数学定义域固定为
    # [-1, 1]，在这里收口可避免边界阈值把本应保留的候选误判为越界。
    return max(-1.0, min(1.0, dot / (left_norm * right_norm)))


def jaccard_similarity(left_tokens: Iterable[str], right_tokens: Iterable[str]) -> float:
    """计算 token 集合 Jaccard 相似度，用于 MMR 去冗余。"""

    left = set(left_tokens)
    right = set(right_tokens)
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


@dataclass(frozen=True)
class LexicalScore:
    """词项分数结果。"""

    score: float
    match_terms: tuple[str, ...]


@dataclass(frozen=True)
class LexicalChunkProfile:
    """一个 chunk 可重复使用的词法画像。

    高密度知识库中，同一个 chunk 会先参加整句词法召回，再参加多个 facet 补充评分。若每次都重新
    分词并构造 ``Counter``，CPU 时间会随着“查询变体数 x chunk 数”重复增长。画像只保存标题、标签、
    正文 token 计数和长度归一化值，不保存额外正文副本；它不改变分数，只把原来重复计算的中间结果
    放到 Retriever 的生命周期缓存中。
    """

    title: str
    tags: str
    body_tokens: Mapping[str, int]
    body_length_norm: float


def build_lexical_chunk_profile(chunk: RagChunk) -> LexicalChunkProfile:
    """为一个 chunk 计算一次可复用的词法画像。"""

    body_tokens = Counter(tokenize_for_rag(chunk.text))
    return LexicalChunkProfile(
        title=(chunk.title or "").lower(),
        tags=" ".join(chunk.tags).lower(),
        body_tokens=body_tokens,
        body_length_norm=math.sqrt(max(sum(body_tokens.values()), 1)),
    )


def prepare_lexical_query_variants(text: str) -> tuple[tuple[str, ...], ...]:
    """把同一查询的有限变体预先转换为 token 集，供多个 chunk 复用。"""

    return tuple(
        tuple(tokenize_for_rag(variant))
        for variant in _query_lexical_variants(text)
        if variant
    )


def lexical_score(
    query_terms: tuple[str, ...],
    chunk: RagChunk,
    *,
    profile: LexicalChunkProfile | None = None,
) -> LexicalScore:
    """计算 BM25 风格的轻量词项分。

    这里不完整实现 BM25 的 IDF，因为内存知识库 V1 没有维护全局文档频次。但它仍体现核心思想：
    - 标题命中权重大于正文；
    - 标签命中有额外加分；
    - 同一词重复出现有边际递减；
    - 文本越长，分数会被轻微归一化，避免长文天然占优。
    """

    if not query_terms:
        return LexicalScore(score=0.0, match_terms=())
    resolved_profile = profile or build_lexical_chunk_profile(chunk)
    title = resolved_profile.title
    tags = resolved_profile.tags
    body_tokens = resolved_profile.body_tokens
    body_length_norm = resolved_profile.body_length_norm
    score = 0.0
    matches: set[str] = set()
    for term in set(query_terms):
        term_score = 0.0
        if term in title:
            term_score += 2.0
        if term in tags:
            term_score += 1.5
        if term in body_tokens:
            term_score += min(3.0, 1.0 + math.log1p(body_tokens[term]))
        if term_score > 0:
            matches.add(term)
            score += term_score
    return LexicalScore(score=score / body_length_norm, match_terms=tuple(sorted(matches)))


def lexical_score_for_query(
    text: str,
    chunk: RagChunk,
    *,
    profile: LexicalChunkProfile | None = None,
    prepared_variants: Sequence[tuple[str, ...]] | None = None,
) -> LexicalScore:
    """按整句和有限子问题计算词法分，并合并命中词。

    整句分保留问题整体语义，子问题分解决“同时/并且/分别”这类多跳请求。我们采用最高子分而不是
    简单累加，避免一个候选只因为包含很多连接词就获得不合理的长度优势；当同一 chunk 覆盖多个子句时，
    再给一个很小的覆盖奖励，帮助真正的综合资料稳定排在前面。
    """

    variants = (
        tuple(prepared_variants)
        if prepared_variants is not None
        else prepare_lexical_query_variants(text)
    )
    resolved_profile = profile or build_lexical_chunk_profile(chunk)
    scores = tuple(
        lexical_score(variant, chunk, profile=resolved_profile)
        for variant in variants
        if variant
    )
    if not scores:
        return LexicalScore(score=0.0, match_terms=())
    best_score = max(item.score for item in scores)
    covered_variants = sum(1 for item in scores if item.score > 0)
    coverage_bonus = min(0.25, max(0, covered_variants - 1) * 0.05)
    return LexicalScore(
        score=best_score + coverage_bonus,
        match_terms=tuple(sorted({term for item in scores for term in item.match_terms})),
    )


def _query_lexical_variants(text: str) -> tuple[str, ...]:
    """返回原始子问题和少量受控术语扩展，供词法分和证据门禁共同使用。"""

    base_variants = split_rag_query_variants(text)
    expanded: list[str] = list(base_variants)
    for variant in base_variants:
        for phrase, aliases in _RAG_QUERY_EXPANSIONS:
            if phrase in variant:
                expanded.extend(aliases)
    return tuple(dict.fromkeys(item for item in expanded if item))


def distinctive_rag_query_terms(text: str) -> tuple[str, ...]:
    """提取不应被泛词单独替代的查询词。

    这不是通用中文分词器，也不是敏感词表。它只用于无答案门禁：如果问题含有“火星冷链”或“海岛
    传感器”这样的实体，而候选只命中“规则/字段/审批”等高频泛词，就不能把候选当作可靠证据。英文
    标识符和非泛化的中文 n-gram（包括两字运维术语）会保留，精确码则由独立 exact 通道优先处理。
    """

    normalized_query = normalize_rag_retrieval_question(text)
    # 先按查询上下文删除完整治理泛词，再生成中文 n-gram。只在最终锚点提取路径做这一步，正常
    # lexical 排序仍保留原问题和受控扩展，避免因为拒答保护而损失可回答问题的召回。这样“火星冷链
    # 调度规则的当前阈值”会留下“火星冷链”，不会把跨越“规则”边界的“则的”误当成独特实体。
    terms = tuple(
        token
        for variant in _query_lexical_variants(normalized_query)
        for token in tokenize_for_rag(_strip_generic_rag_query_phrases(variant))
    )
    distinctive: list[str] = []
    seen: set[str] = set()
    for term in terms:
        normalized = term.casefold()
        if _is_generic_rag_query_term(normalized):
            continue
        if len(normalized) < 2:
            continue
        # 两字中文术语不能一概丢弃。“哈希、核验、引用、位点”等正是运维资料里的有效锚点；是否
        # 过于泛化由上面的治理泛词表和候选实际命中共同判断。未知实体如“火星、冷链”也会保留，
        # 因而仍能阻止只命中“规则、调度、阈值”的通用文档通过门禁。
        if normalized not in seen:
            seen.add(normalized)
            distinctive.append(normalized)
    return tuple(distinctive)


def _strip_generic_rag_query_phrases(text: str) -> str:
    """从拒答锚点提取输入中删除完整的治理泛词。

    中文没有空格分词时，直接对整句生成 n-gram 会把“规则的”“当前阈值”切成多个看似独特的边界
    片段。这里使用固定、可审计的泛词表，从长到短替换为空格；不使用文档内容、黄金集文档 ID 或
    模型预测，因此不会把某个客户实体误写进全局规则。英文/ASCII 标识符不在该表中，仍由原有
    exact 和 token 路径保留。
    """

    normalized = str(text or "")
    for phrase in sorted(_GENERIC_RAG_QUERY_TERMS, key=len, reverse=True):
        if phrase:
            normalized = normalized.replace(phrase, " ")
    return normalized


def normalize_rag_query_facet(text: str) -> str:
    """删除子问题中的提问骨架，返回真正参与证据评分的业务 facet。

    多证据问题经常在末尾带有“满足哪些边界”“给出排查顺序”等句式。如果把这些句式也当成一个
    检索 facet，任意事故复盘都可能因为写了“排查顺序”而压过用户真正点名的“历史记录”。本函数只
    删除有限、可审计的问句结构，不改写错误码、字段名、对象名或领域术语；返回值可直接用于词法评分。
    """

    normalized = normalize_rag_retrieval_question(text)
    for phrase in sorted(_RAG_QUERY_SCAFFOLD_TERMS, key=len, reverse=True):
        normalized = normalized.replace(phrase, " ")
    normalized = re.sub(r"\s+", " ", normalized).strip(" ，,、。：:；;!?！？")
    return normalized


def rag_query_variant_has_substantive_signal(text: str) -> bool:
    """判断一个拆分后的子问题是否包含独立证据主题。

    先调用 :func:`normalize_rag_query_facet` 删除提问骨架，再复用独特词和受控同义扩展。这样既能丢弃
    “满足哪些边界”，也能保留“批量、并发、超时、最终运行结果”等短但真实的业务 facet。
    """

    return bool(distinctive_rag_query_terms(normalize_rag_query_facet(text)))


def rag_query_document_intent_score(
    text: str,
    chunk: RagChunk,
    *,
    context_text: str | None = None,
) -> float:
    """计算查询与文档职责元数据之间的轻量匹配分。

    词法检索回答的是“正文里出现了哪些词”，但治理知识库还需要回答“这份资料负责说明哪一类事实”。
    例如“字段画像”更应该落到数据集统计，“限流”更应该同时覆盖限流事故、API 参数案例和连接器
    清单，而不是被一份碰巧提到“容量”的通用 PostgreSQL 手册抢走。这里不调用模型，只读取已经经过
    Manifest 白名单校验的 ``category``、``sourceType``、``contentFormat``、标题和标签。

    返回值不是概率，也不是答案可信度，只用于候选排序和多证据覆盖的次级先验：

    - category 精确命中或前缀命中最强；
    - sourceType/物理格式是较弱的职责提示；
    - 没有结构化元数据时，标题和标签只提供更弱的补充信号；
    - 没有激活的意图或没有匹配的职责时返回 ``0``。

    ``text`` 是当前真正评分的查询或 facet；可选的 ``context_text`` 只用于同一职责内部的语境消歧，
    例如“分片 replay”在“接口追踪到最终验证”问题中应落到事件流水，在 Checkpoint 排障中则应落到
    replay 案例。整句上下文不会激活 facet 本身没有出现的新职责，避免重新制造“一份综合资料覆盖全部
    子问题”的串线。

    该函数刻意不检查 documentId，因此不会把黄金集答案写入检索器；范围过滤、来源状态、过期替代关系
    和证据门禁仍由知识库及管线的既有步骤负责。
    """

    normalized_query = normalize_rag_retrieval_question(text).casefold()
    if not normalized_query:
        return 0.0
    normalized_context = normalize_rag_retrieval_question(
        context_text if context_text is not None else text
    ).casefold()

    metadata = chunk.metadata or {}
    category = str(metadata.get("category") or "").strip().casefold()
    content_format = str(metadata.get("contentFormat") or "").strip().casefold()
    source_type_value = getattr(chunk.source_type, "value", chunk.source_type)
    source_type = str(source_type_value or "").strip().casefold()
    title_and_tags = " ".join((chunk.title or "", *chunk.tags)).casefold()

    score = 0.0
    for hint in _RAG_QUERY_DOCUMENT_INTENT_HINTS:
        concept_group_hits = _rag_intent_concept_group_hits(
            hint.concept_groups,
            normalized_query,
        )
        if not concept_group_hits:
            continue
        # 两个独立概念组才表示完整职责；单组命中只保留极小的平局信号，不能单独越过高置信
        # 职责门槛。开放表达仍由词法、Embedding 和 Reranker 共同决定，而不是由题干短语选文档。
        high_confidence_intent = len(concept_group_hits) >= 2
        if not _rag_intent_hint_is_active(
            hint.intent_key,
            normalized_query,
            context_query=normalized_context,
        ):
            continue
        if not _rag_intent_category_allowed(
            hint.intent_key,
            category,
            normalized_query,
            context_query=normalized_context,
        ):
            continue

        category_match = any(
            _rag_intent_category_matches(category, pattern)
            for pattern in hint.category_patterns
        )
        source_match = source_type in {str(value).casefold() for value in hint.source_types}
        format_match = content_format in {str(value).casefold() for value in hint.formats}
        title_match = any(
            str(term).casefold() in title_and_tags
            for group in hint.concept_groups
            for term in group
        )

        # 结构化类别优先于标题。sourceType 和格式不能单独证明相关性，但在两个正文相似的候选之间
        # 能稳定表达“这是事故/Runbook/任务案例/数据集”的职责差异，因此保留为逐级减弱的信号。
        if category:
            # Manifest 已声明 category 时，以它为准。不能因为所有任务案例都共享 task_case，
            # 就让“成功运行记录”意图给配置差异、Kafka 案例等全部同样加分；这正是高密度语料
            # 中最容易造成职责串线的地方。标题仍允许提供很弱的补充，但 sourceType/格式不再
            # 覆盖一个已经明确声明的、但不相符的类别。
            if category_match:
                # architecture/runbook/governance 等宽泛 category 只能说明“资料大类”，不能说明
                # 它回答了当前主题。必须再命中标题或标签中的主题词，才能把它当作高置信职责；否则
                # 同一大类下的所有邻居都会一起进入 Reranker 窗口，最终引用精确率会下降。
                broad_category = category in {
                    "architecture",
                    "document",
                    "governance",
                    "incident",
                    "rule",
                    "runbook",
                }
                if broad_category and high_confidence_intent and not title_match:
                    continue
                match_strength = 1.0 if high_confidence_intent else 0.08
            elif title_match:
                match_strength = 0.12 if high_confidence_intent else 0.04
            else:
                continue
        elif source_match and title_match:
            # 没有 category 的 Markdown 资料仍可用“来源职责 + 标题/标签主题”形成高置信先验。
            # 两个条件缺一不可，避免只因同为 document/runbook 就把整类资料全部抬高。
            match_strength = 0.85 if high_confidence_intent else 0.08
        elif source_match:
            match_strength = 0.42 if high_confidence_intent else 0.06
        elif format_match:
            match_strength = 0.20 if high_confidence_intent else 0.04
        elif title_match:
            match_strength = 0.14 if high_confidence_intent else 0.04
        else:
            continue
        score += float(hint.weight) * match_strength

    # 多个独立 facet 命中同一份综合资料时，允许它在集合覆盖中略占优势；上限防止元数据先验反客为主。
    return round(max(0.0, min(2.5, score)), 6)


def _rag_intent_hint_matches_query(
    intent_key: str,
    phrases: Sequence[str],
    normalized_query: str,
) -> bool:
    """用可审计的短语或概念组合激活资料职责。

    大多数职责由稳定业务术语直接激活。对用户表达变化较大的运维处置记录，则使用两个独立概念组：
    一组表示“事情经过”，另一组表示“已经处置并复核”。这种组合可以理解“从发现到复核的处置经过”
    等自然表达，同时避免把某一道黄金题的完整句子写进检索器。
    """

    query = str(normalized_query or "").casefold()
    # 兼容旧调用方传入平面短语序列；新提示表传入的是“每组同义词”的嵌套序列。
    if phrases and all(isinstance(item, str) for item in phrases):
        return any(str(phrase).casefold() in query for phrase in phrases)
    if _rag_intent_concept_group_hits(phrases, query):
        return True
    if intent_key == "operations_record":
        chronology_signal = any(
            term in query
            for term in ("时间线", "处置经过", "处理经过", "恢复过程", "从发现到", "值守处置")
        )
        closure_signal = any(
            term in query for term in ("修复", "处置", "复核", "验证", "验收", "恢复结果")
        )
        return chronology_signal and closure_signal
    return False


def _rag_intent_concept_group_hits(
    concept_groups: Sequence[Sequence[str]],
    normalized_query: str,
) -> tuple[int, ...]:
    """返回查询命中的独立概念组编号。

    组内词是同义词，组间词代表不同证据维度。只命中一个组时，调用方可以保留弱排序信号，但不能
    宣称已经识别出完整资料职责；这正是从“题干式路由”回到模型自主检索所需的最小规则边界。
    """

    query = str(normalized_query or "").casefold()
    hits: list[int] = []
    for index, group in enumerate(concept_groups):
        if any(
            str(term).strip().casefold() in query
            for term in group
            if str(term).strip()
        ):
            hits.append(index)
    return tuple(hits)


def _rag_intent_category_matches(category: str, pattern: str) -> bool:
    """匹配一个职责类别或类别前缀。"""

    normalized_category = str(category or "").strip().casefold()
    normalized_pattern = str(pattern or "").strip().casefold()
    if not normalized_category or not normalized_pattern:
        return False
    if normalized_pattern.endswith("*"):
        return normalized_category.startswith(normalized_pattern[:-1])
    return normalized_category == normalized_pattern or normalized_category.startswith(
        normalized_pattern + "_"
    )


def _rag_intent_hint_is_active(
    intent_key: str,
    normalized_query: str,
    *,
    context_query: str | None = None,
) -> bool:
    """按上下文关闭容易产生职责串线的宽泛意图。

    同一个词在不同业务问题中可能指向不同资料。例如“有界自治恢复”出现在定时任务问题里时，用户
    通常需要任务调度合同和案例，而不是 Recovery 接口手册；“配置版本”出现在“最近成功任务”里时，
    需要成功任务参数，不应自动附带另一份配置差异快照。这里仅使用查询上下文做可审计的优先级修正，
    不读取文档正文，也不把任何黄金用例 ID 写进运行时规则。
    """

    query = str(normalized_query or "").casefold()
    context = str(context_query or query).casefold()
    # 同一查询中的通用词可能同时激活多个宽泛职责。下面这些收敛规则只在用户已经给出明确的
    # 资料主题时生效：它们把“字段画像”“持久化快照”“WebSocket 事件字典”等主职责与旁边的
    # 事故、Recovery 或通用接口资料分开，但不会把没有明确主题的自然问法强行路由到某类文档。
    if intent_key == "schema_mapping":
        if "字段画像" in context and not any(
            term in context for term in ("字段映射", "映射修复", "字段案例", "字段约束")
        ):
            return False
        # 用户询问事故时间线、处置动作和恢复验证时，资料职责是运维处置记录。问题中可能顺带出现
        # “非空约束”等故障名称，但只凭故障名不能再把 Schema 事故复盘作为第二份答案。只有同时
        # 明确询问根因、映射、默认值或 Schema 漂移时，才保留字段诊断职责。
        chronology_context = any(
            term in context
            for term in ("时间线", "处置经过", "处理经过", "恢复过程", "从发现到", "值守处置")
        ) and any(
            term in context for term in ("修复", "处置", "复核", "验证", "验收", "恢复结果")
        )
        schema_diagnosis_requested = any(
            term in context
            for term in ("根因", "字段映射", "映射修复", "默认值", "schema 漂移", "字段漂移")
        )
        if chronology_context and not schema_diagnosis_requested:
            return False
    if intent_key == "data_quality_task_cases":
        # “脏数据数量”既可能是质量诊断主题，也可能只是一次成功运行记录里的统计列。用户明确询问
        # 最近/成功运行的配置版本、结果和数量时，应由运行记录承担事实来源；只有同时出现质量规则、
        # 隔离或停继续决策等质量动作，才让数据质量案例加入候选职责。这样不会因为一个字段名把整份
        # 质量案例库排到运行台账前面，也不会妨碍真正的脏数据治理问题召回质量案例。
        successful_run_context = any(
            term in context for term in ("成功运行", "运行结果", "成功任务", "上一次成功")
        )
        statistic_context = any(term in context for term in ("数量", "多少", "条数", "记录数", "统计"))
        explicit_quality_action = any(
            term in context
            for term in (
                "质量规则",
                "数据质量",
                "隔离脏数据",
                "脏数据隔离",
                "继续或停止",
                "质量校验",
                "质量门禁",
            )
        )
        if successful_run_context and statistic_context and not explicit_quality_action:
            return False
        if "worker 日志" in context and not any(
            term in context for term in ("字段映射", "映射修复", "字段案例")
        ):
            return False
        if any(
            term in context
            for term in (
                "事故证据与修复动作台账",
                "字段非空失败对应",
                "事故时间线",
                "自动修复和验证结果",
            )
        ):
            return False
    if intent_key == "recovery_and_audit":
        if "持久化快照" in context and not any(
            term in context for term in ("恢复台账", "最终验证")
        ):
            return False
        if "websocket" in context and "全链路状态" in context:
            return False
        if any(term in context for term in ("事故证据与修复动作台账", "字段非空失败对应")):
            return False
    if intent_key == "autonomous_recovery" and "e2e" in context and "specialist" in context:
        return False
    if intent_key in {"api_contract", "task_and_data_sync_api"} and any(
        term in context for term in ("稳定标识关联", "稳定标识")
    ):
        return intent_key == "api_contract"
    if intent_key == "observability_operations":
        # 多证据拆分会把“日志、指标和 trace”拆成几个短 facet。短 facet 单独看时无法恢复完整
        # 运维语义，因此允许使用整句上下文激活这个职责，但仍要求至少出现两个可观测性信号，
        # 避免普通“错误日志”问题把所有结果强行路由到可观测性手册。
        observability_signals = ("日志", "指标", "trace", "追踪", "运行线索", "运行痕迹", "运行轨迹")
        signal_count = sum(1 for signal in observability_signals if signal in context)
        return (
            signal_count >= 2
            or ("跨 agent" in context and "worker" in context)
            or ("运行线索" in context and any(term in context for term in ("问题位置", "定位", "异常")))
        )
    if intent_key == "backup_disaster_recovery":
        # 灾备问题经常把数据库、对象存储、Kafka 位点和服务列成一个恢复顺序。子问题只剩“Kafka
        # 位点”时仍需回到整句确认这是平台灾备，而不是消费者积压排障。
        return any(
            term in context
            for term in (
                "灾难恢复",
                "灾难演练",
                "严重故障",
                "故障后",
                "业务资料",
                "恢复顺序",
                "rpo",
                "rto",
            )
        )
    if intent_key == "platform_deployment":
        # 只有部署/上线/健康检查语境才激活总部署手册；普通 Kafka 或 pgvector 故障不能仅凭组件名称进入该职责。
        platform_components = ("java", "jdk", "kafka", "pgvector", "ai runtime")
        component_count = sum(1 for component in platform_components if component in context)
        lifecycle_context = any(
            term in context
            for term in ("部署", "发布", "安装", "上线", "新环境", "健康检查")
        )
        # 自然问法经常只说“新环境上线前检查基础服务”，不重复列出所有组件；“健康检查 +
        # 基础服务”已经足以表达部署手册职责，但仍不影响普通组件故障查询。
        return lifecycle_context and (
            component_count >= 2
            or ("健康检查" in context and "基础服务" in context)
        )
    if intent_key == "kafka_operations":
        lifecycle_context = any(term in context for term in ("部署", "灾难恢复", "灾难演练"))
        kafka_failure_context = any(
            term in context
            for term in (
                "积压",
                "堆积",
                "分区",
                "消费组",
                "处理变慢",
                "dlt",
                "死信",
                "消费者日志",
                "重复消费",
                "group lag",
                "同步积压",
            )
        )
        if lifecycle_context and not kafka_failure_context:
            return False
    if intent_key == "pgvector_and_model_provider":
        deployment_context = "部署" in context
        pgvector_failure_context = any(
            term in context
            for term in (
                "向量检索",
                "维度",
                "变慢",
                "降级",
                "provider degraded",
                "模型 provider",
                "资料匹配",
                "语义检索",
                "检索结果",
                "存放设置",
                "存储设置",
                "结果对不上",
                "外部智能服务",
                "外部服务",
                "被限制",
                "答复不全",
                "响应缺项",
            )
        )
        if deployment_context and not pgvector_failure_context:
            return False
        # “外部智能服务不稳定/答复不全”与“资料匹配变慢/结果对不上”是两类不同的自然
        # 问法，但都已经具备 Provider 或 pgvector 的故障语义；只有在命中这些受控状态词时
        # 才允许该职责先验参与排序，避免所有“检索”问题都抬高模型手册。
        return pgvector_failure_context
    if intent_key == "configuration_versions":
        # 明确要求“上一版本/差异/对比”时才启用配置差异职责；最近成功任务的配置由成功任务案例承担。
        if any(term in query for term in ("最近成功任务", "成功同步任务", "成功任务参数")):
            return any(term in query for term in ("差异", "上一版本", "上一次成功配置", "对比"))
    if intent_key == "autonomous_recovery":
        # 定时任务和任务案例问题的“进入自治恢复”属于调度/任务合同语境；只有用户明确问 Recovery
        # 接口、修复动作或人工接管时，才需要 Recovery API 资料。
        schedule_context = any(term in query for term in ("定时任务", "非工作时间", "调度", "任务案例"))
        explicit_recovery_api = any(
            term in query
            for term in ("recovery 接口", "恢复接口", "recovery api", "恢复 api", "修复动作", "人工接管")
        )
        if schedule_context and not explicit_recovery_api:
            return False
    return True


def _rag_intent_category_allowed(
    intent_key: str,
    category: str,
    normalized_query: str,
    *,
    context_query: str | None = None,
) -> bool:
    """在同一个意图内部选择更贴近当前 facet 的职责类别。

    ``category`` 是资料 Manifest 的职责声明，不是权限字段。它只能缩小排序先验，不能绕过词法、向量
    或证据门禁。这里处理的是几个常见的职责竞争：通用运维流程与 Kafka/可观测性专册、连接器能力与
    清单、成功任务参数与 Kafka/API 任务案例、恢复事件与恢复决策轨迹等。``normalized_query`` 始终是
    当前 facet；``context_query`` 只允许在已经激活的职责内部区分“事件追踪”与“案例配置”，不能用来
    激活 facet 中没有出现的新意图。
    """

    normalized_category = str(category or "").strip().casefold()
    query = str(normalized_query or "").casefold()
    context = str(context_query or query).casefold()
    if not normalized_category:
        return True

    if intent_key == "pgvector_and_model_provider":
        # 两类自然问法共享“检索/模型”词汇，但资料职责不同：外部服务不稳定、被限流或答复
        # 不全时应优先 Provider 手册；资料匹配、存放设置和结果对不上时应优先 pgvector 手册。
        # 该分流只收窄已经激活的职责先验，不创建新候选，也不替代正文和向量证据。
        provider_context = any(
            term in context
            for term in ("外部智能服务", "外部服务", "被限制", "答复不全", "响应缺项", "限流")
        )
        pgvector_context = any(
            term in context
            for term in ("资料匹配", "语义检索", "存放设置", "存储设置", "结果对不上", "向量检索")
        )
        if provider_context and not pgvector_context:
            return normalized_category == "model_provider_manual"
        if pgvector_context and not provider_context:
            return normalized_category == "postgresql_pgvector_manual"

    if intent_key == "operations_flow":
        # “运维流程/排查顺序”默认落到平台通用 Runbook；Kafka 和可观测性专册由各自明确术语激活。
        if "只读命令" in context:
            return normalized_category == "operations_command_reference"
        return normalized_category in {"operations_manual", "operations_command_reference"}

    if intent_key == "operations_history":
        if "历史记录" in query or "运维记录" in query:
            return normalized_category == "operations_record"
        return normalized_category == "operations_record" or normalized_category.startswith("incident_")

    if intent_key in {"api_contract", "task_and_data_sync_api"} and any(
        term in context for term in ("稳定标识关联", "稳定标识")
    ):
        return intent_key == "api_contract" and normalized_category == "api_reference"

    if intent_key == "connector_capacity":
        # API/限流问题需要带实际版本和限流阈值的清单；一般容量核对优先使用能力快照。
        # 当用户明确说“清单”或询问 CDC/checkpoint replay 支持能力时，问题需要的是可逐行核对的
        # connector_inventory，而不是只描述当前上限的 connector_capabilities 快照。两者都属于
        # metadata，必须用问题中的业务动作继续区分，不能让远端 Reranker 仅凭“版本/容量”猜测。
        # 多证据问题拆分后，当前 facet 可能只剩“连接器容量降低压力”，而“API 目标限流”仍在整句
        # context 中。这里允许上下文在已经激活的 connector_capacity 职责内部做二选一，但它不能
        # 激活新的资料职责，因此不会让任意 API 问题凭空召回连接器清单。
        connector_context = f"{query} {context_query}"
        if any(
            term in connector_context
            for term in ("api", "限流", "429", "连接池", "连接器清单", "cdc", "checkpoint", "支持")
        ):
            return normalized_category == "connector_inventory"
        return normalized_category == "connector_capabilities"

    if intent_key == "task_and_data_sync_api":
        # “全量任务”与“执行接口”经常出现在同一个整句问题里，但它们是两个不同的证据面：
        # 前者应由全量任务案例说明参数和对象行为，后者才由数据同步 API 合同说明请求/响应字段。
        # facet 拆分后，如果 API 类别仍凭“全量任务”获得高职责分，就会在集合覆盖中一次吞掉
        # 全量案例。只有明确出现执行接口、数据同步执行或 API 合同时，才激活 API 合同职责；
        # 其他任务类型词仍由对应的案例 category 负责。
        if "全量任务" in query and not any(
            term in query for term in ("执行接口", "数据同步执行", "api", "接口合同")
        ):
            return False
        return normalized_category in {"api_data_sync_reference", "api_task_reference"}

    if intent_key == "successful_task_configuration":
        # Kafka、DLT、消费者积压等问题的“任务参数”应优先由对应模式的任务案例解释。只有用户
        # 明确要求最近成功基线、上一版参数或成功任务对比时，才重新启用通用成功配置资料；否则
        # 一份同时写有 batch/channel 的泛成功案例会在共享 facet 上压过 Kafka 专用案例。
        mode_specific_context = any(
            term in context for term in ("kafka", "dlt", "消费者", "积压", "group lag", "同步积压")
        )
        explicit_success_baseline = any(
            term in context
            for term in (
                "最近成功",
                "上一次成功",
                "上一回成功",
                "成功任务",
                "成功任务基线",
                "成功任务参数",
                "成功参数",
                "参数对比",
            )
        )
        if mode_specific_context and not explicit_success_baseline:
            return False
        return normalized_category == "successful_task_case"

    if intent_key == "task_parameters":
        # 任务参数 facet 必须服从当前同步模式；否则“Kafka 任务参数”会被成功全量案例抢走。
        task_parameter_context = f"{query} {context}"
        facet_explicit_success = any(
            term in query for term in ("成功任务", "最近成功", "上一次成功", "成功参数")
        )
        if facet_explicit_success:
            return normalized_category == "successful_task_case"
        if any(term in task_parameter_context for term in ("kafka", "dlt", "消费者", "积压")):
            return normalized_category == "kafka_task_cases"
        if any(term in task_parameter_context for term in ("api", "限流", "分页")):
            return normalized_category == "api_task_cases"
        return True

    if intent_key == "task_case_library":
        # “任务案例”是一个独立资料职责。Kafka/API 问题如果没有明确要求通用案例流水，应优先保留
        # 对应模式的表格；Recovery replay 只有在查询明确出现 replay/失败分片时才加入。
        task_case_context = f"{query} {context}"
        facet_explicit_success = any(
            term in query for term in ("成功任务", "最近成功", "上一次成功", "成功案例")
        )
        if facet_explicit_success:
            return normalized_category == "successful_task_case"
        if any(term in task_case_context for term in ("kafka", "dlt", "消费者", "积压")):
            return normalized_category == "kafka_task_cases"
        if any(term in task_case_context for term in ("api", "限流", "分页")):
            return normalized_category == "api_task_cases"
        if any(term in task_case_context for term in ("replay", "分片", "失败对象")):
            return normalized_category in {"task_case_library", "recovery_replay_cases"}
        return normalized_category in {"task_case_library", "schedule_case", "successful_task_case"}

    if intent_key == "kafka_operations":
        # Kafka 排障的三个证据面职责不同：消费者日志证明当前 lag，DLT 手册解释规则与处置，
        # 任务案例保存参数。事故复盘可以解释历史根因，但不能因为同时提到这些词就包办全部职责。
        # 本判断既作用于拆分后的 facet，也作用于整句领域平局裁决；它只读取 category，不绑定文档 ID。
        if any(term in query for term in ("消费者日志", "consumer lag", "group lag", "grouplag")):
            return normalized_category == "kafka_lag_log"
        if any(term in query for term in ("dlt", "死信", "规则处置", "回放规则")):
            return normalized_category == "kafka_operations_manual"
        if "任务参数" in query or "参数基线" in query:
            return normalized_category == "kafka_task_cases"
        return normalized_category in {
            "kafka_operations_manual",
            "kafka_task_cases",
            "kafka_lag_log",
            "incident_kafka_backlog",
        }

    if intent_key == "recovery_decision":
        # “修复决策/决策轨迹”默认需要保存 actionCode、decisionReason 和授权边界的决策轨迹。
        # 只有用户明确问事件流水或接口追踪时，Recovery 事件字典才承担这个 facet；否则事件字典
        # 里偶然出现“修复”一词不能把真正的决策资料挤掉。
        if any(term in context for term in ("恢复事件", "事件流水", "接口追踪", "追踪")):
            return normalized_category == "recovery_events"
        return normalized_category == "recovery_decision_trace"

    if intent_key == "recovery_replay":
        # “某个 replay 案例如何配置”需要案例表；“从接口追踪到 replay 和最终验证”需要事件流水。
        # 两者正文都可能出现 replay，使用整句上下文做职责选择可以避免把案例数据误当成运行证据。
        lifecycle_trace = any(
            term in context
            for term in ("接口标识", "最终验证", "恢复事件", "事件流水", "追踪")
        )
        if lifecycle_trace:
            return normalized_category == "recovery_events"
        # Checkpoint/安全位点/失败对象 replay 的默认证据是可复现的任务案例；只有问题明确要求
        # 事件流水、接口追踪或最终验证链路时，才把 recovery_events 作为该 facet 的职责候选。
        # 这样决策轨迹或事件字典即使复述了“replay”一词，也不能覆盖真正的 replay 案例。
        return normalized_category == "recovery_replay_cases"

    if intent_key == "recovery_and_audit":
        if any(term in query for term in ("修复决策", "recovery 决策", "决策轨迹")):
            return normalized_category in {"recovery_decision_trace", "recovery_events"}
        if "恢复台账" in query or "对象台账" in query:
            return normalized_category in {"database_recovery_ledger", "recovery_events"}
        if "最终验证" in query:
            # 恢复台账通常同时保存对象结果、证据摘要、修复动作和验证状态，是最终验证的持久事实
            # 来源。它不是只记录决策原因的轨迹，因此在明确问最终验证时应与事件/状态快照并列。
            return normalized_category in {
                "database_recovery_ledger",
                "recovery_events",
                "agent_state_snapshot",
            }
        # 普通状态快照不应自动带入只记录决策原因的轨迹。
        return normalized_category in {"recovery_events", "agent_state_snapshot"}

    if intent_key == "checkpoint_incident":
        return normalized_category == "incident_checkpoint"

    return True


def _is_generic_rag_query_term(term: str) -> bool:
    """判断一个查询 n-gram 是否只表达治理语境中的泛化词。

    中文 n-gram 会产生 ``调度规``、``度规则`` 这类跨词片段。如果只做完整字符串比较，候选文档中
    的“调度规则”仍然可能被误认为独特实体，导致“火星冷链调度规则”这类知识库外问题通过证据门禁。
    对中文片段采用“包含泛词也视为泛化片段”的规则，可以把这些跨边界 n-gram 一起过滤掉；真正的
    实体片段（例如“火星冷链”）不包含治理泛词，仍会作为门禁锚点保留。ASCII 标识符不走该规则，
    因为错误码、字段名和资料码已经由 exact 通道单独处理。
    """

    normalized = str(term or "").casefold()
    if not normalized:
        return True
    if normalized in _GENERIC_RAG_QUERY_TERMS:
        return True
    if normalized in _GENERIC_RAG_QUERY_NGRAMS:
        return True
    if not _contains_cjk(normalized):
        return False
    return any(
        generic in normalized
        for generic in _GENERIC_RAG_QUERY_TERMS
        if _contains_cjk(generic) and len(generic) >= 2
    )


def _sliding_windows(text: str, *, max_chars: int, overlap_chars: int) -> list[str]:
    """对超长段落做滑窗切块。"""

    windows: list[str] = []
    step = max(1, max_chars - overlap_chars)
    for start in range(0, len(text), step):
        window = text[start : start + max_chars].strip()
        if window:
            windows.append(window)
        if start + max_chars >= len(text):
            break
    return windows


def _tail_overlap(text: str, overlap_chars: int) -> str:
    """返回上一块尾部 overlap 文本。"""

    if overlap_chars <= 0:
        return ""
    return text[-overlap_chars:].strip()


def _clip(text: str, max_chars: int) -> str:
    """裁剪文本并保留截断标记。"""

    normalized = str(text or "").strip()
    if len(normalized) <= max_chars:
        return normalized
    return normalized[:max_chars].rstrip() + "...[TRUNCATED]"


def _contains_cjk(value: str) -> bool:
    """判断字符串中是否包含 CJK 字符。"""

    return any("\u4e00" <= char <= "\u9fff" for char in value)


__all__ = [
    "distinctive_rag_query_terms",
    "ExactIdentifierMatch",
    "extract_rag_exact_identifiers",
    "LexicalScore",
    "chunk_document",
    "compress_chunk_text",
    "cosine_similarity",
    "jaccard_similarity",
    "lexical_score",
    "lexical_score_for_query",
    "normalize_rag_retrieval_question",
    "normalize_rag_query_facet",
    "rag_query_document_intent_score",
    "rag_query_requests_explicit_exact",
    "rag_query_requests_multiple_evidence",
    "rag_query_variant_has_substantive_signal",
    "split_rag_query_variants",
    "tokenize_for_rag",
]
