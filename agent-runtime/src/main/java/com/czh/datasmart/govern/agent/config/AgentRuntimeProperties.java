/**
 * @Author : Cui
 * @Date: 2026/05/13 22:18
 * @Description DataSmart Govern Backend - AgentRuntimeProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import com.czh.datasmart.govern.agent.model.ModelCapability;
import com.czh.datasmart.govern.agent.model.ModelProviderType;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Runtime 配置。
 *
 * <p>该配置块的关键目标是“模型可替换”：
 * 不在 Java 代码里写死 Qwen、DeepSeek、Mistral、vLLM 或某个 Python 服务地址，
 * 而是通过 `datasmart.agent-runtime.model-routes` 声明不同工作负载应该走哪个模型路由。
 *
 * <p>后续真实部署时，可以把主推理模型配置为 Qwen3.5/DeepSeek/Mistral，
 * 把 embedding 和 rerank 配置为专用模型，把多模态路由配置到独立服务。
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime")
public class AgentRuntimeProperties {

    /**
     * 是否启用 Agent Runtime 控制面。
     *
     * <p>开发期默认启用，便于联调 gateway 和模型路由；生产环境如果 AI 服务尚未就绪，可以先关闭入口。
     */
    private Boolean enabled = true;

    /**
     * 单个会话最多绑定多少个工具。
     *
     * <p>工具绑定越多，Agent 规划空间越大，模型越容易选择错误工具，也越难做权限和审计解释。
     * 这里先给出保守上限，后续可以按租户套餐、项目级配额或角色能力动态调整。
     */
    private Integer maxToolBindingsPerSession = 20;

    /**
     * 单个会话最多保留多少次运行记录。
     *
     * <p>当前版本使用内存仓储，如果不限制运行数量，长对话会让单个会话对象持续膨胀。
     * 后续接入 MySQL/Redis/EventStore 后，该配置仍可作为在线上下文窗口上限，历史运行再进入审计归档。
     */
    private Integer maxRunsPerSession = 200;

    /**
     * 单个会话允许同时存在多少个非终态运行。
     *
     * <p>商业化 Agent 后续可能支持并行子任务，但第一阶段建议保持 1。
     * 这样可以避免同一会话内多个运行同时修改同一工作空间、调用同一工具、写入同一任务状态导致竞态。
     */
    private Integer maxActiveRunsPerSession = 1;

    /**
     * 会话默认生存时间，单位小时。
     *
     * <p>当前版本只作为响应说明和后续清理任务依据，尚未实现后台清理。
     * 后续接入 Redis 或数据库时，可以用它控制在线上下文保留时间，避免模型上下文和临时文件无限增长。
     */
    private Integer sessionTtlHours = 24;

    /**
     * 会话绑定工具时是否强制要求工具来自启用的工具目录。
     *
     * <p>商业化环境建议保持 true。
     * 如果允许调用方随意传 toolCode、toolType 和 targetService，Agent 就可能绕过平台工具治理，退化成“模型拼接口”。
     * 开发期如需临时验证未注册工具，可以在本地配置中显式关闭，但关闭后不能进入生产默认配置。
     */
    private Boolean strictToolRegistryBinding = true;

    /**
     * 模型路由表。
     *
     * <p>Key 建议使用 `ModelWorkloadType` 枚举名，例如 `AGENT_REASONING`、`EMBEDDING`。
     * Value 描述该工作负载应该调用哪个 Provider、哪个模型、哪个服务地址和哪些能力。
     */
    private Map<String, ModelRouteProperties> modelRoutes = new LinkedHashMap<>();

    /**
     * Agent 工具目录。
     *
     * <p>工具目录是 Agent Runtime 进入“可商用智能体平台”的关键一步。
     * 模型路由只能说明“调用哪个模型”，工具目录说明“模型规划出来的动作最终能调用哪些平台能力”。
     *
     * <p>当前采用配置驱动，而不是数据库驱动，原因是：
     * 1. 第一阶段工具集合较少，配置更容易审查和版本管理；
     * 2. 工具元数据还在快速演进，先避免过早设计复杂管理表；
     * 3. 后续如果需要后台可视化管理，可把该结构平滑迁移到 permission-admin 或独立 tool-registry 表。
     */
    private Map<String, ToolDefinitionProperties> toolRegistry = new LinkedHashMap<>();

    /**
     * 工具下游服务基础地址表。
     *
     * <p>Key 使用工具目录里的 targetService，例如 `datasource-management`。
     * Value 是本地直连或内网服务地址，例如 `http://localhost:8082`。
     *
     * <p>当前先用显式 baseUrl，而不是直接写死在适配器里，原因是：
     * 1. 本地开发、Docker Compose、测试环境和生产环境地址不同；
     * 2. 后续可以平滑替换为 gateway、Nacos + LoadBalancer、OpenFeign 或服务网格；
     * 3. 工具适配器只关心“我要调用哪个业务能力”，不应该内置部署拓扑。
     */
    private Map<String, String> toolServiceBaseUrls = new LinkedHashMap<>();

    /**
     * 单条模型路由配置。
     */
    @Data
    public static class ModelRouteProperties {

        /**
         * 该路由是否启用。
         */
        private Boolean enabled = true;

        /**
         * Provider 名称。
         *
         * <p>用于审计、排障和管理后台展示，例如 `local-vllm`、`python-agent-runtime`、`qwen-main`。
         */
        private String providerName = "dry-run";

        /**
         * Provider 类型，表达接入协议或部署形态。
         */
        private ModelProviderType providerType = ModelProviderType.DRY_RUN;

        /**
         * 模型名称。
         *
         * <p>这里保留当前推荐代际的占位名，真实部署时应替换为可用模型 ID。
         */
        private String modelName = "Qwen3.5-placeholder";

        /**
         * Provider 服务地址。
         *
         * <p>DRY_RUN 可以为空；OpenAI-compatible/vLLM/SGLang/Python 服务需要配置实际 endpoint。
         */
        private String endpoint;

        /**
         * 调用超时时间。
         */
        private Long timeoutMs = 30000L;

        /**
         * 该路由声明支持的模型能力。
         */
        private List<ModelCapability> capabilities = new ArrayList<>();
    }

    /**
     * Agent 工具定义配置。
     *
     * <p>一条工具定义并不等于直接执行权限。
     * 它只是把“某个工具是否存在、在哪里、能做什么、风险多高、需要哪些参数”注册到 Agent Runtime。
     * 真正执行时仍需要结合会话工作空间、permission-admin、数据范围、审批和下游服务二次校验。
     */
    @Data
    public static class ToolDefinitionProperties {

        /**
         * 工具是否启用。
         *
         * <p>禁用工具仍可保留在配置中，方便灰度、维护窗口、客户环境裁剪和故障临时下线。
         */
        private Boolean enabled = true;

        /**
         * 工具编码。
         *
         * <p>工具编码应全局稳定，例如 `datasource.metadata.read`。
         * Agent 运行事件、工具调用审计、审批单和前端展示都应使用该编码关联。
         */
        private String toolCode;

        /**
         * 工具类型。
         */
        private AgentToolType toolType = AgentToolType.KNOWLEDGE_RETRIEVAL;

        /**
         * 展示名称。
         */
        private String displayName;

        /**
         * 工具说明。
         *
         * <p>说明字段不仅面向前端，也可以在后续作为 Agent 工具选择提示的一部分。
         */
        private String description;

        /**
         * 下游服务名。
         *
         * <p>例如 `datasource-management`、`data-quality`、`task-management`。
         * 这里记录服务名而不是直接实例地址，方便通过 gateway、服务发现或内部客户端解析。
         */
        private String targetService;

        /**
         * 下游端点模板。
         *
         * <p>示例：`/datasources/{datasourceId}/metadata`。
         * 当前只作为目录元数据展示，后续工具适配器会根据 inputSchema 和会话上下文填充路径变量。
         */
        private String targetEndpoint;

        /**
         * 是否只读。
         *
         * <p>只读不等于无风险。只读 SQL、异常样本、字段统计也可能涉及敏感数据，因此还需要配合 riskLevel。
         */
        private Boolean readOnly = true;

        /**
         * 风险等级。
         */
        private AgentToolRiskLevel riskLevel = AgentToolRiskLevel.LOW;

        /**
         * 执行模式。
         */
        private AgentToolExecutionMode executionMode = AgentToolExecutionMode.SYNC;

        /**
         * 是否需要审批。
         *
         * <p>当 riskLevel 为 HIGH/CRITICAL 或工具会产生写操作时，通常应设为 true。
         */
        private Boolean requiresApproval = false;

        /**
         * 是否幂等。
         *
         * <p>幂等工具可以安全重试；非幂等工具重试前必须有去重键或人工确认。
         */
        private Boolean idempotent = true;

        /**
         * 是否受租户范围约束。
         *
         * <p>绝大多数 DataSmart 工具都应该是租户内工具。
         * 只有平台级诊断、公共知识检索或系统健康检查这类能力，才可能设置为 false。
         * 这个字段会进入工具描述符，供 Python Runtime、智能网关和未来 MCP 适配层判断是否必须携带 tenantId。
         */
        private Boolean tenantScoped = true;

        /**
         * 是否受项目范围约束。
         *
         * <p>数据源、同步、质量、任务等治理动作通常都需要 projectId 或 authorizedProjectIds 保护。
         * 如果工具是纯公共知识检索，可以设置为 false；如果工具会读取或写入业务对象，应保持 true。
         */
        private Boolean projectScoped = true;

        /**
         * 工具执行后是否允许写入 Agent 记忆。
         *
         * <p>该字段用于连接“工具调用”和“长期记忆”：
         * - NONE：不写记忆，例如简单健康检查；
         * - EPISODIC：写入一次执行事件，例如同步失败诊断；
         * - SEMANTIC：沉淀为业务知识，例如字段含义、指标定义；
         * - PROCEDURAL：沉淀为流程经验，例如某类故障的修复步骤。
         *
         * <p>当前先用字符串，避免过早固定枚举；后续记忆模块稳定后可以升级为枚举。
         */
        private String memoryWritePolicy = "NONE";

        /**
         * 模型调用或工具描述缓存策略。
         *
         * <p>面向 prefix cache / KV cache / 工具 schema 复用治理。
         * 示例值：
         * - GLOBAL_SAFE：纯公共工具描述，可跨租户复用；
         * - TENANT_SAFE：租户内可复用；
         * - PROJECT_SAFE：项目内可复用；
         * - SESSION_ONLY：仅当前会话安全。
         */
        private String cachePolicy = "SESSION_ONLY";

        /**
         * 工具调用超时。
         */
        private Long timeoutMs = 10000L;

        /**
         * 最大重试次数。
         */
        private Integer maxRetries = 0;

        /**
         * 允许动作列表。
         */
        private List<String> allowedActions = new ArrayList<>();

        /**
         * 输入字段 schema。
         */
        private List<ToolInputFieldProperties> inputSchema = new ArrayList<>();
    }

    /**
     * Agent 工具输入字段定义。
     *
     * <p>当前使用轻量字段 schema，而不是完整 JSON Schema。
     * 这样代码更容易学习和维护；后续如果要支持复杂对象、数组、oneOf/anyOf，再升级为标准 JSON Schema。
     */
    @Data
    public static class ToolInputFieldProperties {

        /**
         * 字段名。
         */
        private String name;

        /**
         * 字段类型，例如 string、number、boolean、object、array。
         */
        private String type = "string";

        /**
         * 是否必填。
         */
        private Boolean required = false;

        /**
         * 字段说明。
         */
        private String description;

        /**
         * 示例值。
         */
        private String example;

        /**
         * 是否敏感字段。
         *
         * <p>例如 SQL、导出范围、数据源凭据、项目 ID、文件路径、异常样本主键都可能是敏感参数。
         * 标记后，审批提示、审计日志和前端展示可以做脱敏或二次确认。
         */
        private Boolean sensitive = false;

        /**
         * 参数解析方式。
         *
         * <p>该字段面向工具规划器：
         * - USER_REQUIRED：必须由用户显式提供；
         * - CAN_FILL_FROM_CONTEXT：可以从会话、项目、数据源元数据或记忆中补齐；
         * - SYSTEM_INJECTED：由系统注入，例如 tenantId、actorId、traceId；
         * - DERIVED：由其他字段推导。
         */
        private String resolution = "USER_REQUIRED";
    }
}
