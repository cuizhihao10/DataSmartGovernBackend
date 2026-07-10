"""Python AI Runtime 默认工具注册表。

`config.py` 已经承担模型路由、Skill 注册表和环境变量模型路由装配职责。如果继续把所有工具定义也放在
同一个文件里，文件行数会超过项目约定的 500 行，也会让“模型配置”和“工具目录契约”两个关注点混在一起。

本模块专门维护本地默认工具目录：
- 生产环境优先从 Java `agent-runtime` 工具目录同步；
- 本地学习、离线测试或 Java 服务不可用时回退到这里；
- 所有工具定义只描述低敏 schema、权限、风险和目标服务，不包含 prompt、真实工具参数、凭据或内部地址。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import ToolDefinition, ToolExecutionMode, ToolRiskLevel


def _data_sync_agent_tools() -> tuple[ToolDefinition, ...]:
    """返回数据同步 Agent 的真实业务工具影子契约。

    数据源凭据不属于 Agent 参数。用户必须先在 datasource-management 的可信表单中创建数据源，
    Python 计划只携带 datasourceId、对象映射和工具输出引用。
    """

    datasource_tools = tuple(
        ToolDefinition(
            name=name,
            description=description,
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
            required_permissions=(permission,),
            target_service="datasource-management",
            target_endpoint=endpoint,
            input_schema={
                "datasourceId": {
                    "type": "number",
                    "required": True,
                    "sensitive": False,
                    "resolution": "user_required",
                    "description": "用户已通过可信数据源管理页面安全创建并选择的数据源 ID。",
                }
            },
            read_only=True,
            idempotent=True,
            allowed_actions=(action,),
            tool_type="DATASOURCE_METADATA",
            tenant_scoped=True,
            project_scoped=True,
            memory_write_policy="semantic" if "metadata" in name else "none",
            cache_policy="project_safe" if "metadata" in name else "no_cache",
        )
        for name, description, permission, endpoint, action in (
            (
                "datasource.source.connection.test",
                "测试用户已选择源端数据源的真实连接，不读取或返回数据库密码。",
                "datasource:connection:test",
                "/datasources/{datasourceId}/test",
                "TEST_CONNECTION",
            ),
            (
                "datasource.target.connection.test",
                "测试用户已选择目标端数据源的真实连接，不读取或返回数据库密码。",
                "datasource:connection:test",
                "/datasources/{datasourceId}/test",
                "TEST_CONNECTION",
            ),
            (
                "datasource.source.metadata.read",
                "读取源端表、字段、主键和索引，为同步对象与字段映射提供真实依据。",
                "datasource:metadata:read",
                "/datasources/{datasourceId}/metadata/discover",
                "USE",
            ),
            (
                "datasource.target.metadata.read",
                "读取目标端表、字段、主键和索引，为映射和预检查提供真实依据。",
                "datasource:metadata:read",
                "/datasources/{datasourceId}/metadata/discover",
                "USE",
            ),
        )
    )
    sync_tools = (
        ToolDefinition(
            name="sync.task.draft.save",
            description="根据两端真实元数据生成字段映射，并通过 data-sync 创建向导保存同步任务草稿。",
            risk_level=ToolRiskLevel.HIGH,
            execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
            required_permissions=("sync:task:create",),
            target_service="data-sync",
            target_endpoint="/sync-tasks/create-wizard/drafts",
            input_schema={
                "sourceDatasourceId": {"type": "number", "required": True, "sensitive": False, "resolution": "user_required"},
                "targetDatasourceId": {"type": "number", "required": True, "sensitive": False, "resolution": "user_required"},
                "objectMappings": {"type": "array", "required": True, "sensitive": True, "resolution": "user_required"},
            },
            requires_approval=True,
            idempotent=False,
            allowed_actions=("CREATE_DRAFT",),
            tool_type="DATA_SYNC",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("objectMappings",),
            memory_write_policy="episodic",
            cache_policy="session_only",
        ),
        ToolDefinition(
            name="sync.task.precheck",
            description="调用 data-sync 真实预检查，验证对象、字段、目标约束和执行器准入。",
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
            required_permissions=("sync:task:precheck",),
            target_service="data-sync",
            target_endpoint="/sync-templates/{templateId}/precheck",
            input_schema={"draftRef": {"type": "object", "required": True, "sensitive": False, "resolution": "derived"}},
            read_only=True,
            idempotent=True,
            allowed_actions=("PRECHECK",),
            tool_type="DATA_SYNC",
            tenant_scoped=True,
            project_scoped=True,
            memory_write_policy="episodic",
            cache_policy="no_cache",
        ),
        ToolDefinition(
            name="sync.task.publish",
            description="预检查通过后发布同步任务定义，不直接搬运数据。",
            risk_level=ToolRiskLevel.HIGH,
            execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
            required_permissions=("sync:task:publish",),
            target_service="data-sync",
            target_endpoint="/sync-tasks/{taskId}/publish",
            input_schema={"draftRef": {"type": "object", "required": True, "sensitive": False, "resolution": "derived"}},
            requires_approval=True,
            idempotent=False,
            allowed_actions=("PUBLISH",),
            tool_type="DATA_SYNC",
            tenant_scoped=True,
            project_scoped=True,
            memory_write_policy="episodic",
            cache_policy="no_cache",
        ),
        ToolDefinition(
            name="sync.task.run",
            description="对已发布同步任务创建 execution 并提交真实 worker 队列。",
            risk_level=ToolRiskLevel.HIGH,
            execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
            required_permissions=("sync:task:run",),
            target_service="data-sync",
            target_endpoint="/sync-tasks/{taskId}/run",
            input_schema={"taskRef": {"type": "object", "required": True, "sensitive": False, "resolution": "derived"}},
            requires_approval=True,
            idempotent=False,
            allowed_actions=("RUN",),
            tool_type="DATA_SYNC",
            tenant_scoped=True,
            project_scoped=True,
            memory_write_policy="episodic",
            cache_policy="no_cache",
        ),
        ToolDefinition(
            name="sync.execution.status",
            description="查询本次 Agent 创建任务的最新 execution 状态和低敏进度计数。",
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
            required_permissions=("sync:execution:view",),
            target_service="data-sync",
            target_endpoint="/sync-tasks/{taskId}/executions",
            input_schema={"taskRef": {"type": "object", "required": True, "sensitive": False, "resolution": "derived"}},
            read_only=True,
            idempotent=True,
            allowed_actions=("VIEW",),
            tool_type="DATA_SYNC",
            tenant_scoped=True,
            project_scoped=True,
            memory_write_policy="episodic",
            cache_policy="no_cache",
        ),
    )
    return (*datasource_tools, *sync_tools)


def default_tool_registry() -> tuple[ToolDefinition, ...]:
    """返回默认工具注册表。

    这份注册表是 Python 层对 Java 控制面能力的“影子契约”。真实生产环境中，工具定义应从
    Java `agent-runtime` 的工具注册表动态同步过来。现在先写在这里，是为了让 ToolPlanner 能
    在不启动整套微服务的情况下验证规划逻辑。
    """

    return (
        *_data_sync_agent_tools(),
        ToolDefinition(
            name="datasource.metadata.read",
            description="读取指定数据源的库表字段、字段类型、主键、索引和基础统计信息。",
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
            required_permissions=("datasource:metadata:read",),
            target_service="datasource-management",
            input_schema={
                "datasourceId": {
                    "type": "string",
                    "required": True,
                    "sensitive": True,
                    "resolution": "context_or_clarify",
                    "description": "数据源 ID，可来自用户显式选择，也可由当前项目上下文或元数据检索补齐。",
                }
            },
            read_only=True,
            idempotent=True,
            allowed_actions=("VIEW",),
            tool_type="DATASOURCE_METADATA",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("datasourceId",),
            memory_write_policy="semantic",
            cache_policy="project_safe",
        ),
        ToolDefinition(
            name="workspace.file.read",
            description=(
                "读取受控 Agent workspace 内的文本文件片段。该工具只允许 workspace 相对路径，"
                "真实路径由执行器内部解析，响应和事件只能返回 pathDigest、contentSha256、字节数和 artifactReference。"
            ),
            risk_level=ToolRiskLevel.MEDIUM,
            execution_mode=ToolExecutionMode.SYNC,
            required_permissions=("agent:workspace-file:read",),
            target_service="python-ai-runtime",
            target_endpoint="internal://workspace-file/read-text",
            input_schema={
                "workspaceReference": {
                    "type": "string",
                    "required": True,
                    "sensitive": False,
                    "resolution": "system_injected",
                    "description": "低敏 workspace 引用，例如 agent-workspace:tenant-10/project-20/run-001。",
                },
                "filePathRef": {
                    "type": "string",
                    "required": True,
                    "sensitive": True,
                    "resolution": "derived",
                    "description": "文件相对路径的低敏引用或摘要；真实 relativePath 只能在执行器内部使用。",
                },
            },
            read_only=True,
            idempotent=True,
            allowed_actions=("READ_TEXT",),
            tool_type="AGENT_WORKSPACE_FILE",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("filePathRef",),
            memory_write_policy="none",
            cache_policy="session_only",
        ),
        ToolDefinition(
            name="workspace.file.write",
            description=(
                "向受控 Agent workspace 写入文本文件。写操作必须经过权限/审批/恢复事实治理，"
                "工具计划只携带 contentRef、pathDigest 和写入模式，不携带正文。"
            ),
            risk_level=ToolRiskLevel.HIGH,
            execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
            required_permissions=("agent:workspace-file:write",),
            target_service="python-ai-runtime",
            target_endpoint="internal://workspace-file/write-text",
            input_schema={
                "workspaceReference": {
                    "type": "string",
                    "required": True,
                    "sensitive": False,
                    "resolution": "system_injected",
                    "description": "低敏 workspace 引用，执行器会用它校验本次写入是否属于当前工作空间。",
                },
                "filePathRef": {
                    "type": "string",
                    "required": True,
                    "sensitive": True,
                    "resolution": "derived",
                    "description": "文件相对路径的低敏引用或摘要，不应把真实路径写入普通计划响应。",
                },
                "contentRef": {
                    "type": "string",
                    "required": True,
                    "sensitive": True,
                    "resolution": "derived",
                    "description": "待写入内容的 payloadReference、objectReference 或 hash 引用；不得承载正文。",
                },
            },
            requires_approval=True,
            idempotent=False,
            allowed_actions=("WRITE_TEXT", "OVERWRITE_TEXT"),
            tool_type="AGENT_WORKSPACE_FILE",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("filePathRef", "contentRef"),
            memory_write_policy="none",
            cache_policy="session_only",
        ),
        ToolDefinition(
            name="knowledge.rag.query",
            description=(
                "执行受控治理知识 RAG 查询。该工具只接收 queryRef、scopePolicy、evidencePolicy 等低敏引用，"
                "真实用户问题、检索正文、引用 URL、压缩上下文和模型回答只能在 RAG 执行器内部处理，"
                "并通过 LangGraph checkpoint 写入低敏节点摘要。"
            ),
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
            required_permissions=("agent:rag:query",),
            target_service="python-ai-runtime",
            target_endpoint="/agent/rag/query",
            input_schema={
                "queryRef": {
                    "type": "object",
                    "required": True,
                    "sensitive": True,
                    "resolution": "derived",
                    "description": (
                        "RAG 查询的低敏引用，包含 queryDigest、长度、来源和物化策略；"
                        "不得把用户原始问题写入 AgentPlan、runtime event 或 Java projection。"
                    ),
                },
                "scopePolicy": {
                    "type": "object",
                    "required": True,
                    "sensitive": False,
                    "resolution": "system_injected",
                    "description": "租户、项目、workspace、sourceTypes 和标签过滤策略摘要，用于检索前先做权限范围收敛。",
                },
                "evidencePolicy": {
                    "type": "object",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": "证据门控策略，例如最少匹配词、最少证据数、是否允许无证据生成和 citation 约束。",
                },
            },
            output_schema={
                "retrievalSummary": {
                    "type": "object",
                    "description": "只允许返回候选数、接受证据数、弱证据拒绝数、citation 数和 failClosed 等摘要。",
                }
            },
            read_only=True,
            idempotent=True,
            allowed_actions=("QUERY_RAG", "RETRIEVE_WITH_EVIDENCE_GATE", "ANSWER_WITH_CITATIONS"),
            tool_type="GOVERNANCE_RAG",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("queryRef",),
            memory_write_policy="none",
            cache_policy="project_safe",
        ),
        ToolDefinition(
            name="web.search.query",
            description=(
                "生成受控网页搜索请求草案。该工具只产生 searchQueryRef、Provider allowlist、缓存 TTL、"
                "限流窗口和引用结果策略，不允许模型或 Python Runtime 直接抓取任意 URL。"
            ),
            risk_level=ToolRiskLevel.MEDIUM,
            execution_mode=ToolExecutionMode.DRAFT_ONLY,
            required_permissions=("agent:network:web-search",),
            target_service="python-ai-runtime",
            target_endpoint="internal://web-search/query",
            input_schema={
                "searchQueryRef": {
                    "type": "object",
                    "required": True,
                    "sensitive": True,
                    "resolution": "derived",
                    "description": (
                        "搜索查询的低敏引用，包含 queryDigest、长度、敏感等级和 materialization 策略；"
                        "不得在 AgentPlan、事件或日志中携带原始查询正文。"
                    ),
                },
                "providerPolicy": {
                    "type": "object",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": "允许的搜索 Provider 摘要和密钥管理边界，不包含 endpoint 或 API Key。",
                },
                "resultPolicy": {
                    "type": "object",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": "搜索结果引用策略，例如必须带 citation、只允许 snippet 和来源元数据。",
                },
            },
            read_only=True,
            idempotent=True,
            allowed_actions=("PREPARE_SEARCH_QUERY", "SEARCH_WITH_CITATIONS"),
            tool_type="WEB_SEARCH",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("searchQueryRef",),
            memory_write_policy="none",
            cache_policy="session_only",
        ),
        ToolDefinition(
            name="quality.rule.suggest",
            description="根据元数据、业务目标和历史质量问题生成数据质量规则草案。",
            risk_level=ToolRiskLevel.MEDIUM,
            execution_mode=ToolExecutionMode.DRAFT_ONLY,
            required_permissions=("quality:rule:draft",),
            target_service="data-quality",
            input_schema={
                "datasourceId": {
                    "type": "string",
                    "required": True,
                    "sensitive": True,
                    "resolution": "context_or_clarify",
                    "description": "规则所依赖的数据源 ID，缺失时优先从上下文补齐。",
                },
                "businessGoal": {
                    "type": "string",
                    "required": True,
                    "sensitive": False,
                    "resolution": "user_required",
                    "description": "质量规则要服务的业务目标，例如唯一性、完整性、范围校验或异常识别。",
                },
            },
            allowed_actions=("GENERATE", "DRAFT"),
            tool_type="DATA_QUALITY",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("datasourceId",),
            memory_write_policy="episodic",
            cache_policy="project_safe",
        ),
        ToolDefinition(
            name="quality.remediation.task.draft",
            description=(
                "基于质量报告或异常工作台的低敏筛选条件生成治理/复核任务草案；"
                "当前只面向 dry-run 预览和人工确认，不直接修改源端数据。"
            ),
            risk_level=ToolRiskLevel.MEDIUM,
            execution_mode=ToolExecutionMode.DRAFT_ONLY,
            required_permissions=("quality:anomaly:remediation:create-draft",),
            target_service="data-quality",
            target_endpoint="/quality-rules/remediation-tasks",
            input_schema={
                "remediationScope": {
                    "type": "object",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": (
                        "治理任务的低敏定位范围。它只描述 reportId、ruleId、severity、anomalyType、"
                        "fieldName、targetObject 等筛选维度，不包含异常样本、观测值、SQL、prompt 或模型输出正文。"
                    ),
                },
                "remediationType": {
                    "type": "string",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": (
                        "治理类型，例如 MANUAL_REVIEW、CLEANING_PLAN、SOURCE_SYSTEM_FIX 或 RULE_TUNING。"
                        "该字段只是任务意图，不代表 Agent 可以自动执行清洗写入。"
                    ),
                },
                "reason": {
                    "type": "string",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": (
                        "创建治理任务的低敏原因摘要。默认由规划器生成稳定文案，避免把用户 prompt、工具参数、"
                        "样本值或模型输出原文写入任务 payload。"
                    ),
                },
                "dryRun": {
                    "type": "boolean",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": (
                        "固定为 true，表示只请求 data-quality 生成低敏治理任务预览，不真正提交 task-management。"
                    ),
                },
            },
            allowed_actions=("CREATE_REMEDIATION_TASK_DRAFT", "DRY_RUN_PREVIEW"),
            tool_type="DATA_QUALITY_REMEDIATION",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("remediationScope", "reason", "recommendation"),
            memory_write_policy="episodic",
            cache_policy="session_only",
        ),
        ToolDefinition(
            name="task.create.draft",
            description="生成任务创建草案，供用户或管理员确认后再进入任务管理模块。",
            risk_level=ToolRiskLevel.HIGH,
            execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
            required_permissions=("task:create",),
            target_service="task-management",
            input_schema={
                "taskType": {
                    "type": "string",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": "任务类型，可由 Agent 根据用户目标推导，但执行前仍需要用户确认。",
                },
                "payload": {
                    "type": "object",
                    "required": True,
                    "sensitive": True,
                    "resolution": "derived",
                    "description": "任务草案载荷，可能包含数据源、规则、调度和风险标签，审批展示时需要脱敏。",
                },
            },
            requires_approval=True,
            allowed_actions=("CREATE_DRAFT",),
            tool_type="TASK_MANAGEMENT",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("payload",),
            memory_write_policy="episodic",
            cache_policy="session_only",
        ),
        ToolDefinition(
            name="task.draft.persist",
            description="将 Agent 已生成并确认要保留的任务草稿保存到 task-management 的 task_draft 表。",
            risk_level=ToolRiskLevel.HIGH,
            execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
            required_permissions=("task:create",),
            target_service="task-management",
            target_endpoint="/task-drafts",
            input_schema={
                "taskDraftRef": {
                    "type": "object",
                    "required": True,
                    "sensitive": False,
                    "resolution": "derived",
                    "description": "指向 task.create.draft 输出 taskDraft 的显式引用；Java 控制面按同一 Run 解析。",
                }
            },
            requires_approval=True,
            allowed_actions=("CREATE",),
            tool_type="TASK_MANAGEMENT",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=("taskDraft",),
            memory_write_policy="episodic",
            cache_policy="session_only",
        ),
    )


__all__ = ["default_tool_registry"]
