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
     * 是否启用 Run 级同步工具自动执行入口。
     *
     * <p>该开关只控制“受控同步自动执行器”的批量入口，不影响单个工具的人工 execute 接口。
     * 商业化部署时可以按租户、环境或灰度策略关闭它，让系统只提供 policy 预检和人工执行。
     * 当前默认开启，是为了本地学习和端到端联调更顺畅；真正生产环境还应叠加 gateway RBAC、
     * permission-admin 动作权限、租户配额、工具健康熔断和审计告警。</p>
     */
    private Boolean syncAutoExecutionEnabled = true;

    /**
     * 单次 Run 级同步自动执行最多处理多少个工具。
     *
     * <p>即使所有工具都被 policy 标记为 AUTO_EXECUTABLE，也不应该在一个 HTTP 请求里无限制地全部执行。
     * 这个上限用于防止一次 Agent 规划生成大量只读工具后挤占请求线程、连接池和下游服务容量。
     * 后续如果需要高并发执行，应转向异步 worker、队列限流和分组调度，而不是单请求大批量同步调用。</p>
     */
    private Integer maxSyncAutoExecutionsPerRun = 5;

    /**
     * 是否启用异步工具命令草案规划。
     *
     * <p>该开关只控制“生成 command envelope 草案”的只读能力，不会真正向 Kafka 投递消息，也不会创建
     * task-management 任务。之所以先做规划再做投递，是因为商业化异步执行必须先固定跨服务契约：
     * commandId、幂等键、租户/项目/工作空间边界、目标服务、参数快照、重试语义和审计引用。
     * 如果这些字段尚未稳定就直接接 Kafka，后续很容易出现重复任务、跨租户消费和无法回放的问题。</p>
     */
    private Boolean asyncTaskCommandPlanningEnabled = true;

    /**
     * Agent 异步工具命令建议投递的 Kafka topic。
     *
     * <p>当前只是 command plan 中的路由建议。后续真正接入 Kafka producer 后，建议继续配合：
     * producer 幂等、事务 outbox、消费者去重表、死信队列、重放权限和 topic 级 ACL。</p>
     */
    private String asyncTaskCommandTopic = "datasmart.agent.tool.async.commands";

    /**
     * 异步命令建议由哪个平台模块消费并转换为可恢复任务。
     *
     * <p>默认使用 task-management，因为长耗时扫描、同步、导出、批量质量检测都需要任务中心提供
     * 队列、租约、心跳、重试、暂停、恢复、死信和运营干预，而不是由 agent-runtime 自己维护第二套任务系统。</p>
     */
    private String asyncTaskCommandConsumerService = "task-management";

    /**
     * 是否要求异步工具声明幂等后才允许进入自动下发候选。
     *
     * <p>Kafka 等消息系统通常采用至少一次投递语义：网络抖动、消费者重启、超时重试都可能让同一 command
     * 被重复消费。如果工具不是幂等的，重复执行可能造成重复同步任务、重复导出或重复写入。
     * 当前默认保持严格模式；后续可以在 task-management 落地 command 幂等表后，再按工具级策略放宽。</p>
     */
    private Boolean requireIdempotentAsyncTaskCommands = true;

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
     * Agent 工具调用沙箱配置。
     *
     * <p>这里的“沙箱”不是指已经启动了容器、Firecracker、gVisor 或 Kubernetes Job。
     * 当前阶段它是 Java 控制面里的执行前策略沙箱：在真正调用 datasource-management、data-quality、
     * task-management 等下游服务之前，先检查工具目录一致性、目标服务范围、参数体量、幂等重试、同步超时、
     * 高风险审批和敏感参数暴露等风险。</p>
     *
     * <p>为什么先做控制面沙箱，而不是直接做进程级隔离：
     * 1. DataSmart 当前工具大多是平台内部 HTTP 工具，最大风险首先来自“模型计划绕过治理规则”，不是本地 shell；
     * 2. 控制面沙箱能被人工执行、自动执行、DAG worker 和未来 MCP bridge 复用；
     * 3. 后续如果引入真实容器沙箱、SQL dry-run、网络 egress policy 或文件系统隔离，也可以把这些底层结果回写到同一 verdict。</p>
     */
    private ToolSandboxProperties toolSandbox = new ToolSandboxProperties();

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
     * 工具调用沙箱策略配置。
     *
     * <p>该配置面向“商业化 Agent 工具执行”而不是演示联调：
     * - enabled 控制是否启用沙箱硬校验；
     * - requireRegisteredTool 控制是否必须命中工具目录；
     * - requireKnownTargetService 控制 targetService 是否必须有基础地址；
     * - maxArgumentBytes 控制模型计划参数的最大体量，避免 prompt 注入或异常大对象拖垮网关/审计；
     * - maxSyncTimeoutMs 控制同步工具的最长可接受超时，超过后应转异步任务；
     * - allowed/blockedTargetServices 支持生产环境把工具 egress 限定在已批准服务集合内。</p>
     */
    @Data
    public static class ToolSandboxProperties {

        /**
         * 是否启用工具调用沙箱。
         *
         * <p>生产环境建议保持 true。关闭后 Guard 仍会保留基础会话边界、参数缺失和写工具审批校验，
         * 但不会执行注册表一致性、服务白名单、参数体量、幂等重试等扩展策略。</p>
         */
        private Boolean enabled = true;

        /**
         * 是否要求工具必须存在于启用的工具目录。
         *
         * <p>商业化环境应保持 true，避免调用方伪造 toolCode、targetService 或 riskLevel。
         * 如果本地研发需要临时验证未注册工具，应在本地配置显式关闭，而不是修改业务代码。</p>
         */
        private Boolean requireRegisteredTool = true;

        /**
         * 是否要求 targetService 必须出现在 toolServiceBaseUrls 中。
         *
         * <p>该检查并不代表服务一定健康，只代表目标服务属于平台明确配置过的下游调用面。
         * 后续可继续叠加服务发现健康、熔断、限流和队列容量。</p>
         */
        private Boolean requireKnownTargetService = true;

        /**
         * 工具计划参数最大字节数。
         *
         * <p>Agent 计划参数会进入审计、审批提示、下游工具请求和二轮推理上下文。
         * 如果不限制体量，模型可能把大段表结构、样本数据或用户粘贴的超长文本塞进参数，造成内存压力和审计污染。
         * 默认 64KB 是一个保守的控制面上限，真实导入/导出大文件应走对象存储引用，而不是直接放进 tool arguments。</p>
         */
        private Integer maxArgumentBytes = 64 * 1024;

        /**
         * 同步工具最大超时时间。
         *
         * <p>超过该阈值的工具不适合在 HTTP 请求线程内同步执行，应转为 ASYNC_TASK、Kafka command 或 task-management worker。
         * 这样可以避免一次 Agent 请求长时间占用网关连接、Tomcat/Netty 线程和下游连接池。</p>
         */
        private Long maxSyncTimeoutMs = 30000L;

        /**
         * 是否阻断“非幂等工具配置了自动重试”的组合。
         *
         * <p>非幂等写操作如果自动重试，可能产生重复任务、重复导出、重复审批或不一致副作用。
         * 当前默认阻断，直到对应工具具备业务幂等键、去重表或补偿流程。</p>
         */
        private Boolean blockNonIdempotentRetry = true;

        /**
         * 是否要求敏感参数必须有人工审批事实。
         *
         * <p>工具目录 inputSchema 中标记 sensitive=true 的字段，通常包含任务草稿、SQL、导出范围、异常样本或业务目标。
         * 如果这类字段出现在参数中但没有审批人，沙箱会阻断，防止模型在用户未确认时把敏感上下文带入下游执行。</p>
         */
        private Boolean blockSensitiveArgumentsWithoutApproval = true;

        /**
         * 允许调用的目标服务列表。
         *
         * <p>为空表示不启用 allow-list，只要求命中工具目录和已知 baseUrl。
         * 生产环境可以按租户套餐、部署域或安全域配置，例如只允许 datasource-management 和 task-management。</p>
         */
        private List<String> allowedTargetServices = new ArrayList<>();

        /**
         * 禁止调用的目标服务列表。
         *
         * <p>该列表优先级高于 allowedTargetServices，可用于维护窗口、紧急下线、事故止血或客户环境裁剪。</p>
         */
        private List<String> blockedTargetServices = new ArrayList<>();

        /**
         * 默认必须审批的高危动作关键词。
         *
         * <p>工具目录 allowedActions 是一层抽象动作描述。即使某个工具暂时把 riskLevel 配低了，
         * 只要动作包含 DELETE、EXPORT、EXECUTE_SQL 这类高危意图，沙箱仍会要求人工审批。</p>
         */
        private List<String> approvalRequiredActions = new ArrayList<>(List.of(
                "DELETE",
                "EXPORT",
                "EXECUTE_SQL",
                "WRITE",
                "ADMIN",
                "APPROVE",
                "PUBLISH",
                "SYNC_RUN",
                "TASK_SUBMIT"
        ));
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
